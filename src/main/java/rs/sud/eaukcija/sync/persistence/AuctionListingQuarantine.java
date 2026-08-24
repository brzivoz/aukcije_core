package rs.sud.eaukcija.sync.persistence;

/**
 * Redacted evidence for one discovered auction whose listing row could not be
 * represented by the persistence contract. The source row hash permits stable
 * operator correlation without retaining the rejected source payload.
 */
public record AuctionListingQuarantine(
        long auctionId,
        String sourceRowSha256,
        String errorCode,
        int rootCategoryId,
        Integer childCategoryId,
        int pageNumber) {

    public AuctionListingQuarantine {
        if (auctionId <= 0) {
            throw new IllegalArgumentException("auctionId must be positive");
        }
        SyncPersistenceValidation.sha256(sourceRowSha256, "sourceRowSha256");
        if (!"INVALID_DATA".equals(errorCode)) {
            throw new IllegalArgumentException("listing quarantine errorCode must be INVALID_DATA");
        }
        if (rootCategoryId <= 0) {
            throw new IllegalArgumentException("rootCategoryId must be positive");
        }
        if (childCategoryId != null && childCategoryId <= 0) {
            throw new IllegalArgumentException("childCategoryId must be positive when present");
        }
        if (childCategoryId != null && childCategoryId == rootCategoryId) {
            throw new IllegalArgumentException("childCategoryId must differ from rootCategoryId");
        }
        if (pageNumber <= 0) {
            throw new IllegalArgumentException("pageNumber must be positive");
        }
    }
}
