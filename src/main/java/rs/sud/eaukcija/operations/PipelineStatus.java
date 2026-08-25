package rs.sud.eaukcija.operations;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import rs.sud.eaukcija.basemap.BasemapStatus;

/** Payload-safe operator view assembled entirely from retained local evidence. */
public record PipelineStatus(
        Instant generatedAt,
        String state,
        boolean ready,
        boolean servingLastGoodData,
        List<String> readinessFailures,
        List<String> warnings,
        List<String> notices,
        Policy policy,
        Database database,
        Sync sync,
        Enrichment enrichment,
        Imports imports,
        Artifacts artifacts) {

    public PipelineStatus {
        readinessFailures = List.copyOf(readinessFailures);
        warnings = List.copyOf(warnings);
        notices = List.copyOf(notices);
    }

    public record Policy(
            long syncStaleAfterSeconds,
            long backlogMaxDepth,
            long backlogMaxAgeSeconds,
            boolean externalSourceOutageImmediatelyAffectsReadiness) {
    }

    public record Database(
            boolean available,
            String schemaVersion,
            String expectedSchemaVersion,
            boolean migrationsCurrent) {
    }

    public record Sync(
            RunMetric lastAttempt,
            RunMetric lastSuccessful,
            Long lastSuccessfulAgeSeconds,
            boolean stale,
            String externalSourceState) {
    }

    public record RunMetric(
            UUID runId,
            String triggerKind,
            String status,
            String stage,
            Instant startedAt,
            Instant finishedAt,
            Long durationMillis,
            long sourceCount,
            Long sourceDelta,
            long listingRowsObserved,
            long listingRowsQuarantined,
            long duplicateCount,
            long detailsSucceeded,
            long detailsQuarantined,
            long retryCount,
            long errorCount,
            long unresolvedErrorCount,
            SnapshotChanges rawSnapshotChanges,
            Map<String, Long> errorClasses) {

        public RunMetric {
            errorClasses = Map.copyOf(errorClasses);
        }
    }

    public record SnapshotChanges(long newCount, long changedCount, long unchangedCount) {
    }

    public record Enrichment(
            EnrichmentRunMetric lastAttempt,
            EnrichmentRunMetric lastSuccessful,
            long backlogDepth,
            Instant oldestBacklogAt,
            Long oldestBacklogAgeSeconds,
            boolean backlogThresholdExceeded,
            Map<String, Long> stateDistribution,
            String qualityParserVersion,
            Map<String, Long> parserResults,
            Map<String, Long> precisionDistribution,
            Map<String, Long> resolutionSourceDistribution,
            Map<String, Long> retryErrorClasses) {

        public Enrichment {
            stateDistribution = Map.copyOf(stateDistribution);
            parserResults = Map.copyOf(parserResults);
            precisionDistribution = Map.copyOf(precisionDistribution);
            resolutionSourceDistribution = Map.copyOf(resolutionSourceDistribution);
            retryErrorClasses = Map.copyOf(retryErrorClasses);
        }
    }

    public record EnrichmentRunMetric(
            UUID runId,
            String triggerKind,
            String status,
            Instant startedAt,
            Instant finishedAt,
            Long durationMillis,
            String parserVersion,
            String resolverVersion,
            String datasetVersion,
            long candidateCount,
            long attemptedCount,
            long succeededCount,
            long retryableFailureCount,
            long terminalNotFoundCount,
            long ambiguousCount,
            long permanentFailureCount,
            long attemptLimitCount) {
    }

    public record Imports(
            ImportRunMetric active,
            ImportRunMetric lastAttempt,
            ImportRunMetric lastSuccessful,
            RetentionJobMetric lastRetention) {
    }

    public record ImportRunMetric(
            UUID runId,
            String action,
            String outcome,
            Instant startedAt,
            Instant finishedAt,
            Long durationMillis,
            UUID snapshotId,
            String sourceDate,
            Long sourceRowCount,
            Long importedRowCount,
            String errorCode) {
    }

    public record RetentionJobMetric(
            UUID importRunId,
            String outcome,
            Instant startedAt,
            Instant finishedAt,
            long durationMillis,
            Integer retainedSnapshotCount,
            String errorCode) {
    }

    public record Artifacts(
            AddressRegistryArtifact addressRegistry,
            ResolverArtifact resolver,
            BasemapStatus basemap) {
    }

    public record AddressRegistryArtifact(
            UUID snapshotId,
            String sourceDate,
            String gpkgSha256,
            long importedRowCount,
            Instant activatedAt) {
    }

    public record ResolverArtifact(
            UUID runId,
            Instant finishedAt,
            String resolverVersion,
            String datasetVersion,
            String datasetSha256,
            long populationCount,
            long unresolvedCount) {
    }
}
