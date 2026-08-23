package rs.sud.eaukcija.spatial;

import java.util.List;
import java.util.StringJoiner;

/** Generates the shared location-selection policy used by every SQL consumer. */
public final class LocationSelectionSql {

    private static final List<String> PUBLISHABLE_EXTRACTION_STATUSES =
            List.of("EXTRACTED", "USER_CONFIRMED");

    private LocationSelectionSql() {
    }

    /**
     * Builds SQL from trusted, compile-time-constant column expressions only. Never pass request or
     * persisted data as an expression argument.
     */
    public static String bestOrder(
            String precisionExpression,
            String referenceOrderExpression,
            String completedAtExpression,
            String attemptIdExpression) {
        return precisionRank(precisionExpression) + " DESC NULLS LAST, "
                + referenceOrderExpression + " ASC, "
                + completedAtExpression + " DESC, "
                + attemptIdExpression + " ASC";
    }

    /** Builds SQL from a trusted, compile-time-constant column expression only. */
    public static String precisionRank(String precisionExpression) {
        LocationPrecision[] values = LocationPrecision.values();
        StringJoiner rank = new StringJoiner(" ", "CASE " + precisionExpression + " ", " END");
        for (int index = 0; index < values.length; index++) {
            rank.add("WHEN '" + values[index].name() + "' THEN " + (values.length - index - 1));
        }
        return rank.toString();
    }

    /** Builds SQL from a trusted, compile-time-constant column expression only. */
    public static String publishableReferencePredicate(String extractionStatusExpression) {
        return extractionStatusExpression + " IN (" + PUBLISHABLE_EXTRACTION_STATUSES.stream()
                .map(status -> "'" + status + "'")
                .collect(java.util.stream.Collectors.joining(", ")) + ")";
    }

    public static boolean publishableExtractionStatus(String extractionStatus) {
        return extractionStatus != null && PUBLISHABLE_EXTRACTION_STATUSES.contains(extractionStatus);
    }

    public static boolean publishableSelection(String extractionStatus, String resolutionStatus) {
        return "RESOLVED".equals(resolutionStatus) && publishableExtractionStatus(extractionStatus);
    }
}
