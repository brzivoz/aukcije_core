package rs.sud.eaukcija.sync.persistence;

import java.time.Instant;

public record SyncRunProgress(
        SyncRunStage stage,
        String categoryTreeSha256,
        Instant categoryTreeObservedAt,
        int pagesExpected,
        int pagesCompleted,
        long listingRowsObserved,
        long listingRowsQuarantined,
        long uniqueAuctionCount,
        long duplicateAuctionCount,
        long unknownPropertyKindCount,
        long detailsRequired,
        long detailsAttempted,
        long detailsSucceeded,
        long detailsQuarantined,
        long detailsFailed,
        long retryCount,
        long errorCount,
        long unresolvedErrorCount) {

    public SyncRunProgress {
        SyncPersistenceValidation.required(stage, "stage");
        if ((categoryTreeSha256 == null) != (categoryTreeObservedAt == null)) {
            throw new IllegalArgumentException("taxonomy hash and observation time must both be set or both be null");
        }
        if (categoryTreeSha256 != null) {
            SyncPersistenceValidation.sha256(categoryTreeSha256, "categoryTreeSha256");
        }
        SyncPersistenceValidation.nonNegative(pagesExpected, "pagesExpected");
        SyncPersistenceValidation.nonNegative(pagesCompleted, "pagesCompleted");
        SyncPersistenceValidation.nonNegative(listingRowsObserved, "listingRowsObserved");
        SyncPersistenceValidation.nonNegative(listingRowsQuarantined, "listingRowsQuarantined");
        SyncPersistenceValidation.nonNegative(uniqueAuctionCount, "uniqueAuctionCount");
        SyncPersistenceValidation.nonNegative(duplicateAuctionCount, "duplicateAuctionCount");
        SyncPersistenceValidation.nonNegative(unknownPropertyKindCount, "unknownPropertyKindCount");
        SyncPersistenceValidation.nonNegative(detailsRequired, "detailsRequired");
        SyncPersistenceValidation.nonNegative(detailsAttempted, "detailsAttempted");
        SyncPersistenceValidation.nonNegative(detailsSucceeded, "detailsSucceeded");
        SyncPersistenceValidation.nonNegative(detailsQuarantined, "detailsQuarantined");
        SyncPersistenceValidation.nonNegative(detailsFailed, "detailsFailed");
        SyncPersistenceValidation.nonNegative(retryCount, "retryCount");
        SyncPersistenceValidation.nonNegative(errorCount, "errorCount");
        SyncPersistenceValidation.nonNegative(unresolvedErrorCount, "unresolvedErrorCount");
        if (pagesCompleted > pagesExpected) {
            throw new IllegalArgumentException("pagesCompleted must not exceed pagesExpected");
        }
        if (detailsSucceeded + detailsQuarantined > detailsRequired
                || detailsFailed > detailsRequired
                || detailsAttempted < detailsSucceeded + detailsQuarantined + detailsFailed) {
            throw new IllegalArgumentException("detail counters are inconsistent");
        }
        if (unresolvedErrorCount > errorCount) {
            throw new IllegalArgumentException("unresolvedErrorCount must not exceed errorCount");
        }
    }

    /** Compatibility constructor for callers that predate listing-row quarantine. */
    public SyncRunProgress(
            SyncRunStage stage,
            String categoryTreeSha256,
            Instant categoryTreeObservedAt,
            int pagesExpected,
            int pagesCompleted,
            long listingRowsObserved,
            long uniqueAuctionCount,
            long duplicateAuctionCount,
            long unknownPropertyKindCount,
            long detailsRequired,
            long detailsAttempted,
            long detailsSucceeded,
            long detailsQuarantined,
            long detailsFailed,
            long retryCount,
            long errorCount,
            long unresolvedErrorCount) {
        this(stage, categoryTreeSha256, categoryTreeObservedAt,
                pagesExpected, pagesCompleted, listingRowsObserved, 0,
                uniqueAuctionCount, duplicateAuctionCount, unknownPropertyKindCount,
                detailsRequired, detailsAttempted, detailsSucceeded, detailsQuarantined,
                detailsFailed, retryCount, errorCount, unresolvedErrorCount);
    }

    /** Compatibility constructor for callers that predate detail quarantine. */
    public SyncRunProgress(
            SyncRunStage stage,
            String categoryTreeSha256,
            Instant categoryTreeObservedAt,
            int pagesExpected,
            int pagesCompleted,
            long listingRowsObserved,
            long uniqueAuctionCount,
            long duplicateAuctionCount,
            long unknownPropertyKindCount,
            long detailsRequired,
            long detailsAttempted,
            long detailsSucceeded,
            long detailsFailed,
            long retryCount,
            long errorCount,
            long unresolvedErrorCount) {
        this(stage, categoryTreeSha256, categoryTreeObservedAt,
                pagesExpected, pagesCompleted, listingRowsObserved, 0,
                uniqueAuctionCount, duplicateAuctionCount, unknownPropertyKindCount,
                detailsRequired, detailsAttempted, detailsSucceeded, 0, detailsFailed,
                retryCount, errorCount, unresolvedErrorCount);
    }

    public static SyncRunProgress claimed() {
        return new SyncRunProgress(
                SyncRunStage.CLAIMED, null, null,
                0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0);
    }
}
