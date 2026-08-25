package rs.sud.eaukcija.refresh;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;

import rs.sud.eaukcija.coarselocation.CoarseLocationResolutionService;
import rs.sud.eaukcija.enrichment.EnrichmentRunClaim;
import rs.sud.eaukcija.enrichment.EnrichmentRunStatus;
import rs.sud.eaukcija.enrichment.EnrichmentRunView;
import rs.sud.eaukcija.enrichment.EnrichmentSelector;
import rs.sud.eaukcija.enrichment.EnrichmentService;
import rs.sud.eaukcija.enrichment.EnrichmentTriggerKind;
import rs.sud.eaukcija.enrichment.EnrichmentVersions;
import rs.sud.eaukcija.map.MapDataStatus;
import rs.sud.eaukcija.map.MapDataStatusService;
import rs.sud.eaukcija.service.SyncService;
import rs.sud.eaukcija.sync.persistence.SyncRunClaimResult;
import rs.sud.eaukcija.sync.persistence.SyncRunStage;
import rs.sud.eaukcija.sync.persistence.SyncRunStatus;
import rs.sud.eaukcija.sync.persistence.SyncRunView;
import rs.sud.eaukcija.sync.persistence.SyncTriggerKind;

class RefreshCoordinatorTest {

    private static final Instant NOW = Instant.parse("2026-08-25T12:00:00Z");
    private static final UUID WORKFLOW = UUID.fromString("40000000-0000-4000-8000-000000000040");
    private static final UUID SOURCE = UUID.fromString("17000000-0000-4000-8000-000000000017");
    private static final UUID ENRICHMENT = UUID.fromString("29000000-0000-4000-8000-000000000029");
    private static final UUID MAP_RUN = UUID.fromString("38000000-0000-4000-8000-000000000038");
    private static final EnrichmentVersions VERSIONS =
            new EnrichmentVersions("parser-v1", "resolver-v1", "dataset-v1");

    private RefreshRepository repository;
    private SyncService sync;
    private EnrichmentService enrichment;
    private CoarseLocationResolutionService coarse;
    private MapDataStatusService mapStatus;
    private RefreshCoordinator coordinator;

    @BeforeEach
    void setUp() {
        RefreshProperties properties = new RefreshProperties();
        properties.setScheduleCron("-");
        properties.setPollInterval(java.time.Duration.ofMillis(1));
        repository = mock(RefreshRepository.class);
        sync = mock(SyncService.class);
        enrichment = mock(EnrichmentService.class);
        coarse = mock(CoarseLocationResolutionService.class);
        mapStatus = mock(MapDataStatusService.class);
        TaskExecutor direct = Runnable::run;
        coordinator = new RefreshCoordinator(
                properties, repository, sync, enrichment, coarse, mapStatus,
                direct, Clock.fixed(NOW, ZoneOffset.UTC));
        when(sync.isEnabled()).thenReturn(true);
        when(enrichment.isEnabled()).thenReturn(true);
    }

    @Test
    void completesOnlyAfterCorrelatedSourceEnrichmentAndMapEvidence() {
        RefreshRunView claimed = workflow(null, null, null);
        RefreshRunView sourceLinked = workflow(SOURCE, null, VERSIONS);
        when(repository.claim(any(), eq(RefreshTriggerKind.MANUAL), any()))
                .thenReturn(new RefreshClaim(WORKFLOW, false, false));
        when(repository.find(WORKFLOW))
                .thenReturn(Optional.of(claimed), Optional.of(sourceLinked), Optional.of(sourceLinked));
        when(sync.startManual(any())).thenReturn(new SyncRunClaimResult(SOURCE, false));
        when(sync.findRun(SOURCE)).thenReturn(Optional.of(syncRun(SyncRunStatus.SUCCEEDED)));
        when(enrichment.activeVersions()).thenReturn(VERSIONS);
        when(enrichment.startForSource(any(), eq(SOURCE), eq(EnrichmentTriggerKind.MANUAL)))
                .thenReturn(new EnrichmentRunClaim(ENRICHMENT, false));
        when(enrichment.findRun(ENRICHMENT)).thenReturn(Optional.of(enrichmentRun()));
        when(repository.sourceIsFullyEnriched(SOURCE, VERSIONS)).thenReturn(true);
        CoarseLocationResolutionService.RunResult prepared =
                mock(CoarseLocationResolutionService.RunResult.class);
        when(prepared.runId()).thenReturn(MAP_RUN);
        when(coarse.run(WORKFLOW)).thenReturn(prepared);
        MapDataStatus ready = new MapDataStatus(
                true, "AVAILABLE", "coarse/centroids/hash", NOW, false,
                10, 9, Map.of("CADASTRAL_MUNICIPALITY", 9L, "NONE", 1L),
                MAP_RUN, WORKFLOW, null);
        when(mapStatus.status()).thenReturn(
                new MapDataStatus(false, "UNAVAILABLE", null, null, true, 0, 0,
                        Map.of(), null, null, "NO_SUCCESSFUL_MAP_SYNC"),
                ready);

        RefreshClaim result = coordinator.startManual(UUID.randomUUID());

        org.assertj.core.api.Assertions.assertThat(result.workflowId()).isEqualTo(WORKFLOW);
        verify(repository).linkSourceRun(WORKFLOW, SOURCE);
        verify(repository).pinEnrichmentVersions(WORKFLOW, VERSIONS);
        verify(repository).linkEnrichmentRun(WORKFLOW, ENRICHMENT);
        verify(repository).markPreparingMap(WORKFLOW);
        verify(repository).complete(WORKFLOW, ready);
        verify(repository, never()).fail(eq(WORKFLOW), any());
    }

