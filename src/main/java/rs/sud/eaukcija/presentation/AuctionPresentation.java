package rs.sud.eaukcija.presentation;

import java.util.Map;

/** Public display text only. Never modifies retained source values or infers taxonomy. */
public final class AuctionPresentation {
    private AuctionPresentation() {}

    private static final Map<String, String> STATUSES = Map.ofEntries(
            Map.entry("InPrediction", "У најави"), Map.entry("Published", "Објављено"),
            Map.entry("Verification", "У провери"), Map.entry("Verified", "Проверено на извору"),
            Map.entry("InProgress", "У току на извору"), Map.entry("Completed", "Окончано на извору"),
            Map.entry("Closed", "Затворено на извору"));

    public static String statusLabel(String value) {
        String safe = text(value, 256);
        return safe == null ? "Статус није познат" : STATUSES.getOrDefault(safe, "Непознат изворни статус: " + safe);
    }
    public static String category(String value) {
        String safe = text(value, 256);
        return safe == null ? "Категорија није наведена" : safe;
    }
    public static String text(String value, int maximum) { return clean(value, maximum, false); }
    public static String description(String value, int maximum) { return clean(value, maximum, true); }

    private static String clean(String value, int maximum, boolean multiline) {
        if (value == null) return null;
        StringBuilder result = new StringBuilder();
        int count = 0;
        for (int offset = 0; offset < value.length() && count < maximum;) {
            int cp = value.codePointAt(offset);
            offset += Character.charCount(cp);
            if (Character.getType(cp) == Character.FORMAT || Character.getType(cp) == Character.SURROGATE) continue;
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                if (multiline) { result.appendCodePoint(cp); count++; }
                else if (!result.isEmpty() && result.charAt(result.length() - 1) != ' ') {
                    result.append(' '); count++;
                }
            } else if (!Character.isISOControl(cp)) {
                result.appendCodePoint(cp); count++;
            }
        }
        String safe = result.toString().strip();
        return safe.isEmpty() ? null : safe;
    }
}
