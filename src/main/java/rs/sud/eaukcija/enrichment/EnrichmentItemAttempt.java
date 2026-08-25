package rs.sud.eaukcija.enrichment;

public record EnrichmentItemAttempt(int attemptNumber, int retryableFailureNumber) {
    public EnrichmentItemAttempt {
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("attemptNumber must be positive");
        }
        if (retryableFailureNumber < 1) {
            throw new IllegalArgumentException("retryableFailureNumber must be positive");
        }
    }
}
