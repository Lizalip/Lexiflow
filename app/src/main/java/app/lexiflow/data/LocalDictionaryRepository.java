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
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Called on one background executor. SQLite connections always open read-only. */
public final class LocalDictionaryRepository implements DictionaryRepository {
    private static final String[] TABLES = {"entry", "sense", "translation", "form", "example", "example_translation"};
    private final Context context;
    private volatile Status status = Status.MISSING;
    private SQLiteDatabase database;
    private PackageManifest manifest;

    public LocalDictionaryRepository(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override public Status status() { return status; }
    @Override public Set<String> installedLanguages() { return status == Status.AVAILABLE ? Set.of("en","ru") : Set.of(); }
    public boolean isSynthetic() { return manifest != null && manifest.version().startsWith("synthetic-"); }

    public synchronized void load() {
        if (database != null) return;
        status = Status.LOADING;
        File root = new File(context.getFilesDir(), "dictionary");
        try {
            AtomicFile active = new AtomicFile(new File(root, "active"));
            String key = null;
            try {
                key = new String(active.readFully(), StandardCharsets.UTF_8).trim();
            } catch (FileNotFoundException ignored) {
                // A fresh installation can use the debug asset.
            }
            if (key != null) {
                if (!key.matches("[0-9a-f-]{36}")) throw new SQLiteException("invalid active key");
                File folder = new File(root,key);
                try (InputStream json = new FileInputStream(new File(folder,"manifest.json"))) {
                    PackageManifest candidate = readManifest(json);
                    accept(openValidated(new File(folder,"dictionary.sqlite"), candidate), candidate);
                }
            } else if (debug()) {
                try (InputStream json = context.getAssets().open("dictionary/manifest.json");
                     InputStream db = context.getAssets().open("dictionary/dictionary.sqlite")) {
                    install(json,db);
                } catch (FileNotFoundException absent) {
                    status = Status.MISSING;
                }
            } else {
                status = Status.MISSING;
            }
        } catch (UnsupportedOperationException incompatible) {
            status = Status.INCOMPATIBLE;
        } catch (JSONException | SQLiteException | IllegalArgumentException invalid) {
            status = Status.CORRUPTED;
        } catch (IOException failure) {
            status = Status.ERROR;
        }
    }

    private boolean debug() {
        return (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    public synchronized void install(InputStream json, InputStream db) throws IOException, JSONException {
        File root = new File(context.getFilesDir(), "dictionary");
        File folder = new File(root, UUID.randomUUID().toString());
        Files.createDirectories(folder.toPath());
        File jsonFile = new File(folder, "manifest.json");
        File dbFile = new File(folder, "dictionary.sqlite");
        SQLiteDatabase candidateDb = null;
        boolean activated = false;
        boolean pointerWritten = false;
        try {
            Files.copy(json, jsonFile.toPath());
            PackageManifest candidate;
            try (InputStream metadata = new FileInputStream(jsonFile)) {
                candidate = readManifest(metadata);
            }
            Files.copy(db,dbFile.toPath());
            try (FileOutputStream written = new FileOutputStream(dbFile,true);
                 FileOutputStream metadata = new FileOutputStream(jsonFile,true)) {
                written.getFD().sync();
                metadata.getFD().sync();
            }
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
            String installedKey = new String(active.readFully(),StandardCharsets.UTF_8);
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
        if (!candidate.digest().equals(sha256(file))) throw new SQLiteException("checksum mismatch");
        SQLiteDatabase opened = SQLiteDatabase.openDatabase(file.getPath(),null,SQLiteDatabase.OPEN_READONLY);
        try {
            if (opened.getVersion() != 1) throw new UnsupportedOperationException("unsupported schema");
            if (!"ok".equals(scalar(opened,"PRAGMA integrity_check"))) throw new SQLiteException("integrity check");
            try (Cursor check = opened.rawQuery("PRAGMA foreign_key_check",null)) {
                if (check.moveToFirst()) throw new SQLiteException("foreign key check");
            }
            for (String table : TABLES) {
                if (Long.parseLong(scalar(opened,"SELECT count(*) FROM " + table)) != candidate.counts().getLong(table)) {
                    throw new SQLiteException("manifest count mismatch");
                }
                try (Cursor refs = opened.rawQuery("SELECT DISTINCT attribution_id FROM " + table,null)) {
                    while (refs.moveToNext()) {
                        var att = candidate.attributions().getJSONObject(refs.getString(0));
                        att.getString("source_url");
                        att.getString("attribution");
                    }
                }
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
            try (Cursor refs = opened.rawQuery("SELECT DISTINCT origin_attribution_id FROM translation",null)) {
                while (refs.moveToNext()) candidate.attributions().getJSONObject(refs.getString(0));
            }
            for (String sql : List.of("SELECT spelling,search_key FROM entry",
                    "SELECT spelling,search_key FROM form","SELECT wording,search_key FROM translation")) {
                try (Cursor keys = opened.rawQuery(sql,null)) {
                    while (keys.moveToNext()) {
                        if (!SearchNormalization.normalize(keys.getString(0)).equals(keys.getString(1))) {
                            throw new UnsupportedOperationException("package search keys differ from Android ICU");
                        }
                    }
                }
            }
            if (candidate.counts().getLong("entry") == 0 || candidate.counts().getLong("translation") == 0
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

    private static void checkIndex(SQLiteDatabase db,String index,String table,String secondary) {
        if (!table.equals(scalar(db,"SELECT tbl_name FROM sqlite_master WHERE type='index' AND name='" + index + "'"))) {
            throw new SQLiteException("index has wrong table");
        }
        try (Cursor columns = db.rawQuery("PRAGMA index_info(" + index + ")",null)) {
            if (columns.getCount() != 2 || !columns.moveToNext() || !"search_key".equals(columns.getString(2))
                    || !columns.moveToNext() || !secondary.equals(columns.getString(2))) {
                throw new SQLiteException("index has wrong columns");
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
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int count; (count = input.read(buffer)) != -1;) {
            bytes.write(buffer,0,count);
        }
        return PackageManifest.parse(new String(bytes.toByteArray(),StandardCharsets.UTF_8));
    }

    private static String scalar(SQLiteDatabase db, String query) {
        try (Cursor cursor = db.rawQuery(query,null)) {
            if (!cursor.moveToFirst()) throw new SQLiteException("missing package metadata");
            return cursor.getString(0);
        }
    }

    private static String sha256(File file) throws IOException {
        try (InputStream input = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DigestInputStream hashing = new DigestInputStream(input,digest)) {
                byte[] buffer = new byte[32768];
                while (hashing.read(buffer) != -1) { /* digest updated by stream */ }
            }
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
        if (database == null || query.codePointCount(0, query.length()) > 256) return List.of();
        String key = SearchNormalization.normalize(query);
        if (key.isEmpty()) return List.of();
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
        List<Hit> results = new ArrayList<>();
        String sql = "WITH hits AS (" + hits + "), grouped AS (SELECT sense_id,min(search_key) AS best FROM hits GROUP BY sense_id)"
                + " SELECT e.id,s.id,e.spelling,e.pos,s.position FROM grouped g JOIN sense s ON s.id=g.sense_id"
                + " JOIN entry e ON e.id=s.entry_id ORDER BY (g.best=?) DESC,g.best,e.id,s.position,s.id LIMIT 50";
        try (Cursor cursor = database.rawQuery(sql,args.toArray(new String[0]))) {
            while (cursor.moveToNext()) results.add(new Hit(cursor.getString(0),cursor.getString(1),
                    cursor.getString(2),cursor.getString(3),cursor.getInt(4)));
        }
        return results;
    }

    private String attribution(String ref) {
        var object = manifest.attributions().optJSONObject(ref);
        if (object == null) throw new SQLiteException("missing attribution");
        return object.optString("attribution") + " · " + object.optString("license_id") + "\n" + object.optString("source_url");
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
                    senses.add(new Sense(sid,rows.getInt(1),translations,examples,String.join("\n",credits)));
                }
            }
            return new Entry(id,entry.getString(0),entry.getString(1),senses,attribution(entry.getString(2)));
        }
    }

    public synchronized void close() {
        if (database != null) {
            database.close();
            database = null;
        }
    }
}
