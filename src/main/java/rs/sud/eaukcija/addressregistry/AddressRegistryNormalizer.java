package rs.sud.eaukcija.addressregistry;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic Serbian Cyrillic/Latin lookup normalization. */
final class AddressRegistryNormalizer {

    private static final Pattern PARCEL = Pattern.compile("^\\s*(\\d+)\\s*(?:/\\s*(\\d+))?\\s*$");

    private AddressRegistryNormalizer() {
    }

    static String name(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String ascii = folded(value)
                .replaceAll("[^A-Z0-9]+", " ")
                .trim()
                .replaceAll(" +", " ");
        return ascii.isEmpty() ? null : ascii;
    }

    static String parcel(String value) {
        if (value == null) {
            return null;
        }
        Matcher match = PARCEL.matcher(value);
        if (!match.matches()) {
            return null;
        }
        String main = stripLeadingZeroes(match.group(1));
        String sub = match.group(2) == null ? null : stripLeadingZeroes(match.group(2));
        return sub == null || "0".equals(sub) ? main : main + "/" + sub;
    }

    static String houseNumber(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = folded(value).replaceAll("[^A-Z0-9/-]+", "");
        return normalized.isEmpty() ? null : normalized;
    }

    static boolean isActive(String statusCyrillic, String statusLatin, String retired) {
        if (retired != null && !retired.isBlank()) {
            return false;
        }
        String normalized = name(statusLatin == null || statusLatin.isBlank() ? statusCyrillic : statusLatin);
        return "AKTIVAN".equals(normalized);
    }

    private static String transliterate(int codePoint) {
        return switch (Character.toUpperCase(codePoint)) {
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

    private static String folded(String value) {
        StringBuilder transliterated = new StringBuilder();
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            transliterated.append(transliterate(codePoint));
            offset += Character.charCount(codePoint);
        }
        return Normalizer.normalize(transliterated, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toUpperCase(Locale.ROOT);
    }

    private static String stripLeadingZeroes(String value) {
        String stripped = value.replaceFirst("^0+(?!$)", "");
        return stripped.isEmpty() ? "0" : stripped;
    }
}
