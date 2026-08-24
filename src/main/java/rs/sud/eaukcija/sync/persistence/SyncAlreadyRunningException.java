package rs.sud.eaukcija.sync.persistence;

import java.util.UUID;

public final class SyncAlreadyRunningException extends RuntimeException {

    private final UUID activeRunId;

    public SyncAlreadyRunningException(UUID activeRunId) {
        super("a sync run is already active");
        this.activeRunId = activeRunId;
    }

    public UUID activeRunId() {
        return activeRunId;
    }
}
