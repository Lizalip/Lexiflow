package app.lexiflow.data;

import java.io.StringReader;
import java.util.Collections;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class CompactAttributionEncodingTest {
    private static final String LOCATOR = "entry/ёж/\"\\\t\n𐐀";
    private static final String SHORT_ID = "43cec351c50fe0aca503af189b01c6a28f64793c78bd255b91280d7c3509ea10";
    private static final String LONG_ID = "8ca9501008760763ae9b48bcb5b2347f81fd7503a20d0dfc814c1e50a55a9f2c";

    @Test public void preservesCanonicalAsciiForEscapesAndOversizedAttribution() throws Exception {
        // These IDs were calculated independently using Python json.dumps(sort_keys=True).
        String shortModification = "research-local-v1: compact";
        String longModification = "research-local-v1: "+String.join("",Collections.nCopies(700,"я"));
        var manifest = parse(shortModification,longModification);
        assertEquals(LOCATOR,manifest.attribution(SHORT_ID).locator());
        assertEquals(shortModification,manifest.attribution(SHORT_ID).modification());
        assertEquals(longModification,manifest.attribution(LONG_ID).modification());
        assertThrows(JSONException.class,() -> parse(shortModification+"x",longModification));
        assertThrows(JSONException.class,() -> parse(shortModification,longModification+"x"));
    }

    private static PackageManifest parse(String shortModification,String longModification) throws Exception {
        String snapshot = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        String source = "https://example.invalid/source";
        var policy = new JSONObject().put("source","format fixture").put("snapshot",snapshot)
                .put("source_url",source).put("license_id","fixture")
                .put("terms_url","https://example.invalid/terms").put("attribution","test credit");
        var attributions = new JSONObject()
                .put(SHORT_ID,new JSONArray().put(0).put(LOCATOR).put(0))
                .put(LONG_ID,new JSONArray().put(0).put(LOCATOR).put(1));
        var document = new JSONObject().put("schema_version",2).put("package_format",3)
                .put("normalization","nfc-casefold-nfc-python-whitespace-v1")
                .put("language_pairs",new JSONArray().put("en-ru").put("ru-en"))
                .put("corpus_version","research-encoding-fixture").put("unicode_version","16.0.0")
                .put("database_sha256",snapshot).put("counts",new JSONObject())
                .put("distribution_policy","research-local-v1")
                .put("sources",new JSONArray().put(new JSONArray().put("format fixture").put(snapshot).put(source)))
                .put("policies",new JSONArray().put(policy)).put("attributions",attributions)
                .put("modifications",new JSONArray().put(shortModification).put(longModification));
        return PackageManifest.parse(new StringReader(document.toString()));
    }
}