    @Test
    void partialSourceNeverStartsEnrichment() {
        when(repository.claim(any(), eq(RefreshTriggerKind.MANUAL), any()))
                .thenReturn(new RefreshClaim(WORKFLOW, false, false));
        when(repository.find(WORKFLOW)).thenReturn(Optional.of(workflow(null, null, null)));
        when(sync.startManual(any())).thenReturn(new SyncRunClaimResult(SOURCE, false));
        when(sync.findRun(SOURCE)).thenReturn(Optional.of(syncRun(SyncRunStatus.PARTIAL)));

        coordinator.startManual(UUID.randomUUID());

        verify(repository).fail(WORKFLOW, "SOURCE_SYNC_FAILED");
        verify(enrichment, never()).startForSource(any(), any(), any());
        verify(coarse, never()).run(any(UUID.class));
    }

    @Test
    void detailFailureIsAttributedAndNeverStartsEnrichment() {
        SyncRunView failedDetails = mock(SyncRunView.class);
        when(failedDetails.status()).thenReturn(SyncRunStatus.PARTIAL);
        when(failedDetails.stage()).thenReturn(SyncRunStage.COMPLETED);
        when(failedDetails.detailsRequired()).thenReturn(2L);
        when(failedDetails.detailsAttempted()).thenReturn(2L);
        when(failedDetails.detailsSucceeded()).thenReturn(1L);
        when(failedDetails.detailsFailed()).thenReturn(1L);
        when(repository.claim(any(), eq(RefreshTriggerKind.MANUAL), any()))
                .thenReturn(new RefreshClaim(WORKFLOW, false, false));
        when(repository.find(WORKFLOW)).thenReturn(Optional.of(workflow(null, null, null)));
        when(sync.startManual(any())).thenReturn(new SyncRunClaimResult(SOURCE, false));
        when(sync.findRun(SOURCE)).thenReturn(Optional.of(failedDetails));

        coordinator.startManual(UUID.randomUUID());

        verify(repository).fail(WORKFLOW, "SOURCE_DETAILS_FAILED");
        verify(enrichment, never()).startForSource(any(), any(), any());
        verify(coarse, never()).run(any(UUID.class));
    }

    @Test
    void failedEnrichmentCannotPrepareOrTimestampTheMap() {
        RefreshRunView claimed = workflow(null, null, null);
        RefreshRunView sourceLinked = workflow(SOURCE, null, VERSIONS);
        when(repository.claim(any(), eq(RefreshTriggerKind.MANUAL), any()))
                .thenReturn(new RefreshClaim(WORKFLOW, false, false));
        when(repository.find(WORKFLOW))
                .thenReturn(Optional.of(claimed), Optional.of(sourceLinked));
        when(sync.startManual(any())).thenReturn(new SyncRunClaimResult(SOURCE, false));
        when(sync.findRun(SOURCE)).thenReturn(Optional.of(syncRun(SyncRunStatus.SUCCEEDED)));
        when(enrichment.activeVersions()).thenReturn(VERSIONS);
        when(enrichment.startForSource(any(), eq(SOURCE), any()))
                .thenReturn(new EnrichmentRunClaim(ENRICHMENT, false));
        when(enrichment.findRun(ENRICHMENT)).thenReturn(Optional.of(
                enrichmentRun(EnrichmentRunStatus.FAILED, VERSIONS)));

        coordinator.startManual(UUID.randomUUID());

        verify(repository).fail(WORKFLOW, "ENRICHMENT_FAILED");
        verify(repository, never()).markPreparingMap(any());
        verify(repository, never()).complete(any(), any());
        verify(coarse, never()).run(any(UUID.class));
    }

