package rs.sud.eaukcija.enrichment;

public record EnrichmentItemAttempt(int attemptNumber) {
    public EnrichmentItemAttempt {
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("attemptNumber must be positive");
        }
    }
}
