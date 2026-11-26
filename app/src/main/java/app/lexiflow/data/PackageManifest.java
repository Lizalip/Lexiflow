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
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

/** Compact links share immutable policies for the lifetime of the open package. */
record PackageManifest(int format, int schema, String version, String unicode, String digest, boolean research,
                       Map<String,Object> counts, Map<String,Attribution> attributions,
                       Map<String,ContextLink> contexts,Map<String,List<EntryNote>> entryNotes,
                       Map<String,List<CefrLabel>> entryCefr,Map<String,UsageExample> usageExamples) {
    static final String NORMALIZATION = "nfc-casefold-nfc-python-whitespace-v1";
    private static final String RESEARCH_POLICY = "research-local-v1";
    private static final String USAGE_EXTENSION = "entry_usage_examples";
    private static final String USAGE_SNAPSHOT = "f4b0e574f5b9f8bee0e8e59b6e4e9c762db2dbbc05a4f02c60f63ad586690dd1";
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> POLICY_FIELDS = new HashSet<>(Arrays.asList(
            "source","snapshot","source_url","license_id","terms_url","attribution"));
    private static final Set<String> ATTRIBUTION_FIELDS = new HashSet<>(Arrays.asList(
            "source","snapshot","source_url","license_id","terms_url","attribution","locator","modification"));
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final int CACHED_CANONICAL_POLICIES = 128;

    record Source(String name, String snapshot, String url) {}
    record Policy(Source source, String license, String terms, String credit) {}
    private record CanonicalPolicy(byte[] prefix, byte[] suffix) {
        CanonicalPolicy(Policy policy) {
            this(ascii(appendPolicyPrefix(new StringBuilder(),policy)),
                 ascii(appendPolicySuffix(new StringBuilder(),policy)));
        }
    }
    record ContextLink(String text, Attribution owner, Attribution definition) {
        boolean belongsTo(Attribution parent) {
            var source = parent.policy().source();
            return sameSnapshot(source,owner.policy().source()) && sameSnapshot(source,definition.policy().source())
                    && owner.locator().matches(Pattern.quote(parent.locator()) + "/sense/(0|[1-9][0-9]*)")
                    && definition.locator().matches(Pattern.quote(owner.locator()) + "/def/(0|[1-9][0-9]*)");
        }

        private static boolean sameSnapshot(Source a,Source b) {
            return a.name().equals(b.name()) && a.snapshot().equals(b.snapshot());
        }
    }
    record Attribution(Policy policy, String locator, String modification) {
        String displayCredit() {
            return policy.credit() + " · " + policy.license() + "\n" + policy.source().url();
        }
    }
    record EntryNote(String text,Attribution definition) {
        boolean belongsTo(Attribution entry) {
            return ContextLink.sameSnapshot(entry.policy().source(),definition.policy().source())
                    && definition.locator().matches(Pattern.quote(entry.locator())
                    + "/sense/(0|[1-9][0-9]*)/sense/(0|[1-9][0-9]*)/def/(0|[1-9][0-9]*)");
        }
    }
    record CefrLabel(String level,Attribution evidence) {
        boolean belongsTo(String lemma,String pos) {
            return evidence.modification().equals(RESEARCH_POLICY+": CEFR-J lemma="+lemma+"; pos="+pos);
        }
    }
    record UsageSentence(String text,Attribution evidence,String id) {
        String credit() {
            return evidence.policy().credit() + " · " + evidence.policy().license()
                    + "\nhttps://tatoeba.org/en/sentences/show/" + id + "\n" + evidence.policy().terms();
        }
    }
    record UsageExample(UsageSentence original,UsageSentence translation) {}

    static PackageManifest parse(Reader input) throws IOException, JSONException {
        return parse(input,null,true);
    }

    static PackageManifest parse(Reader input,BiConsumer<String,Long> timing,boolean verifyAttributionIds)
            throws IOException, JSONException {
        long started = System.nanoTime();
        var reader = new JsonReader(input);
        Map<String,Object> root = object(read(reader,0));
        if (reader.peek() != JsonToken.END_DOCUMENT) throw new JSONException("trailing manifest data");
        if (timing != null) timing.accept("json_with_input",System.nanoTime()-started);
        int format = integer(root.get("package_format"));
        int schema = integer(root.get("schema_version"));
        if ((format < 1 || format > 3) || (schema < 1 || schema > 3)
                || !NORMALIZATION.equals(root.get("normalization"))
                || !Arrays.asList("en-ru","ru-en").equals(root.get("language_pairs"))) {
            throw new UnsupportedOperationException("unsupported package");
        }
        String digest = requiredText(root.get("database_sha256"));
        if (!SHA256.matcher(digest).matches()) throw new JSONException("invalid database digest");
        String version = requiredText(root.get("corpus_version"));
        boolean research = version.toLowerCase(java.util.Locale.ROOT).startsWith("research-");
        if (research ? format < 2 || schema < 2 || !RESEARCH_POLICY.equals(root.get("distribution_policy"))
                : root.containsKey("distribution_policy") || root.containsKey("source_entry_notes")
                || root.containsKey("entry_cefr") || root.containsKey(USAGE_EXTENSION)) {
            throw new JSONException("inconsistent research package markers");
        }
        if ((format == 3 && !research) || ((schema == 3 || root.containsKey("entry_cefr")) && format != 3)
                || (root.containsKey("modifications") != (format == 3))
                || (root.containsKey(USAGE_EXTENSION) && (schema!=3 || format!=3))) {
            throw new JSONException("invalid modification table format");
        }
        started = System.nanoTime();
        var attributions = decode(root,format,verifyAttributionIds,research);
        if (timing != null) timing.accept("attribution_decode_and_verify",System.nanoTime()-started);
        var contexts = new HashMap<String,ContextLink>();
        if (root.containsKey("sense_context")) {
            var extension = object(root.get("sense_context"));
            if (schema != 2 || extension.size() != 2 || integer(extension.get("version")) != 1) {
                throw new JSONException("invalid sense context extension");
            }
            var owners = new HashSet<Attribution>();
            for (var entry : object(extension.get("senses")).entrySet()) {
                var row = object(entry.getValue());
                var owner = attributions.get(requiredText(row.get("owner")));
                var definition = attributions.get(requiredText(row.get("definition")));
                if (row.size() != 5 || !"direct-child".equals(row.get("relationship"))
                        || !"en".equals(row.get("language")) || owner == null || definition == null
                        || !owners.add(owner)) throw new JSONException("invalid sense context row");
                contexts.put(entry.getKey(),new ContextLink(requiredText(row.get("text")),owner,definition));
            }
            if (contexts.isEmpty()) throw new JSONException("empty sense context extension");
        }
        return new PackageManifest(format,schema,version,requiredText(root.get("unicode_version")),digest,research,
                object(root.get("counts")),attributions,contexts,decodeEntryNotes(root,attributions),
                decodeEntryCefr(root,attributions),decodeUsageExamples(root,attributions));
    }

    private static Map<String,UsageExample> decodeUsageExamples(Map<String,Object> root,
                Map<String,Attribution> attributions) throws JSONException {
        if (!root.containsKey(USAGE_EXTENSION)) return java.util.Collections.emptyMap();
        var extension = object(root.get(USAGE_EXTENSION));
        var entries = object(extension.get("entries"));
        if (extension.size()!=2 || integer(extension.get("version"))!=1 || entries.size()!=1) {
            throw new JSONException("invalid usage example extension");
        }
        var row = entries.entrySet().iterator().next();
        var pair = object(row.getValue());
        if (pair.size()!=2) throw new JSONException("invalid usage example pair");
        var original = decodeUsageSentence(pair.get("en"),"en",attributions);
        var translation = decodeUsageSentence(pair.get("ru"),"ru",attributions);
        String content = String.join("\n",row.getKey(),original.id(),translation.id(),original.text(),translation.text());
        var digest = sha256().digest(content.getBytes(StandardCharsets.UTF_8));
        var text = new StringBuilder(RESEARCH_POLICY+": entry usage ");
        for (byte value : digest) text.append(HEX[(value&255)>>>4]).append(HEX[value&15]);
        if (original.id().equals(translation.id()) || !text.toString().equals(original.evidence().modification())
                || !text.toString().equals(translation.evidence().modification())) {
            throw new JSONException("usage example owner or text changed");
        }
        return java.util.Collections.singletonMap(row.getKey(),new UsageExample(original,translation));
    }

    private static UsageSentence decodeUsageSentence(Object row,String language,
                Map<String,Attribution> attributions) throws JSONException {
        var fields = object(row);
        String text = requiredText(fields.get("text"));
        var evidence = attributions.get(requiredText(fields.get("evidence")));
        if (fields.size()!=2 || evidence==null || text.codePointCount(0,text.length())>240
                || text.contains("\n") || text.contains("\r") || text.contains("://")) {
            throw new JSONException("invalid usage example sentence");
        }
        var policy = evidence.policy();
        var locator = Pattern.compile("sentences/([1-9][0-9]*)/"+language).matcher(evidence.locator());
        String terms = switch (policy.license()) {
            case "CC BY 2.0 FR" -> "https://creativecommons.org/licenses/by/2.0/fr/";
            case "CC0 1.0" -> "https://creativecommons.org/publicdomain/zero/1.0/";
            default -> "";
        };
        if (!locator.matches() || !"Tatoeba API v1".equals(policy.source().name())
                || !USAGE_SNAPSHOT.equals(policy.source().snapshot()) || !"https://api.tatoeba.org/".equals(policy.source().url())
                || !terms.equals(policy.terms()) || !policy.credit().startsWith("Tatoeba · ")
                || policy.credit().substring("Tatoeba · ".length()).trim().isEmpty()) {
            throw new JSONException("invalid usage example attribution");
        }
        return new UsageSentence(text,evidence,locator.group(1));
    }

    private static Map<String,List<CefrLabel>> decodeEntryCefr(Map<String,Object> root,
                Map<String,Attribution> attributions) throws JSONException {
        if (!root.containsKey("entry_cefr")) return java.util.Collections.emptyMap();
        Map<String,Object> extension = object(root.get("entry_cefr"));
        if (extension.size()!=2 || integer(extension.get("version"))!=1) throw new JSONException("invalid entry CEFR");
        var result = new HashMap<String,List<CefrLabel>>();
        for (var entry : object(extension.get("entries")).entrySet()) {
            var labels = array(entry.getValue());
            if (labels.isEmpty() || labels.size()>16) throw new JSONException("invalid CEFR label count");
            List<CefrLabel> parsed = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (Object item : labels) {
                var row = object(item);
                String level = requiredText(row.get("level"));
                String reference = requiredText(row.get("evidence"));
                Attribution evidence = attributions.get(reference);
                if (row.size()!=2 || !level.matches("[AB][12]") || !seen.add(reference) || evidence==null
                        || !evidence.locator().matches("ALL_sep/row/[1-9][0-9]*/"+level)) {
                    throw new JSONException("invalid entry CEFR label");
                }
                Policy policy = evidence.policy();
                if (!"CEFR-J Wordlist 1.6".equals(policy.source().name())
                        || !"c837d2c00ab8954ed8db48e79afd8ef37099570295fec36950dbf9322303a37a".equals(policy.source().snapshot())
                        || !"https://www.cefr-j.org/download.html".equals(policy.source().url())
                        || !policy.source().url().equals(policy.terms())
                        || !"CEFR-J-Wordlist-1.6-attribution".equals(policy.license())) {
                    throw new JSONException("invalid CEFR source");
                }
                parsed.add(new CefrLabel(level,evidence));
            }
            result.put(entry.getKey(),parsed);
        }
        if (result.isEmpty()) throw new JSONException("empty CEFR extension");
        return result;
    }

    private static Map<String,List<EntryNote>> decodeEntryNotes(Map<String,Object> root,
                         Map<String,Attribution> attributions) throws JSONException {
        Map<String,List<EntryNote>> result = new HashMap<>();
        if (!root.containsKey("source_entry_notes")) return result;
        var extension = object(root.get("source_entry_notes"));
        if (extension.size() != 2 || integer(extension.get("version")) != 1) {
            throw new JSONException("invalid source entry notes version");
        }
        for (var entry : object(extension.get("entries")).entrySet()) {
            var notes = array(entry.getValue());
            if (notes.isEmpty() || notes.size() > 3) throw new JSONException("invalid source entry note count");
            List<EntryNote> decoded = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (Object item : notes) {
                var row = object(item);
                String text = requiredText(row.get("text"));
                String reference = requiredText(row.get("definition"));
                Attribution definition = attributions.get(reference);
                if (row.size() != 2 || text.codePointCount(0,text.length()) > 1000 || definition == null
                        || !seen.add(reference)) throw new JSONException("invalid source entry note");
                decoded.add(new EntryNote(text,definition));
            }
            result.put(entry.getKey(),decoded);
        }
        if (result.isEmpty()) throw new JSONException("empty source entry note extension");
        return result;
    }

    private static Map<String,Attribution> decode(Map<String,Object> root,int format,boolean verifyAttributionIds,boolean research)
            throws JSONException {
        List<Policy> policies = new ArrayList<>();
        List<CanonicalPolicy> encodedPolicies = new ArrayList<>();
        if (format >= 2) {
            Set<Policy> distinct = new HashSet<>();
            for (Object raw : array(root.get("policies"))) {
                Map<String,Object> fields = object(raw);
                if (!fields.keySet().equals(POLICY_FIELDS)) throw new JSONException("invalid policy fields");
                Policy policy = policy(fields);
                if (!distinct.add(policy)) throw new JSONException("duplicate attribution policy");
                policies.add(policy);
                if (verifyAttributionIds && encodedPolicies.size() < CACHED_CANONICAL_POLICIES) {
                    encodedPolicies.add(new CanonicalPolicy(policy));
                }
            }
        }
        List<String> modifications = new ArrayList<>();
        Set<Integer> usedModifications = new HashSet<>();
        if (format == 3) {
            Set<String> distinct = new HashSet<>();
            for (Object raw : array(root.get("modifications"))) {
                String value = requiredText(raw);
                if (!distinct.add(value)) throw new JSONException("duplicate modification");
                modifications.add(value);
            }
            if (modifications.isEmpty()) throw new JSONException("empty modification table");
        }
        var used = new HashSet<Integer>();
        var sources = new HashSet<Source>();
        Map<String,Object> rawEntries = object(root.get("attributions"));
        Map<String,Attribution> entries = new HashMap<>(rawEntries.size()*4/3+1);
        MessageDigest hash = verifyAttributionIds ? sha256() : null;
        StringBuilder canonical = new StringBuilder();
        var rows = rawEntries.entrySet().iterator();
        while (rows.hasNext()) {
            var entry = rows.next();
            if (!SHA256.matcher(entry.getKey()).matches()) throw new JSONException("invalid attribution identifier");
            Attribution attribution;
            CanonicalPolicy encoded = null;
            if (format >= 2) {
                List<?> row = array(entry.getValue());
                if (row.size() != 3) throw new JSONException("invalid attribution link");
                int index = integer(row.get(0));
                if (index < 0 || index >= policies.size()) throw new JSONException("unknown attribution policy");
                used.add(index);
                String modification;
                if (format == 3) {
                    int ref = integer(row.get(2));
                    if (ref < 0 || ref >= modifications.size()) throw new JSONException("unknown modification reference");
                    usedModifications.add(ref);
                    modification = modifications.get(ref);
                } else {
                    modification = requiredText(row.get(2));
                }
                attribution = new Attribution(policies.get(index),requiredText(row.get(1)),modification);
                if (index < encodedPolicies.size()) encoded = encodedPolicies.get(index);
            } else {
                Map<String,Object> row = object(entry.getValue());
                if (!row.keySet().equals(ATTRIBUTION_FIELDS)) throw new JSONException("invalid attribution fields");
                attribution = new Attribution(policy(row),requiredText(row.get("locator")),
                        requiredText(row.get("modification")));
            }
            if (attribution.modification().startsWith(RESEARCH_POLICY+":") != research) {
                throw new JSONException("inconsistent research attribution");
            }
            if (verifyAttributionIds && !matchesId(hash,entry.getKey(),attribution,encoded,canonical)) {
                throw new JSONException("attribution identifier mismatch");
            }
            sources.add(attribution.policy().source());
            entries.put(entry.getKey(),attribution);
            rows.remove();
        }
        if (entries.isEmpty() || used.size() != policies.size() || usedModifications.size() != modifications.size()) {
            throw new JSONException("missing or unused attribution");
        }
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
    private static boolean matchesId(MessageDigest hash,String id,Attribution record,
                                     CanonicalPolicy encoded,StringBuilder canonical) {
        canonical.setLength(0);
        if (encoded == null) appendPolicyPrefix(canonical,record.policy());
        else hash.update(encoded.prefix());
        quote(canonical,record.locator()).append(", \"modification\": ");
        quote(canonical,record.modification());
        if (encoded == null) appendPolicySuffix(canonical,record.policy());
        hash.update(ascii(canonical));
        if (encoded != null) hash.update(encoded.suffix());
        byte[] digest = hash.digest();
        for (int i = 0; i < digest.length; i++) {
            if (id.charAt(2*i) != HEX[(digest[i] & 255) >>> 4] || id.charAt(2*i+1) != HEX[digest[i] & 15]) {
                return false;
            }
        }
        return true;
    }

    private static StringBuilder appendPolicyPrefix(StringBuilder canonical,Policy policy) {
        canonical.append("{\"attribution\": ");
        quote(canonical,policy.credit()).append(", \"license_id\": ");
        return quote(canonical,policy.license()).append(", \"locator\": ");
    }

    private static StringBuilder appendPolicySuffix(StringBuilder canonical,Policy policy) {
        canonical.append(", \"snapshot\": ");
        quote(canonical,policy.source().snapshot()).append(", \"source\": ");
        quote(canonical,policy.source().name()).append(", \"source_url\": ");
        quote(canonical,policy.source().url()).append(", \"terms_url\": ");
        return quote(canonical,policy.terms()).append('}');
    }

    private static byte[] ascii(StringBuilder value) {
        return value.toString().getBytes(StandardCharsets.US_ASCII);
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