    @Test
    void activeVersionChangeAfterEnrichmentFailsClosedBeforeMapPreparation() {
        EnrichmentVersions changed =
                new EnrichmentVersions("parser-v2", "resolver-v1", "dataset-v1");
        RefreshRunView claimed = workflow(null, null, null);
        RefreshRunView sourceLinked = workflow(SOURCE, null, VERSIONS);
        when(repository.claim(any(), eq(RefreshTriggerKind.MANUAL), any()))
                .thenReturn(new RefreshClaim(WORKFLOW, false, false));
        when(repository.find(WORKFLOW))
                .thenReturn(Optional.of(claimed), Optional.of(sourceLinked));
        when(sync.startManual(any())).thenReturn(new SyncRunClaimResult(SOURCE, false));
        when(sync.findRun(SOURCE)).thenReturn(Optional.of(syncRun(SyncRunStatus.SUCCEEDED)));
        when(enrichment.activeVersions()).thenReturn(VERSIONS, changed);
        when(enrichment.startForSource(any(), eq(SOURCE), any()))
                .thenReturn(new EnrichmentRunClaim(ENRICHMENT, false));
        when(enrichment.findRun(ENRICHMENT)).thenReturn(Optional.of(enrichmentRun()));

        coordinator.startManual(UUID.randomUUID());

        verify(repository).fail(WORKFLOW, "ENRICHMENT_ACTIVE_VERSION_CHANGED");
        verify(repository, never()).sourceIsFullyEnriched(any(), any());
        verify(repository, never()).markPreparingMap(any());
        verify(coarse, never()).run(any(UUID.class));
    }

    @Test
    void mapStatusMismatchCannotProduceFalseSuccess() {
        RefreshRunView claimed = workflow(null, null, null);
        RefreshRunView sourceLinked = workflow(SOURCE, null, VERSIONS);
        when(repository.claim(any(), eq(RefreshTriggerKind.MANUAL), any()))
                .thenReturn(new RefreshClaim(WORKFLOW, false, false));
        when(repository.find(WORKFLOW))
                .thenReturn(Optional.of(claimed), Optional.of(sourceLinked), Optional.of(sourceLinked));
        when(sync.startManual(any())).thenReturn(new SyncRunClaimResult(SOURCE, false));
        when(sync.findRun(SOURCE)).thenReturn(Optional.of(syncRun(SyncRunStatus.SUCCEEDED)));
        when(enrichment.activeVersions()).thenReturn(VERSIONS);
        when(enrichment.startForSource(any(), eq(SOURCE), any()))
                .thenReturn(new EnrichmentRunClaim(ENRICHMENT, false));
        when(enrichment.findRun(ENRICHMENT)).thenReturn(Optional.of(enrichmentRun()));
        when(repository.sourceIsFullyEnriched(SOURCE, VERSIONS)).thenReturn(true);
        CoarseLocationResolutionService.RunResult prepared =
                mock(CoarseLocationResolutionService.RunResult.class);
        when(prepared.runId()).thenReturn(MAP_RUN);
        when(coarse.run(WORKFLOW)).thenReturn(prepared);
        when(mapStatus.status()).thenReturn(new MapDataStatus(
                        false, "UNAVAILABLE", null, null, true, 0, 0,
                        Map.of(), null, null, "NO_SUCCESSFUL_MAP_SYNC"),
                new MapDataStatus(true, "AVAILABLE", "stale-map", NOW.minusSeconds(90_000),
                        true, 10, 9, Map.of("CADASTRAL_MUNICIPALITY", 9L, "NONE", 1L),
                        MAP_RUN, WORKFLOW, "STALE_MAP_DATA"));

        coordinator.startManual(UUID.randomUUID());

        verify(repository).fail(WORKFLOW, "MAP_STATUS_MISMATCH");
        verify(repository, never()).complete(any(), any());
    }

