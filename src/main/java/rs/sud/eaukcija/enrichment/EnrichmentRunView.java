package rs.sud.eaukcija.enrichment;

import java.time.Instant;
import java.util.UUID;

public record EnrichmentRunView(
        UUID runId,
        EnrichmentTriggerKind triggerKind,
        EnrichmentRunStatus status,
        Instant startedAt,
        Instant heartbeatAt,
        Instant finishedAt,
        EnrichmentVersions versions,
        EnrichmentSelector selector,
        int maxItems,
        long candidateCount,
        long attemptedCount,
        long succeededCount,
        long retryableFailureCount,
        long terminalNotFoundCount,
        long ambiguousCount,
        long permanentFailureCount,
        long attemptLimitCount) {
}
