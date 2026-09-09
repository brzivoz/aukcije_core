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

    /**
     * Requires a parcel attempt to remain tied to the exact current #33 result.
     * The alias is a trusted compile-time identifier supplied by repository code.
     */
    public static String currentParcelEligibilityPredicate(String attemptAlias) {
        return "(" + attemptAlias + ".resolver NOT IN ('RGZ_WFS_PARCEL', 'OFFICIAL_ADDRESS_REGISTRY') OR EXISTS ("
                + "SELECT 1 FROM current_property_reference_ko_matches current_ko "
                + "JOIN property_reference_ko_match_results ko_result "
                + "ON ko_result.reference_id = current_ko.reference_id "
                + "AND ko_result.input_fingerprint = current_ko.input_fingerprint "
                + "JOIN property_reference_extraction_memberships current_membership "
                + "ON current_membership.reference_id = current_ko.reference_id "
                + "JOIN current_property_reference_extractions current_extraction "
                + "ON current_extraction.auction_id = current_membership.auction_id "
                + "AND current_extraction.extraction_run_id "
                + "= current_membership.extraction_run_id "
                + "WHERE current_ko.reference_id = " + attemptAlias + ".property_reference_id "
                + "AND " + attemptAlias + ".upstream_ko_match_input_fingerprint "
                + "= current_ko.input_fingerprint "
                + "AND ko_result.status = 'MATCHED' "
                + "AND ko_result.reconciliation_status <> 'STRUCTURED_ONLY'))";
    }
}
