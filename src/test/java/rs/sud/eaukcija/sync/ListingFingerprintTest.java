package rs.sud.eaukcija.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import rs.sud.eaukcija.client.AuctionSummaryFingerprint;
import rs.sud.eaukcija.client.EAukcijaApiTypes.AuctionSummary;

class ListingFingerprintTest {

    @Test
    void isStableAcrossEquivalentDecimalScalesButChangesWithSourceMeaning() {
        AuctionSummary first = summary(new BigDecimal("100.00"), "Verified");
        AuctionSummary equivalent = summary(new BigDecimal("100.0"), "Verified");
        AuctionSummary changed = summary(new BigDecimal("100.0"), "Completed");

        assertThat(ListingFingerprint.sha256(first))
                .matches("[0-9a-f]{64}")
                .isEqualTo(AuctionSummaryFingerprint.sha256(first))
                .isEqualTo(ListingFingerprint.sha256(equivalent))
                .isNotEqualTo(ListingFingerprint.sha256(changed));
    }

    private static AuctionSummary summary(BigDecimal price, String status) {
        return new AuctionSummary(
                17L,
                "N17",
                "2026-08-24T10:00:00Z",
                "2026-08-24T11:00:00Z",
                price,
                null,
                null,
                "parcel",
                status,
                true,
                "ImmovableProperties");
    }
}
