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
        long attemptLimitCount,
        long propertyReferenceExtractionSuccessCount,
        long propertyReferenceParseFailureCount,
        long propertyReferenceCount,
        long textReferenceCount,
        long noStructuredReferenceCount,
        long koConflictCount,
        String propertyReferenceQualityCorpusVersion,
        String propertyReferenceQualityMetricsSha256) {

    public EnrichmentRunView(
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
        this(runId, triggerKind, status, startedAt, heartbeatAt, finishedAt,
                versions, selector, maxItems, candidateCount, attemptedCount,
                succeededCount, retryableFailureCount, terminalNotFoundCount,
                ambiguousCount, permanentFailureCount, attemptLimitCount,
                0, 0, 0, 0, 0, 0, null, null);
    }
}
