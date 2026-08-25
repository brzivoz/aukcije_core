package rs.sud.eaukcija.enrichment;

import java.util.UUID;

public final class EnrichmentSubmissionException extends RuntimeException {

    private final UUID runId;

    public EnrichmentSubmissionException(UUID runId) {
        super("the enrichment run was retained but could not be submitted");
        this.runId = runId;
    }

    public UUID runId() {
        return runId;
    }
}
