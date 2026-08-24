package rs.sud.eaukcija.service;

import java.util.UUID;

public final class SyncSubmissionException extends RuntimeException {
    private final UUID runId;

    public SyncSubmissionException(UUID runId) {
        super("the durable sync run could not be submitted");
        this.runId = runId;
    }

    public UUID runId() {
        return runId;
    }
}
