package rs.sud.eaukcija.enrichment;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record EnrichmentBacklogStatus(
        boolean enabled,
        boolean paused,
        EnrichmentVersions activeVersions,
        long backlogSize,
        Instant oldestPendingSince,
        long populationGapCount,
        Map<EnrichmentStateStatus, Long> statusDistribution,
        UUID activeRunId) {
}
