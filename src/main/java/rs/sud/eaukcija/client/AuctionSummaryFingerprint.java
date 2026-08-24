package rs.sud.eaukcija.client;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

import rs.sud.eaukcija.client.EAukcijaApiTypes.AuctionSummary;

/** Canonical semantic SHA-256 for one eAukcija listing summary. */
public final class AuctionSummaryFingerprint {

    private AuctionSummaryFingerprint() {
    }

    public static String sha256(AuctionSummary summary) {
        Objects.requireNonNull(summary, "summary");
        String canonical = String.join("\u001f",
                Long.toString(summary.id()),
                text(summary.auctionNumber()),
                text(summary.startDate()),
                text(summary.endDate()),
                decimal(summary.startingPrice()),
                decimal(summary.currentPrice()),
                decimal(summary.maxOfferedPrice()),
                text(summary.shortDescription()),
                text(summary.status()),
                Boolean.toString(summary.firstSale()),
                text(summary.propertyType()));
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String text(String value) {
        return value == null ? "-" : value.length() + ":" + value;
    }

    private static String decimal(BigDecimal value) {
        if (value == null) {
            return "-";
        }
        BigDecimal normalized = value.signum() == 0
                ? BigDecimal.ZERO
                : value.stripTrailingZeros();
        return normalized.toPlainString();
    }
}
