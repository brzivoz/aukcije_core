package rs.sud.eaukcija.operations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.flywaydb.core.api.FlywayException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

import rs.sud.eaukcija.basemap.BasemapArtifactRegistry;
import rs.sud.eaukcija.basemap.BasemapStatus;
import rs.sud.eaukcija.operations.PipelineStatusRepository.PersistedEvidence;

/** Applies documented freshness/readiness policy to retained evidence. */
@Service
@Profile("!local-h2")
@EnableConfigurationProperties(PipelineStatusProperties.class)
public class PipelineStatusService {

    private static final Set<String> EXTERNAL_SOURCE_ERRORS = Set.of(
            "TIMEOUT", "IO", "INTERRUPTED", "HTTP_STATUS", "RATE_LIMITED");

    private final PipelineStatusRepository repository;
    private final BasemapArtifactRegistry basemap;
    private final PipelineStatusProperties properties;
    private final Clock clock;

    @Autowired
    public PipelineStatusService(
            PipelineStatusRepository repository,
            BasemapArtifactRegistry basemap,
            PipelineStatusProperties properties) {
        this(repository, basemap, properties, Clock.systemUTC());
    }

    PipelineStatusService(
            PipelineStatusRepository repository,
            BasemapArtifactRegistry basemap,
            PipelineStatusProperties properties,
            Clock clock) {
        this.repository = repository;
        this.basemap = basemap;
        this.properties = properties;
        this.clock = clock;
    }

    public PipelineStatus status() {
        Instant now = clock.instant();
        PersistedEvidence evidence;
        try {
            evidence = repository.read();
        } catch (DataAccessException | TransactionException | FlywayException databaseFailure) {
            return databaseUnavailable(now);
        }
        BasemapStatus basemapStatus = basemap.status();
        List<String> readinessFailures = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> notices = new ArrayList<>();

        if (!evidence.database().available()) {
            readinessFailures.add("DATABASE_UNAVAILABLE");
        }
        if (!evidence.database().migrationsCurrent()) {
            readinessFailures.add("MIGRATIONS_NOT_CURRENT");
        }
        if (!basemapStatus.healthy()) {
            readinessFailures.add("BASEMAP_UNAVAILABLE");
        } else if (basemapStatus.warning() != null) {
            warnings.add("BASEMAP_LAST_ACTIVATION_REJECTED");
        }

        PipelineStatus.RunMetric successfulSync = evidence.lastSuccessfulSync();
        Long successfulAge = successfulSync == null
                ? null : ageSeconds(now, successfulSync.finishedAt());
        boolean stale = successfulAge == null
                || successfulAge > properties.getSyncStaleAfter().toSeconds();
        if (successfulSync == null) {
            readinessFailures.add("NO_SUCCESSFUL_SYNC");
        } else if (stale) {
            readinessFailures.add("SUCCESSFUL_SYNC_STALE");
        }

        PipelineStatus.RunMetric attemptedSync = evidence.lastSyncAttempt();
        String externalSourceState = externalSourceState(attemptedSync);
        if (attemptedSync != null && !"SUCCEEDED".equals(attemptedSync.status())) {
            warnings.add("SYNC_LAST_ATTEMPT_" + attemptedSync.status());
        }
        if ("OUTAGE".equals(externalSourceState)) {
            warnings.add("EXTERNAL_SOURCE_OUTAGE_SERVING_LAST_GOOD");
        }

        Long backlogAge = ageSeconds(now, evidence.backlog().oldestAt());
        boolean backlogExceeded = evidence.backlog().depth() > properties.getBacklogMaxDepth()
                || (backlogAge != null
                    && backlogAge > properties.getBacklogMaxAge().toSeconds());
        if (backlogExceeded) {
            warnings.add("ENRICHMENT_BACKLOG_THRESHOLD_EXCEEDED");
        }
        if (evidence.enrichmentStateDistribution().getOrDefault("RETRYABLE_FAILURE", 0L) > 0) {
            warnings.add("ENRICHMENT_RETRYABLE_FAILURES_PENDING");
        }
        if (!evidence.retryErrorClasses().isEmpty()) {
            notices.add("ENRICHMENT_ERROR_EVIDENCE_RETAINED");
        }
        if (evidence.lastEnrichmentAttempt() != null
                && !"SUCCEEDED".equals(evidence.lastEnrichmentAttempt().status())) {
            warnings.add("ENRICHMENT_LAST_ATTEMPT_" + evidence.lastEnrichmentAttempt().status());
        }
        if (evidence.activeImport() != null) {
            notices.add("ADDRESS_REGISTRY_IMPORT_RUNNING");
        }
        if (evidence.lastImportAttempt() != null
                && "FAILED".equals(evidence.lastImportAttempt().outcome())) {
            warnings.add("ADDRESS_REGISTRY_LAST_IMPORT_FAILED");
        }
        if (evidence.lastRetentionJob() != null
                && "FAILED".equals(evidence.lastRetentionJob().outcome())) {
            warnings.add("ADDRESS_REGISTRY_RETENTION_FAILED");
        }
        if (evidence.addressRegistryArtifact() == null) {
            warnings.add("ADDRESS_REGISTRY_ARTIFACT_MISSING");
        }
        if (evidence.resolverArtifact() == null) {
            warnings.add("RESOLVER_ARTIFACT_MISSING");
        }

        boolean ready = readinessFailures.isEmpty();
        boolean canServeLastGood = successfulSync != null
                && evidence.database().available()
                && basemapStatus.healthy();
        boolean servingLastGood = canServeLastGood && (!ready || !warnings.isEmpty());
        String state = !ready
                ? (servingLastGood ? "SERVING_LAST_GOOD_DATA" : "UNAVAILABLE")
                : warnings.isEmpty() ? "FRESH_AND_HEALTHY" : "SERVING_LAST_GOOD_DATA";

        return new PipelineStatus(
                now,
                state,
                ready,
                servingLastGood,
                readinessFailures,
                warnings,
                notices,
                new PipelineStatus.Policy(
                        properties.getSyncStaleAfter().toSeconds(),
                        properties.getBacklogMaxDepth(),
                        properties.getBacklogMaxAge().toSeconds(),
                        false),
                new PipelineStatus.Database(
                        evidence.database().available(),
                        evidence.database().schemaVersion(),
                        evidence.database().expectedSchemaVersion(),
                        evidence.database().migrationsCurrent()),
                new PipelineStatus.Sync(
                        attemptedSync,
                        successfulSync,
                        successfulAge,
                        stale,
                        externalSourceState),
                new PipelineStatus.Enrichment(
                        evidence.lastEnrichmentAttempt(),
                        evidence.lastSuccessfulEnrichment(),
                        evidence.backlog().depth(),
                        evidence.backlog().oldestAt(),
                        backlogAge,
                        backlogExceeded,
                        evidence.enrichmentStateDistribution(),
                        evidence.qualityParserVersion(),
                        evidence.parserResults(),
                        evidence.precisionDistribution(),
                        evidence.resolutionSourceDistribution(),
                        evidence.retryErrorClasses()),
                new PipelineStatus.Imports(
                        evidence.activeImport(),
                        evidence.lastImportAttempt(),
                        evidence.lastSuccessfulImport(),
                        evidence.lastRetentionJob()),
                new PipelineStatus.Artifacts(
                        evidence.addressRegistryArtifact(),
                        evidence.resolverArtifact(),
                        basemapStatus));
    }

