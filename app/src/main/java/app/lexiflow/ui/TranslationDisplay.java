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
        return sourceAnswer && research ? wording(text) : text;
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
        for (String text : source) unique.add(wording(text));
        return new ArrayList<>(unique);
    }
}
