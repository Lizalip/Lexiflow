package app.lexiflow.data;

import android.util.JsonReader;
import android.util.JsonToken;
import org.json.JSONException;
import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Compact links share immutable policies for the lifetime of the open package. */
record PackageManifest(int format, int schema, String version, String unicode, String digest,
                       Map<String,Object> counts, Map<String,Attribution> attributions) {
    static final String NORMALIZATION = "nfc-casefold-nfc-python-whitespace-v1";
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> POLICY_FIELDS = new HashSet<>(Arrays.asList(
            "source","snapshot","source_url","license_id","terms_url","attribution"));
    private static final Set<String> ATTRIBUTION_FIELDS = new HashSet<>(Arrays.asList(
            "source","snapshot","source_url","license_id","terms_url","attribution","locator","modification"));
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    record Source(String name, String snapshot, String url) {}
    record Policy(Source source, String license, String terms, String credit) {}
    record Attribution(Policy policy, String locator, String modification) {
        String displayCredit() {
            return policy.credit() + " · " + policy.license() + "\n" + policy.source().url();
        }
    }

    static PackageManifest parse(Reader input) throws IOException, JSONException {
        var reader = new JsonReader(input);
        Map<String,Object> root = object(read(reader,0));
        if (reader.peek() != JsonToken.END_DOCUMENT) throw new JSONException("trailing manifest data");
        int format = integer(root.get("package_format"));
        int schema = integer(root.get("schema_version"));
        if ((format != 1 && format != 2) || (schema != 1 && schema != 2)
                || !NORMALIZATION.equals(root.get("normalization"))
                || !Arrays.asList("en-ru","ru-en").equals(root.get("language_pairs"))) {
            throw new UnsupportedOperationException("unsupported package");
        }
        String digest = requiredText(root.get("database_sha256"));
        if (!SHA256.matcher(digest).matches()) throw new JSONException("invalid database digest");
        return new PackageManifest(format,schema,requiredText(root.get("corpus_version")),
                requiredText(root.get("unicode_version")),digest,object(root.get("counts")),decode(root,format));
    }

    private static Map<String,Attribution> decode(Map<String,Object> root,int format) throws JSONException {
        List<Policy> policies = new ArrayList<>();
        if (format == 2) {
            Set<Policy> distinct = new HashSet<>();
            for (Object raw : array(root.get("policies"))) {
                Map<String,Object> fields = object(raw);
                if (!fields.keySet().equals(POLICY_FIELDS)) throw new JSONException("invalid policy fields");
                Policy policy = policy(fields);
                if (!distinct.add(policy)) throw new JSONException("duplicate attribution policy");
                policies.add(policy);
            }
        }
        var used = new HashSet<Integer>();
        var sources = new HashSet<Source>();
        Map<String,Attribution> entries = new HashMap<>();
        MessageDigest hash = sha256();
        var rows = object(root.get("attributions")).entrySet().iterator();
        while (rows.hasNext()) {
            var entry = rows.next();
            if (!SHA256.matcher(entry.getKey()).matches()) throw new JSONException("invalid attribution identifier");
            Attribution attribution;
            if (format == 2) {
                List<?> row = array(entry.getValue());
                if (row.size() != 3) throw new JSONException("invalid attribution link");
                int index = integer(row.get(0));
                if (index < 0 || index >= policies.size()) throw new JSONException("unknown attribution policy");
                used.add(index);
                attribution = new Attribution(policies.get(index),requiredText(row.get(1)),requiredText(row.get(2)));
            } else {
                Map<String,Object> row = object(entry.getValue());
                if (!row.keySet().equals(ATTRIBUTION_FIELDS)) throw new JSONException("invalid attribution fields");
                attribution = new Attribution(policy(row),requiredText(row.get("locator")),
                        requiredText(row.get("modification")));
            }
            if (!matchesId(hash,entry.getKey(),attribution)) throw new JSONException("attribution identifier mismatch");
            sources.add(attribution.policy().source());
            entries.put(entry.getKey(),attribution);
            rows.remove();
        }
        if (entries.isEmpty() || used.size() != policies.size()) throw new JSONException("missing or unused attribution");
        Set<Source> declared = new HashSet<>();
        for (Object value : array(root.get("sources"))) {
            List<?> row = array(value);
            if (row.size() != 3 || !declared.add(new Source(requiredText(row.get(0)),
                    requiredText(row.get(1)),https(row.get(2))))) throw new JSONException("invalid source list");
        }
        if (!sources.equals(declared)) throw new JSONException("inconsistent source list");
        return entries;
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException unavailable) { throw new AssertionError(unavailable); }
    }

    /** Matches Python json.dumps(record, sort_keys=True) with its default ASCII escaping. */
    private static boolean matchesId(MessageDigest hash,String id,Attribution record) {
        Policy policy = record.policy();
        StringBuilder canonical = new StringBuilder("{\"attribution\": ");
        quote(canonical,policy.credit()).append(", \"license_id\": ");
        quote(canonical,policy.license()).append(", \"locator\": ");
        quote(canonical,record.locator()).append(", \"modification\": ");
        quote(canonical,record.modification()).append(", \"snapshot\": ");
        quote(canonical,policy.source().snapshot()).append(", \"source\": ");
        quote(canonical,policy.source().name()).append(", \"source_url\": ");
        quote(canonical,policy.source().url()).append(", \"terms_url\": ");
        quote(canonical,policy.terms()).append('}');
        byte[] digest = hash.digest(canonical.toString().getBytes(StandardCharsets.US_ASCII));
        for (int i = 0; i < digest.length; i++) {
            if (id.charAt(2*i) != HEX[(digest[i] & 255) >>> 4] || id.charAt(2*i+1) != HEX[digest[i] & 15]) {
                return false;
            }
        }
        return true;
    }

    private static StringBuilder quote(StringBuilder buffer,String value) {
        buffer.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> buffer.append("\\\"");
                case '\\' -> buffer.append("\\\\");
                case '\b' -> buffer.append("\\b");
                case '\f' -> buffer.append("\\f");
                case '\n' -> buffer.append("\\n");
                case '\r' -> buffer.append("\\r");
                case '\t' -> buffer.append("\\t");
                default -> {
                    if (c < 32 || c >= 127) {
                        buffer.append("\\u").append(HEX[c >>> 12]).append(HEX[(c >>> 8) & 15])
                                .append(HEX[(c >>> 4) & 15]).append(HEX[c & 15]);
                    } else buffer.append(c);
                }
            }
        }
        return buffer.append('"');
    }

    long count(String key) throws JSONException {
        if (!(counts.get(key) instanceof Long value) || value < 0) throw new JSONException("invalid table count");
        return value;
    }

    Attribution attribution(String id) throws JSONException {
        Attribution value = attributions.get(id);
        if (value == null) throw new JSONException("unresolved attribution");
        return value;
    }

    private static Policy policy(Map<String,Object> fields) throws JSONException {
        String snapshot = requiredText(fields.get("snapshot"));
        if (!SHA256.matcher(snapshot).matches()) throw new JSONException("invalid snapshot");
        return new Policy(new Source(requiredText(fields.get("source")),snapshot,https(fields.get("source_url"))),
                requiredText(fields.get("license_id")),https(fields.get("terms_url")),
                requiredText(fields.get("attribution")));
    }

    private static String https(Object value) throws JSONException {
        String text = requiredText(value);
        try {
            URI uri = URI.create(text);
            if ("https".equals(uri.getScheme()) && uri.getHost() != null && uri.getRawUserInfo() == null) return text;
        } catch (IllegalArgumentException ignored) {
            // Reject malformed source and license URLs.
        }
        throw new JSONException("invalid attribution URL");
    }

    private static String requiredText(Object raw) throws JSONException {
        if (!(raw instanceof String text) || text.trim().isEmpty()) throw new JSONException("invalid manifest text");
        return text;
    }

    private static int integer(Object value) throws JSONException {
        if (!(value instanceof Long number) || number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
            throw new JSONException("invalid manifest integer");
        }
        return number.intValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> object(Object value) throws JSONException {
        if (!(value instanceof Map)) throw new JSONException("expected manifest object");
        return (Map<String,Object>) value;
    }

    private static List<?> array(Object value) throws JSONException {
        if (!(value instanceof List<?> list)) throw new JSONException("expected manifest array");
        return list;
    }

    private static Object read(JsonReader reader,int depth) throws IOException, JSONException {
        if (depth > 12) throw new JSONException("manifest nesting limit");
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                Map<String,Object> value = new HashMap<>();
                reader.beginObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (value.containsKey(key)) throw new JSONException("duplicate manifest identifier");
                    value.put(key,read(reader,depth+1));
                }
                reader.endObject();
                yield value;
            }
            case BEGIN_ARRAY -> {
                List<Object> value = new ArrayList<>();
                reader.beginArray();
                while (reader.hasNext()) value.add(read(reader,depth+1));
                reader.endArray();
                yield value;
            }
            case STRING -> reader.nextString();
            case NUMBER -> {
                String number = reader.nextString();
                try { yield Long.valueOf(number); }
                catch (NumberFormatException invalid) { throw new JSONException("invalid manifest number"); }
            }
            case BOOLEAN -> reader.nextBoolean();
            case NULL -> { reader.nextNull(); yield null; }
            default -> throw new JSONException("invalid manifest JSON");
        };
    }
}
