package rs.sud.eaukcija.sync.persistence;

public record SyncRunRootResult(
        int rootCategoryId,
        long sourceTotalCount,
        long rowsObserved,
        long uniqueIds,
        long duplicateIds,
        int pagesExpected,
        int pagesCompleted,
        boolean totalConsistent,
        boolean complete) {

    public SyncRunRootResult {
        if (rootCategoryId <= 0) {
            throw new IllegalArgumentException("rootCategoryId must be positive");
        }
        SyncPersistenceValidation.nonNegative(sourceTotalCount, "sourceTotalCount");
        SyncPersistenceValidation.nonNegative(rowsObserved, "rowsObserved");
        SyncPersistenceValidation.nonNegative(uniqueIds, "uniqueIds");
        SyncPersistenceValidation.nonNegative(duplicateIds, "duplicateIds");
        SyncPersistenceValidation.nonNegative(pagesExpected, "pagesExpected");
        SyncPersistenceValidation.nonNegative(pagesCompleted, "pagesCompleted");
        if (uniqueIds > rowsObserved || duplicateIds != rowsObserved - uniqueIds) {
            throw new IllegalArgumentException("root row counters are inconsistent");
        }
        if (pagesCompleted > pagesExpected) {
            throw new IllegalArgumentException("root page counters are inconsistent");
        }
        if (complete && (!totalConsistent || pagesCompleted != pagesExpected
                || uniqueIds != sourceTotalCount)) {
            throw new IllegalArgumentException("a complete root result must satisfy all completeness checks");
        }
    }
}
