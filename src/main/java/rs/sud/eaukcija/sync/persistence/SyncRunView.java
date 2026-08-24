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

    public SyncRunView {
        configuredRoots = List.copyOf(configuredRoots);
    }
}
