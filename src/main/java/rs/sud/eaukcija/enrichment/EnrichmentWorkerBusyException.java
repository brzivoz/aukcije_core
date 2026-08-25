package rs.sud.eaukcija.enrichment;

import java.util.UUID;

/** A claimed run that was safely skipped because the shared sync worker was busy. */
public class EnrichmentWorkerBusyException extends RuntimeException {

    private final UUID runId;
    private final UUID activeSyncRunId;

    public EnrichmentWorkerBusyException(UUID runId, UUID activeSyncRunId) {
        super("shared sync/enrichment worker is busy");
        this.runId = runId;
        this.activeSyncRunId = activeSyncRunId;
    }

    public UUID runId() {
        return runId;
    }

    public UUID activeSyncRunId() {
        return activeSyncRunId;
    }
}
