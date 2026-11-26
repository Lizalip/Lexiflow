package app.lexiflow.data;

import java.io.StringReader;
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
}
