package app.lexiflow.data;

import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import java.io.StringReader;
import java.util.Collections;
import java.util.HashMap;
import org.json.JSONException;
import org.junit.Test;
import static org.junit.Assert.*;

public class AdvancedManifestTest {
    private static final String ID = "8e5dd511684b65e2ae754141d3c53ab707b5ae3aa9f3defddce79374f7ededfd";
    private static final String SNAPSHOT = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String MOD = "research-local-v1: fixture";

    private static String fixture() {
        return String.format(java.util.Locale.ROOT,"""
            {"schema_version":2,"package_format":3,"normalization":"nfc-casefold-nfc-python-whitespace-v1",
             "language_pairs":["en-ru","ru-en"],"corpus_version":"research-format-fixture",
             "unicode_version":"16.0.0","database_sha256":"%s","counts":{},
             "distribution_policy":"research-local-v1","modifications":["%s"],
             "sources":[["format fixture","%s","https://example.invalid/source"]],
             "policies":[{"source":"format fixture","snapshot":"%s","source_url":"https://example.invalid/source",
             "license_id":"fixture","terms_url":"https://example.invalid/terms","attribution":"test credit"}],
             "attributions":{"%s":[0,"entry/1",0]}}
            """,SNAPSHOT,MOD,SNAPSHOT,SNAPSHOT,ID);
    }

    @Test public void restoresExactCanonicalAttributionForVersionsTwoAndThree() throws Exception {
        var pooled = PackageManifest.parse(new StringReader(fixture()));
        var old = PackageManifest.parse(new StringReader(fixture().replace("\"package_format\":3","\"package_format\":2")
                .replace(",\"modifications\":[\""+MOD+"\"]","")
                .replace("[0,\"entry/1\",0]","[0,\"entry/1\",\""+MOD+"\"]")));
        assertEquals(MOD,pooled.attribution(ID).modification());
        assertEquals(old.attribution(ID),pooled.attribution(ID));
    }

    @Test public void rejectsMalformedMissingAndCorruptedFields() {
        for (String invalid : new String[]{
                fixture().replace("[0,\"entry/1\",0]","[0,\"entry/1\",4]"),
                fixture().replace("\"modifications\":[\""+MOD+"\"]","\"modifications\":[]"),
                fixture().replace("\"modifications\":[\""+MOD+"\"]","\"modifications\":[],\"modifications\":[\""+MOD+"\"]"),
                fixture().replace(",\"modifications\":[\""+MOD+"\"]",""),
                fixture().replace("test credit","different credit"),
                fixture().replace(ID,SNAPSHOT)}) {
            assertThrows(JSONException.class,() -> PackageManifest.parse(new StringReader(invalid)));
        }
        assertThrows(UnsupportedOperationException.class,() ->
                PackageManifest.parse(new StringReader(fixture().replace("\"package_format\":3","\"package_format\":4"))));
    }

    @Test public void scannedMetadataRejectsMissingAndWrongLanguageOwners() throws Exception {
        var base = PackageManifest.parse(new StringReader(fixture()));
        var origin = base.attribution(ID);
        var note = new PackageManifest.EntryNote("fixture definition",
                new PackageManifest.Attribution(origin.policy(),"entry/1/sense/0/sense/0/def/0",MOD));
        var label = new PackageManifest.CefrLabel("A1",new PackageManifest.Attribution(origin.policy(),
                "fixture","research-local-v1: CEFR-J lemma=fixture; pos=n"));
        var manifest = new PackageManifest(3,3,base.version(),base.unicode(),base.digest(),true,
                base.counts(),base.attributions(),Collections.emptyMap(),
                Collections.singletonMap("note-owner",Collections.singletonList(note)),
                Collections.singletonMap("level-owner",Collections.singletonList(label)),Collections.emptyMap());
        try (var db = SQLiteDatabase.create(null)) {
            db.execSQL("CREATE TABLE entry(id TEXT PRIMARY KEY,attribution_id TEXT,search_key TEXT,pos TEXT,language TEXT)");
            for (String id : new String[]{"note-owner","level-owner"}) {
                db.execSQL("INSERT INTO entry VALUES (?,?,'fixture','n','en')",new Object[]{id,ID});
            }
            LocalDictionaryRepository.checkAttributedEntries(db,manifest);
            db.execSQL("UPDATE entry SET language='ru' WHERE id='level-owner'");
            assertThrows(JSONException.class,() -> LocalDictionaryRepository.checkAttributedEntries(db,manifest));
            db.execSQL("UPDATE entry SET language='en' WHERE id='level-owner'");
            db.execSQL("DELETE FROM entry WHERE id='note-owner'");
            assertThrows(JSONException.class,() -> LocalDictionaryRepository.checkAttributedEntries(db,manifest));
        }
    }

