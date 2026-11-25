package app.lexiflow.data;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.StringReader;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class PackageManifestTest {
    @Test public void compactAndFlatHashTheSameEscapedAttribution() throws Exception {
        // SHA-256 of Python json.dumps(record, sort_keys=True) for the fixture below.
        String id = "bcda0b4bd565595a6dd8993f0fd7de88a0ca23d8203eee3b09df756ec900aa8a";
        String snapshot = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        String url = "https://example.invalid/source";
        String locator = "entry/one\\two";
        String modification = "строка\nemoji 🙂";
        var policy = new JSONObject().put("source","fixture").put("snapshot",snapshot)
                .put("source_url",url).put("license_id","fixture").put("terms_url","https://example.invalid/terms")
                .put("attribution","сайт \"A\"");
        var row = new JSONArray().put(0).put(locator).put(modification);
        var root = new JSONObject().put("package_format",2).put("schema_version",2)
                .put("normalization",PackageManifest.NORMALIZATION).put("database_sha256",snapshot)
                .put("language_pairs",new JSONArray().put("en-ru").put("ru-en"))
                .put("corpus_version","synthetic-escape-test").put("unicode_version","fixture")
                .put("sources",new JSONArray().put(new JSONArray().put("fixture").put(snapshot).put(url)))
                .put("counts",new JSONObject()).put("policies",new JSONArray().put(policy))
                .put("attributions",new JSONObject().put(id,row));
        var compact = PackageManifest.parse(new StringReader(root.toString())).attribution(id);
        assertEquals(modification,compact.modification());
        row.put(2,"changed");
        assertThrows(JSONException.class,() -> PackageManifest.parse(new StringReader(root.toString())));
        root.remove("policies");
        policy.put("locator",locator).put("modification",modification);
        root.put("package_format",1).put("attributions",new JSONObject().put(id,policy));
        assertEquals(compact,PackageManifest.parse(new StringReader(root.toString())).attribution(id));
    }
}
