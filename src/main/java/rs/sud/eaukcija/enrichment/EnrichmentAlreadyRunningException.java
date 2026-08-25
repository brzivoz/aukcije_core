package rs.sud.eaukcija.enrichment;

import java.util.UUID;

public final class EnrichmentAlreadyRunningException extends RuntimeException {

    private final UUID activeRunId;

    public EnrichmentAlreadyRunningException(UUID activeRunId) {
        super("an enrichment run is already active");
        this.activeRunId = activeRunId;
    }

    public UUID activeRunId() {
        return activeRunId;
    }
}
