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

/** Called on one background executor. SQLite connections always open read-only. */
public final class LocalDictionaryRepository implements DictionaryRepository {
    private static final String[] TABLES = {"entry", "sense", "translation", "form", "example", "example_translation"};
    private static final Set<String> LANGUAGES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList("en","ru")));
    private static final Set<String> METADATA_LINKS = new HashSet<>(
            Arrays.asList("entry:entry_id:id","sense:sense_id:id","form:form_id:id"));
    static final long MAX_MANIFEST = 64L * 1024 * 1024;
    static final long MAX_DATABASE = 256L * 1024 * 1024;
    private final Context context;
    private volatile Status status = Status.MISSING;
    private SQLiteDatabase database;
    private PackageManifest manifest;
    private boolean loadAttempted;

    public LocalDictionaryRepository(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override public Status status() { return status; }
    @Override public Set<String> installedLanguages() { return status == Status.AVAILABLE ? LANGUAGES : Collections.emptySet(); }
    public boolean isSynthetic() { return manifest != null && manifest.version().startsWith("synthetic-"); }

    public synchronized void load() {
        if (database != null || loadAttempted) return;
        loadAttempted = true;
        status = Status.LOADING;
        File root = packageRoot();
        try {
            File activeManifest;
            try {
                activeManifest = openActive(root);
            } catch (IOException | JSONException | RuntimeException invalidActive) {
                if (debug() || !installReleaseAsset(null)) throw invalidActive;
                return;
            }
            if (!debug()) installReleaseAsset(activeManifest);
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
        String key;
        try {
            key = readPointer(new AtomicFile(new File(root,"active")));
        } catch (FileNotFoundException absent) {
            return null;
        }
        if (!validId(key)) throw new SQLiteException("invalid active key");
        File folder = new File(root,key);
        File manifestFile = new File(folder,"manifest.json");
        try (InputStream json = new FileInputStream(manifestFile)) {
            PackageManifest candidate = readManifest(json);
            accept(openValidated(new File(folder,"dictionary.sqlite"),candidate),candidate);
        }
        return manifestFile;
    }

    private boolean installReleaseAsset(File active) throws IOException, JSONException {
        String path = "dictionary-release";
        if (active != null) {
            try (InputStream bundled = context.getAssets().open(path+"/manifest.json")) {
                if (sha256(bundled).equals(sha256(active))) return true;
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
        if (!debug()) throw new IOException("bundled V2 unavailable");
        try (InputStream json = context.getAssets().open("dictionary-v2/manifest.json")) {
            PackageManifest bundled = readManifest(json);
            if (database != null && bundled.format() == manifest.format() && bundled.schema() == manifest.schema()
                    && bundled.version().equals(manifest.version()) && bundled.digest().equals(manifest.digest())) return;
        }
        if (!installAsset("dictionary-v2")) throw new IOException("bundled V2 unavailable");
    }

    private boolean debug() {
        return (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    private File packageRoot() {
        return new File(context.getFilesDir(),debug() ? "dictionary" : "trusted-dictionary");
    }

    public synchronized void install(InputStream json, InputStream db) throws IOException, JSONException {
        if (!debug()) throw new UnsupportedOperationException("local installation is debug only");
        installPackage(json,db);
    }

    private void installPackage(InputStream json, InputStream db) throws IOException, JSONException {
        File root = packageRoot();
        File folder = new File(root, UUID.randomUUID().toString());
        Files.createDirectories(folder.toPath());
        File jsonFile = new File(folder, "manifest.json");
        File dbFile = new File(folder, "dictionary.sqlite");
        SQLiteDatabase candidateDb = null;
        boolean activated = false;
        boolean pointerWritten = false;
        try {
            writeCandidate(json,jsonFile,MAX_MANIFEST);
            PackageManifest candidate;
            try (InputStream metadata = new FileInputStream(jsonFile)) {
                candidate = readManifest(metadata);
            }
            writeCandidate(db,dbFile,MAX_DATABASE);
            candidateDb = openValidated(dbFile,candidate);
            AtomicFile active = new AtomicFile(new File(root,"active"));
            FileOutputStream pointer = active.startWrite();
            try {
                pointer.write(folder.getName().getBytes(StandardCharsets.UTF_8));
                active.finishWrite(pointer);
                pointerWritten = true;
            } catch (IOException failed) {
                active.failWrite(pointer);
                throw failed;
            }
            String installedKey = readPointer(active);
            if (!folder.getName().equals(installedKey)) {
                pointerWritten = false;
                throw new IOException("package pointer was not updated");
            }
            accept(candidateDb,candidate);
            activated = true;
        } finally {
            if (!activated) {
                if (candidateDb != null) candidateDb.close();
                if (!pointerWritten) {
                    Files.deleteIfExists(dbFile.toPath());
                    Files.deleteIfExists(jsonFile.toPath());
                    Files.deleteIfExists(folder.toPath());
                }
            }
        }
    }

    private SQLiteDatabase openValidated(File file, PackageManifest candidate) throws IOException, JSONException {
        if (candidate.version().startsWith("synthetic-") && !debug()) {
            throw new UnsupportedOperationException("synthetic packages require a debug installation");
        }
        if (file.length() > MAX_DATABASE || !candidate.digest().equals(sha256(file))) {
            throw new SQLiteException("size or checksum mismatch");
        }
        SQLiteDatabase opened = SQLiteDatabase.openDatabase(file.getPath(),null,SQLiteDatabase.OPEN_READONLY);
        try {
            if (opened.getVersion() != candidate.schema()) throw new UnsupportedOperationException("schema mismatch");
            if (!"ok".equals(scalar(opened,"PRAGMA integrity_check"))) throw new SQLiteException("integrity check");
            try (Cursor check = opened.rawQuery("PRAGMA foreign_key_check",null)) {
                if (check.moveToFirst()) throw new SQLiteException("foreign key check");
            }
            for (String table : TABLES) checkTable(opened,candidate,table);
            for (var entry : candidate.contexts().entrySet()) {
                try (Cursor row = opened.rawQuery("SELECT attribution_id FROM sense WHERE id=?",new String[]{entry.getKey()})) {
                    if (!row.moveToFirst() || !entry.getValue().belongsTo(candidate.attribution(row.getString(0)))) {
                        throw new JSONException("invalid source sense relationship");
                    }
                }
            }
            if (candidate.schema() == 2) {
                checkMetadata(opened,candidate);
                checkIndexColumns(opened,"translation_preview","translation","sense_id","search_key","wording");
                checkIndexColumns(opened,"form_parent","form","entry_id","id");
                checkIndexColumns(opened,"example_parent","example","sense_id","id");
                checkIndexColumns(opened,"example_translation_parent","example_translation","example_id","id");
            }
            try (Cursor ids = opened.rawQuery("SELECT id FROM entry UNION ALL SELECT id FROM sense",null)) {
                while (ids.moveToNext()) if (!validId(ids.getString(0))) throw new SQLiteException("invalid persistent ID");
            }
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
            try (Cursor refs = opened.rawQuery("SELECT t.origin_attribution_id,t.attribution_id,s.attribution_id,e.attribution_id"
                    + " FROM translation t JOIN sense s ON s.id=t.sense_id JOIN entry e ON e.id=s.entry_id",null)) {
                while (refs.moveToNext()) {
                    var origin = candidate.attribution(refs.getString(0)).policy().source();
                    for (int i = 1; i < 4; i++) {
                        var source = candidate.attribution(refs.getString(i)).policy().source();
                        if (!origin.name().equals(source.name()) || !origin.snapshot().equals(source.snapshot())) {
                            throw new JSONException("inconsistent translation provenance");
                        }
                    }
                }
            }
            for (String sql : new String[]{"SELECT spelling,search_key FROM entry",
                    "SELECT spelling,search_key FROM form","SELECT wording,search_key FROM translation"}) {
                try (Cursor keys = opened.rawQuery(sql,null)) {
                    while (keys.moveToNext()) {
                        if (!SearchNormalization.normalize(keys.getString(0)).equals(keys.getString(1))) {
                            throw new UnsupportedOperationException("package search keys differ from Android ICU");
                        }
                    }
                }
            }
            if (candidate.count("entry") == 0 || candidate.count("translation") == 0
                    || !"0".equals(scalar(opened,"SELECT count(*) FROM sense s WHERE NOT EXISTS "
                    + "(SELECT 1 FROM translation t WHERE t.sense_id=s.id)"))
                    || !"0".equals(scalar(opened,"SELECT count(*) FROM entry e WHERE NOT EXISTS "
                    + "(SELECT 1 FROM sense s WHERE s.entry_id=e.id)"))) throw new SQLiteException("empty or incomplete corpus");
            return opened;
        } catch (RuntimeException | JSONException failed) {
            opened.close();
            throw failed;
        }
    }

    private static boolean validId(String value) {
        try { return value != null && UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException invalid) { return false; }
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

    private static PackageManifest readManifest(InputStream input) throws IOException, JSONException {
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
            return PackageManifest.parse(new InputStreamReader(bounded,StandardCharsets.UTF_8.newDecoder()));
        } catch (android.util.MalformedJsonException | java.io.EOFException malformed) {
            throw new JSONException("malformed package manifest");
        }
    }

    private static String readPointer(AtomicFile active) throws IOException {
        try (InputStream input = active.openRead()) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(36);
            transfer(input,bytes,36);
            return new String(bytes.toByteArray(),StandardCharsets.UTF_8);
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

    private static String sha256(File file) throws IOException {
        try (InputStream input = new FileInputStream(file)) {
            return sha256(input);
        }
    }

    private static String sha256(InputStream input) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            DigestInputStream hashing = new DigestInputStream(input,digest);
            byte[] buffer = new byte[32768];
            while (hashing.read(buffer) != -1) { /* digest updated by stream */ }
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest.digest()) {
                hex.append(Character.forDigit((value & 0xff) >>> 4,16));
                hex.append(Character.forDigit(value & 15,16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private void accept(SQLiteDatabase opened, PackageManifest candidate) {
        if (database != null) database.close();
        database = opened;
        manifest = candidate;
        status = Status.AVAILABLE;
    }

    @Override public synchronized List<Hit> search(String query, boolean reverse) {
        if (database == null || query.codePointCount(0, query.length()) > 256) return Collections.emptyList();
        String key = SearchNormalization.normalize(query);
        if (key.isEmpty()) return Collections.emptyList();
        String end = SearchNormalization.prefixEnd(key);
        String range = "search_key>=?" + (end == null ? "" : " AND search_key<?");
        String forward = "SELECT s.id AS sense_id,e.search_key FROM entry e JOIN sense s ON s.entry_id=e.id WHERE e." + range
                + " UNION ALL SELECT s.id,f.search_key FROM form f JOIN sense s ON s.entry_id=f.entry_id WHERE f." + range;
        String hits = reverse ? "SELECT sense_id,search_key FROM translation WHERE " + range : forward;
        List<String> args = new ArrayList<>();
        for (int i = 0; i < (reverse ? 1 : 2); i++) {
            args.add(key);
            if (end != null) args.add(end);
        }
        args.add(key);
        args.add(key);
        List<Hit> results = new ArrayList<>();
        String sql = "WITH hits AS (" + hits + "), grouped AS (SELECT sense_id,min(search_key) AS best FROM hits GROUP BY sense_id),"
                + " selected AS (SELECT g.sense_id,g.best FROM grouped g JOIN sense s ON s.id=g.sense_id"
                + " ORDER BY (g.best=?) DESC,g.best,s.entry_id,s.position,s.id LIMIT 50)"
                + " SELECT e.id,s.id,e.spelling,e.pos,s.position,"
                + "(SELECT t.wording FROM translation t WHERE t.sense_id=s.id "
                + (reverse ? "AND t.search_key=g.best " : "")
                + "ORDER BY t.search_key,t.wording,t.id LIMIT 1),"
                + (reverse ? "NULL" : "(SELECT min(f.spelling) FROM form f WHERE f.entry_id=e.id AND f.search_key=g.best AND e.search_key<>g.best)")
                + " FROM selected g JOIN sense s ON s.id=g.sense_id"
                + " JOIN entry e ON e.id=s.entry_id ORDER BY (g.best=?) DESC,g.best,e.id,s.position,s.id";
        try (Cursor cursor = database.rawQuery(sql,args.toArray(new String[0]))) {
            while (cursor.moveToNext()) results.add(new Hit(cursor.getString(0),cursor.getString(1),
                    cursor.getString(2),cursor.getString(3),cursor.getInt(4),cursor.getString(5),cursor.getString(6)));
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
        try (Cursor entry = database.rawQuery("SELECT spelling,pos,attribution_id FROM entry WHERE id=?",new String[]{id})) {
            if (!entry.moveToFirst()) return null;
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
            return new Entry(id,entry.getString(0),entry.getString(1),senses,attribution(entry.getString(2)),metadata(id,false));
        }
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
        if (database != null) {
            database.close();
            database = null;
            manifest = null;
            status = Status.MISSING;
        }
    }
}
