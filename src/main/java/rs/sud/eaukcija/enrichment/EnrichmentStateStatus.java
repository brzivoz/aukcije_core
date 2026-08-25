package rs.sud.eaukcija.enrichment;

public enum EnrichmentStateStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    RETRYABLE_FAILURE,
    TERMINAL_NOT_FOUND,
    AMBIGUOUS,
    PERMANENT_FAILURE,
    ATTEMPT_LIMIT_REACHED,
    /** Retained run-item evidence only; current state is reset to PENDING. */
    INTERRUPTED;

    public boolean isFailure() {
        return this == RETRYABLE_FAILURE
                || this == PERMANENT_FAILURE
                || this == ATTEMPT_LIMIT_REACHED;
    }
}
