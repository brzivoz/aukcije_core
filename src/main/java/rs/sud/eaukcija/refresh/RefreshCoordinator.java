package rs.sud.eaukcija.refresh;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.context.annotation.Profile;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import rs.sud.eaukcija.coarselocation.CoarseLocationResolutionService;
import rs.sud.eaukcija.enrichment.EnrichmentAlreadyRunningException;
import rs.sud.eaukcija.enrichment.EnrichmentRunClaim;
import rs.sud.eaukcija.enrichment.EnrichmentRunStatus;
import rs.sud.eaukcija.enrichment.EnrichmentRunView;
import rs.sud.eaukcija.enrichment.EnrichmentService;
import rs.sud.eaukcija.enrichment.EnrichmentTriggerKind;
import rs.sud.eaukcija.enrichment.EnrichmentVersions;
import rs.sud.eaukcija.map.MapDataStatus;
import rs.sud.eaukcija.map.MapDataStatusService;
import rs.sud.eaukcija.service.SyncService;
import rs.sud.eaukcija.sync.persistence.SyncAlreadyRunningException;
import rs.sud.eaukcija.sync.persistence.SyncRunClaimResult;
import rs.sud.eaukcija.sync.persistence.SyncRunStage;
import rs.sud.eaukcija.sync.persistence.SyncRunStatus;
import rs.sud.eaukcija.sync.persistence.SyncRunView;

/** Spring-managed, durable source-to-map orchestration for issue #40. */
@Service
@Profile("!local-h2")
public class RefreshCoordinator {

    private static final Logger log = LoggerFactory.getLogger(RefreshCoordinator.class);

    private final RefreshProperties properties;
    private final RefreshRepository repository;
    private final SyncService syncService;
    private final EnrichmentService enrichmentService;
    private final CoarseLocationResolutionService coarseLocations;
    private final MapDataStatusService mapStatusService;
    private final TaskExecutor executor;
    private final Clock clock;
    private final Set<UUID> executingWorkflows = ConcurrentHashMap.newKeySet();

    @Autowired
    public RefreshCoordinator(
            RefreshProperties properties,
            RefreshRepository repository,
            SyncService syncService,
            EnrichmentService enrichmentService,
            CoarseLocationResolutionService coarseLocations,
            MapDataStatusService mapStatusService,
            @Qualifier("refreshCoordinatorExecutor") TaskExecutor executor) {
        this(properties, repository, syncService, enrichmentService, coarseLocations,
                mapStatusService, executor, Clock.systemUTC());
    }

    RefreshCoordinator(
            RefreshProperties properties,
            RefreshRepository repository,
            SyncService syncService,
            EnrichmentService enrichmentService,
            CoarseLocationResolutionService coarseLocations,
            MapDataStatusService mapStatusService,
            TaskExecutor executor,
            Clock clock) {
        this.properties = properties;
        this.repository = repository;
        this.syncService = syncService;
        this.enrichmentService = enrichmentService;
        this.coarseLocations = coarseLocations;
        this.mapStatusService = mapStatusService;
        this.executor = executor;
        this.clock = clock;
    }

    public RefreshClaim startManual(UUID idempotencyKey) {
        return start(idempotencyKey, RefreshTriggerKind.MANUAL);
    }

    public RefreshClaim startScheduled(UUID idempotencyKey) {
        return start(idempotencyKey, RefreshTriggerKind.SCHEDULED);
    }

    public Optional<RefreshWorkflowState> findState(UUID workflowId) {
        if (!executingWorkflows.contains(workflowId)) {
            repository.recoverIfStale(workflowId, properties.getRunningStaleAfter());
        }
        return repository.find(workflowId).map(this::state);
    }

