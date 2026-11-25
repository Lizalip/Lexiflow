package app.lexiflow.data;

import org.json.JSONException;
import org.json.JSONObject;

record PackageManifest(String version, String unicode, String digest, JSONObject counts, JSONObject attributions) {
    static final String NORMALIZATION = "nfc-casefold-nfc-python-whitespace-v1";

    static PackageManifest parse(String json) throws JSONException {
        JSONObject object = new JSONObject(json);
        var pairs = object.getJSONArray("language_pairs");
        if (object.getInt("package_format") != 1 || object.getInt("schema_version") != 1
                || !NORMALIZATION.equals(object.getString("normalization")) || pairs.length() != 2
                || !"en-ru".equals(pairs.getString(0)) || !"ru-en".equals(pairs.getString(1))) {
            throw new UnsupportedOperationException("unsupported package");
        }
        String digest = object.getString("database_sha256");
        if (!digest.matches("[0-9a-f]{64}") || object.getString("corpus_version").isBlank()
                || object.getString("unicode_version").isBlank()) throw new JSONException("invalid version or digest");
        return new PackageManifest(object.getString("corpus_version"), object.getString("unicode_version"),
                digest, object.getJSONObject("counts"), object.getJSONObject("attributions"));
    }
}
