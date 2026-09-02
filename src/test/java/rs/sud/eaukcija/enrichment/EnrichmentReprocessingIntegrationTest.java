package rs.sud.eaukcija.enrichment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;

import rs.sud.eaukcija.model.Auction;
import rs.sud.eaukcija.repository.AuctionRepository;
import rs.sud.eaukcija.sync.persistence.SyncRunRepository;
import rs.sud.eaukcija.sync.persistence.WorkerLockLease;
import rs.sud.eaukcija.testsupport.PostgisTestContainer;

@SpringBootTest(
        classes = EnrichmentReprocessingIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class EnrichmentReprocessingIntegrationTest {

    private static final Instant BASE_TIME = Instant.parse("2026-08-24T12:00:00Z");
    private static final String TAXONOMY_HASH = "a".repeat(64);
    private static final EnrichmentVersions V1 = new EnrichmentVersions(
            "parser-v1", "resolver-v1", "dataset-v1");

    @ServiceConnection(name = "postgresql")
    static final PostgreSQLContainer<?> POSTGIS = PostgisTestContainer.shared();

    @Autowired
    private EnrichmentRunRepository repository;

    @Autowired
    private SyncRunRepository syncRuns;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuctionRepository auctionRepository;

    @BeforeEach
    void cleanBefore() {
        clean();
    }

    @AfterEach
    void cleanAfter() {
        clean();
    }

    /**
     * Issue #43. {@code ck_enrichment_run_time} requires {@code finished_at >=
     * started_at}, so the run row cannot mix an application-clock start with a
     * database-clock finish.
     */
    @Test
    void enrichmentTerminalizationSurvivesADatabaseClockThatTrailsTheApplication() {
        Duration skew = Duration.ofHours(1);
        EnrichmentRunRepository skewed = new EnrichmentRunRepository(
                jdbc, auctionRepository, objectMapper,
                Clock.offset(Clock.systemUTC(), skew));

        EnrichmentRunClaim claimed = skewed.claim(
                "clock-skew-enrichment", EnrichmentTriggerKind.MANUAL, V1,
                EnrichmentSelector.none(), 10);
        skewed.finish(claimed.runId(), EnrichmentRunStatus.SUCCEEDED);

        EnrichmentRunView terminal = skewed.find(claimed.runId()).orElseThrow();
        assertThat(terminal.status()).isEqualTo(EnrichmentRunStatus.SUCCEEDED);
        assertThat(terminal.startedAt())
                .isAfter(Instant.now().plus(skew).minus(Duration.ofMinutes(5)));
        assertThat(terminal.finishedAt())
                .isNotNull()
                .isAfterOrEqualTo(terminal.startedAt());
    }

    @Test
    void unchangedSecondRunPerformsNoWorkAndLeavesRowsAndHashesIdentical() {
        seedAcceptedAuctions(10, 10_000L);
        AtomicInteger calls = new AtomicInteger();
        EnrichmentService service = service(V1, item -> {
            calls.incrementAndGet();
            return success(item);
        }, 3);

        EnrichmentRunClaim first = service.startManual(UUID.randomUUID());
        String stateAfterFirst = stateJson();
        EnrichmentRunClaim second = service.startManual(UUID.randomUUID());

        assertThat(service.findRun(first.runId()).orElseThrow().status())
                .isEqualTo(EnrichmentRunStatus.SUCCEEDED);
        EnrichmentRunView repeated = service.findRun(second.runId()).orElseThrow();
        assertThat(repeated.status()).isEqualTo(EnrichmentRunStatus.SUCCEEDED);
        assertThat(repeated.candidateCount()).isZero();
        assertThat(repeated.attemptedCount()).isZero();
        assertThat(calls).hasValue(10);
        assertThat(stateJson()).isEqualTo(stateAfterFirst);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(DISTINCT output_sha256) FROM enrichment_state", Long.class))
                .isEqualTo(10);
    }

    @Test
    void retryingTheSameIdempotencyKeyWhileItsRunIsHealthyReturnsThatRun() {
        seedAcceptedAuctions(1, 15_000L);
        EnrichmentPipeline pipeline = mock(EnrichmentPipeline.class);
        when(pipeline.activeVersions()).thenReturn(V1);
        when(pipeline.pinActiveVersions())
                .thenAnswer(invocation -> new EnrichmentPipeline.PinnedRun(V1, List.of()));
        EnrichmentItemProcessor processor = mock(EnrichmentItemProcessor.class);
        when(processor.process(any())).thenAnswer(invocation ->
                success(invocation.getArgument(0)));
        AtomicReference<Runnable> submitted = new AtomicReference<>();
        AtomicInteger submissionCount = new AtomicInteger();
        EnrichmentService service = new EnrichmentService(
                new EnrichmentProperties(),
                pipeline,
                processor,
                repository,
                syncRuns,
                task -> {
                    submissionCount.incrementAndGet();
                    if (!submitted.compareAndSet(null, task)) {
                        throw new AssertionError("idempotent replay submitted a second task");
                    }
                });
        UUID key = UUID.randomUUID();

        EnrichmentRunClaim original = service.startManual(key);
        EnrichmentRunClaim replay = service.startManual(key);

        assertThat(repository.find(original.runId()).orElseThrow().status())
                .isEqualTo(EnrichmentRunStatus.RUNNING);
        assertThat(replay).isEqualTo(new EnrichmentRunClaim(original.runId(), true));
        assertThat(submissionCount).hasValue(1);

        submitted.get().run();
        assertThat(repository.find(original.runId()).orElseThrow().status())
                .isEqualTo(EnrichmentRunStatus.SUCCEEDED);
    }

    @Test
    void parserResolverAndDatasetBumpsSelectExactlyMismatchedAuctions() {
        seedAcceptedAuctions(3, 20_000L);
        EnrichmentService first = service(V1, EnrichmentReprocessingIntegrationTest::success, 3);
        first.startManual(UUID.randomUUID());

        for (EnrichmentVersions bumped : List.of(
                new EnrichmentVersions("parser-v2", "resolver-v1", "dataset-v1"),
                new EnrichmentVersions("parser-v1", "resolver-v2", "dataset-v1"),
                new EnrichmentVersions("parser-v1", "resolver-v1", "dataset-v2"))) {
            assertThat(repository.discoverCandidates(
                    bumped, EnrichmentSelector.none(), 3, 100))
                    .extracting(candidate -> candidate.item().auctionId())
                    .containsExactly(20_000L, 20_001L, 20_002L);
        }

        EnrichmentVersions parserV2 = new EnrichmentVersions(
                "parser-v2", "resolver-v1", "dataset-v1");
        EnrichmentCandidate alreadyCurrent = repository.discoverCandidates(
                parserV2, EnrichmentSelector.none(), 3, 100).get(0);
        jdbc.update("""
                UPDATE enrichment_state
                   SET parser_version = ?, resolver_version = ?, dataset_version = ?,
                       dependency_sha256 = ?, work_key_sha256 = ?
                 WHERE auction_id = ?
                """,
                parserV2.parserVersion(), parserV2.resolverVersion(), parserV2.datasetVersion(),
                alreadyCurrent.item().dependencySha256(), alreadyCurrent.item().workKeySha256(),
                alreadyCurrent.item().auctionId());

        assertThat(repository.discoverCandidates(
                parserV2, EnrichmentSelector.none(), 3, 100))
                .extracting(candidate -> candidate.item().auctionId())
                .containsExactly(20_001L, 20_002L);
    }

    @Test
    void activeVersionChangeBetweenClaimAndPinnedExecutionCannotStartTheOldWorkKey() {
        seedAcceptedAuctions(1, 25_000L);
        EnrichmentVersions version2 = new EnrichmentVersions(
                "parser-v2", "resolver-v2", "dataset-v2");
        EnrichmentPipeline changingPipeline = mock(EnrichmentPipeline.class);
        when(changingPipeline.activeVersions()).thenReturn(V1);
        when(changingPipeline.pinActiveVersions())
                .thenReturn(new EnrichmentPipeline.PinnedRun(version2, List.of()));
        EnrichmentItemProcessor processor = mock(EnrichmentItemProcessor.class);
        when(processor.process(any())).thenAnswer(invocation ->
                success(invocation.getArgument(0)));
        EnrichmentProperties properties = new EnrichmentProperties();
        EnrichmentService changing = new EnrichmentService(
                properties,
                changingPipeline,
                processor,
                repository,
                syncRuns,
                Runnable::run);

        EnrichmentRunClaim interrupted = changing.startManual(UUID.randomUUID());

        assertThat(repository.find(interrupted.runId()).orElseThrow().status())
                .isEqualTo(EnrichmentRunStatus.FAILED);
        assertThat(repository.items(interrupted.runId())).isEmpty();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM enrichment_state WHERE auction_id = 25000
                """, Long.class)).isZero();
        assertThat(repository.discoverCandidates(
                version2, EnrichmentSelector.none(), 3, 100))
                .extracting(candidate -> candidate.item().auctionId())
                .containsExactly(25_000L);
    }

    @Test
    void killAndRestartRetainsInterruptedEvidenceAndConvergesToUninterruptedOutputs()
            throws Exception {
        seedAcceptedAuctions(3, 30_000L);
        EnrichmentRunClaim interrupted = repository.claim(
                "interrupted-run", EnrichmentTriggerKind.MANUAL, V1,
                EnrichmentSelector.none(), 100);
        List<EnrichmentCandidate> initial = repository.discoverCandidates(
                V1, EnrichmentSelector.none(), 3, 100);
        repository.setCandidateCount(interrupted.runId(), initial.size());

        EnrichmentCandidate first = initial.get(0);
        repository.startItem(interrupted.runId(), 1, first, V1);
        EnrichmentItemResult firstResult = success(first.item());
        repository.completeItem(
                interrupted.runId(), first.item().auctionId(), firstResult.status(),
                firstResult.lastStage(), firstResult.outputSha256(), null, null);

        EnrichmentCandidate inFlight = initial.get(1);
        crashAfterStartingItem(interrupted.runId(), inFlight);
        try (WorkerLockLease ignored = syncRuns.tryAcquireWorkerLock().orElseThrow()) {
            assertThat(repository.recoverInterruptedRuns(3)).containsExactly(interrupted.runId());
        }

        assertThat(repository.find(interrupted.runId()).orElseThrow().status())
                .isEqualTo(EnrichmentRunStatus.INTERRUPTED);
        assertThat(repository.items(interrupted.runId()))
                .extracting(EnrichmentRunItemView::status)
                .containsExactly(EnrichmentStateStatus.SUCCEEDED, EnrichmentStateStatus.INTERRUPTED);

        EnrichmentRunClaim recovery = repository.claim(
                "recovery-run", EnrichmentTriggerKind.RECOVERY, V1,
                EnrichmentSelector.none(), 100);
        List<EnrichmentCandidate> remaining = repository.discoverCandidates(
                V1, EnrichmentSelector.none(), 3, 100);
        assertThat(remaining)
                .extracting(candidate -> candidate.item().auctionId())
                .containsExactly(30_001L, 30_002L);
        repository.setCandidateCount(recovery.runId(), remaining.size());
        int ordinal = 0;
        for (EnrichmentCandidate candidate : remaining) {
            repository.startItem(recovery.runId(), ++ordinal, candidate, V1);
            EnrichmentItemResult result = success(candidate.item());
            repository.completeItem(
                    recovery.runId(), candidate.item().auctionId(), result.status(),
                    result.lastStage(), result.outputSha256(), null, null);
        }
        repository.finish(recovery.runId(), EnrichmentRunStatus.SUCCEEDED);

        assertThat(jdbc.queryForList("""
                SELECT auction_id FROM enrichment_state
                 WHERE status = 'SUCCEEDED'
                 ORDER BY auction_id
                """, Long.class)).containsExactly(30_000L, 30_001L, 30_002L);
        assertThat(jdbc.queryForList("""
                SELECT output_sha256 FROM enrichment_state ORDER BY auction_id
                """, String.class)).containsExactly(
                output(30_000L), output(30_001L), output(30_002L));
        assertThat(jdbc.queryForObject("""
                SELECT attempt_count FROM enrichment_state WHERE auction_id = 30001
                """, Integer.class)).isEqualTo(2);
    }

    @Test
    void startupRecoveryReprocessesBothTheInterruptedItemAndNeverStartedAcceptedWork() {
        seedAcceptedAuctions(3, 35_000L);
        EnrichmentRunClaim interrupted = repository.claim(
                "startup-interrupted-run",
                EnrichmentTriggerKind.MANUAL,
                V1,
                EnrichmentSelector.none(),
                100);
        List<EnrichmentCandidate> candidates = repository.discoverCandidates(
                V1, EnrichmentSelector.none(), 3, 100);
        repository.setCandidateCount(interrupted.runId(), candidates.size());

        repository.startItem(interrupted.runId(), 1, candidates.get(0), V1);
        EnrichmentItemResult first = success(candidates.get(0).item());
        repository.completeItem(
                interrupted.runId(),
                candidates.get(0).item().auctionId(),
                first.status(),
                first.lastStage(),
                first.outputSha256(),
                null,
                null);
        repository.startItem(interrupted.runId(), 2, candidates.get(1), V1);

        EnrichmentService restarted = service(
                V1, EnrichmentReprocessingIntegrationTest::success, 3);
        restarted.recoverInterruptedRunsAfterStartup();

        assertThat(repository.find(interrupted.runId()).orElseThrow().status())
                .isEqualTo(EnrichmentRunStatus.INTERRUPTED);
        UUID recoveryRunId = jdbc.queryForObject("""
                SELECT id FROM enrichment_runs WHERE trigger_kind = 'RECOVERY'
                """, UUID.class);
        EnrichmentRunView recovery = repository.find(recoveryRunId).orElseThrow();
        assertThat(recovery.status()).isEqualTo(EnrichmentRunStatus.SUCCEEDED);
        assertThat(recovery.selector()).isEqualTo(EnrichmentSelector.none());
        assertThat(repository.items(recoveryRunId))
                .extracting(EnrichmentRunItemView::auctionId)
                .containsExactly(35_001L, 35_002L);
        assertThat(jdbc.queryForList("""
                SELECT auction_id FROM enrichment_state
                 WHERE status = 'SUCCEEDED' ORDER BY auction_id
                """, Long.class)).containsExactly(35_000L, 35_001L, 35_002L);
    }

    @Test
    void aLaterStartSelfHealsAStaleRunningRunWithoutRestartingTheProcess() {
        seedAcceptedAuctions(1, 36_000L);
        EnrichmentRunClaim wedged = repository.claim(
                "wedged-run", EnrichmentTriggerKind.MANUAL, V1,
                EnrichmentSelector.none(), 100);
        jdbc.update("""
                UPDATE enrichment_runs
                   SET heartbeat_at = CURRENT_TIMESTAMP - INTERVAL '16 minutes'
                 WHERE id = ?
                """, wedged.runId());

        EnrichmentService service = service(V1, EnrichmentReprocessingIntegrationTest::success, 3);
        EnrichmentRunClaim replacement = service.startManual(UUID.randomUUID());

        assertThat(repository.find(wedged.runId()).orElseThrow().status())
                .isEqualTo(EnrichmentRunStatus.INTERRUPTED);
        assertThat(repository.find(replacement.runId()).orElseThrow().status())
                .isEqualTo(EnrichmentRunStatus.SUCCEEDED);
        assertThat(repository.items(replacement.runId()))
                .extracting(EnrichmentRunItemView::auctionId)
                .containsExactly(36_000L);
    }

    @Test
    void statusIsReadOnlyAndReportsAuctionsMissingFromTheSnapshotLineage() {
        seedAcceptedAuctions(1, 37_000L);
        jdbc.update("""
                UPDATE auctions SET current_enrichment_snapshot_sha256 = NULL WHERE id = 37000
                """);
        long snapshotsBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM auction_enrichment_input_snapshots", Long.class);
        long observationsBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM auction_enrichment_snapshot_observations", Long.class);

        EnrichmentBacklogStatus status = service(
                V1, EnrichmentReprocessingIntegrationTest::success, 3).status();

        assertThat(status.backlogSize()).isZero();
        assertThat(status.populationGapCount()).isOne();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM auction_enrichment_input_snapshots", Long.class))
                .isEqualTo(snapshotsBefore);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM auction_enrichment_snapshot_observations", Long.class))
                .isEqualTo(observationsBefore);
        assertThat(jdbc.queryForObject("""
                SELECT current_enrichment_snapshot_sha256 FROM auctions WHERE id = 37000
                """, String.class)).isNull();
    }

    @Test
    void interruptionsHaveTheirOwnCapAndDoNotConsumeRetryableFailureAttempts() {
        seedAcceptedAuctions(1, 38_000L);
        interruptOnlyCandidate("interruption-one", 3);
        interruptOnlyCandidate("interruption-two", 3);

        assertThat(jdbc.queryForMap("""
                SELECT attempt_count, retryable_failure_count, interruption_count
                  FROM enrichment_state WHERE auction_id = 38000
                """)).containsEntry("attempt_count", 2)
                .containsEntry("retryable_failure_count", 0)
                .containsEntry("interruption_count", 2);

        EnrichmentService failures = service(V1, item -> {
            throw EnrichmentStageException.retryable("TEST_TRANSIENT_FAILURE", null);
        }, 3);
        assertThat(run(failures).retryableFailureCount()).isOne();
        assertThat(run(failures).retryableFailureCount()).isOne();
        assertThat(run(failures).attemptLimitCount()).isOne();
        assertThat(run(failures).candidateCount()).isZero();
        assertThat(jdbc.queryForMap("""
                SELECT status, attempt_count, retryable_failure_count, interruption_count
                  FROM enrichment_state WHERE auction_id = 38000
                """)).containsEntry("status", "ATTEMPT_LIMIT_REACHED")
                .containsEntry("attempt_count", 5)
                .containsEntry("retryable_failure_count", 3)
                .containsEntry("interruption_count", 2);
    }

    @Test
    void deterministicSuccessResetsFailureBudgetsBeforeAnExplicitReplay() {
        seedAcceptedAuctions(1, 38_500L);
        interruptOnlyCandidate("reset-interruption-one", 3);
        interruptOnlyCandidate("reset-interruption-two", 3);
        EnrichmentService transientFailure = service(V1, item -> {
            throw EnrichmentStageException.retryable("TEST_TRANSIENT_FAILURE", null);
        }, 3);
        assertThat(run(transientFailure).retryableFailureCount()).isOne();

        assertThat(run(service(V1, EnrichmentReprocessingIntegrationTest::success, 3)).succeededCount())
                .isOne();
        assertThat(jdbc.queryForMap("""
                SELECT status, retryable_failure_count, interruption_count
                  FROM enrichment_state WHERE auction_id = 38500
                """)).containsEntry("status", "SUCCEEDED")
                .containsEntry("retryable_failure_count", 0)
                .containsEntry("interruption_count", 0);

        EnrichmentSelector selector = new EnrichmentSelector(EnrichmentSelectorType.AUCTION, "38500");
        EnrichmentRunClaim replay = repository.claim(
                "post-success-explicit-replay", EnrichmentTriggerKind.REPLAY, V1, selector, 1);
        EnrichmentCandidate candidate = repository.discoverCandidates(V1, selector, 3, 1).get(0);
        repository.setCandidateCount(replay.runId(), 1);
        repository.startItem(replay.runId(), 1, candidate, V1);
        try (WorkerLockLease ignored = syncRuns.tryAcquireWorkerLock().orElseThrow()) {
            assertThat(repository.recoverInterruptedRuns(3)).containsExactly(replay.runId());
        }

        assertThat(jdbc.queryForMap("""
                SELECT status, retryable_failure_count, interruption_count
                  FROM enrichment_state WHERE auction_id = 38500
                """)).containsEntry("status", "PENDING")
                .containsEntry("retryable_failure_count", 0)
                .containsEntry("interruption_count", 1);
    }

    @Test
    void repeatedProcessInterruptionsReachABoundedTerminalState() {
        seedAcceptedAuctions(1, 39_000L);
        interruptOnlyCandidate("crash-one", 3);
        interruptOnlyCandidate("crash-two", 3);
        interruptOnlyCandidate("crash-three", 3);

        assertThat(repository.discoverCandidates(
                V1, EnrichmentSelector.none(), 3, 100)).isEmpty();
        assertThat(jdbc.queryForMap("""
                SELECT status, interruption_count, error_class, error_message
                  FROM enrichment_state WHERE auction_id = 39000
                """)).containsEntry("status", "ATTEMPT_LIMIT_REACHED")
                .containsEntry("interruption_count", 3)
                .containsEntry("error_class", "ATTEMPT_LIMIT_REACHED")
                .containsEntry("error_message", "INTERRUPTION_LIMIT_REACHED");
    }

    @Test
    void anErrorEscapingItemExecutionTerminalizesTheRunAndInFlightItem() {
        seedAcceptedAuctions(1, 39_500L);
        EnrichmentService service = service(V1, item -> {
            throw new AssertionError("simulated executor thread death");
        }, 3);

        assertThatThrownBy(() -> service.startManual(UUID.randomUUID()))
                .isInstanceOf(AssertionError.class);

        UUID runId = jdbc.queryForObject(
                "SELECT id FROM enrichment_runs ORDER BY started_at DESC LIMIT 1", UUID.class);
        assertThat(repository.find(runId).orElseThrow().status()).isEqualTo(EnrichmentRunStatus.FAILED);
        assertThat(repository.items(runId))
                .extracting(EnrichmentRunItemView::status)
                .containsExactly(EnrichmentStateStatus.INTERRUPTED);
        assertThat(jdbc.queryForMap("""
                SELECT status, interruption_count FROM enrichment_state WHERE auction_id = 39500
                """)).containsEntry("status", "PENDING")
                .containsEntry("interruption_count", 1);
    }

    @Test
    void workerContentionSkipsRatherThanFailsTheRecordedRun() {
        seedAcceptedAuctions(1, 39_600L);
        EnrichmentService service = service(V1, EnrichmentReprocessingIntegrationTest::success, 3);

        EnrichmentRunClaim claim;
        try (WorkerLockLease ignored = syncRuns.tryAcquireWorkerLock().orElseThrow()) {
            claim = service.startScheduled(UUID.randomUUID());
        }

        assertThat(repository.find(claim.runId()).orElseThrow().status())
                .isEqualTo(EnrichmentRunStatus.SKIPPED);
        assertThat(repository.items(claim.runId())).isEmpty();
    }

    @Test
    void rejectedSubmissionDuringAnActiveSyncIsASkipNotAnOperationalFailure() {
        seedAcceptedAuctions(1, 39_700L);
        UUID activeSyncRunId = insertActiveSyncRun();
        EnrichmentPipeline pipeline = mock(EnrichmentPipeline.class);
        when(pipeline.activeVersions()).thenReturn(V1);
        when(pipeline.pinActiveVersions())
                .thenAnswer(invocation -> new EnrichmentPipeline.PinnedRun(V1, List.of()));
        EnrichmentProperties properties = new EnrichmentProperties();
        EnrichmentService service = new EnrichmentService(
                properties,
                pipeline,
                mock(EnrichmentItemProcessor.class),
                repository,
                syncRuns,
                task -> { throw new TaskRejectedException("shared worker occupied"); });

        assertThatThrownBy(() -> service.startScheduled(UUID.randomUUID()))
                .isInstanceOf(EnrichmentWorkerBusyException.class)
                .satisfies(failure -> {
                    EnrichmentWorkerBusyException busy = (EnrichmentWorkerBusyException) failure;
                    assertThat(busy.activeSyncRunId()).isEqualTo(activeSyncRunId);
                    assertThat(repository.find(busy.runId()).orElseThrow().status())
                            .isEqualTo(EnrichmentRunStatus.SKIPPED);
                });
    }

    @ParameterizedTest
    @EnumSource(EnrichmentStageName.class)
    void oneFailureAtEveryPossibleStageDoesNotStopTheOtherSixHundred(
            EnrichmentStageName failingStage) {
        UUID sourceRun = seedAcceptedAuctions(601, 40_000L);
        EnrichmentService service = service(V1, item -> {
            if (item.auctionId() == 40_333L) {
                throw EnrichmentStageException.permanent("TEST_PERMANENT_FAILURE", null)
                        .atStage(failingStage);
            }
            return success(item);
        }, 3);

        long started = System.nanoTime();
        EnrichmentRunClaim claim = service.startManual(UUID.randomUUID());
        long durationMillis = (System.nanoTime() - started) / 1_000_000;
        EnrichmentRunView run = service.findRun(claim.runId()).orElseThrow();

        assertThat(run.status()).isEqualTo(EnrichmentRunStatus.PARTIAL);
        assertThat(run.candidateCount()).isEqualTo(601);
        assertThat(run.attemptedCount()).isEqualTo(601);
        assertThat(run.succeededCount()).isEqualTo(600);
        assertThat(run.permanentFailureCount()).isOne();
        assertThat(run.propertyReferenceParseFailureCount())
                .isEqualTo(failingStage == EnrichmentStageName.PARSE ? 1 : 0);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM enrichment_state WHERE status = 'SUCCEEDED'
                """, Long.class)).isEqualTo(600);
        assertThat(jdbc.queryForObject("""
                SELECT error_message FROM enrichment_state WHERE auction_id = 40333
                """, String.class)).isEqualTo("TEST_PERMANENT_FAILURE");
        assertThat(jdbc.queryForObject("""
                SELECT last_stage FROM enrichment_state WHERE auction_id = 40333
                """, String.class)).isEqualTo(failingStage.name());
        assertThat(repository.discoverCandidates(
                V1,
                new EnrichmentSelector(EnrichmentSelectorType.SOURCE_SYNC_RUN, sourceRun.toString()),
                3,
                1)).hasSize(1);
        assertThat(durationMillis).isLessThan(30_000L);
    }

    @Test
    void retryableFailureRunsOnNextScheduleUntilBoundedAttemptCap() {
        seedAcceptedAuctions(1, 50_000L);
        EnrichmentService service = service(V1, item -> {
            throw EnrichmentStageException.retryable("TEST_TRANSIENT_FAILURE", null);
        }, 3);

        EnrichmentRunView first = run(service);
        EnrichmentRunView second = run(service);
        EnrichmentRunView third = run(service);
        EnrichmentRunView fourth = run(service);

        assertThat(first.retryableFailureCount()).isOne();
        assertThat(second.retryableFailureCount()).isOne();
        assertThat(third.attemptLimitCount()).isOne();
        assertThat(fourth.candidateCount()).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT attempt_count FROM enrichment_state WHERE auction_id = 50000
                """, Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                SELECT status FROM enrichment_state WHERE auction_id = 50000
                """, String.class)).isEqualTo("ATTEMPT_LIMIT_REACHED");
    }

    @Test
    void unchangedLaterSyncDoesNotResetTheOldestRetryableBacklogAge() {
        seedAcceptedAuctions(1, 55_000L);
        EnrichmentService service = service(V1, item -> {
            throw EnrichmentStageException.retryable("TEST_TRANSIENT_FAILURE", null);
        }, 3);
        run(service);

        UUID laterRunId = UUID.randomUUID();
        Instant later = BASE_TIME.plusSeconds(86_400);
        String listingHash = EnrichmentHashing.sha256("listing", "55000");
        String snapshotHash = EnrichmentHashing.sha256("snapshot", "55000");
        jdbc.update("""
                INSERT INTO sync_runs (
                    id, idempotency_key_sha256, trigger_kind, status, stage,
                    started_at, heartbeat_at, configured_roots, page_size,
                    category_tree_sha256, category_tree_observed_at,
                    unique_auction_count
                ) VALUES (?, ?, 'MANUAL', 'RUNNING', 'PROMOTING', ?, ?,
                          CAST('[7]' AS jsonb), 3000, ?, ?, 1)
                """,
                laterRunId,
                EnrichmentHashing.sha256("later-source-run", laterRunId.toString()),
                databaseTime(later),
                databaseTime(later),
                TAXONOMY_HASH,
                databaseTime(later));
        jdbc.update("""
                INSERT INTO sync_run_auction_observations (
                    run_id, auction_id, listing_fingerprint, detail_refreshed,
                    enrichment_eligible, enrichment_reason
                ) VALUES (?, 55000, ?, FALSE, FALSE, 'NONE')
                """, laterRunId, listingHash);
        jdbc.update("""
                UPDATE sync_runs
                   SET status = 'SUCCEEDED', stage = 'COMPLETED',
                       finished_at = ?, heartbeat_at = ?
                 WHERE id = ?
                """, databaseTime(later.plusSeconds(1)),
                databaseTime(later.plusSeconds(1)), laterRunId);
        jdbc.update("""
                INSERT INTO auction_enrichment_snapshot_observations (
                    source_sync_run_id, auction_id, snapshot_sha256, observed_at
                ) VALUES (?, 55000, ?, ?)
                """, laterRunId, snapshotHash, databaseTime(later));

        EnrichmentBacklogMeasure backlog = repository.measureBacklog(V1, 3);
        assertThat(backlog.count()).isOne();
        assertThat(backlog.oldestPendingSince()).isEqualTo(BASE_TIME);
    }

    @Test
    void databaseRejectsOverlappingClaimsAndSharedWorkerLockSerializesSyncAndEnrichment() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try {
            Future<EnrichmentRunClaim> left = executor.submit(() -> {
                ready.countDown();
                start.await();
                return repository.claim("overlap-left", EnrichmentTriggerKind.MANUAL,
                        V1, EnrichmentSelector.none(), 10);
            });
            Future<EnrichmentRunClaim> right = executor.submit(() -> {
                ready.countDown();
                start.await();
                return repository.claim("overlap-right", EnrichmentTriggerKind.MANUAL,
                        V1, EnrichmentSelector.none(), 10);
            });
            ready.await();
            start.countDown();

            int winners = 0;
            int rejected = 0;
            for (Future<EnrichmentRunClaim> result : List.of(left, right)) {
                try {
                    result.get();
                    winners++;
                } catch (ExecutionException failure) {
                    assertThat(failure.getCause()).isInstanceOf(EnrichmentAlreadyRunningException.class);
                    rejected++;
                }
            }
            assertThat(winners).isOne();
            assertThat(rejected).isOne();
        } finally {
            executor.shutdownNow();
        }

        try (WorkerLockLease heldBySync = syncRuns.tryAcquireWorkerLock().orElseThrow()) {
            assertThat(syncRuns.tryAcquireWorkerLock()).isEmpty();
        }
        try (WorkerLockLease heldByEnrichment = syncRuns.tryAcquireWorkerLock().orElseThrow()) {
            assertThat(heldByEnrichment).isNotNull();
        }
    }

    @Test
    void pauseIsDurableAndReplayIsBoundedToOneExplicitAuction() {
        seedAcceptedAuctions(3, 60_000L);
        EnrichmentService service = service(V1, EnrichmentReprocessingIntegrationTest::success, 3);
        UUID completedKey = UUID.randomUUID();
        EnrichmentRunClaim completed = service.startManual(completedKey);

        assertThat(service.pause()).isTrue();
        assertThat(service.startManual(completedKey))
                .isEqualTo(new EnrichmentRunClaim(completed.runId(), true));
        assertThatThrownBy(() -> service.startManual(UUID.randomUUID()))
                .isInstanceOf(EnrichmentUnavailableException.class);
        assertThatThrownBy(() -> repository.claim(
                "paused-direct-claim",
                EnrichmentTriggerKind.MANUAL,
                V1,
                EnrichmentSelector.none(),
                1))
                .isInstanceOf(EnrichmentUnavailableException.class);
        assertThat(service.status().paused()).isTrue();
        assertThat(service.status().statusDistribution())
                .doesNotContainKey(EnrichmentStateStatus.INTERRUPTED);
        assertThat(service.resume()).isFalse();

        EnrichmentRunClaim replay = service.startReplay(
                UUID.randomUUID(),
                new EnrichmentSelector(EnrichmentSelectorType.AUCTION, "60001"),
                1);
        EnrichmentRunView run = service.findRun(replay.runId()).orElseThrow();
        assertThat(run.candidateCount()).isOne();
        assertThat(service.items(run.runId()))
                .extracting(EnrichmentRunItemView::auctionId)
                .containsExactly(60_001L);
        assertThatThrownBy(() -> service.startReplay(
                UUID.randomUUID(),
                new EnrichmentSelector(EnrichmentSelectorType.VERSION, "parser-v1"),
                1_001)).isInstanceOf(IllegalArgumentException.class);
    }

    private EnrichmentService service(
            EnrichmentVersions versions,
            ProcessorBehavior behavior,
            int maxAttempts) {
        EnrichmentPipeline pipeline = mock(EnrichmentPipeline.class);
        when(pipeline.activeVersions()).thenReturn(versions);
        when(pipeline.pinActiveVersions())
                .thenAnswer(invocation -> new EnrichmentPipeline.PinnedRun(versions, List.of()));
        EnrichmentItemProcessor processor = mock(EnrichmentItemProcessor.class);
        when(processor.process(any())).thenAnswer(invocation ->
                behavior.process(invocation.getArgument(0)));
        EnrichmentProperties properties = new EnrichmentProperties();
        properties.setMaxAttempts(maxAttempts);
        properties.setMaxItemsPerRun(1_000);
        properties.setMaxReplayItems(1_000);
        return new EnrichmentService(
                properties,
                pipeline,
                processor,
                repository,
                syncRuns,
                Runnable::run);
    }

    private void interruptOnlyCandidate(String idempotencyKey, int maxInterruptions) {
        EnrichmentRunClaim run = repository.claim(
                idempotencyKey, EnrichmentTriggerKind.MANUAL, V1,
                EnrichmentSelector.none(), 100);
        EnrichmentCandidate candidate = repository.discoverCandidates(
                V1, EnrichmentSelector.none(), 3, 100).get(0);
        repository.setCandidateCount(run.runId(), 1);
        repository.startItem(run.runId(), 1, candidate, V1);
        try (WorkerLockLease ignored = syncRuns.tryAcquireWorkerLock().orElseThrow()) {
            assertThat(repository.recoverInterruptedRuns(maxInterruptions))
                    .containsExactly(run.runId());
        }
    }

    private void crashAfterStartingItem(UUID runId, EnrichmentCandidate candidate) throws Exception {
        EnrichmentWorkItem item = candidate.item();
        String canonical = Base64.getUrlEncoder().withoutPadding().encodeToString(
                objectMapper.writeValueAsBytes(item.canonicalInput()));
        ProcessBuilder processBuilder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty("java.class.path"),
                EnrichmentCrashProbe.class.getName(),
                runId.toString(),
                Long.toString(item.auctionId()),
                V1.parserVersion(),
                V1.resolverVersion(),
                V1.datasetVersion(),
                item.sourceSyncRunId().toString(),
                item.snapshotSha256(),
                item.dependencySha256(),
                canonical);
        processBuilder.redirectErrorStream(true);
        processBuilder.environment().put("ENRICHMENT_CRASH_DB_URL", POSTGIS.getJdbcUrl());
        processBuilder.environment().put("ENRICHMENT_CRASH_DB_USER", POSTGIS.getUsername());
        processBuilder.environment().put("ENRICHMENT_CRASH_DB_PASSWORD", POSTGIS.getPassword());
        Process process = processBuilder.start();
        boolean exited = process.waitFor(15, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            throw new AssertionError("crash probe did not exit");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.exitValue()).isEqualTo(29);
        assertThat(output).contains("ENRICHMENT_ITEM_DURABLY_STARTED");
        assertThat(repository.items(runId))
                .extracting(EnrichmentRunItemView::status)
                .containsExactly(EnrichmentStateStatus.SUCCEEDED, EnrichmentStateStatus.RUNNING);
    }

    private UUID insertActiveSyncRun() {
        UUID runId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO sync_runs (
                    id, idempotency_key_sha256, trigger_kind, status, stage,
                    started_at, heartbeat_at, configured_roots, page_size,
                    category_tree_sha256, category_tree_observed_at
                ) VALUES (?, ?, 'SCHEDULED', 'RUNNING', 'PROMOTING', ?, ?,
                          CAST('[7]' AS jsonb), 3000, ?, ?)
                """,
                runId,
                EnrichmentHashing.sha256("active-sync", runId.toString()),
                databaseTime(BASE_TIME.plusSeconds(10)),
                databaseTime(BASE_TIME.plusSeconds(10)),
                TAXONOMY_HASH,
                databaseTime(BASE_TIME));
        return runId;
    }

    private static EnrichmentRunView run(EnrichmentService service) {
        EnrichmentRunClaim claim = service.startScheduled(UUID.randomUUID());
        return service.findRun(claim.runId()).orElseThrow();
    }

    private static EnrichmentItemResult success(EnrichmentWorkItem item) {
        return new EnrichmentItemResult(
                EnrichmentStateStatus.SUCCEEDED,
                EnrichmentStageName.SELECTED_RESOLUTION,
                output(item.auctionId()));
    }

    private static String output(long auctionId) {
        return EnrichmentHashing.sha256("deterministic-output", Long.toString(auctionId));
    }

    private UUID seedAcceptedAuctions(int count, long firstAuctionId) {
        UUID runId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO eaukcija_taxonomies (
                    tree_sha256, normalizer_version, canonical_tree, first_observed_at
                ) VALUES (?, 'test-taxonomy-v1', CAST('[{"value":7,"children":[]}]' AS jsonb), ?)
                ON CONFLICT (tree_sha256) DO NOTHING
                """, TAXONOMY_HASH, databaseTime(BASE_TIME));
        jdbc.update("""
                INSERT INTO sync_runs (
                    id, idempotency_key_sha256, trigger_kind, status, stage,
                    started_at, heartbeat_at, configured_roots, page_size,
                    category_tree_sha256, category_tree_observed_at,
                    unique_auction_count
                ) VALUES (?, ?, 'MANUAL', 'RUNNING', 'PROMOTING', ?, ?,
                          CAST('[7]' AS jsonb), 3000, ?, ?, ?)
                """,
                runId,
                EnrichmentHashing.sha256("source-run", runId.toString()),
                databaseTime(BASE_TIME),
                databaseTime(BASE_TIME),
                TAXONOMY_HASH,
                databaseTime(BASE_TIME),
                count);
        for (int index = 0; index < count; index++) {
            long auctionId = firstAuctionId + index;
            String listingHash = EnrichmentHashing.sha256("listing", Long.toString(auctionId));
            jdbc.update("""
                    INSERT INTO auctions (
                        id, auction_number, first_sale, details_fetched, listing_fingerprint,
                        last_successful_sync_run_id, cadastral, place_name, municipality
                    ) VALUES (?, ?, FALSE, TRUE, ?, ?, 'TEST KO', 'Test settlement', 'Test municipality')
                    """, auctionId, "A-" + auctionId, listingHash, runId);
            jdbc.update("""
                    INSERT INTO sync_run_auction_observations (
                        run_id, auction_id, listing_fingerprint, detail_refreshed,
                        enrichment_eligible, enrichment_reason
                    ) VALUES (?, ?, ?, TRUE, TRUE, 'NEW')
                    """, runId, auctionId, listingHash);
        }
        jdbc.update("""
                UPDATE sync_runs
                   SET status = 'SUCCEEDED', stage = 'COMPLETED',
                       finished_at = ?, heartbeat_at = ?
                 WHERE id = ?
                """, databaseTime(BASE_TIME.plusSeconds(1)),
                databaseTime(BASE_TIME.plusSeconds(1)), runId);
        for (int index = 0; index < count; index++) {
            long auctionId = firstAuctionId + index;
            String snapshotHash = EnrichmentHashing.sha256("snapshot", Long.toString(auctionId));
            String canonical = "{\"schemaVersion\":\"test-v1\",\"auctionId\":" + auctionId + "}";
            jdbc.update("""
                    INSERT INTO auction_enrichment_input_snapshots (
                        auction_id, snapshot_sha256, canonical_input, created_at
                    ) VALUES (?, ?, CAST(? AS jsonb), ?)
                    """, auctionId, snapshotHash, canonical, databaseTime(BASE_TIME.plusSeconds(index)));
            jdbc.update("""
                    INSERT INTO auction_enrichment_snapshot_observations (
                        source_sync_run_id, auction_id, snapshot_sha256, observed_at
                    ) VALUES (?, ?, ?, ?)
                    """, runId, auctionId, snapshotHash, databaseTime(BASE_TIME.plusSeconds(index)));
            jdbc.update("""
                    UPDATE auctions SET current_enrichment_snapshot_sha256 = ? WHERE id = ?
                    """, snapshotHash, auctionId);
        }
        return runId;
    }

    private String stateJson() {
        try {
            return objectMapper.writeValueAsString(jdbc.queryForList("""
                    SELECT auction_id, source_sync_run_id, snapshot_sha256,
                           parser_version, resolver_version, dataset_version,
                           dependency_sha256, work_key_sha256, status, attempt_count,
                           pending_since, last_attempt_at, completed_at,
                           last_enrichment_run_id, last_stage, output_sha256,
                           error_class, error_message
                      FROM enrichment_state
                     ORDER BY auction_id
                    """));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private void clean() {
        jdbc.execute("""
                TRUNCATE TABLE
                    enrichment_run_items,
                    enrichment_state,
                    enrichment_runs,
                    auction_enrichment_snapshot_observations,
                    auction_enrichment_input_snapshots,
                    sync_enrichment_queue,
                    sync_run_listing_quarantines,
                    sync_run_detail_quarantines,
                    sync_run_auction_observations,
                    auction_source_category_memberships,
                    sync_run_errors,
                    sync_run_child_results,
                    sync_run_root_results,
                    auctions,
                    sync_runs,
                    eaukcija_taxonomies
                CASCADE
                """);
        jdbc.update("""
                UPDATE enrichment_control
                   SET paused = FALSE, changed_at = CURRENT_TIMESTAMP, change_code = 'TEST_RESET'
                 WHERE singleton
                """);
    }

    private static OffsetDateTime databaseTime(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    @FunctionalInterface
    private interface ProcessorBehavior {
        EnrichmentItemResult process(EnrichmentWorkItem item);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = Auction.class)
    @EnableJpaRepositories(basePackageClasses = AuctionRepository.class)
    @Import({EnrichmentRunRepository.class, SyncRunRepository.class})
    static class TestApplication {
    }
}