    @Test public void combinedTranslationScanRejectsInvalidLanguageKeysAndProvenance() throws Exception {
        var base = PackageManifest.parse(new StringReader(fixture()));
        var original = base.attribution(ID);
        var english = new PackageManifest.Attribution(new PackageManifest.Policy(
                new PackageManifest.Source("fixture","f3dadcc29d7df6e77edb13b98a2ba2c3fac002852eddd8b557637f0b13d39c39",
                        "https://example.invalid/source"),
                "fixture","https://example.invalid/terms","test credit"),"origin",MOD);
        var attributions = new HashMap<String,PackageManifest.Attribution>();
        attributions.put("origin",english);
        attributions.put("different",original);
        var manifest = new PackageManifest(3,3,base.version(),base.unicode(),base.digest(),true,
                base.counts(),attributions,Collections.emptyMap(),Collections.emptyMap(),
                Collections.emptyMap(),Collections.emptyMap());
        try (var db = SQLiteDatabase.create(null)) {
            db.execSQL("CREATE TABLE entry(id TEXT PRIMARY KEY,attribution_id TEXT,language TEXT)");
            db.execSQL("CREATE TABLE sense(id TEXT PRIMARY KEY,entry_id TEXT,attribution_id TEXT)");
            db.execSQL("CREATE TABLE translation(sense_id TEXT,origin_attribution_id TEXT,attribution_id TEXT,"
                    + "wording TEXT,search_key TEXT,language TEXT)");
            db.execSQL("INSERT INTO entry VALUES ('entry','origin','en')");
            db.execSQL("INSERT INTO sense VALUES ('sense','entry','origin')");
            db.execSQL("INSERT INTO translation VALUES ('sense','origin','origin',' ЁЖ ','ёж','ru')");
            LocalDictionaryRepository.checkTranslations(db,manifest);
            for (String invalid : new String[]{"NULL","'en'","'fr'"}) {
                db.execSQL("UPDATE translation SET language="+invalid);
                assertThrows(SQLiteException.class,() -> LocalDictionaryRepository.checkTranslations(db,manifest));
            }
            db.execSQL("UPDATE translation SET language='ru',search_key='еж'");
            assertThrows(UnsupportedOperationException.class,() -> LocalDictionaryRepository.checkTranslations(db,manifest));
            db.execSQL("UPDATE translation SET search_key='ёж',attribution_id='missing'");
            assertThrows(JSONException.class,() -> LocalDictionaryRepository.checkTranslations(db,manifest));
            db.execSQL("UPDATE translation SET attribution_id='different'");
            assertThrows(JSONException.class,() -> LocalDictionaryRepository.checkTranslations(db,manifest));
            db.execSQL("UPDATE translation SET attribution_id='origin',origin_attribution_id='different'");
            assertThrows(JSONException.class,() -> LocalDictionaryRepository.checkTranslations(db,manifest));
        }
    }

    @Test public void canonicalSearchNormalizationPreservesUnicodeAndWhitespaceRules() {
        assertEquals("",SearchNormalization.normalize(""));
        assertEquals("",SearchNormalization.normalize("\t\u001C\u3000"));
        assertEquals("ёж strasse σ 𐐨",SearchNormalization.normalize("  Е\u0308Ж\u0085Straße\u00A0ς\t𐐀 "));
        assertThrows(IllegalArgumentException.class,() -> SearchNormalization.normalize("bad\0key"));
        assertThrows(IllegalArgumentException.class,() -> SearchNormalization.normalize("bad\uD800key"));
    }
}
