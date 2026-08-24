package rs.sud.eaukcija.sync.persistence;

import java.util.UUID;

public record SyncRunClaimResult(UUID runId, boolean replayed) {
    public SyncRunClaimResult {
        SyncPersistenceValidation.required(runId, "runId");
    }
}
