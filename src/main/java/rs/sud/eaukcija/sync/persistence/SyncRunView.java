package rs.sud.eaukcija.sync.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SyncRunView(
        UUID runId,
        SyncTriggerKind triggerKind,
        SyncRunStatus status,
        SyncRunStage stage,
        Instant startedAt,
        Instant heartbeatAt,
        Instant finishedAt,
        List<Integer> configuredRoots,
        int pageSize,
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

    public SyncRunView {
        configuredRoots = List.copyOf(configuredRoots);
    }

    /** Compatibility constructor for callers that predate listing-row quarantine. */
    public SyncRunView(
            UUID runId,
            SyncTriggerKind triggerKind,
            SyncRunStatus status,
            SyncRunStage stage,
            Instant startedAt,
            Instant heartbeatAt,
            Instant finishedAt,
            List<Integer> configuredRoots,
            int pageSize,
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
        this(runId, triggerKind, status, stage, startedAt, heartbeatAt, finishedAt,
                configuredRoots, pageSize, categoryTreeSha256, categoryTreeObservedAt,
                pagesExpected, pagesCompleted, listingRowsObserved, 0,
                uniqueAuctionCount, duplicateAuctionCount, unknownPropertyKindCount,
                detailsRequired, detailsAttempted, detailsSucceeded, detailsQuarantined,
                detailsFailed, retryCount, errorCount, unresolvedErrorCount);
    }

    /** Compatibility constructor for callers that predate detail quarantine. */
    public SyncRunView(
            UUID runId,
            SyncTriggerKind triggerKind,
            SyncRunStatus status,
            SyncRunStage stage,
            Instant startedAt,
            Instant heartbeatAt,
            Instant finishedAt,
            List<Integer> configuredRoots,
            int pageSize,
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
        this(runId, triggerKind, status, stage, startedAt, heartbeatAt, finishedAt,
                configuredRoots, pageSize, categoryTreeSha256, categoryTreeObservedAt,
                pagesExpected, pagesCompleted, listingRowsObserved, 0, uniqueAuctionCount,
                duplicateAuctionCount, unknownPropertyKindCount, detailsRequired,
                detailsAttempted, detailsSucceeded, 0, detailsFailed, retryCount,
                errorCount, unresolvedErrorCount);
    }
}
