package rs.sud.eaukcija.sync.persistence;

/** Bounded, payload-free evidence for one direct child-category listing endpoint. */
public record SyncRunChildResult(
        int parentRootCategoryId,
        int childCategoryId,
        long sourceTotalCount,
        long rowsObserved,
        long uniqueIds,
        long duplicateIds,
        int pagesExpected,
        int pagesCompleted,
        boolean totalConsistent,
        boolean subsetOfParentRoot,
        boolean complete) {

    public SyncRunChildResult {
        if (parentRootCategoryId <= 0) {
            throw new IllegalArgumentException("parentRootCategoryId must be positive");
        }
        if (childCategoryId <= 0) {
            throw new IllegalArgumentException("childCategoryId must be positive");
        }
        if (childCategoryId == parentRootCategoryId) {
            throw new IllegalArgumentException("childCategoryId must differ from parentRootCategoryId");
        }
        SyncPersistenceValidation.nonNegative(sourceTotalCount, "sourceTotalCount");
        SyncPersistenceValidation.nonNegative(rowsObserved, "rowsObserved");
        SyncPersistenceValidation.nonNegative(uniqueIds, "uniqueIds");
        SyncPersistenceValidation.nonNegative(duplicateIds, "duplicateIds");
        SyncPersistenceValidation.nonNegative(pagesExpected, "pagesExpected");
        SyncPersistenceValidation.nonNegative(pagesCompleted, "pagesCompleted");
        if (uniqueIds > rowsObserved || duplicateIds != rowsObserved - uniqueIds) {
            throw new IllegalArgumentException("child row counters are inconsistent");
        }
        if (pagesCompleted > pagesExpected) {
            throw new IllegalArgumentException("child page counters are inconsistent");
        }
        if (complete && (!totalConsistent || !subsetOfParentRoot || pagesCompleted != pagesExpected
                || uniqueIds != sourceTotalCount)) {
            throw new IllegalArgumentException(
                    "a complete child result must satisfy all completeness checks");
        }
    }
}
