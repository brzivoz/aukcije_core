package rs.sud.eaukcija.enrichment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

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

    @BeforeEach
    void cleanBefore() {
        clean();
    }

    @AfterEach
    void cleanAfter() {
        clean();
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
    void activeVersionChangeDuringAnItemCannotCompleteTheOldWorkKey() {
        seedAcceptedAuctions(1, 25_000L);
        EnrichmentVersions version2 = new EnrichmentVersions(
                "parser-v2", "resolver-v2", "dataset-v2");
        EnrichmentPipeline changingPipeline = mock(EnrichmentPipeline.class);
        when(changingPipeline.activeVersions()).thenReturn(V1, V1, version2, version2);
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
        assertThat(repository.items(interrupted.runId()))
                .extracting(EnrichmentRunItemView::status)
                .containsExactly(EnrichmentStateStatus.INTERRUPTED);
        assertThat(jdbc.queryForObject("""
                SELECT status FROM enrichment_state WHERE auction_id = 25000
                """, String.class)).isEqualTo("PENDING");
        assertThat(repository.discoverCandidates(
                version2, EnrichmentSelector.none(), 3, 100))
                .extracting(candidate -> candidate.item().auctionId())
                .containsExactly(25_000L);
    }

    @Test
    void killAndRestartRetainsInterruptedEvidenceAndConvergesToUninterruptedOutputs() {
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
        repository.startItem(interrupted.runId(), 2, inFlight, V1);
        try (WorkerLockLease ignored = syncRuns.tryAcquireWorkerLock().orElseThrow()) {
            assertThat(repository.recoverInterruptedRuns()).containsExactly(interrupted.runId());
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
