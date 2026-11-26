package app.lexiflow.data;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.util.AtomicFile;
import org.json.JSONException;
import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

/** Called on one background executor. SQLite connections always open read-only. */
public final class LocalDictionaryRepository implements DictionaryRepository {
    private static final String[] TABLES = {"entry", "sense", "translation", "form", "example", "example_translation"};
    private static final byte[] URL_NAMESPACE = ByteBuffer.allocate(16)
            .putLong(0x6ba7b8119dad11d1L).putLong(0x80b400c04fd430c8L).array();
    private static final Set<String> LANGUAGES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList("en","ru")));
    private static final Set<String> METADATA_LINKS = new HashSet<>(
            Arrays.asList("entry:entry_id:id","sense:sense_id:id","form:form_id:id"));
    static final long MAX_MANIFEST = 64L * 1024 * 1024;
    static final long MAX_DATABASE = 256L * 1024 * 1024;
    private final Context context;
    private final BiConsumer<String,Long> startupTiming;
    private volatile Status status = Status.MISSING;
    private SQLiteDatabase database;
    private PackageManifest manifest;
    private File openFolder;
    private String openManifestDigest;
    private boolean loadAttempted;

    public LocalDictionaryRepository(Context context) {
        this(context,null);
    }

    LocalDictionaryRepository(Context context,BiConsumer<String,Long> startupTiming) {
        this.context = context.getApplicationContext();
        this.startupTiming = startupTiming;
    }

    @Override public Status status() { return status; }
    @Override public Set<String> installedLanguages() { return status == Status.AVAILABLE ? LANGUAGES : Collections.emptySet(); }
    public boolean isSynthetic() { return manifest != null && manifest.version().startsWith("synthetic-"); }
    public synchronized String corpusVersion() { return manifest == null ? null : manifest.version(); }

    public static boolean isResearchProfile(Context context) {
        String packageName = context.getPackageName();
        return ("app.lexiflow.research".equals(packageName) || "app.lexiflow.researchcheck".equals(packageName))
                && (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    public synchronized void load() {
        long started = System.nanoTime();
        synchronized (DictionaryPackageStore.LOCK) {
            loadLocked();
        }
        timed("repository",started);
    }

    private void timed(String phase,long started) {
        if (startupTiming != null) startupTiming.accept(phase,System.nanoTime()-started);
    }

    private void loadLocked() {
        if (database != null || loadAttempted) return;
        loadAttempted = true;
        status = Status.LOADING;
        File root = packageRoot();
        String bundled = isResearchProfile(context) ? "dictionary-research" : debug() ? null : "dictionary-release";
        try {
            File activeManifest;
            try {
                activeManifest = openActive(root);
            } catch (IOException | JSONException | RuntimeException invalidActive) {
                if (bundled == null || !installBundledPackage(bundled,null)) throw invalidActive;
                return;
            }
            if (bundled != null) installBundledPackage(bundled,activeManifest);
            else if (activeManifest == null && !installAsset("dictionary-v2")) installAsset("dictionary");
            if (database == null) {
                status = Status.MISSING;
            }
        } catch (UnsupportedOperationException incompatible) {
            if (database == null) status = Status.INCOMPATIBLE;
        } catch (JSONException | SQLiteException | IllegalArgumentException invalid) {
            if (database == null) status = Status.CORRUPTED;
        } catch (IOException failure) {
            if (database == null) status = Status.ERROR;
        }
    }

    private File openActive(File root) throws IOException, JSONException {
        var state = DictionaryPackageStore.read(root);
        if (state.active() == null) {
            DictionaryPackageStore.prune(root,state);
            return null;
        }
        File manifestFile;
        try {
            manifestFile = openStored(root,state.active());
        } catch (IOException | JSONException | RuntimeException activeFailure) {
            if (state.previous() == null) throw activeFailure;
            try {
                manifestFile = openStored(root,state.previous());
            } catch (IOException | JSONException | RuntimeException fallbackFailure) {
                activeFailure.addSuppressed(fallbackFailure);
                throw activeFailure;
            }
            var recovered = new DictionaryPackageStore.State(state.previous(),null);
            try {
                DictionaryPackageStore.write(root,recovered);
                state = recovered;
            } catch (IOException | RuntimeException ignored) {
                // The previous package is still open. Leave both paths untouched.
                return manifestFile;
            }
        }
        DictionaryPackageStore.prune(root,state);
        return manifestFile;
    }

    private File openStored(File root,String key) throws IOException, JSONException {
        File folder = new File(root,key);
        if (!Files.isDirectory(folder.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("missing package directory");
        }
        File manifestFile = new File(folder,"manifest.json");
        File databaseFile = new File(folder,"dictionary.sqlite");
        String databaseDigest = checkedDatabaseDigest(databaseFile);
        long started = System.nanoTime();
        String expectedManifest = sha256(manifestFile,MAX_MANIFEST);
        timed("manifest_digest",started);
        started = System.nanoTime();
        boolean verified = matchesReceipt(receipt(databaseFile),validation(expectedManifest,databaseDigest));
        timed("receipt",started);
        var parsed = readFileManifest(manifestFile,!verified);
        // Bind reuse to the bytes consumed by the parser, even if the file changed after hashing.
        if (verified && !expectedManifest.equals(parsed.digest())) {
            throw new SQLiteException("manifest changed while opening verified package");
        }
        accept(openValidated(databaseFile,parsed,databaseDigest,verified),parsed,folder);
        return manifestFile;
    }

    private boolean installBundledPackage(String path,File active) throws IOException, JSONException {
        if (active != null) {
            try (InputStream bundled = context.getAssets().open(path+"/manifest.json")) {
                long started = System.nanoTime();
                String digest = sha256(bundled);
                timed("bundled_manifest_digest",started);
                if (digest.equals(openManifestDigest)) return true;
            } catch (FileNotFoundException absent) {
                return false;
            }
        }
        return installAsset(path);
    }

    private boolean installAsset(String path) throws IOException, JSONException {
        try (InputStream json = context.getAssets().open(path + "/manifest.json");
             InputStream db = context.getAssets().open(path + "/dictionary.sqlite")) {
            installPackage(json,db);
            return true;
        } catch (FileNotFoundException absent) {
            return false;
        }
    }

    public synchronized void installBundledV2() throws IOException, JSONException {
        if (!debug() || isResearchProfile(context)) throw new IOException("bundled V2 unavailable");
        synchronized (DictionaryPackageStore.LOCK) {
            try (InputStream json = context.getAssets().open("dictionary-v2/manifest.json")) {
                if (sha256(json).equals(openManifestDigest)) return;
            }
            if (!installAsset("dictionary-v2")) throw new IOException("bundled V2 unavailable");
        }
    }

    private boolean debug() {
        return (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    private File packageRoot() {
        return new File(context.getFilesDir(),debug() ? "dictionary" : "trusted-dictionary");
    }

    public synchronized void install(InputStream json, InputStream db) throws IOException, JSONException {
        if (!debug()) throw new UnsupportedOperationException("local installation is debug only");
        synchronized (DictionaryPackageStore.LOCK) {
            installPackage(json,db);
        }
    }

    private void installPackage(InputStream json, InputStream db) throws IOException, JSONException {
        File root = packageRoot();
        if (database == null) {
            try { openActive(root); }
            catch (IOException | JSONException | RuntimeException ignored) {
                // An unusable old state cannot prevent a fresh, verified activation.
            }
        }
        File folder = new File(root, UUID.randomUUID().toString());
        Files.createDirectories(folder.toPath());
        File jsonFile = new File(folder, "manifest.json");
        File dbFile = new File(folder, "dictionary.sqlite");
        SQLiteDatabase candidateDb = null;
        boolean activated = false;
        boolean pointerAttempted = false;
        try {
            long started = System.nanoTime();
            writeCandidate(json,jsonFile,MAX_MANIFEST);
            timed("manifest_copy",started);
            var candidate = readFileManifest(jsonFile,true);
            started = System.nanoTime();
            writeCandidate(db,dbFile,MAX_DATABASE);
            timed("sqlite_copy",started);
            candidateDb = openValidated(dbFile,candidate,checkedDatabaseDigest(dbFile),false);
            var state = new DictionaryPackageStore.State(folder.getName(),openFolder == null ? null : openFolder.getName());
            started = System.nanoTime();
            pointerAttempted = true;
            DictionaryPackageStore.write(root,state);
            accept(candidateDb,candidate,folder);
            activated = true;
            DictionaryPackageStore.prune(root,state);
            timed("atomic_activation",started);
        } finally {
            if (!activated) {
                if (candidateDb != null) candidateDb.close();
                if (!pointerAttempted) DictionaryPackageStore.removeCandidate(folder);
            }
        }
    }

    private static void checkSidecars(File file) {
        for (String sidecar : new String[]{"-wal","-shm","-journal"}) {
            if (new File(file.getPath()+sidecar).exists()) throw new SQLiteException("unexpected database sidecar");
        }
    }

    private String checkedDatabaseDigest(File file) throws IOException {
        checkSidecars(file);
        long started = System.nanoTime();
        String digest = sha256(file,MAX_DATABASE);
        timed("sqlite_digest",started);
        return digest;
    }

    private String validation(String manifestDigest,String databaseDigest) {
        return "6\n" + (isResearchProfile(context) ? "research" : debug() ? "debug" : "apk") + "\n"
                + android.os.Build.VERSION.SDK_INT + "\n" + android.os.Build.VERSION.INCREMENTAL + "\n"
                + android.icu.lang.UCharacter.getUnicodeVersion() + "\n" + manifestDigest + "\n" + databaseDigest;
    }

    private SQLiteDatabase openValidated(File file, ParsedManifest parsed, String databaseDigest, boolean verified)
            throws IOException, JSONException {
        PackageManifest candidate = parsed.manifest();
        if (candidate.research() != isResearchProfile(context)) {
            throw new UnsupportedOperationException("research package and application profile must match");
        }
        if (candidate.version().startsWith("synthetic-") && !debug()) {
            throw new UnsupportedOperationException("synthetic packages require a debug installation");
        }
        checkSidecars(file);
        if (!candidate.digest().equals(databaseDigest)) {
            throw new SQLiteException("size or checksum mismatch");
        }
        long started = System.nanoTime();
        SQLiteDatabase opened = SQLiteDatabase.openDatabase(file.getPath(),null,SQLiteDatabase.OPEN_READONLY);
        timed("sqlite_open",started);
        try {
            if (opened.getVersion() != candidate.schema()) throw new UnsupportedOperationException("schema mismatch");
            if (verified) return opened;
            started = System.nanoTime();
            if (!"ok".equals(scalar(opened,"PRAGMA integrity_check"))) throw new SQLiteException("integrity check");
            try (Cursor check = opened.rawQuery("PRAGMA foreign_key_check",null)) {
                if (check.moveToFirst()) throw new SQLiteException("foreign key check");
            }
            timed("sqlite_integrity",started);
            started = System.nanoTime();
            for (String table : TABLES) checkTable(opened,candidate,table);
            for (var entry : candidate.contexts().entrySet()) {
                try (Cursor row = opened.rawQuery("SELECT attribution_id FROM sense WHERE id=?",new String[]{entry.getKey()})) {
                    if (!row.moveToFirst() || !entry.getValue().belongsTo(candidate.attribution(row.getString(0)))) {
                        throw new JSONException("invalid source sense relationship");
                    }
                }
            }
            checkAttributedEntries(opened,candidate);
            timed("attributed_metadata",started);
            started = System.nanoTime();
            if (candidate.schema() >= 2) {
                checkMetadata(opened,candidate);
                checkIndexColumns(opened,"translation_preview","translation","sense_id","search_key","wording");
                checkIndexColumns(opened,"form_parent","form","entry_id","id");
                checkIndexColumns(opened,"example_parent","example","sense_id","id");
                checkIndexColumns(opened,"example_translation_parent","example_translation","example_id","id");
            }
            if (candidate.schema()==3) {
                checkIndexColumns(opened,"entry_language_search","entry","language","search_key","id");
                checkIndexColumns(opened,"translation_language_search","translation","language","search_key","sense_id");
                if (!"2".equals(scalar(opened,"SELECT count(DISTINCT language) FROM entry"))) {
                    throw new SQLiteException("invalid bilingual direction");
                }
                for (var examples : candidate.usageExamples().entrySet()) {
                    String entryId = examples.getKey();
                    try (Cursor row = opened.rawQuery("SELECT search_key,pos FROM entry WHERE id=?"
                            +" AND language='en'",new String[]{entryId})) {
                        if (!row.moveToFirst()) throw new JSONException("invalid usage example owner");
                        String key = row.getString(0), pos = row.getString(1);
                        for (var example : examples.getValue()) checkUsageOwner(opened,entryId,key,pos,example);
                    }
                }
            }
            checkPersistentIds(opened,candidate);
            try (Cursor overlap = opened.rawQuery("SELECT id FROM (SELECT id FROM entry UNION ALL SELECT id FROM sense)"
                    + " GROUP BY id HAVING count(*)>1 LIMIT 1",null)) {
                if (overlap.moveToFirst()) throw new SQLiteException("duplicate persistent ID");
            }
            checkIndex(opened,"entry_search","entry","id");
            checkIndex(opened,"translation_search","translation","sense_id");
            checkIndex(opened,"form_search","form","entry_id");
            checkLink(opened,"sense","entry","entry_id");
            checkLink(opened,"translation","sense","sense_id");
            checkLink(opened,"form","entry","entry_id");
            checkLink(opened,"example","sense","sense_id");
            checkLink(opened,"example_translation","example","example_id");
            String[] probes = {
                "SELECT id,spelling,pos,search_key FROM entry LIMIT 0",
                "SELECT id,entry_id,position FROM sense LIMIT 0",
                "SELECT sense_id,wording,search_key,origin_attribution_id FROM translation LIMIT 0",
                "SELECT entry_id,search_key FROM form LIMIT 0",
                "SELECT id,sense_id,body FROM example LIMIT 0",
                "SELECT example_id,body FROM example_translation LIMIT 0"
            };
            for (String sql : probes) {
                try (Cursor probe = opened.rawQuery(sql,null)) { probe.getColumnNames(); }
            }
            checkTranslations(opened,candidate);
            for (String sql : new String[]{"SELECT spelling,search_key FROM entry","SELECT spelling,search_key FROM form"}) {
                try (Cursor keys = opened.rawQuery(sql,null)) {
                    while (keys.moveToNext()) checkSearchKey(keys.getString(0),keys.getString(1));
                }
            }
            if (candidate.count("entry") == 0 || candidate.count("translation") == 0
                    || !"0".equals(scalar(opened,"SELECT count(*) FROM sense s WHERE NOT EXISTS "
                    + "(SELECT 1 FROM translation t WHERE t.sense_id=s.id)"))
                    || !"0".equals(scalar(opened,"SELECT count(*) FROM entry e WHERE NOT EXISTS "
                    + "(SELECT 1 FROM sense s WHERE s.entry_id=e.id)"))) throw new SQLiteException("empty or incomplete corpus");
            writeReceipt(receipt(file),validation(parsed.digest(),databaseDigest));
            timed("package_relationships_and_keys",started);
            return opened;
        } catch (RuntimeException | JSONException failed) {
            opened.close();
            throw failed;
        }
    }

    static void checkTranslations(SQLiteDatabase db,PackageManifest candidate) throws JSONException {
        boolean bilingual = candidate.schema()==3;
        try (Cursor rows = db.rawQuery("SELECT t.origin_attribution_id,t.attribution_id,s.attribution_id,e.attribution_id,"
                + "t.wording,t.search_key" + (bilingual ? ",e.language,t.language" : "")
                + " FROM translation t JOIN sense s ON s.id=t.sense_id JOIN entry e ON e.id=s.entry_id",null)) {
            while (rows.moveToNext()) {
                var origin = candidate.attribution(rows.getString(0)).policy().source();
                if (bilingual) {
                    String articleLanguage = rows.getString(6), translationLanguage = rows.getString(7);
                    if (!LANGUAGES.contains(articleLanguage) || !LANGUAGES.contains(translationLanguage)
                            || articleLanguage.equals(translationLanguage)) {
                        throw new SQLiteException("invalid bilingual direction");
                    }
                    String snapshot = "ru".equals(articleLanguage)
                            ? "9ab8e1a00c463f631e510899d20d26738b11af7b8cdddf8e44a396ba8349bfd5"
                            : "f3dadcc29d7df6e77edb13b98a2ba2c3fac002852eddd8b557637f0b13d39c39";
                    if (!snapshot.equals(origin.snapshot())) throw new JSONException("source does not own article language");
                }
                for (int i = 1; i < 4; i++) {
                    var source = candidate.attribution(rows.getString(i)).policy().source();
                    if (!origin.name().equals(source.name()) || !origin.snapshot().equals(source.snapshot())) {
                        throw new JSONException("inconsistent translation provenance");
                    }
                }
                checkSearchKey(rows.getString(4),rows.getString(5));
            }
        }
    }

    private static void checkSearchKey(String wording,String key) {
        if (!SearchNormalization.normalize(wording).equals(key)) {
            throw new UnsupportedOperationException("package search keys differ from Android ICU");
        }
    }

    static void checkAttributedEntries(SQLiteDatabase db,PackageManifest candidate) throws JSONException {
        var notes = candidate.entryNotes();
        var labels = candidate.entryCefr();
        if (notes.isEmpty() && labels.isEmpty()) return;
        int noteOwners = 0, labelOwners = 0;
        String language = candidate.schema()==3 ? "language" : "'en'";
        try (Cursor row = db.rawQuery("SELECT id,attribution_id,search_key,pos,"+language+" FROM entry",null)) {
            while (row.moveToNext()) {
                String entryId = row.getString(0);
                var entryNotes = notes.get(entryId);
                if (entryNotes!=null) {
                    noteOwners++;
                    var origin = candidate.attribution(row.getString(1));
                    for (var note : entryNotes) {
                        if (!note.belongsTo(origin)) throw new JSONException("source entry note provenance mismatch");
                    }
                }
                var entryLabels = labels.get(entryId);
                if (entryLabels!=null && "en".equals(row.getString(4))) {
                    labelOwners++;
                    String key = row.getString(2), pos = row.getString(3);
                    for (var label : entryLabels) {
                        if (!label.belongsTo(key,pos)) throw new JSONException("invalid CEFR owner");
                    }
                }
            }
        }
        if (noteOwners!=notes.size()) throw new JSONException("missing source entry note owner");
        if (labelOwners!=labels.size()) throw new JSONException("missing CEFR owner");
    }

    private static AtomicFile receipt(File database) {
        return new AtomicFile(new File(database.getParentFile(),"validation-receipt"));
    }

    private static boolean matchesReceipt(AtomicFile receipt,String validation) {
        try (InputStream input = receipt.openRead()) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(256);
            transfer(input,bytes,2048);
            return validation.equals(new String(bytes.toByteArray(),StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            return false;
        }
    }

    private static void writeReceipt(AtomicFile receipt,String validation) {
        FileOutputStream output = null;
        try {
            output = receipt.startWrite();
            output.write(validation.getBytes(StandardCharsets.UTF_8));
            receipt.finishWrite(output);
        } catch (IOException ignored) {
            if (output != null) receipt.failWrite(output);
        }
    }

    private static boolean validId(String value) {
        try { return value != null && UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException invalid) { return false; }
    }

    private static void checkUsageOwner(SQLiteDatabase db,String entryId,String key,String pos,
                                        PackageManifest.UsageExample example) throws JSONException {
        if (example.pos()==null && "chance".equals(key) && "n".equals(pos)) return;
        if (example.pos()!=null && example.pos().equals(pos)) {
            if ("lemma".equals(example.relationship()) && key.equals(example.token())) return;
            if ("form".equals(example.relationship()) && !key.equals(example.token())) {
                try (Cursor form = db.rawQuery("SELECT 1 FROM form WHERE entry_id=? AND search_key=?",
                        new String[]{entryId,example.token()})) {
                    if (form.moveToFirst()) return;
                }
            }
        }
        throw new JSONException("usage example has no compatible lexical owner");
    }

    private static void checkPersistentIds(SQLiteDatabase db,PackageManifest candidate) throws JSONException {
        boolean bilingual = candidate.schema()==3;
        String columns = bilingual ? "id,attribution_id," : "id,";
        MessageDigest digest = null;
        if (bilingual) {
            try { digest = MessageDigest.getInstance("SHA-1"); }
            catch (NoSuchAlgorithmException missing) { throw new IllegalStateException(missing); }
        }
        try (Cursor ids = db.rawQuery("SELECT "+columns+"'entry' FROM entry UNION ALL SELECT "
                +columns+"'sense' FROM sense",null)) {
            while (ids.moveToNext()) {
                String id = ids.getString(0);
                if (!validId(id)) throw new SQLiteException("invalid persistent ID");
                if (digest != null) {
                    // UUIDv5 defines SHA-1 for namespace derivation. Package integrity uses SHA-256.
                    var evidence = candidate.attribution(ids.getString(1));
                    String name = "lexiflow/research/"+evidence.policy().source().snapshot()+"/"
                            +ids.getString(2)+"/"+evidence.locator();
                    digest.update(URL_NAMESPACE);
                    byte[] hash = digest.digest(name.getBytes(StandardCharsets.UTF_8));
                    hash[6] = (byte)((hash[6]&0x0f)|0x50);
                    hash[8] = (byte)((hash[8]&0x3f)|0x80);
                    var bytes = ByteBuffer.wrap(hash);
                    if (!new UUID(bytes.getLong(),bytes.getLong()).toString().equals(id)) {
                        throw new SQLiteException("persistent ID does not match source locator");
                    }
                }
            }
        }
    }

    private static void checkTable(SQLiteDatabase db,PackageManifest candidate,String table) throws JSONException {
        if (Long.parseLong(scalar(db,"SELECT count(*) FROM " + table)) != candidate.count(table)) {
            throw new SQLiteException("manifest count mismatch");
        }
        try (Cursor refs = db.rawQuery("SELECT DISTINCT attribution_id FROM " + table,null)) {
            while (refs.moveToNext()) candidate.attribution(refs.getString(0));
        }
    }

    private static void checkMetadata(SQLiteDatabase db,PackageManifest candidate) throws JSONException {
        checkTable(db,candidate,"lexical_metadata");
        try (Cursor links = db.rawQuery("PRAGMA foreign_key_list(lexical_metadata)",null)) {
            Set<String> actual = new HashSet<>();
            while (links.moveToNext()) actual.add(links.getString(2)+":"+links.getString(3)+":"+links.getString(4));
            if (!METADATA_LINKS.equals(actual)) {
                throw new SQLiteException("invalid metadata relationships");
            }
        }
        for (String owner : new String[]{"entry","sense","form"}) {
            try (Cursor index = db.rawQuery("PRAGMA index_info(metadata_" + owner + ")",null)) {
                if (index.getCount() != 1 || !index.moveToFirst() || !(owner+"_id").equals(index.getString(2))) {
                    throw new SQLiteException("missing metadata index");
                }
            }
        }
        String shape = "SELECT count(*) FROM lexical_metadata WHERE lang IS NULL OR lang NOT IN ('en','ru','und') "
                + "OR value IS NULL OR trim(value)='' OR (kind='cefr' AND value NOT IN ('A1','A2','B1','B2','C1','C2')) "
                + "OR NOT coalesce(((entry_id IS NOT NULL AND sense_id IS NULL AND form_id IS NULL AND kind IN ('ipa','pronunciation','region')) "
                + "OR (entry_id IS NULL AND sense_id IS NOT NULL AND form_id IS NULL AND kind IN ('definition','source_gloss','style','region','cefr')) "
                + "OR (entry_id IS NULL AND sense_id IS NULL AND form_id IS NOT NULL AND kind='grammar')),0)";
        if (!"0".equals(scalar(db,shape))) throw new SQLiteException("invalid lexical metadata");
    }

    private static void checkIndex(SQLiteDatabase db,String index,String table,String secondary) {
        checkIndexColumns(db,index,table,"search_key",secondary);
    }

    private static void checkIndexColumns(SQLiteDatabase db,String index,String table,String... expected) {
        if (!table.equals(scalar(db,"SELECT tbl_name FROM sqlite_master WHERE type='index' AND name='" + index + "'"))) {
            throw new SQLiteException("index has wrong table");
        }
        try (Cursor columns = db.rawQuery("PRAGMA index_info(" + index + ")",null)) {
            if (columns.getCount() != expected.length) throw new SQLiteException("index has wrong columns");
            for (String column : expected) {
                if (!columns.moveToNext() || !column.equals(columns.getString(2))) {
                    throw new SQLiteException("index has wrong columns");
                }
            }
        }
    }

    private static void checkLink(SQLiteDatabase db,String table,String parent,String column) {
        try (Cursor links = db.rawQuery("PRAGMA foreign_key_list(" + table + ")",null)) {
            if (links.getCount() != 1 || !links.moveToFirst() || !parent.equals(links.getString(2))
                    || !column.equals(links.getString(3)) || !"id".equals(links.getString(4))) {
                throw new SQLiteException("invalid package relationship");
            }
        }
    }

    private PackageManifest readManifest(InputStream input,boolean verifyAttributionIds) throws IOException, JSONException {
        InputStream bounded = new FilterInputStream(input) {
            private long remaining = MAX_MANIFEST;

            @Override public int read(byte[] bytes,int offset,int length) throws IOException {
                int count = in.read(bytes,offset,(int) Math.min(length,remaining+1));
                if (count > 0 && (remaining -= count) < 0) throw new IOException("package input exceeds size limit");
                return count;
            }

            @Override public int read() throws IOException {
                int value = in.read();
                if (value != -1 && --remaining < 0) throw new IOException("package input exceeds size limit");
                return value;
            }
        };
        try {
            return PackageManifest.parse(new InputStreamReader(bounded,StandardCharsets.UTF_8.newDecoder()),
                    startupTiming,verifyAttributionIds);
        } catch (android.util.MalformedJsonException | java.io.EOFException malformed) {
            throw new JSONException("malformed package manifest");
        }
    }

    private record ParsedManifest(PackageManifest manifest,String digest) {}

    private ParsedManifest readFileManifest(File file,boolean verifyAttributionIds) throws IOException, JSONException {
        long started = System.nanoTime();
        MessageDigest digest = newDigest();
        try (InputStream json = new DigestInputStream(new FileInputStream(file),digest)) {
            PackageManifest manifest = readManifest(json,verifyAttributionIds);
            timed("manifest_with_digest",started);
            return new ParsedManifest(manifest,hexDigest(digest.digest()));
        }
    }

    private static void writeCandidate(InputStream input,File file,long limit) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file)) {
            transfer(input,output,limit);
            output.getFD().sync();
        }
    }

    static void transfer(InputStream input,OutputStream output,long limit) throws IOException {
        byte[] buffer = new byte[8192];
        long total = 0;
        for (int count; (count = input.read(buffer)) != -1;) {
            total += count;
            if (total > limit) throw new IOException("package input exceeds size limit");
            output.write(buffer,0,count);
        }
    }

    private static String scalar(SQLiteDatabase db, String query) {
        try (Cursor cursor = db.rawQuery(query,null)) {
            if (!cursor.moveToFirst()) throw new SQLiteException("missing package metadata");
            return cursor.getString(0);
        }
    }

    private static String sha256(File file,long limit) throws IOException {
        var digest = newDigest();
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[32768];
            long size = 0;
            for (int count; (count = input.read(buffer)) != -1;) {
                size += count;
                if (size > limit) throw new SQLiteException("package size limit");
                digest.update(buffer,0,count);
            }
        }
        return hexDigest(digest.digest());
    }

    private static String sha256(InputStream input) throws IOException {
        MessageDigest digest = newDigest();
        DigestInputStream hashing = new DigestInputStream(input,digest);
        byte[] buffer = new byte[32768];
        while (hashing.read(buffer) != -1) { /* digest updated by stream */ }
        return hexDigest(digest.digest());
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static String hexDigest(byte[] digest) {
        StringBuilder hex = new StringBuilder(64);
        for (byte value : digest) {
            hex.append(Character.forDigit((value & 0xff) >>> 4,16));
            hex.append(Character.forDigit(value & 15,16));
        }
        return hex.toString();
    }

    private void accept(SQLiteDatabase opened,ParsedManifest candidate,File folder) {
        closeActive();
        database = opened;
        openFolder = folder;
        DictionaryPackageStore.opened(folder);
        manifest = candidate.manifest();
        openManifestDigest = candidate.digest();
        status = Status.AVAILABLE;
    }

    @Override public synchronized List<Hit> search(String query, boolean reverse) {
        if (database == null || query.codePointCount(0, query.length()) > 256) return Collections.emptyList();
        String key = SearchNormalization.normalize(query);
        if (key.isEmpty()) return Collections.emptyList();
        boolean bilingual = manifest.schema()==3;
        List<Hit> results = queryHits(DictionarySearch.ranked(key,reverse,bilingual));
        if (!bilingual && reverse && results.isEmpty() && manifest.research()) {
            var fallback = DictionarySearch.russianStressFallback(key);
            if (fallback != null) return queryHits(fallback);
        }
        return results;
    }

    private List<Hit> queryHits(DictionarySearch.Query query) {
        List<Hit> results = new ArrayList<>();
        try (Cursor cursor = database.rawQuery(query.sql(),query.arguments())) {
            while (cursor.moveToNext()) results.add(new Hit(cursor.getString(0),cursor.getString(1),
                    cursor.getString(2),cursor.getString(3),cursor.getInt(4),cursor.getString(5),cursor.getString(6),
                    cursor.getString(7),cursor.getInt(8)!=0));
        }
        return results;
    }

    private String attribution(String ref) {
        try {
            return manifest.attribution(ref).displayCredit();
        } catch (JSONException invalid) {
            throw new SQLiteException("missing attribution");
        }
    }

    @Override public synchronized Entry entry(String id) {
        if (database == null) return null;
        String languageColumn = manifest.schema()==3 ? "language" : "'en'";
        try (Cursor entry = database.rawQuery("SELECT spelling,pos,attribution_id,"+languageColumn+" FROM entry WHERE id=?",new String[]{id})) {
            if (!entry.moveToFirst()) return null;
            String language = entry.getString(3);
            List<Sense> senses = new ArrayList<>();
            try (Cursor rows = database.rawQuery("SELECT id,position,attribution_id FROM sense WHERE entry_id=? ORDER BY position",new String[]{id})) {
                while (rows.moveToNext()) {
                    String sid = rows.getString(0);
                    List<String> translations = new ArrayList<>();
                    Set<String> credits = new java.util.LinkedHashSet<>();
                    credits.add(attribution(rows.getString(2)));
                    try (Cursor trans = database.rawQuery("SELECT wording,attribution_id,origin_attribution_id FROM translation WHERE sense_id=? ORDER BY id",new String[]{sid})) {
                        while (trans.moveToNext()) {
                            String word = trans.getString(0);
                            if (!translations.contains(word)) translations.add(word);
                            credits.add(attribution(trans.getString(1)));
                            credits.add(attribution(trans.getString(2)));
                        }
                    }
                    List<Example> examples = new ArrayList<>();
                    try (Cursor ex = database.rawQuery("SELECT e.body,t.body,e.attribution_id,t.attribution_id FROM example e LEFT JOIN example_translation t ON t.example_id=e.id WHERE e.sense_id=? ORDER BY e.id,t.id",new String[]{sid})) {
                        while (ex.moveToNext()) {
                            String credit = attribution(ex.getString(2));
                            if (!ex.isNull(3)) credit += "\n" + attribution(ex.getString(3));
                            examples.add(new Example(ex.getString(0),ex.getString(1),credit));
                        }
                    }
                    var link = manifest.contexts().get(sid);
                    SenseContext context = link == null ? null : new SenseContext(link.text(),link.owner().locator(),
                            link.definition().locator(),link.definition().displayCredit());
                    senses.add(new Sense(sid,rows.getInt(1),translations,examples,String.join("\n",credits),
                            metadata(sid,true),context));
                }
            }
            Set<Form> forms = new java.util.LinkedHashSet<>();
            String formQuery = manifest.schema()==1
                    ? "SELECT spelling,attribution_id,NULL FROM form WHERE entry_id=? ORDER BY id"
                    : "SELECT f.spelling,f.attribution_id,m.value FROM form f"
                        +" LEFT JOIN lexical_metadata m ON m.form_id=f.id WHERE f.entry_id=? ORDER BY f.id,m.id";
            try (Cursor rows = database.rawQuery(formQuery,new String[]{id})) {
                while (rows.moveToNext()) forms.add(new Form(rows.getString(0),attribution(rows.getString(1)),rows.getString(2)));
            }
            List<Metadata> entryMetadata = metadata(id,false);
            var notes = manifest.entryNotes().get(id);
            if (notes != null) {
                for (var note : notes) {
                    entryMetadata.add(new Metadata("definition",note.text(),language,null,note.definition().displayCredit()));
                }
            }
            var cefr = manifest.entryCefr().get(id);
            if (cefr != null) {
                var levels = new java.util.TreeSet<String>();
                for (var label : cefr) levels.add(label.level());
                entryMetadata.add(new Metadata("cefr",String.join(" · ",levels),"en",null,
                        cefr.get(0).evidence().displayCredit()));
            }
            var usage = manifest.usageExamples().get(id);
            if (usage != null) {
                for (var example : usage) {
                    entryMetadata.add(new Metadata("usage_example",example.original().text()+"\n"+example.translation().text(),
                            "en",null,example.original().credit()+"\n"+example.translation().credit()));
                }
            }
            return new Entry(id,entry.getString(0),entry.getString(1),senses,attribution(entry.getString(2)),
                    entryMetadata,new ArrayList<>(forms),language);
        }
    }

    public synchronized java.util.Map<String,String> sourceLanguages(Set<String> ids) {
        if (database==null || ids.isEmpty()) return Collections.emptyMap();
        String language = manifest.schema()==3 ? "language" : "'en'";
        String slots = String.join(",",Collections.nCopies(ids.size(),"?"));
        var result = new java.util.HashMap<String,String>();
        try (Cursor rows = database.rawQuery("SELECT id,"+language+" FROM entry WHERE id IN ("+slots+")",
                ids.toArray(new String[0]))) {
            while (rows.moveToNext()) result.put(rows.getString(0),rows.getString(1));
        }
        return result;
    }

    private List<Metadata> metadata(String id,boolean sense) {
        if (manifest.schema() == 1) return Collections.emptyList();
        List<Metadata> result = new ArrayList<>();
        String owner = sense ? "sense_id" : "entry_id";
        String sql = "SELECT kind,value,lang,NULL AS spelling,attribution_id,id FROM lexical_metadata WHERE " + owner + "=?";
        if (!sense) sql += " UNION ALL SELECT m.kind,m.value,m.lang,f.spelling,m.attribution_id,m.id "
                + "FROM form f JOIN lexical_metadata m ON m.form_id=f.id WHERE f.entry_id=?";
        sql += " ORDER BY id";
        try (Cursor rows = database.rawQuery(sql,sense ? new String[]{id} : new String[]{id,id})) {
            while (rows.moveToNext()) result.add(new Metadata(rows.getString(0),rows.getString(1),
                    rows.getString(2),rows.getString(3),attribution(rows.getString(4))));
        }
        return result;
    }

    public synchronized void close() {
        loadAttempted = false;
        synchronized (DictionaryPackageStore.LOCK) {
            closeActive();
        }
    }

    private void closeActive() {
        if (database != null) {
            database.close();
            DictionaryPackageStore.closed(openFolder);
            database = null;
            openFolder = null;
            openManifestDigest = null;
            manifest = null;
            status = Status.MISSING;
        }
    }
}
