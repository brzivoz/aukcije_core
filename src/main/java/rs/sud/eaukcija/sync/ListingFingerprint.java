package rs.sud.eaukcija.sync;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import rs.sud.eaukcija.client.EAukcijaApiTypes.AuctionSummary;

/** Stable semantic fingerprint used to decide when source details are stale. */
public final class ListingFingerprint {

    private ListingFingerprint() {
    }

    public static String sha256(AuctionSummary summary) {
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
                    MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String text(String value) {
        if (value == null) {
            return "-";
        }
        return value.length() + ":" + value;
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
