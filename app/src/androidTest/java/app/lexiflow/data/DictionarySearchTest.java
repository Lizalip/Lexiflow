package app.lexiflow.data;

import android.database.sqlite.SQLiteDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class DictionarySearchTest {
    @Test public void exactHeadwordsThenFormsAndPreservesEverySense() {
        try (var db = SQLiteDatabase.create(null)) {
            db.execSQL("CREATE TABLE entry(id TEXT PRIMARY KEY,spelling TEXT,pos TEXT,search_key TEXT)");
            db.execSQL("CREATE TABLE sense(id TEXT PRIMARY KEY,entry_id TEXT,position INTEGER)");
            db.execSQL("CREATE TABLE form(entry_id TEXT,spelling TEXT,search_key TEXT)");
            db.execSQL("CREATE TABLE translation(id INTEGER PRIMARY KEY,sense_id TEXT,wording TEXT,search_key TEXT)");
            db.execSQL("CREATE INDEX entry_search ON entry(search_key,id)");
            db.execSQL("CREATE INDEX translation_search ON translation(search_key,sense_id)");
            db.execSQL("CREATE INDEX form_search ON form(search_key,entry_id)");
            db.execSQL("INSERT INTO entry VALUES ('h','test','noun','test'),('f','other','verb','other'),"
                    + "('v','test','verb','test'),('p','test phrase','phrase','test phrase')");
            db.execSQL("INSERT INTO sense VALUES ('h1','h',1),('h2','h',2),('form','f',1),('verb','v',1),('prefix','p',1)");
            db.execSQL("INSERT INTO entry VALUES ('punct','test''s-phrase [%_]','phrase','test''s-phrase [%_]')");
            db.execSQL("INSERT INTO sense VALUES ('punct','punct',1)");
            db.execSQL("INSERT INTO form VALUES ('f','test','test'),('f','test-s','test-s')");
            db.execSQL("INSERT INTO translation(sense_id,wording,search_key) VALUES "
                    + "('h1','Ёж','ёж'),('h1','Ёжик','ёжик'),('h2','Еж','еж'),('prefix','Ёж-сказка','ёж-сказка')");
            var rows = query(db," TEST ",false);
            assertEquals(Arrays.asList("h1","h2","verb","form","prefix","punct"),rows);
            assertEquals(Arrays.asList("h1","prefix"),query(db,"Е\u0308Ж",true));
            assertEquals(Arrays.asList("h2"),query(db,"ЕЖ",true));
            assertEquals(Arrays.asList("prefix"),query(db,"  test\u00a0phrase ",false));
            assertTrue(query(db,"test’",false).isEmpty());
            assertEquals(Arrays.asList("punct"),query(db,"TEST'S-PHRASE [%_]",false));
            assertTrue(query(db,"test's-phrase [_%]",false).isEmpty());
            assertTrue(query(db,"test's–phrase",false).isEmpty());
            var reverse = DictionarySearch.ranked("ёж",true);
            try (var cursor = db.rawQuery(reverse.sql(),reverse.arguments())) {
                assertTrue(cursor.moveToFirst());
                assertEquals("Ёж",cursor.getString(5));
            }
        }
    }

    private static List<String> query(SQLiteDatabase db,String text,boolean reverse) {
        var query = DictionarySearch.ranked(SearchNormalization.normalize(text),reverse);
        List<String> ids = new ArrayList<>();
        try (var cursor = db.rawQuery(query.sql(),query.arguments())) {
            while (cursor.moveToNext()) ids.add(cursor.getString(1));
        }
        return ids;
    }
}
