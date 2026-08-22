package rs.sud.eaukcija.addressregistry;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic Serbian Cyrillic/Latin lookup normalization. */
final class AddressRegistryNormalizer {

    private static final Pattern PARCEL = Pattern.compile("^\\s*(\\d+)\\s*(?:/\\s*(\\d+))?\\s*$");

    private AddressRegistryNormalizer() {
    }

    static String name(String value) {
        return SerbianNameNormalizer.normalize(value);
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

    private static String folded(String value) {
        return SerbianNameNormalizer.foldScriptAndCase(value);
    }

    private static String stripLeadingZeroes(String value) {
        String stripped = value.replaceFirst("^0+(?!$)", "");
        return stripped.isEmpty() ? "0" : stripped;
    }
}
