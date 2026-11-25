package app.lexiflow.ui;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import app.lexiflow.data.SearchNormalization;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class DictionaryReadinessTest {
    @Test public void readsVersionAndGroupsPrefixResultsFromTemporaryDatabase() throws IOException {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File file = File.createTempFile("artificial-dictionary", ".sqlite", context.getCacheDir());
        try {
            try (SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(file, null)) {
                db.setForeignKeyConstraintsEnabled(true);
                db.setVersion(1);
                db.execSQL("CREATE TABLE entry(id TEXT PRIMARY KEY, search_key TEXT NOT NULL)");
                db.execSQL("CREATE INDEX entry_search ON entry(search_key,id)");
                db.execSQL("CREATE TABLE sense(id TEXT PRIMARY KEY, entry_id TEXT REFERENCES entry(id))");
                db.execSQL("INSERT INTO entry VALUES ('entry-a','artificial'),('entry-b','artificial-extra')");
                db.execSQL("INSERT INTO sense VALUES ('sense-a','entry-a'),('sense-b','entry-b')");
            }
            try (SQLiteDatabase db = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
                assertEquals(1, db.getVersion());
                try (Cursor c = db.rawQuery("PRAGMA quick_check", null)) {
                    assertTrue(c.moveToFirst());
                    assertEquals("ok", c.getString(0));
                }
                try (Cursor c = db.rawQuery("PRAGMA foreign_key_check", null)) {
                    assertEquals(0, c.getCount());
                }
                try (Cursor c = db.rawQuery(
                        "SELECT s.id, min(e.search_key) AS matched "
                        + "FROM entry e JOIN sense s ON s.entry_id=e.id "
                        + "WHERE e.search_key>=? AND e.search_key<? GROUP BY s.id ORDER BY matched LIMIT 1",
                        new String[]{"artificial", "artificiam"})) {
                    assertTrue(c.moveToFirst());
                    assertEquals("sense-a", c.getString(0));
                }
            }
        } finally {
            SQLiteDatabase.deleteDatabase(file);
        }
    }

    @Test public void icuFoldingMatchesSelectedPythonVectors() {
        String[][] vectors = {
            {"Е\u0308Ж", "ёж"}, {"ЕЖ", "еж"}, {"Straße", "strasse"},
            {"Σςσ", "σσσ"}, {"İ", "i\u0307"}, {"CAFE\u0301", "café"},
            {" A\u00A0B\u2003C\u001C", "a b c"}, {"IT'S IT’S A-B A–B", "it's it’s a-b a–b"},
            {"\uD801\uDC00 😀", "\uD801\uDC28 😀"}
        };
        for (String[] vector : vectors) {
            assertEquals(vector[1], SearchNormalization.normalize(vector[0]));
        }
        assertThrows(IllegalArgumentException.class, () -> SearchNormalization.normalize("\uD800"));
        assertThrows(IllegalArgumentException.class, () -> SearchNormalization.normalize("\uDC00"));
        assertThrows(IllegalArgumentException.class, () -> SearchNormalization.normalize("\u0000"));
    }

    @Test public void icuMatchesSharedDebugAssetVectors() throws Exception {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (var reader = new BufferedReader(new InputStreamReader(
                context.getAssets().open("dictionary/normalization_vectors.json"),StandardCharsets.UTF_8))) {
            StringBuilder json = new StringBuilder();
            for (String line; (line = reader.readLine()) != null;) json.append(line);
            var data = new JSONObject(json.toString());
            var vectors = data.getJSONArray("vectors");
            for (int i = 0; i < vectors.length(); i++) {
                var item = vectors.getJSONObject(i);
                assertEquals(item.getString("expected"),SearchNormalization.normalize(item.getString("input")));
            }
            var invalid = data.getJSONArray("invalid_codepoints");
            for (int i = 0; i < invalid.length(); i++) {
                String value = String.valueOf((char) invalid.getInt(i));
                assertThrows(IllegalArgumentException.class,() -> SearchNormalization.normalize(value));
            }
        }
    }
}
