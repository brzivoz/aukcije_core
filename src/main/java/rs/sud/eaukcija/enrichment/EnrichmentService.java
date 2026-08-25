package rs.sud.eaukcija.enrichment;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import rs.sud.eaukcija.sync.persistence.SyncRunRepository;
import rs.sud.eaukcija.sync.persistence.WorkerLockLease;

/** Queue-free, deterministic enrichment coordinator for issue #29. */
@Service
public class EnrichmentService {

    private static final Logger log = LoggerFactory.getLogger(EnrichmentService.class);

    private final EnrichmentProperties properties;
    private final EnrichmentPipeline pipeline;
    private final EnrichmentItemProcessor processor;
    private final EnrichmentRunRepository repository;
    private final SyncRunRepository syncRuns;
    private final TaskExecutor executor;

    public EnrichmentService(
            EnrichmentProperties properties,
            EnrichmentPipeline pipeline,
            EnrichmentItemProcessor processor,
            EnrichmentRunRepository repository,
            SyncRunRepository syncRuns,
            @Qualifier("syncRunExecutor") TaskExecutor executor) {
        this.properties = properties;
        this.pipeline = pipeline;
        this.processor = processor;
        this.repository = repository;
        this.syncRuns = syncRuns;
        this.executor = executor;
    }

    public EnrichmentRunClaim startManual(UUID idempotencyKey) {
        return start(
                idempotencyKey.toString(),
                EnrichmentTriggerKind.MANUAL,
                EnrichmentSelector.none(),
                properties.getMaxItemsPerRun());
    }

    public EnrichmentRunClaim startScheduled(UUID idempotencyKey) {
        return start(
                idempotencyKey.toString(),
                EnrichmentTriggerKind.SCHEDULED,
                EnrichmentSelector.none(),
                properties.getMaxItemsPerRun());
    }

    public EnrichmentRunClaim startReplay(
            UUID idempotencyKey,
            EnrichmentSelector selector,
            int maxItems) {
        if (selector == null || !selector.explicitReplay()) {
            throw new IllegalArgumentException("a bounded replay selector is required");
        }
        if (maxItems < 1 || maxItems > properties.getMaxReplayItems()) {
            throw new IllegalArgumentException(
                    "replay maxItems must be between 1 and " + properties.getMaxReplayItems());
        }
        return start(idempotencyKey.toString(), EnrichmentTriggerKind.REPLAY, selector, maxItems);
    }

    public Optional<EnrichmentRunView> findRun(UUID runId) {
        return properties.isEnabled() ? repository.find(runId) : Optional.empty();
    }

    public List<EnrichmentRunItemView> items(UUID runId) {
        return properties.isEnabled() ? repository.items(runId) : List.of();
    }

    public boolean pause() {
        requireEnabled();
        return repository.setPaused(true);
    }

