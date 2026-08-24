package rs.sud.eaukcija.sync.persistence;

import java.time.Instant;

public record PersistedAuctionDetailQuarantine(
        long auctionId,
        String listingFingerprint,
        String errorCode,
        Instant occurredAt) {
}
