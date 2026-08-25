package rs.sud.eaukcija.refresh;

import java.util.UUID;

public class RefreshSubmissionException extends RuntimeException {

    private final UUID workflowId;

    public RefreshSubmissionException(UUID workflowId) {
        super("durable refresh submission failed");
        this.workflowId = workflowId;
    }

    public UUID workflowId() {
        return workflowId;
    }
}
