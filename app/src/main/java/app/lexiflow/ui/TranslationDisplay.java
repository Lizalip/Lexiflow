package app.lexiflow.ui;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Project simple wiki links to plain display text, preserving the original dictionary values. */
final class TranslationDisplay {
    private static final Pattern LINK = Pattern.compile(
            "\\[\\[([\\p{L}\\p{M}\\p{Nd} .’'-]{1,120})(?:\\|([\\p{L}\\p{M}\\p{Nd} .,’'()–-]{1,160}))?]]");
    private static final Pattern MARKER = Pattern.compile("\\[\\[|]]");

    private TranslationDisplay() {}

    static String answer(String text,boolean sourceAnswer,boolean research) {
        if (!sourceAnswer) return text;
        var values = new LinkedHashSet<String>();
        for (String part : text.split(" · ",-1)) values.add(russian(research ? wording(part) : part));
        return String.join(" · ",values);
    }

    static String russian(String source) {
        var display = new StringBuilder(source.length());
        boolean cyrillic = false;
        for (int i=0;i<source.length();) {
            int code = source.codePointAt(i);
            i += Character.charCount(code);
            if (code == 0x301 && cyrillic) continue;
            if (Character.getType(code) != Character.NON_SPACING_MARK) {
                cyrillic = Character.UnicodeScript.of(code) == Character.UnicodeScript.CYRILLIC;
            }
            display.appendCodePoint(code);
        }
        return display.toString();
    }

    static String wording(String source) {
        if (!source.contains("[[") || source.contains("{{") || source.contains("}}")
                || source.contains("<") || source.contains(">")) return source;
        var markers = MARKER.matcher(source);
        int markerCount = 0;
        while (markers.find()) markerCount++;
        var links = LINK.matcher(source);
        var result = new StringBuffer();
        int known = 0;
        while (links.find()) {
            String display = links.group(2)==null ? links.group(1) : links.group(2);
            if (display.trim().isEmpty()) return source;
            links.appendReplacement(result,Matcher.quoteReplacement(display));
            known++;
        }
        if (known*2!=markerCount) return source;
        links.appendTail(result);
        return result.toString();
    }

    static List<String> alternatives(List<String> source) {
        var unique = new LinkedHashSet<String>();
        for (String text : source) unique.add(russian(wording(text)));
        return new ArrayList<>(unique);
    }
}
