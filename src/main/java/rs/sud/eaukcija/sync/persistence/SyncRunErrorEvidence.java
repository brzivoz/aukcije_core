package rs.sud.eaukcija.sync.persistence;

public record SyncRunErrorEvidence(
        SyncRunStage stage,
        Integer rootCategoryId,
        Integer childCategoryId,
        Integer pageNumber,
        Long auctionId,
        Integer httpStatus,
        String errorCode,
        boolean retryable,
        int attemptNumber) {

    public SyncRunErrorEvidence {
        SyncPersistenceValidation.required(stage, "stage");
        if (stage == SyncRunStage.CLAIMED || stage == SyncRunStage.COMPLETED) {
            throw new IllegalArgumentException("error evidence requires a source or promotion stage");
        }
        if (rootCategoryId != null && rootCategoryId <= 0) {
            throw new IllegalArgumentException("rootCategoryId must be positive");
        }
        if (childCategoryId != null && childCategoryId <= 0) {
            throw new IllegalArgumentException("childCategoryId must be positive");
        }
        if (pageNumber != null && pageNumber <= 0) {
            throw new IllegalArgumentException("pageNumber must be positive");
        }
        if (httpStatus != null && (httpStatus < 100 || httpStatus > 599)) {
            throw new IllegalArgumentException("httpStatus must be between 100 and 599");
        }
        SyncPersistenceValidation.errorCode(errorCode);
        if (attemptNumber <= 0) {
            throw new IllegalArgumentException("attemptNumber must be positive");
        }
    }
}