    @Test
    void duplicateClickAttachesWithoutSubmittingAnotherCoordinatorTask() {
        when(repository.claim(any(), eq(RefreshTriggerKind.MANUAL), any()))
                .thenReturn(new RefreshClaim(WORKFLOW, true, false));

        RefreshClaim claim = coordinator.startManual(UUID.randomUUID());

        org.assertj.core.api.Assertions.assertThat(claim.alreadyRunning()).isTrue();
        verify(repository, never()).find(WORKFLOW);
        verify(sync, never()).startManual(any());
    }

    @Test
    void restartReconcilesAlreadyPreparedMapWithoutDuplicatingResolutionWork() {
        RefreshRunView resumed = workflow(SOURCE, ENRICHMENT, VERSIONS);
        when(repository.findActive()).thenReturn(Optional.of(resumed));
        when(repository.find(WORKFLOW))
                .thenReturn(Optional.of(resumed), Optional.of(resumed), Optional.of(resumed));
        when(sync.findRun(SOURCE)).thenReturn(Optional.of(syncRun(SyncRunStatus.SUCCEEDED)));
        when(enrichment.findRun(ENRICHMENT)).thenReturn(Optional.of(enrichmentRun()));
        when(enrichment.activeVersions()).thenReturn(VERSIONS);
        when(repository.sourceIsFullyEnriched(SOURCE, VERSIONS)).thenReturn(true);
        MapDataStatus retained = new MapDataStatus(
                true, "AVAILABLE", "map-v1", NOW, false, 10, 9,
                Map.of("CADASTRAL_MUNICIPALITY", 9L, "NONE", 1L),
                MAP_RUN, WORKFLOW, null);
        when(mapStatus.status()).thenReturn(retained);

        coordinator.recoverActiveWorkflow();

        verify(repository).complete(WORKFLOW, retained);
        verify(coarse, never()).run(WORKFLOW);
    }

    @Test
    void staleSourceHeartbeatFailsTheWorkflowInsteadOfPollingForever() {
        SyncRunView stalled = mock(SyncRunView.class);
        when(stalled.status()).thenReturn(SyncRunStatus.RUNNING);
        when(stalled.heartbeatAt()).thenReturn(NOW.minus(Duration.ofMinutes(16)));
        when(repository.claim(any(), eq(RefreshTriggerKind.MANUAL), any()))
                .thenReturn(new RefreshClaim(WORKFLOW, false, false));
        when(repository.find(WORKFLOW)).thenReturn(Optional.of(workflow(null, null, null)));
        when(sync.startManual(any())).thenReturn(new SyncRunClaimResult(SOURCE, false));
        when(sync.findRun(SOURCE)).thenReturn(Optional.of(stalled));

        coordinator.startManual(UUID.randomUUID());

        verify(repository).fail(WORKFLOW, "SOURCE_STALLED");
        verify(repository, never()).updateSourceProgress(any(), any(), any());
        verify(enrichment, never()).startForSource(any(), any(), any());
    }

    @Test
    void staleEnrichmentHeartbeatFailsTheWorkflowInsteadOfPollingForever() {
        RefreshRunView claimed = workflow(null, null, null);
        RefreshRunView sourceLinked = workflow(SOURCE, null, VERSIONS);
        EnrichmentRunView stalled = mock(EnrichmentRunView.class);
        when(stalled.status()).thenReturn(EnrichmentRunStatus.RUNNING);
        when(stalled.heartbeatAt()).thenReturn(NOW.minus(Duration.ofMinutes(16)));
        when(repository.claim(any(), eq(RefreshTriggerKind.MANUAL), any()))
                .thenReturn(new RefreshClaim(WORKFLOW, false, false));
        when(repository.find(WORKFLOW)).thenReturn(Optional.of(claimed), Optional.of(sourceLinked));
        when(sync.startManual(any())).thenReturn(new SyncRunClaimResult(SOURCE, false));
        when(sync.findRun(SOURCE)).thenReturn(Optional.of(syncRun(SyncRunStatus.SUCCEEDED)));
        when(enrichment.activeVersions()).thenReturn(VERSIONS);
        when(enrichment.startForSource(any(), eq(SOURCE), any()))
                .thenReturn(new EnrichmentRunClaim(ENRICHMENT, false));
        when(enrichment.findRun(ENRICHMENT)).thenReturn(Optional.of(stalled));

        coordinator.startManual(UUID.randomUUID());

        verify(repository).fail(WORKFLOW, "ENRICHMENT_STALLED");
        verify(repository, never()).updateEnrichmentProgress(any(), any());
        verify(coarse, never()).run(any(UUID.class));
    }