    private PipelineStatus databaseUnavailable(Instant now) {
        BasemapStatus basemapStatus = null;
        try {
            basemapStatus = basemap.status();
        } catch (RuntimeException unavailable) {
            // The database gate is already authoritative. Do not expose or log
            // secondary exception text while assembling the safe status body.
        }
        return new PipelineStatus(
                now,
                "UNAVAILABLE",
                false,
                false,
                List.of("DATABASE_UNAVAILABLE"),
                List.of(),
                List.of(),
                new PipelineStatus.Policy(
                        properties.getSyncStaleAfter().toSeconds(),
                        properties.getBacklogMaxDepth(),
                        properties.getBacklogMaxAge().toSeconds(),
                        false),
                new PipelineStatus.Database(false, null, null, false),
                new PipelineStatus.Sync(null, null, null, true, "UNKNOWN"),
                new PipelineStatus.Enrichment(
                        null, null, 0, null, null, false,
                        Map.of(), null, Map.of(), Map.of(), Map.of(), Map.of()),
                new PipelineStatus.Imports(null, null, null, null),
                new PipelineStatus.Artifacts(null, null, basemapStatus));
    }

    static String externalSourceState(PipelineStatus.RunMetric attempt) {
        if (attempt == null) {
            return "UNKNOWN";
        }
        if ("SUCCEEDED".equals(attempt.status())) {
            return "AVAILABLE";
        }
        for (String errorClass : attempt.errorClasses().keySet()) {
            if (EXTERNAL_SOURCE_ERRORS.contains(errorClass)) {
                return "OUTAGE";
            }
        }
        return "DEGRADED_LOCAL_PIPELINE";
    }

    private static Long ageSeconds(Instant now, Instant occurredAt) {
        if (occurredAt == null) {
            return null;
        }
        return Math.max(0, Duration.between(occurredAt, now).toSeconds());
    }
}