    public boolean resume() {
        requireEnabled();
        return repository.setPaused(false);
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    public EnrichmentBacklogStatus status() {
        requireEnabled();
        repository.bootstrapMissingInputSnapshots();
        EnrichmentVersions versions = pipeline.activeVersions();
        EnrichmentBacklogMeasure backlog = repository.measureBacklog(
                versions, properties.getMaxAttempts());
        Map<EnrichmentStateStatus, Long> distribution = repository.statusDistribution();
        return new EnrichmentBacklogStatus(
                true,
                repository.isPaused(),
                versions,
                backlog.count(),
                backlog.oldestPendingSince(),
                distribution,
                repository.activeRunId().orElse(null));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedRunsAfterStartup() {
        if (!properties.isEnabled()) {
            return;
        }
        List<UUID> recovered;
        Optional<WorkerLockLease> acquired;
        try {
            acquired = syncRuns.tryAcquireWorkerLock();
            if (acquired.isEmpty()) {
                return;
            }
            try (WorkerLockLease lease = acquired.orElseThrow()) {
                recovered = repository.recoverInterruptedRuns();
            }
        } catch (RuntimeException recoveryFailure) {
            log.error("Could not recover enrichment runs code=ENRICHMENT_RECOVERY_FAILED");
            return;
        }
        if (repository.isPaused()) {
            return;
        }
        for (UUID runId : recovered) {
            try {
                start(
                        "enrichment-recovery:" + runId,
                        EnrichmentTriggerKind.RECOVERY,
                        EnrichmentSelector.none(),
                        properties.getMaxItemsPerRun());
            } catch (RuntimeException restartFailure) {
                log.error("Could not restart recovered enrichment run runId={} code=ENRICHMENT_RECOVERY_SUBMIT_FAILED",
                        runId);
            }
        }
    }

    private EnrichmentRunClaim start(
            String idempotencyKey,
            EnrichmentTriggerKind triggerKind,
            EnrichmentSelector selector,
            int maxItems) {
        requireEnabled();
        repository.bootstrapMissingInputSnapshots();
        EnrichmentVersions versions;
        try {
            versions = pipeline.activeVersions();
        } catch (RuntimeException unavailable) {
            throw new EnrichmentUnavailableException("active enrichment versions are unavailable");
        }
        EnrichmentRunClaim claim = repository.claim(
                idempotencyKey, triggerKind, versions, selector, maxItems);
        if (claim.replayed()) {
            return claim;
        }
        try {
            executor.execute(() -> execute(claim.runId()));
        } catch (RejectedExecutionException rejected) {
            terminalizeSubmissionFailure(claim.runId());
            throw new EnrichmentSubmissionException(claim.runId());
        } catch (RuntimeException rejected) {
            terminalizeSubmissionFailure(claim.runId());
            throw new EnrichmentSubmissionException(claim.runId());
        }
        return claim;
    }

    private void execute(UUID runId) {
        Optional<WorkerLockLease> acquired;
        try {
            acquired = acquireSharedWorkerLock();
        } catch (RuntimeException lockFailure) {
            terminalizeFailure(runId);
            return;
        }
        if (acquired.isEmpty()) {
            terminalizeFailure(runId);
            return;
        }
        try (WorkerLockLease lease = acquired.orElseThrow()) {
            if (!repository.isRunning(runId)) {
                return;
            }
            executeLocked(runId);
        } catch (RuntimeException unexpected) {
            terminalizeFailure(runId);
        }
    }

    private void executeLocked(UUID runId) {
        EnrichmentRunView run = repository.find(runId)
                .orElseThrow(() -> new IllegalStateException("claimed enrichment run is missing"));
        if (!activeVersionsMatch(runId, run.versions())) {
            return;
        }
        List<EnrichmentCandidate> candidates = repository.discoverCandidates(
                run.versions(),
                run.selector(),
                properties.getMaxAttempts(),
                run.maxItems());
        repository.setCandidateCount(runId, candidates.size());
        int ordinal = 0;
        for (EnrichmentCandidate candidate : candidates) {
            if (repository.isPaused()) {
                repository.finish(runId, EnrichmentRunStatus.PAUSED);
                return;
            }
            ordinal++;
            EnrichmentItemAttempt attempt = repository.startItem(
                    runId, ordinal, candidate, run.versions());
            try {
                EnrichmentItemResult result = processor.process(candidate.item());
                if (!activeVersionsMatch(runId, run.versions())) {
                    return;
                }
                repository.completeItem(
                        runId,
                        candidate.item().auctionId(),
                        result.status(),
                        result.lastStage(),
                        result.outputSha256(),
                        null,
                        null);
            } catch (EnrichmentStageException failure) {
                if (!activeVersionsMatch(runId, run.versions())) {
                    return;
                }
                boolean capped = failure.retryable()
                        && attempt.attemptNumber() >= properties.getMaxAttempts();
                EnrichmentStateStatus status = capped
                        ? EnrichmentStateStatus.ATTEMPT_LIMIT_REACHED
                        : failure.retryable()
                                ? EnrichmentStateStatus.RETRYABLE_FAILURE
                                : EnrichmentStateStatus.PERMANENT_FAILURE;
                repository.completeItem(
                        runId,
                        candidate.item().auctionId(),
                        status,
                        failure.stage() == null ? EnrichmentStageName.PARSE : failure.stage(),
                        null,
                        capped ? "ATTEMPT_LIMIT_REACHED"
                                : failure.retryable() ? "RETRYABLE_STAGE_FAILURE" : "PERMANENT_STAGE_FAILURE",
                        failure.safeCode());
            } catch (RuntimeException transactionFailure) {
                if (!activeVersionsMatch(runId, run.versions())) {
                    return;
                }
                boolean capped = attempt.attemptNumber() >= properties.getMaxAttempts();
                repository.completeItem(
                        runId,
                        candidate.item().auctionId(),
                        capped ? EnrichmentStateStatus.ATTEMPT_LIMIT_REACHED
                                : EnrichmentStateStatus.RETRYABLE_FAILURE,
                        EnrichmentStageName.PARSE,
                        null,
                        capped ? "ATTEMPT_LIMIT_REACHED" : "RETRYABLE_STAGE_FAILURE",
                        "ITEM_TRANSACTION_FAILED");
            }
        }
        if (!activeVersionsMatch(runId, run.versions())) {
            return;
        }
        EnrichmentRunView completed = repository.find(runId)
                .orElseThrow(() -> new IllegalStateException("enrichment run disappeared"));
        boolean partial = completed.retryableFailureCount() > 0
                || completed.permanentFailureCount() > 0
                || completed.attemptLimitCount() > 0;
        repository.finish(runId, partial ? EnrichmentRunStatus.PARTIAL : EnrichmentRunStatus.SUCCEEDED);
        log.info("Enrichment run finished runId={} candidates={} attempted={} status={}",
                runId,
                completed.candidateCount(),
                completed.attemptedCount(),
                partial ? EnrichmentRunStatus.PARTIAL : EnrichmentRunStatus.SUCCEEDED);
    }

    private boolean activeVersionsMatch(UUID runId, EnrichmentVersions expected) {
        try {
            if (expected.equals(pipeline.activeVersions())) {
                return true;
            }
            log.info("Enrichment run stopped runId={} code=ENRICHMENT_ACTIVE_VERSION_CHANGED", runId);
        } catch (RuntimeException unavailable) {
            log.info("Enrichment run stopped runId={} code=ENRICHMENT_ACTIVE_VERSION_UNAVAILABLE", runId);
        }
        terminalizeFailure(runId);
        return false;
    }

    private Optional<WorkerLockLease> acquireSharedWorkerLock() {
        for (int attempt = 1; attempt <= 5; attempt++) {
            Optional<WorkerLockLease> acquired = syncRuns.tryAcquireWorkerLock();
            if (acquired.isPresent() || attempt == 5) {
                return acquired;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private void terminalizeSubmissionFailure(UUID runId) {
        try {
            repository.fail(runId);
        } catch (RuntimeException ledgerFailure) {
            log.error("Could not terminalize rejected enrichment run runId={} code=ENRICHMENT_LEDGER_FAILURE",
                    runId);
        }
    }

    private void terminalizeFailure(UUID runId) {
        try {
            if (repository.isRunning(runId)) {
                repository.fail(runId);
            }
        } catch (RuntimeException ledgerFailure) {
            log.error("Could not terminalize enrichment run runId={} code=ENRICHMENT_LEDGER_FAILURE", runId);
        }
    }

    private void requireEnabled() {
        if (!properties.isEnabled()) {
            throw new EnrichmentUnavailableException("durable enrichment is unavailable for this profile");
        }
    }
}
