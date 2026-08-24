package rs.sud.eaukcija.sync.persistence;

/**
 * Redacted evidence for one discovered auction whose detail response could not
 * be promoted. Quarantines intentionally do not reference {@code auctions}:
 * a newly discovered invalid record must never be inserted merely to retain
 * failure evidence.
 */
public record AuctionDetailQuarantine(
        long auctionId,
        String listingFingerprint,
        String errorCode) {

    public AuctionDetailQuarantine {
        if (auctionId <= 0) {
            throw new IllegalArgumentException("auctionId must be positive");
        }
        SyncPersistenceValidation.sha256(listingFingerprint, "listingFingerprint");
        SyncPersistenceValidation.required(errorCode, "errorCode");
        if (!errorCode.matches("[A-Z0-9_]+")) {
            throw new IllegalArgumentException("errorCode must be a canonical redacted code");
        }
    }
}