    public RefreshWorkflowState latestState() {
        repository.findActive()
                .filter(run -> !executingWorkflows.contains(run.workflowId()))
                .ifPresent(run -> repository.recoverIfStale(
                        run.workflowId(), properties.getRunningStaleAfter()));
        return state(repository.findLatest().orElse(null));
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverActiveWorkflow() {
        if (!properties.isEnabled()) {
            return;
        }
        repository.recoverStaleActive(properties.getRunningStaleAfter());
        repository.findActive().ifPresent(run -> submit(run.workflowId(), false));
    }

    private RefreshClaim start(UUID idempotencyKey, RefreshTriggerKind triggerKind) {
        if (!properties.isEnabled() || !syncService.isEnabled() || !enrichmentService.isEnabled()) {
            throw new RefreshUnavailableException();
        }
        RefreshClaim claim = repository.claim(
                idempotencyKey, triggerKind, properties.getRunningStaleAfter());
        if (!claim.alreadyRunning() && !claim.replayed()) {
            submit(claim.workflowId(), true);
        }
        return claim;
    }

    private void submit(UUID workflowId, boolean propagateRejection) {
        try {
            executor.execute(() -> execute(workflowId));
        } catch (RejectedExecutionException rejected) {
            repository.fail(workflowId, "REFRESH_EXECUTOR_UNAVAILABLE");
            if (propagateRejection) {
                throw new RefreshSubmissionException(workflowId);
            }
            log.error("Refresh recovery submission failed workflowId={} code=REFRESH_EXECUTOR_UNAVAILABLE",
                    workflowId);
        } catch (RuntimeException rejected) {
            repository.fail(workflowId, "REFRESH_EXECUTOR_UNAVAILABLE");
            if (propagateRejection) {
                throw new RefreshSubmissionException(workflowId);
            }
            log.error("Refresh recovery submission failed workflowId={} code=REFRESH_EXECUTOR_UNAVAILABLE",
                    workflowId);
        }
    }

    private void execute(UUID workflowId) {
        if (!executingWorkflows.add(workflowId)) {
            return;
        }
        try {
            RefreshRunView workflow = running(workflowId);
            SyncRunView source = source(workflow);
            if (source.status() != SyncRunStatus.SUCCEEDED) {
                fail(workflowId, sourceFailure(source));
                return;
            }

            EnrichmentVersions versions = workflow.enrichmentVersions();
            if (versions == null) {
                versions = enrichmentService.activeVersions();
                repository.pinEnrichmentVersions(workflowId, versions);
            }
            EnrichmentRunView enrichment = enrichment(running(workflowId), versions);
            if (enrichment.status() != EnrichmentRunStatus.SUCCEEDED) {
                fail(workflowId, enrichmentFailure(enrichment));
                return;
            }
            if (!versions.equals(enrichment.versions())
                    || !versions.equals(enrichmentService.activeVersions())) {
                fail(workflowId, "ENRICHMENT_ACTIVE_VERSION_CHANGED");
                return;
            }
            UUID sourceRunId = running(workflowId).sourceSyncRunId();
            if (!repository.sourceIsFullyEnriched(sourceRunId, versions)) {
                fail(workflowId, "ENRICHMENT_SOURCE_MISMATCH");
                return;
            }

            repository.markPreparingMap(workflowId);
            MapDataStatus alreadyPrepared = mapStatusService.status();
            if (mapReadyFor(workflowId, alreadyPrepared)) {
                repository.complete(workflowId, alreadyPrepared);
                return;
            }
            CoarseLocationResolutionService.RunResult prepared = coarseLocations.run(workflowId);
            if (prepared.runId() == null) {
                fail(workflowId, "MAP_POPULATION_EMPTY");
                return;
            }
            MapDataStatus status = mapStatusService.status();
            if (!mapReadyFor(workflowId, status)
                    || !prepared.runId().equals(status.successfulResolutionRunId())
                    ) {
                fail(workflowId, "MAP_STATUS_MISMATCH");
                return;
            }
            repository.complete(workflowId, status);
            log.info("Refresh workflow completed workflowId={} sourceRunId={} enrichmentRunId={} mapRunId={}",
                    workflowId, sourceRunId, enrichment.runId(), prepared.runId());
        } catch (RefreshInterrupted interrupted) {
            Thread.currentThread().interrupt();
            log.info("Refresh workflow left recoverable workflowId={} code=REFRESH_INTERRUPTED", workflowId);
        } catch (SyncAlreadyRunningException busy) {
            fail(workflowId, "SOURCE_SYNC_BUSY");
        } catch (EnrichmentAlreadyRunningException busy) {
            fail(workflowId, "ENRICHMENT_BUSY");
        } catch (RefreshStageStalled stalled) {
            fail(workflowId, stalled.code);
        } catch (RuntimeException failure) {
            fail(workflowId, "REFRESH_INTERNAL");
            log.error("Refresh workflow failed workflowId={} code=REFRESH_INTERNAL", workflowId);
        } catch (Error failure) {
            fail(workflowId, "REFRESH_INTERNAL");
            log.error("Refresh workflow failed workflowId={} code=REFRESH_INTERNAL", workflowId);
            throw failure;
        } finally {
            executingWorkflows.remove(workflowId);
        }
    }

    private SyncRunView source(RefreshRunView workflow) {
        UUID sourceRunId = workflow.sourceSyncRunId();
        if (sourceRunId == null) {
            SyncRunClaimResult claim = workflow.triggerKind() == RefreshTriggerKind.SCHEDULED
                    ? syncService.startScheduled(stageKey(workflow.workflowId(), "source"))
                    : syncService.startManual(stageKey(workflow.workflowId(), "source"));
            sourceRunId = claim.runId();
            repository.linkSourceRun(workflow.workflowId(), sourceRunId);
        }
        while (true) {
            SyncRunView run = syncService.findRun(sourceRunId)
                    .orElseThrow(() -> new IllegalStateException("source run evidence is missing"));
            if (run.status() == SyncRunStatus.RUNNING && heartbeatExpired(run.heartbeatAt())) {
                throw new RefreshStageStalled("SOURCE_STALLED");
            }
            repository.updateSourceProgress(
                    workflow.workflowId(), sourceStage(run.stage()), run);
            if (run.status() != SyncRunStatus.RUNNING) {
                return run;
            }
            pause();
        }
    }

    private EnrichmentRunView enrichment(
            RefreshRunView workflow, EnrichmentVersions versions) {
        UUID enrichmentRunId = workflow.enrichmentRunId();
        if (enrichmentRunId == null) {
            EnrichmentTriggerKind trigger = workflow.triggerKind() == RefreshTriggerKind.SCHEDULED
                    ? EnrichmentTriggerKind.SCHEDULED : EnrichmentTriggerKind.MANUAL;
            EnrichmentRunClaim claim = enrichmentService.startForSource(
                    stageKey(workflow.workflowId(), "enrichment"),
                    workflow.sourceSyncRunId(), trigger);
            enrichmentRunId = claim.runId();
            repository.linkEnrichmentRun(workflow.workflowId(), enrichmentRunId);
        }
        while (true) {
            EnrichmentRunView run = enrichmentService.findRun(enrichmentRunId)
                    .orElseThrow(() -> new IllegalStateException("enrichment run evidence is missing"));
            if (run.status() == EnrichmentRunStatus.RUNNING && heartbeatExpired(run.heartbeatAt())) {
                throw new RefreshStageStalled("ENRICHMENT_STALLED");
            }
            repository.updateEnrichmentProgress(workflow.workflowId(), run);
            if (run.status() != EnrichmentRunStatus.RUNNING) {
                return run;
            }
            pause();
        }
    }

    private RefreshRunView running(UUID workflowId) {
        RefreshRunView run = repository.find(workflowId)
                .orElseThrow(() -> new IllegalStateException("refresh workflow is missing"));
        if (run.status() != RefreshStatus.RUNNING) {
            throw new IllegalStateException("refresh workflow is terminal");
        }
        return run;
    }

    private void pause() {
        try {
            Thread.sleep(properties.getPollInterval().toMillis());
        } catch (InterruptedException interrupted) {
            throw new RefreshInterrupted();
        }
    }

    private boolean heartbeatExpired(Instant heartbeatAt) {
        return heartbeatAt == null
                || !heartbeatAt.isAfter(clock.instant().minus(properties.getRunningStaleAfter()));
    }

    private void fail(UUID workflowId, String code) {
        if (repository.fail(workflowId, code)) {
            log.warn("Refresh workflow stopped workflowId={} code={}", workflowId, code);
        }
    }

    private RefreshWorkflowState state(RefreshRunView current) {
        RefreshRunView lastSuccess = repository.findLatestSuccessful().orElse(null);
        Instant now = clock.instant();
        Instant end = current == null || current.finishedAt() == null ? now : current.finishedAt();
        long elapsed = current == null ? 0
                : Math.max(0, Duration.between(current.startedAt(), end).toSeconds());
        return new RefreshWorkflowState(
                properties.isEnabled(),
                current == null ? null : current.workflowId(),
                current == null ? null : current.triggerKind().name(),
                current == null ? "IDLE" : current.status().name(),
                current == null ? null : current.stage().name(),
                current == null ? null : current.startedAt(),
                current == null ? null : current.finishedAt(),
                elapsed,
                current == null ? 0 : current.listingsProcessed(),
                current == null ? 0 : current.listingsTotal(),
                current == null ? 0 : current.detailsProcessed(),
                current == null ? 0 : current.detailsTotal(),
                current == null ? 0 : current.locationsProcessed(),
                current == null ? 0 : current.locationsTotal(),
                current == null ? 0 : current.mappedCount(),
                current == null ? 0 : current.populationCount(),
                current == null ? Map.of() : current.precisionCounts(),
                current == null ? null : current.sourceSyncRunId(),
                current == null ? null : current.enrichmentRunId(),
                current == null ? null : current.mapResolutionRunId(),
                current == null ? null : current.mapDataVersion(),
                current == null ? null : current.mapReadyAt(),
                current == null ? null : current.failureCode(),
                current == null ? null : failureMessage(current.failureCode()),
                lastSuccess == null ? null : lastSuccess.workflowId(),
                lastSuccess == null ? null : lastSuccess.finishedAt(),
                !"-".equals(properties.getScheduleCron()),
                properties.getScheduleZone(),
                nextScheduledRun(now));
    }

    private Instant nextScheduledRun(Instant now) {
        if ("-".equals(properties.getScheduleCron())) {
            return null;
        }
        ZoneId zone = ZoneId.of(properties.getScheduleZone());
        ZonedDateTime next = CronExpression.parse(properties.getScheduleCron())
                .next(now.atZone(zone));
        return next == null ? null : next.toInstant();
    }

    private static RefreshStage sourceStage(SyncRunStage stage) {
        return stage == SyncRunStage.DETAILS
                || stage == SyncRunStage.PROMOTING
                || stage == SyncRunStage.COMPLETED
                ? RefreshStage.DOWNLOAD_DETAILS : RefreshStage.DOWNLOAD_LISTINGS;
    }

    private static String sourceFailure(SyncRunView run) {
        return run.stage() == SyncRunStage.DETAILS
                || run.detailsFailed() > 0
                || run.detailsAttempted() < run.detailsRequired()
                ? "SOURCE_DETAILS_FAILED" : "SOURCE_SYNC_FAILED";
    }

    private static String enrichmentFailure(EnrichmentRunView run) {
        return run.status() == EnrichmentRunStatus.PAUSED
                ? "ENRICHMENT_PAUSED" : "ENRICHMENT_FAILED";
    }

    private static boolean mapReadyFor(UUID workflowId, MapDataStatus status) {
        return status.available()
                && !status.stale()
                && status.lastSuccessfulSync() != null
                && status.successfulResolutionRunId() != null
                && workflowId.equals(status.refreshWorkflowId());
    }

    static String failureMessage(String code) {
        if (code == null) {
            return null;
        }
        return switch (code) {
            case "SOURCE_SYNC_BUSY" -> "Друга напредна синхронизација је у току. Сачекајте и покушајте поново.";
            case "SOURCE_SYNC_FAILED" -> "Преузимање огласа није успело. Проверите везу са извором и покушајте поново.";
            case "SOURCE_DETAILS_FAILED" -> "Преузимање детаља није успело. Покушајте поново; претходна карта остаје доступна.";
            case "SOURCE_STALLED" -> "Преузимање података је прекинуто. Покушајте поново; претходна карта остаје доступна.";
            case "ENRICHMENT_BUSY" -> "Друга обрада локација је у току. Сачекајте и покушајте поново.";
            case "ENRICHMENT_PAUSED" -> "Обрада локација је паузирана. Наставите је у напредним контролама, па покушајте поново.";
            case "ENRICHMENT_FAILED" -> "Обрада локација није завршена. Прегледајте безбедну дијагностику и покушајте поново.";
            case "ENRICHMENT_STALLED" -> "Обрада локација је прекинута. Покушајте поново; претходна карта остаје доступна.";
            case "ENRICHMENT_ACTIVE_VERSION_CHANGED", "ENRICHMENT_SOURCE_MISMATCH" ->
                    "Верзије обраде су се промениле током освежавања. Покушајте поново.";
            case "MAP_POPULATION_EMPTY" -> "Нема припремљених података за карту. Проверите изворне податке и покушајте поново.";
            case "MAP_STATUS_MISMATCH" -> "Спремност карте није потврђена за ово освежавање. Претходна карта остаје доступна.";
            case "REFRESH_EXECUTOR_UNAVAILABLE" -> "Освежавање није могло да се покрене. Покушајте поново.";
            case "REFRESH_STALE_RECLAIMED" -> "Претходно освежавање је прекинуто. Покушајте поново.";
            default -> "Освежавање није завршено. Претходна карта остаје доступна; прегледајте дијагностику и покушајте поново.";
        };
    }

    private static UUID stageKey(UUID workflowId, String stage) {
        return UUID.nameUUIDFromBytes(
                ("refresh:" + workflowId + ":" + stage).getBytes(StandardCharsets.UTF_8));
    }

    private static final class RefreshInterrupted extends RuntimeException {
    }

    private static final class RefreshStageStalled extends RuntimeException {
        private final String code;

        private RefreshStageStalled(String code) {
            this.code = code;
        }
    }
}
