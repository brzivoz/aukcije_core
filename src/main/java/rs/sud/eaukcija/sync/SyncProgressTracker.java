package rs.sud.eaukcija.sync;

import java.time.Instant;

import rs.sud.eaukcija.sync.persistence.SyncRunProgress;
import rs.sud.eaukcija.sync.persistence.SyncRunStage;

public final class SyncProgressTracker {

    private String taxonomySha256;
    private Instant taxonomyObservedAt;
    private int pagesExpected;
    private int pagesCompleted;
    private long listingRowsObserved;
    private long uniqueAuctions;
    private long duplicateAuctions;
    private long unknownKinds;
    private long detailsRequired;
    private long detailsAttempted;
    private long detailsSucceeded;
    private long detailsFailed;
    private long retries;
    private long errors;
    private long unresolvedErrors;
    private boolean sourceProgress;

    public void taxonomy(String sha256, Instant observedAt) {
        this.taxonomySha256 = sha256;
        this.taxonomyObservedAt = observedAt;
        this.sourceProgress = true;
    }

    public void expectPages(int count) {
        pagesExpected += count;
    }

    public void pageCompleted(int rowCount, long currentUniqueCount, long currentDuplicateCount) {
        pagesCompleted++;
        listingRowsObserved += rowCount;
        uniqueAuctions = currentUniqueCount;
        duplicateAuctions = currentDuplicateCount;
        sourceProgress = true;
    }

    public void listingCounts(long rowCount, long currentUniqueCount, long currentDuplicateCount) {
        listingRowsObserved = rowCount;
        uniqueAuctions = currentUniqueCount;
        duplicateAuctions = currentDuplicateCount;
    }

    public void detailsRequired(long count) {
        detailsRequired = count;
    }

    public void detailAttempted() {
        detailsAttempted++;
    }

    public void detailSucceeded() {
        detailsSucceeded++;
    }

    public void detailFailed() {
        detailsFailed++;
    }

    public void retries(long count) {
        retries += count;
    }

    public void unknownKinds(long count) {
        unknownKinds = count;
    }

    public void error() {
        errors++;
        unresolvedErrors++;
    }

    public boolean hasSourceProgress() {
        return sourceProgress;
    }

    public long errorCount() {
        return errors;
    }

    public SyncRunProgress snapshot(SyncRunStage stage) {
        return new SyncRunProgress(
                stage,
                taxonomySha256,
                taxonomyObservedAt,
                pagesExpected,
                pagesCompleted,
                listingRowsObserved,
                uniqueAuctions,
                duplicateAuctions,
                unknownKinds,
                detailsRequired,
                detailsAttempted,
                detailsSucceeded,
                detailsFailed,
                retries,
                errors,
                unresolvedErrors);
    }
}
