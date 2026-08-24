package rs.sud.eaukcija.sync.persistence;

import java.time.Instant;

public record PersistedSyncRunError(
        int ordinal,
        Instant occurredAt,
        SyncRunStage stage,
        Integer rootCategoryId,
        Integer childCategoryId,
        Integer pageNumber,
        Long auctionId,
        Integer httpStatus,
        String errorCode,
        boolean retryable,
        int attemptNumber,
        boolean resolved) {

    /** Compatibility constructor for unresolved terminal failures. */
    public PersistedSyncRunError(
            int ordinal,
            Instant occurredAt,
            SyncRunStage stage,
            Integer rootCategoryId,
            Integer childCategoryId,
            Integer pageNumber,
            Long auctionId,
            Integer httpStatus,
            String errorCode,
            boolean retryable,
            int attemptNumber) {
        this(ordinal, occurredAt, stage, rootCategoryId, childCategoryId,
                pageNumber, auctionId, httpStatus, errorCode, retryable,
                attemptNumber, false);
    }
}
