package rs.sud.eaukcija.spatial;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LocationSelectionSqlTest {

    @Test
    void precisionOrderIsGeneratedFromEveryEnumValueAndUnknownTiersSortLast() {
        String order = LocationSelectionSql.bestOrder(
                "attempt.location_precision", "pr.reference_order", "attempt.completed_at", "attempt.id");

        int previous = -1;
        for (LocationPrecision precision : LocationPrecision.values()) {
            int position = order.indexOf("WHEN '" + precision.name() + "'");
            assertThat(position).isGreaterThan(previous);
            previous = position;
        }
        assertThat(order)
                .contains(" END DESC NULLS LAST")
                .endsWith("pr.reference_order ASC, attempt.completed_at DESC, attempt.id ASC");
        assertThat(order).startsWith(
                LocationSelectionSql.precisionRank("attempt.location_precision") + " DESC NULLS LAST");
        assertThat(AuctionLocationRepository.BEST_SELECTION_ORDER).isEqualTo(order);
        assertThat(AuctionLocationRepository.queryFor("?"))
                .contains(order, "pr.extraction_status", "RGZ_WFS_PARCEL",
                        "upstream_ko_match_input_fingerprint")
                .doesNotContain("pr.extraction_status IN");
    }

    @Test
    void publicationPolicyExcludesUnstructuredAndReviewStates() {
        assertThat(LocationSelectionSql.publishableReferencePredicate("pr.extraction_status"))
                .isEqualTo("pr.extraction_status IN ('EXTRACTED', 'USER_CONFIRMED')");
        assertThat(LocationSelectionSql.publishableExtractionStatus("EXTRACTED")).isTrue();
        assertThat(LocationSelectionSql.publishableExtractionStatus("USER_CONFIRMED")).isTrue();
        assertThat(LocationSelectionSql.publishableExtractionStatus("NO_STRUCTURED_REFERENCE")).isFalse();
        assertThat(LocationSelectionSql.publishableExtractionStatus("NEEDS_REVIEW")).isFalse();
        assertThat(LocationSelectionSql.publishableExtractionStatus("INVALID")).isFalse();
        assertThat(LocationSelectionSql.publishableExtractionStatus(null)).isFalse();
        assertThat(LocationSelectionSql.publishableSelection("EXTRACTED", "RESOLVED")).isTrue();
        assertThat(LocationSelectionSql.publishableSelection("EXTRACTED", "NONE")).isFalse();
        assertThat(LocationSelectionSql.publishableSelection("NEEDS_REVIEW", "RESOLVED")).isFalse();
    }

    @Test
    void automaticParcelPublicationRequiresItsExactCurrentKoMatch() {
        String predicate = LocationSelectionSql.currentParcelEligibilityPredicate("attempt");

        assertThat(predicate)
                .contains("attempt.resolver NOT IN ('RGZ_WFS_PARCEL', 'OFFICIAL_ADDRESS_REGISTRY')")
                .contains("attempt.upstream_ko_match_input_fingerprint = current_ko.input_fingerprint")
                .contains("current_property_reference_extractions")
                .contains("property_reference_extraction_memberships")
                .contains("ko_result.status = 'MATCHED'")
                .contains("ko_result.reconciliation_status <> 'STRUCTURED_ONLY'");
    }
}
