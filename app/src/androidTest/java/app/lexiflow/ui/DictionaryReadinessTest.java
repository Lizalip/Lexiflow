package app.lexiflow.ui;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.icu.lang.UCharacter;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.IOException;
import java.text.Normalizer;
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
                        "SELECT s.id, row_number() OVER (PARTITION BY e.id ORDER BY s.id) "
                        + "FROM entry e JOIN sense s ON s.entry_id=e.id "
                        + "WHERE e.search_key>=? AND e.search_key<? ORDER BY e.search_key LIMIT 1",
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
            assertEquals(vector[1], normalize(vector[0]));
        }
        assertThrows(IllegalArgumentException.class, () -> normalize("\uD800"));
        assertThrows(IllegalArgumentException.class, () -> normalize("\uDC00"));
        assertThrows(IllegalArgumentException.class, () -> normalize("\u0000"));
    }

    private static String normalize(String text) {
        text.codePoints().forEach(cp -> {
            if (cp == 0 || (cp >= 0xD800 && cp <= 0xDFFF)) {
                throw new IllegalArgumentException("invalid scalar");
            }
        });
        String nfc = Normalizer.normalize(text, Normalizer.Form.NFC);
        String folded = Normalizer.normalize(UCharacter.foldCase(nfc, true), Normalizer.Form.NFC);
        return folded.replaceAll("[\\x{0009}-\\x{000D}\\x{001C}-\\x{0020}\\x{0085}\\x{00A0}"
                + "\\x{1680}\\x{2000}-\\x{200A}\\x{2028}\\x{2029}\\x{202F}\\x{205F}\\x{3000}]+", " ").trim();
    }
}
