package app.lexiflow.data;

import android.icu.lang.UCharacter;
import java.text.Normalizer;
import java.util.regex.Pattern;

public final class SearchNormalization {
    private static final Pattern WHITESPACE = Pattern.compile(
            "[\\x{0009}-\\x{000D}\\x{001C}-\\x{0020}\\x{0085}\\x{00A0}"
            + "\\x{1680}\\x{2000}-\\x{200A}\\x{2028}\\x{2029}\\x{202F}\\x{205F}\\x{3000}]+");

    private SearchNormalization() {}

    public static String normalize(String text) {
        text.codePoints().forEach(cp -> {
            if (cp == 0 || cp >= 0xD800 && cp <= 0xDFFF) {
                throw new IllegalArgumentException("invalid scalar");
            }
        });
        String nfc = Normalizer.normalize(text, Normalizer.Form.NFC);
        String folded = Normalizer.normalize(UCharacter.foldCase(nfc, true), Normalizer.Form.NFC);
        String spaced = WHITESPACE.matcher(folded).replaceAll(" ");
        return spaced.replaceAll("^ | $", "");
    }

    public static String prefixEnd(String prefix) {
        int end = prefix.length();
        while (end > 0) {
            int codePoint = prefix.codePointBefore(end);
            end -= Character.charCount(codePoint);
            if (codePoint < Character.MAX_CODE_POINT) {
                return prefix.substring(0, end) + new String(Character.toChars(codePoint == 0xD7FF ? 0xE000 : codePoint + 1));
            }
        }
        return null;
    }
}