    @Test
    void anErrorCannotLeaveTheClaimedWorkflowRunning() {
        when(repository.claim(any(), eq(RefreshTriggerKind.MANUAL), any()))
                .thenReturn(new RefreshClaim(WORKFLOW, false, false));
        when(repository.find(WORKFLOW)).thenReturn(Optional.of(workflow(null, null, null)));
        when(sync.startManual(any())).thenThrow(new AssertionError("worker escaped"));

        assertThatThrownBy(() -> coordinator.startManual(UUID.randomUUID()))
                .isInstanceOf(AssertionError.class);

        verify(repository).fail(WORKFLOW, "REFRESH_INTERNAL");
    }

    @Test
    void rejectedStartupRecoveryIsTerminalAndRetryable() {
        RefreshProperties properties = new RefreshProperties();
        properties.setScheduleCron("-");
        TaskExecutor rejecting = task -> {
            throw new RejectedExecutionException("shutting down");
        };
        RefreshCoordinator rejectingCoordinator = new RefreshCoordinator(
                properties, repository, sync, enrichment, coarse, mapStatus,
                rejecting, Clock.fixed(NOW, ZoneOffset.UTC));
        when(repository.findActive()).thenReturn(Optional.of(workflow(null, null, null)));

        rejectingCoordinator.recoverActiveWorkflow();

        verify(repository).fail(WORKFLOW, "REFRESH_EXECUTOR_UNAVAILABLE");
    }

    @Test
    void statusReadReconcilesAnExpiredRetainedWorkflow() {
        RefreshRunView recovered = new RefreshRunView(
                WORKFLOW, RefreshTriggerKind.MANUAL, RefreshStatus.FAILED,
                RefreshStage.DOWNLOAD_LISTINGS, NOW.minusSeconds(1_000), NOW, NOW,
                null, null, null, null, 0, 0, 0, 0, 0, 0, 0, 0,
                Map.of(), null, null, "REFRESH_STALE_RECLAIMED");
        when(repository.find(WORKFLOW)).thenReturn(Optional.of(recovered));

        RefreshWorkflowState state = coordinator.findState(WORKFLOW).orElseThrow();

        verify(repository).recoverIfStale(WORKFLOW, Duration.ofMinutes(15));
        org.assertj.core.api.Assertions.assertThat(state.status()).isEqualTo("FAILED");
        org.assertj.core.api.Assertions.assertThat(state.failureMessage())
                .isEqualTo("Претходно освежавање је прекинуто. Покушајте поново.");
    }

    private static RefreshRunView workflow(
            UUID sourceRunId, UUID enrichmentRunId, EnrichmentVersions versions) {
        return new RefreshRunView(
                WORKFLOW, RefreshTriggerKind.MANUAL, RefreshStatus.RUNNING,
                sourceRunId == null ? RefreshStage.DOWNLOAD_LISTINGS : RefreshStage.PROCESS_LOCATIONS,
                NOW.minusSeconds(10), NOW, null, sourceRunId, enrichmentRunId, null,
                versions, 1, 1, 1, 1, 1, 1, 0, 0, Map.of(), null, null, null);
    }

    private static SyncRunView syncRun(SyncRunStatus status) {
        return new SyncRunView(
                SOURCE, SyncTriggerKind.MANUAL, status, SyncRunStage.COMPLETED,
                NOW.minusSeconds(9), NOW, NOW, List.of(7, 8), 3000,
                "a".repeat(64), NOW.minusSeconds(9), 1, 1,
                1, 0, 1, 0, 0, 1, 1, 1, 0, 0, 0, 0, 0);
    }

    private static EnrichmentRunView enrichmentRun() {
        return enrichmentRun(EnrichmentRunStatus.SUCCEEDED, VERSIONS);
    }

    private static EnrichmentRunView enrichmentRun(
            EnrichmentRunStatus status, EnrichmentVersions versions) {
        return new EnrichmentRunView(
                ENRICHMENT, EnrichmentTriggerKind.MANUAL, status,
                NOW.minusSeconds(5), NOW, NOW, versions,
                EnrichmentSelector.none(), 1000, 1, 1, 1, 0, 0, 0, 0, 0);
    }
}
