package rs.sud.eaukcija.addressregistry;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Shared query/index contract for deterministic Serbian Cyrillic/Latin names.
 *
 * <p>Dictionary construction and every downstream matcher must call this
 * implementation rather than maintaining a private approximation.</p>
 */
public final class SerbianNameNormalizer {

    public static final String CONTRACT_VERSION = "serbian-name-v1";

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^A-Z0-9]+");
    private static final Pattern SPACES = Pattern.compile(" +");

    private SerbianNameNormalizer() {
    }

    /**
     * Applies Unicode compatibility normalization, Serbian-script folding,
     * locale-independent case folding, punctuation removal, and whitespace
     * collapse. Blank/unusable inputs return {@code null}.
     */
    public static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String unmarked = foldScriptAndCase(value);
        String spaced = NON_ALPHANUMERIC.matcher(unmarked).replaceAll(" ").trim();
        String normalized = SPACES.matcher(spaced).replaceAll(" ");
        return normalized.isEmpty() ? null : normalized;
    }

    static String foldScriptAndCase(String value) {
        String compatible = Normalizer.normalize(value, Normalizer.Form.NFKC);
        StringBuilder transliterated = new StringBuilder(compatible.length());
        for (int offset = 0; offset < compatible.length();) {
            int codePoint = compatible.codePointAt(offset);
            transliterated.append(transliterate(codePoint));
            offset += Character.charCount(codePoint);
        }
        String decomposed = Normalizer.normalize(transliterated, Normalizer.Form.NFKD);
        return MARKS.matcher(decomposed).replaceAll("").toUpperCase(Locale.ROOT);
    }

    private static String transliterate(int codePoint) {
        return switch (Character.toUpperCase(codePoint)) {
            case 'Đ' -> "DJ";
            case 'А' -> "A";
            case 'Б' -> "B";
            case 'В' -> "V";
            case 'Г' -> "G";
            case 'Д' -> "D";
            case 'Ђ' -> "DJ";
            case 'Е' -> "E";
            case 'Ж' -> "Z";
            case 'З' -> "Z";
            case 'И' -> "I";
            case 'Ј' -> "J";
            case 'К' -> "K";
            case 'Л' -> "L";
            case 'Љ' -> "LJ";
            case 'М' -> "M";
            case 'Н' -> "N";
            case 'Њ' -> "NJ";
            case 'О' -> "O";
            case 'П' -> "P";
            case 'Р' -> "R";
            case 'С' -> "S";
            case 'Т' -> "T";
            case 'Ћ' -> "C";
            case 'У' -> "U";
            case 'Ф' -> "F";
            case 'Х' -> "H";
            case 'Ц' -> "C";
            case 'Ч' -> "C";
            case 'Џ' -> "DZ";
            case 'Ш' -> "S";
            default -> new String(Character.toChars(codePoint));
        };
    }
}
