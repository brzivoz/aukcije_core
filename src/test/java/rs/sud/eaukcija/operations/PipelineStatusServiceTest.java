package rs.sud.eaukcija.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.CannotCreateTransactionException;

import rs.sud.eaukcija.basemap.BasemapArtifactRegistry;
import rs.sud.eaukcija.basemap.BasemapStatus;
import rs.sud.eaukcija.operations.PipelineStatusRepository.Backlog;
import rs.sud.eaukcija.operations.PipelineStatusRepository.DatabaseEvidence;
import rs.sud.eaukcija.operations.PipelineStatusRepository.PersistedEvidence;

class PipelineStatusServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-25T12:00:00Z");
    private static final UUID SYNC_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID IMPORT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");

    private final PipelineStatusRepository repository = mock(PipelineStatusRepository.class);
    private final BasemapArtifactRegistry basemap = mock(BasemapArtifactRegistry.class);
    private final PipelineStatusProperties properties = new PipelineStatusProperties();

    @BeforeEach
    void healthyBasemap() {
        when(basemap.status()).thenReturn(new BasemapStatus(
                true, "AVAILABLE", "serbia-2026-08", "serbia-2026-08",
                "a".repeat(64), 42L, NOW.minusSeconds(3_600), NOW, null));
    }

    @Test
    void freshSuccessfulEvidenceIsReadyAndHealthy() {
        when(repository.read()).thenReturn(evidence(success("SUCCEEDED", Map.of()), success("SUCCEEDED", Map.of())));

        PipelineStatus status = service().status();

        assertThat(status.ready()).isTrue();
        assertThat(status.state()).isEqualTo("FRESH_AND_HEALTHY");
        assertThat(status.servingLastGoodData()).isFalse();
        assertThat(status.readinessFailures()).isEmpty();
        assertThat(status.warnings()).isEmpty();
        assertThat(status.notices()).isEmpty();
        assertThat(status.sync().lastSuccessfulAgeSeconds()).isEqualTo(3_600);
    }

    @Test
    void partialAttemptKeepsFreshLastGoodDataReadyButVisiblyDegraded() {
        PipelineStatus.RunMetric partial = run("PARTIAL", Map.of("INVALID_DATA", 1L), NOW.minusSeconds(300));
        when(repository.read()).thenReturn(evidence(partial, success("SUCCEEDED", Map.of())));

        PipelineStatus status = service().status();

        assertThat(status.ready()).isTrue();
        assertThat(status.state()).isEqualTo("SERVING_LAST_GOOD_DATA");
        assertThat(status.servingLastGoodData()).isTrue();
        assertThat(status.warnings()).contains("SYNC_LAST_ATTEMPT_PARTIAL");
        assertThat(status.sync().lastAttempt().runId()).isEqualTo(SYNC_ID);
        assertThat(status.sync().lastSuccessful()).isNotNull();
    }

    @Test
    void staleLastSuccessFailsReadinessWhileRetainedDataRemainsExplicit() {
        PipelineStatus.RunMetric stale = run("SUCCEEDED", Map.of(), NOW.minusSeconds(27 * 3_600L));
        when(repository.read()).thenReturn(evidence(stale, stale));

        PipelineStatus status = service().status();

        assertThat(status.ready()).isFalse();
        assertThat(status.state()).isEqualTo("SERVING_LAST_GOOD_DATA");
        assertThat(status.servingLastGoodData()).isTrue();
        assertThat(status.readinessFailures()).containsExactly("SUCCESSFUL_SYNC_STALE");
        assertThat(status.sync().stale()).isTrue();
    }

    @Test
    void queueBacklogThresholdIsAVisibleServingLastGoodWarning() {
        PersistedEvidence base = evidence(success("SUCCEEDED", Map.of()), success("SUCCEEDED", Map.of()));
        when(repository.read()).thenReturn(copy(base, new Backlog(101, NOW.minusSeconds(60)),
                base.lastImportAttempt()));

        PipelineStatus status = service().status();

        assertThat(status.ready()).isTrue();
        assertThat(status.enrichment().backlogThresholdExceeded()).isTrue();
        assertThat(status.warnings()).contains("ENRICHMENT_BACKLOG_THRESHOLD_EXCEEDED");
        assertThat(status.state()).isEqualTo("SERVING_LAST_GOOD_DATA");
    }

    @Test
    void failedImportIsReportedWithoutReplacingTheActiveArtifact() {
        PersistedEvidence base = evidence(success("SUCCEEDED", Map.of()), success("SUCCEEDED", Map.of()));
        PipelineStatus.ImportRunMetric failed = new PipelineStatus.ImportRunMetric(
                IMPORT_ID, "IMPORT", "FAILED", NOW.minusSeconds(120), NOW.minusSeconds(60),
                60_000L, null, "2026-08-25", null, null, "SCHEMA_MISMATCH");
        when(repository.read()).thenReturn(copy(base, base.backlog(), failed));

        PipelineStatus status = service().status();

        assertThat(status.ready()).isTrue();
        assertThat(status.imports().lastAttempt().errorCode()).isEqualTo("SCHEMA_MISMATCH");
        assertThat(status.artifacts().addressRegistry()).isNotNull();
        assertThat(status.warnings()).contains("ADDRESS_REGISTRY_LAST_IMPORT_FAILED");
    }

    @Test
    void sourceOutageDoesNotImmediatelyFailReadinessUnderLocalPrivatePolicy() {
        PipelineStatus.RunMetric failed = run("FAILED", Map.of("TIMEOUT", 3L), NOW.minusSeconds(120));
        when(repository.read()).thenReturn(evidence(failed, success("SUCCEEDED", Map.of())));

        PipelineStatus status = service().status();

        assertThat(status.ready()).isTrue();
        assertThat(status.policy().externalSourceOutageImmediatelyAffectsReadiness()).isFalse();
        assertThat(status.sync().externalSourceState()).isEqualTo("OUTAGE");
        assertThat(status.warnings()).contains("EXTERNAL_SOURCE_OUTAGE_SERVING_LAST_GOOD");
    }

    @Test
    void databaseMigrationAndBasemapGatesFailClosed() {
        PersistedEvidence base = evidence(success("SUCCEEDED", Map.of()), success("SUCCEEDED", Map.of()));
        when(repository.read()).thenReturn(new PersistedEvidence(
                new DatabaseEvidence(true, "13", "14", false),
                base.lastSyncAttempt(), base.lastSuccessfulSync(),
                base.lastEnrichmentAttempt(), base.lastSuccessfulEnrichment(), base.backlog(),
                base.enrichmentStateDistribution(), base.qualityParserVersion(), base.parserResults(),
                base.precisionDistribution(), base.resolutionSourceDistribution(), base.retryErrorClasses(),
                base.activeImport(), base.lastImportAttempt(), base.lastSuccessfulImport(),
                base.lastRetentionJob(), base.addressRegistryArtifact(), base.resolverArtifact()));
        when(basemap.status()).thenReturn(new BasemapStatus(
                false, "UNAVAILABLE", null, null, null, null, null, NOW, "ACTIVE_POINTER_MISSING"));

        PipelineStatus status = service().status();

        assertThat(status.ready()).isFalse();
        assertThat(status.state()).isEqualTo("UNAVAILABLE");
        assertThat(status.readinessFailures())
                .containsExactly("MIGRATIONS_NOT_CURRENT", "BASEMAP_UNAVAILABLE");
    }

    @Test
    void terminalEnrichmentEvidenceAndActiveImportAreInformationalNotStaleData() {
        PersistedEvidence base = evidence(success("SUCCEEDED", Map.of()), success("SUCCEEDED", Map.of()));
        PipelineStatus.ImportRunMetric active = new PipelineStatus.ImportRunMetric(
                IMPORT_ID, "IMPORT", "RUNNING", NOW.minusSeconds(30), null,
                null, null, "2026-08-25", null, null, null);
        when(repository.read()).thenReturn(new PersistedEvidence(
                base.database(), base.lastSyncAttempt(), base.lastSuccessfulSync(),
                base.lastEnrichmentAttempt(), base.lastSuccessfulEnrichment(), base.backlog(),
                Map.of("SUCCEEDED", 588L, "PERMANENT_FAILURE", 1L),
                base.qualityParserVersion(), base.parserResults(), base.precisionDistribution(),
                base.resolutionSourceDistribution(), Map.of("UNRESOLVABLE_ADDRESS", 1L),
                active, base.lastImportAttempt(), base.lastSuccessfulImport(),
                base.lastRetentionJob(), base.addressRegistryArtifact(), base.resolverArtifact()));

        PipelineStatus status = service().status();

        assertThat(status.state()).isEqualTo("FRESH_AND_HEALTHY");
        assertThat(status.servingLastGoodData()).isFalse();
        assertThat(status.warnings()).isEmpty();
        assertThat(status.notices()).containsExactly(
                "ENRICHMENT_ERROR_EVIDENCE_RETAINED", "ADDRESS_REGISTRY_IMPORT_RUNNING");
    }

    @Test
    void retryableEnrichmentWorkIsAStateDemotingWarning() {
        PersistedEvidence base = evidence(success("SUCCEEDED", Map.of()), success("SUCCEEDED", Map.of()));
        when(repository.read()).thenReturn(new PersistedEvidence(
                base.database(), base.lastSyncAttempt(), base.lastSuccessfulSync(),
                base.lastEnrichmentAttempt(), base.lastSuccessfulEnrichment(), base.backlog(),
                Map.of("SUCCEEDED", 588L, "RETRYABLE_FAILURE", 1L),
                base.qualityParserVersion(), base.parserResults(), base.precisionDistribution(),
                base.resolutionSourceDistribution(), Map.of("SOURCE_TEMPORARY", 1L),
                base.activeImport(), base.lastImportAttempt(), base.lastSuccessfulImport(),
                base.lastRetentionJob(), base.addressRegistryArtifact(), base.resolverArtifact()));

        PipelineStatus status = service().status();

        assertThat(status.state()).isEqualTo("SERVING_LAST_GOOD_DATA");
        assertThat(status.warnings()).containsExactly("ENRICHMENT_RETRYABLE_FAILURES_PENDING");
        assertThat(status.notices()).containsExactly("ENRICHMENT_ERROR_EVIDENCE_RETAINED");
    }

    @Test
    void transactionAcquisitionFailureReturnsExplicitDatabaseUnavailableStatus() {
        when(repository.read()).thenThrow(new CannotCreateTransactionException("password=hunter2"));

        PipelineStatus status = service().status();

        assertThat(status.state()).isEqualTo("UNAVAILABLE");
        assertThat(status.ready()).isFalse();
        assertThat(status.readinessFailures()).containsExactly("DATABASE_UNAVAILABLE");
        assertThat(status.database().available()).isFalse();
    }

    private PipelineStatusService service() {
        return new PipelineStatusService(
                repository, basemap, properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static PipelineStatus.RunMetric success(String status, Map<String, Long> errors) {
        return run(status, errors, NOW.minusSeconds(3_600));
    }

    private static PipelineStatus.RunMetric run(
            String status, Map<String, Long> errors, Instant finishedAt) {
        return new PipelineStatus.RunMetric(
                SYNC_ID, "SCHEDULED", status, "COMPLETED",
                finishedAt.minusSeconds(300), finishedAt, 300_000L,
                589, 2L, 590, 0, 1, 10, 0, 2,
                errors.values().stream().mapToLong(Long::longValue).sum(),
                "SUCCEEDED".equals(status) ? 0 : 1,
                "SUCCEEDED".equals(status) ? new PipelineStatus.SnapshotChanges(2, 3, 584) : null,
                errors);
    }

    private static PersistedEvidence evidence(
            PipelineStatus.RunMetric attempt,
            PipelineStatus.RunMetric successful) {
        PipelineStatus.EnrichmentRunMetric enrichment = new PipelineStatus.EnrichmentRunMetric(
                UUID.fromString("33333333-3333-4333-8333-333333333333"),
                "SCHEDULED", "SUCCEEDED", NOW.minusSeconds(1_800), NOW.minusSeconds(1_700),
                100_000L, "parser-v1", "resolver-v1", "dataset-v1",
                5, 5, 5, 0, 0, 0, 0, 0);
        PipelineStatus.ImportRunMetric imported = new PipelineStatus.ImportRunMetric(
                IMPORT_ID, "IMPORT", "SUCCEEDED", NOW.minusSeconds(7_200), NOW.minusSeconds(7_100),
                100_000L, UUID.fromString("44444444-4444-4444-8444-444444444444"),
                "2026-08-25", 100L, 100L, null);
        return new PersistedEvidence(
                new DatabaseEvidence(true, "14", "14", true),
                attempt, successful, enrichment, enrichment,
                new Backlog(0, null), Map.of("SUCCEEDED", 589L), "parser-v1",
                Map.of("EXTRACTED", 589L), Map.of("PARCEL", 587L, "NONE", 2L),
                Map.of("coarse:centroids", 589L), Map.of(), null, imported, imported,
                null,
                new PipelineStatus.AddressRegistryArtifact(
                        imported.snapshotId(), "2026-08-25", "b".repeat(64), 100, NOW.minusSeconds(7_000)),
                new PipelineStatus.ResolverArtifact(
                        UUID.fromString("55555555-5555-4555-8555-555555555555"),
                        NOW.minusSeconds(1_600), "resolver-v1", "dataset-v1", "c".repeat(64),
                        589, 2));
    }

    private static PersistedEvidence copy(
            PersistedEvidence base,
            Backlog backlog,
            PipelineStatus.ImportRunMetric lastImportAttempt) {
        return new PersistedEvidence(
                base.database(), base.lastSyncAttempt(), base.lastSuccessfulSync(),
                base.lastEnrichmentAttempt(), base.lastSuccessfulEnrichment(), backlog,
                base.enrichmentStateDistribution(), base.qualityParserVersion(), base.parserResults(),
                base.precisionDistribution(), base.resolutionSourceDistribution(), base.retryErrorClasses(),
                base.activeImport(), lastImportAttempt, base.lastSuccessfulImport(),
                base.lastRetentionJob(), base.addressRegistryArtifact(), base.resolverArtifact());
    }
}
