package rs.sud.eaukcija.enrichment;

import java.time.Instant;

public record EnrichmentCandidate(
        EnrichmentWorkItem item,
        Instant availableSince,
        boolean explicitReplay) {
}
