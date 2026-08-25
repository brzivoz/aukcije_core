package rs.sud.eaukcija.refresh;

import java.util.UUID;

public record RefreshClaim(UUID workflowId, boolean alreadyRunning, boolean replayed) {
}
