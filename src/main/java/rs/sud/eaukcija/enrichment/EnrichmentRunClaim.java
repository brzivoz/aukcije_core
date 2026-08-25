package rs.sud.eaukcija.enrichment;

import java.util.UUID;

public record EnrichmentRunClaim(UUID runId, boolean replayed) {
}
