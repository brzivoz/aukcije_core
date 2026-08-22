package rs.sud.eaukcija.spatial;

import java.text.Normalizer;
import java.util.Locale;

/** Canonicalizes the two textual components of a parcel identity without parsing either as a number. */
public final class ParcelIdentityNormalizer {

    private ParcelIdentityNormalizer() {
    }

    public static String canonicalKoCode(String raw) {
        return compact(raw, "KO code");
    }

    public static String canonicalParcelNumber(String raw) {
        String normalized = compact(raw, "parcel number");
        return normalized
                .replace('\u2044', '/')
                .replace('\u2215', '/')
                .replace('\u2010', '-')
                .replace('\u2012', '-')
                .replace('\u2013', '-')
                .replace('\u2212', '-');
    }

    private static String compact(String raw, String field) {
        if (raw == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC)
                .toUpperCase(Locale.ROOT);
        StringBuilder compact = new StringBuilder(normalized.length());
        normalized.codePoints()
                .filter(codePoint -> !Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint))
                .forEach(compact::appendCodePoint);
        if (compact.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return compact.toString();
    }
}
