package rs.sud.eaukcija.sync.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import rs.sud.eaukcija.model.Auction;
import rs.sud.eaukcija.repository.AuctionRepository;
import rs.sud.eaukcija.testsupport.PostgisTestContainer;

@SpringBootTest(
        classes = SyncPersistenceIntegrationTest.PersistenceTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class SyncPersistenceIntegrationTest {

    private static final String TAXONOMY_HASH = "a".repeat(64);
    private static final String SCOPED_TAXONOMY_HASH = "d".repeat(64);
    private static final String LISTING_HASH = "b".repeat(64);
    private static final Instant OBSERVED_AT = Instant.parse("2026-08-24T10:00:00Z");
    private static final String DEFAULT_TAXONOMY_JSON = """
            [
              {"value":7,"children":[{"value":47,"children":[]}]},
              {"value":8,"children":[{"value":121,"children":[]}]}
            ]
            """;

    @ServiceConnection(name = "postgresql")
    static final PostgreSQLContainer<?> POSTGIS = PostgisTestContainer.shared();

    @Autowired
    private SyncRunRepository runs;

    @Autowired
    private AuctionPromotionService promotion;

    @Autowired
    private AuctionRepository auctions;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanBefore() {
        truncateSyncState();
    }

    @AfterEach
    void cleanAfter() {
        truncateSyncState();
    }

    @Test
    void claimIsIdempotentAndPostgresRejectsAnotherRunningOrTerminalMutation() {
        SyncRunClaimResult first = runs.claim(claim("manual-request-1"));
        SyncRunClaimResult replay = runs.claim(claim("manual-request-1"));

        assertThat(first.replayed()).isFalse();
        assertThat(replay).isEqualTo(new SyncRunClaimResult(first.runId(), true));
        assertThat(runs.findLatest()).get().extracting(SyncRunView::runId).isEqualTo(first.runId());
        assertThatThrownBy(() -> runs.claim(claim("manual-request-2")))
                .isInstanceOf(SyncAlreadyRunningException.class)
                .satisfies(failure -> assertThat(((SyncAlreadyRunningException) failure).activeRunId())
                        .isEqualTo(first.runId()));
        runs.finishIncomplete(first.runId(), SyncRunStatus.FAILED, SyncRunProgress.claimed());
        assertThat(runs.claim(claim("manual-request-2")).replayed()).isFalse();

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE sync_runs SET heartbeat_at = CURRENT_TIMESTAMP WHERE id = ?", first.runId()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("terminal sync run evidence is immutable");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM sync_runs WHERE id = ?", first.runId()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("sync run evidence cannot be deleted");
    }

    @Test
    void concurrentClaimsHaveExactlyOneDatabaseWinner() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try {
            Future<SyncRunClaimResult> first = executor.submit(() -> {
                ready.countDown();
                start.await();
                return runs.claim(claim("concurrent-a"));
            });
            Future<SyncRunClaimResult> second = executor.submit(() -> {
                ready.countDown();
                start.await();
                return runs.claim(claim("concurrent-b"));
            });
            ready.await();
            start.countDown();

            int successes = 0;
            int rejected = 0;
            for (Future<SyncRunClaimResult> result : List.of(first, second)) {
                try {
                    assertThat(result.get().replayed()).isFalse();
                    successes++;
                } catch (ExecutionException failure) {
                    assertThat(failure.getCause()).isInstanceOf(SyncAlreadyRunningException.class);
                    rejected++;
                }
            }
            assertThat(successes).isOne();
            assertThat(rejected).isOne();
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sync_runs WHERE status = 'RUNNING'", Long.class)).isOne();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void successfulPromotionAtomicallyPublishesStateAbsencesObservationsAndQueue() throws Exception {
        Auction legacyAbsent = auction(900L, "legacy-before");
        legacyAbsent.setAbsenceCount(2);
        auctions.saveAndFlush(legacyAbsent);

        SyncRunClaimResult claim = runs.claim(claim("success-1"));
        prepareCompleteRun(claim.runId(), 1, 1, 1, 0);

        Auction present = auction(901L, "fresh");
        AuctionPromotionCandidate candidate = candidate(
                present,
                EnrichmentReason.NEW,
                new CategoryMembership(7, CategoryMembershipType.ROOT, "Непокретности"),
                new CategoryMembership(47, CategoryMembershipType.CHILD, "Земљиште"));

        promotion.promote(claim.runId(), TAXONOMY_HASH, OBSERVED_AT, List.of(candidate));

        SyncRunView terminal = runs.find(claim.runId()).orElseThrow();
        assertThat(terminal.status()).isEqualTo(SyncRunStatus.SUCCEEDED);
        assertThat(terminal.stage()).isEqualTo(SyncRunStage.COMPLETED);

        Auction stored = auctions.findById(901L).orElseThrow();
        assertThat(stored.getListingFingerprint()).isEqualTo(LISTING_HASH);
        assertThat(stored.getTaxonomySha256()).isEqualTo(TAXONOMY_HASH);
        assertThat(stored.getLastSuccessfulSyncRunId()).isEqualTo(claim.runId());
        assertThat(stored.getLastSeenAt()).isEqualTo(OBSERVED_AT);
        assertThat(stored.getAbsenceCount()).isZero();
        assertThat(auctions.findById(900L).orElseThrow().getAbsenceCount()).isEqualTo(3);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM auction_source_category_memberships WHERE auction_id = 901",
                Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sync_run_auction_observations WHERE run_id = ?",
                Long.class, claim.runId())).isOne();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sync_enrichment_queue WHERE run_id = ? AND status = 'PENDING'",
                Long.class, claim.runId())).isOne();
        assertThat(jdbc.queryForObject(
                "SELECT reason FROM sync_enrichment_queue WHERE run_id = ? AND auction_id = 901",
                String.class, claim.runId())).isEqualTo("NEW");
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE sync_run_auction_observations
                   SET detail_refreshed = FALSE
                 WHERE run_id = ? AND auction_id = 901
                """, claim.runId()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("terminal sync run child evidence is immutable");

        SyncRunClaimResult runningTarget = runs.claim(claim("terminal-child-reparent-target"));
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE sync_run_auction_observations
                   SET run_id = ?
                 WHERE run_id = ? AND auction_id = 901
                """, runningTarget.runId(), claim.runId()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("child evidence identity is immutable");
    }

    @Test
    void childEndpointEvidenceIsScopedReportedAndTerminallyImmutable() throws Exception {
        SyncRunClaimResult claim = runs.claim(claim("child-evidence"));
        runs.recordTaxonomy(new TaxonomySnapshot(
                TAXONOMY_HASH,
                "taxonomy-v1",
                objectMapper.readTree(DEFAULT_TAXONOMY_JSON),
                OBSERVED_AT));
        runs.updateProgress(claim.runId(), new SyncRunProgress(
                SyncRunStage.CATEGORIES, TAXONOMY_HASH, OBSERVED_AT,
                0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0));

        SyncRunChildResult evidence = new SyncRunChildResult(
                7, 47, 2, 3, 2, 1, 1, 1,
                true, true, true);
        runs.recordChildResult(claim.runId(), evidence);

        assertThat(runs.childResults(claim.runId())).containsExactly(evidence);
        assertThatThrownBy(() -> runs.recordChildResult(claim.runId(), new SyncRunChildResult(
                8, 47, 0, 0, 0, 0, 1, 1,
                true, true, true)))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("captured direct child of a configured root");
        assertThatThrownBy(() -> runs.recordChildResult(claim.runId(), new SyncRunChildResult(
                7, 999, 0, 0, 0, 0, 1, 1,
                true, true, true)))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("captured direct child of a configured root");

        runs.finishIncomplete(claim.runId(), SyncRunStatus.PARTIAL, new SyncRunProgress(
                SyncRunStage.LISTINGS, TAXONOMY_HASH, OBSERVED_AT,
                1, 1, 3, 2, 1, 0,
                0, 0, 0, 0, 0, 0, 0));
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE sync_run_child_results
                   SET rows_observed = 2, duplicate_ids = 0
                 WHERE run_id = ? AND parent_root_category_id = 7 AND child_category_id = 47
                """, claim.runId()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("terminal sync run child evidence is immutable");
    }

    @Test
    void missingOrNonSubsetChildEvidenceBlocksPromotionAndRollsBackEverything() throws Exception {
        SyncRunClaimResult missing = runs.claim(claim("missing-child-gate"));
        prepareCompleteRun(missing.runId(), 1, 1, 1, 0);
        jdbc.update("""
                DELETE FROM sync_run_child_results
                 WHERE run_id = ? AND parent_root_category_id = 8 AND child_category_id = 121
                """, missing.runId());

        AuctionPromotionCandidate missingCandidate = candidate(
                auction(902L, "missing-child"),
                EnrichmentReason.NEW,
                new CategoryMembership(7, CategoryMembershipType.ROOT, "Непокретности"),
                new CategoryMembership(47, CategoryMembershipType.CHILD, "Земљиште"));
        assertThatThrownBy(() -> promotion.promote(
                missing.runId(), TAXONOMY_HASH, OBSERVED_AT, List.of(missingCandidate)))
                .isInstanceOf(SyncRunStateException.class)
                .hasMessageContaining("captured direct child");
        assertUnpublished(missing.runId(), 902L);
        runs.finishIncomplete(missing.runId(), SyncRunStatus.PARTIAL,
                completeProgress(TAXONOMY_HASH, 4, 4, 1, 1, 1, 0));

        SyncRunClaimResult nonSubset = runs.claim(claim("non-subset-child-gate"));
        prepareCompleteRun(nonSubset.runId(), 1, 1, 1, 0);
        runs.recordChildResult(nonSubset.runId(), new SyncRunChildResult(
                7, 47, 1, 1, 1, 0, 1, 1,
                true, false, false));

        AuctionPromotionCandidate nonSubsetCandidate = candidate(
                auction(903L, "non-subset-child"),
                EnrichmentReason.NEW,
                new CategoryMembership(7, CategoryMembershipType.ROOT, "Непокретности"),
                new CategoryMembership(47, CategoryMembershipType.CHILD, "Земљиште"));
        assertThatThrownBy(() -> promotion.promote(
                nonSubset.runId(), TAXONOMY_HASH, OBSERVED_AT, List.of(nonSubsetCandidate)))
                .isInstanceOf(SyncRunStateException.class)
                .hasMessageContaining("captured direct child");
        assertUnpublished(nonSubset.runId(), 903L);
        runs.finishIncomplete(nonSubset.runId(), SyncRunStatus.PARTIAL,
                completeProgress(TAXONOMY_HASH, 4, 4, 1, 1, 1, 0));

        SyncRunClaimResult wrongParentMembership = runs.claim(claim("wrong-child-parent-membership"));
        prepareCompleteRun(wrongParentMembership.runId(), 1, 1, 1, 0);
        AuctionPromotionCandidate wrongParentCandidate = candidate(
                auction(904L, "wrong-child-parent"),
                EnrichmentReason.NEW,
                new CategoryMembership(8, CategoryMembershipType.ROOT, "Покретности"),
                new CategoryMembership(47, CategoryMembershipType.CHILD, "Земљиште"));
        assertThatThrownBy(() -> promotion.promote(
                wrongParentMembership.runId(),
                TAXONOMY_HASH,
                OBSERVED_AT,
                List.of(wrongParentCandidate)))
                .isInstanceOf(SyncRunStateException.class)
                .hasMessageContaining("captured parent root");
        assertUnpublished(wrongParentMembership.runId(), 904L);
    }

    @Test
    void absenceScopeUsesRetainedRootMembershipsRatherThanHardCodedSaleScopeRoots() throws Exception {
        runs.recordTaxonomy(new TaxonomySnapshot(
                SCOPED_TAXONOMY_HASH,
                "taxonomy-nondefault-roots",
                objectMapper.readTree("""
                        [{"value":42,"children":[]},{"value":99,"children":[]}]
                        """),
                OBSERVED_AT));

        SyncRunClaimResult seed = runs.claim(new SyncRunClaimRequest(
                "nondefault-root-seed", List.of(42, 99), 3000, SyncTriggerKind.MANUAL));
        runs.recordRootResult(seed.runId(), completeRoot(42, 1));
        runs.recordRootResult(seed.runId(), completeRoot(99, 1));
        runs.updateProgress(seed.runId(), completeProgress(
                SCOPED_TAXONOMY_HASH, 2, 2, 2, 2, 2, 0));
        promotion.promote(seed.runId(), SCOPED_TAXONOMY_HASH, OBSERVED_AT, List.of(
                candidate(
                        auction(930L, "root-42"),
                        EnrichmentReason.NEW,
                        new CategoryMembership(42, CategoryMembershipType.ROOT, "Root 42")),
                candidate(
                        auction(931L, "root-99"),
                        EnrichmentReason.NEW,
                        new CategoryMembership(99, CategoryMembershipType.ROOT, "Root 99"))));

        SyncRunClaimResult root42Only = runs.claim(new SyncRunClaimRequest(
                "nondefault-root-absence", List.of(42), 3000, SyncTriggerKind.MANUAL));
        runs.recordRootResult(root42Only.runId(), completeRoot(42, 0));
        runs.updateProgress(root42Only.runId(), completeProgress(
                SCOPED_TAXONOMY_HASH, 1, 1, 0, 0, 0, 0));
        promotion.promote(root42Only.runId(), SCOPED_TAXONOMY_HASH, OBSERVED_AT, List.of());

        assertThat(auctions.findById(930L).orElseThrow().getAbsenceCount()).isOne();
        assertThat(auctions.findById(931L).orElseThrow().getAbsenceCount()).isZero();
    }

    @Test
    void observedAuctionRetainsRootAndChildMembershipsOutsideTheCurrentRunScope() throws Exception {
        runs.recordTaxonomy(new TaxonomySnapshot(
                SCOPED_TAXONOMY_HASH,
                "taxonomy-nondefault-roots",
                objectMapper.readTree("""
                        [
                          {"value":42,"children":[{"value":420,"children":[]}]},
                          {"value":99,"children":[{"value":990,"children":[]}]}
                        ]
                        """),
                OBSERVED_AT));

        SyncRunClaimResult seed = runs.claim(new SyncRunClaimRequest(
                "cross-scope-seed", List.of(42, 99), 3000, SyncTriggerKind.MANUAL));
        captureTaxonomy(seed.runId(), SCOPED_TAXONOMY_HASH);
        runs.recordRootResult(seed.runId(), completeRoot(42, 1));
        runs.recordRootResult(seed.runId(), completeRoot(99, 1));
        runs.recordChildResult(seed.runId(), completeChild(42, 420, 1));
        runs.recordChildResult(seed.runId(), completeChild(99, 990, 1));
        runs.updateProgress(seed.runId(), completeProgress(
                SCOPED_TAXONOMY_HASH, 4, 4, 1, 1, 1, 0));
        promotion.promote(seed.runId(), SCOPED_TAXONOMY_HASH, OBSERVED_AT, List.of(
                candidate(
                        auction(935L, "both-roots"),
                        EnrichmentReason.NEW,
                        new CategoryMembership(42, CategoryMembershipType.ROOT, "Root 42"),
                        new CategoryMembership(420, CategoryMembershipType.CHILD, "Child 420 seed"),
                        new CategoryMembership(99, CategoryMembershipType.ROOT, "Root 99"),
                        new CategoryMembership(990, CategoryMembershipType.CHILD, "Child 990"))));

        SyncRunClaimResult root42 = runs.claim(new SyncRunClaimRequest(
                "cross-scope-root-42", List.of(42), 3000, SyncTriggerKind.MANUAL));
        captureTaxonomy(root42.runId(), SCOPED_TAXONOMY_HASH);
        runs.recordRootResult(root42.runId(), completeRoot(42, 1));
        runs.recordChildResult(root42.runId(), completeChild(42, 420, 1));
        runs.updateProgress(root42.runId(), completeProgress(
                SCOPED_TAXONOMY_HASH, 2, 2, 1, 1, 1, 0));
        promotion.promote(root42.runId(), SCOPED_TAXONOMY_HASH, OBSERVED_AT, List.of(
                candidate(
                        auction(935L, "seen-under-root-42"),
                        EnrichmentReason.LISTING_CHANGED,
                        new CategoryMembership(42, CategoryMembershipType.ROOT, "Root 42 refreshed"),
                        new CategoryMembership(420, CategoryMembershipType.CHILD, "Child 420 refreshed"))));

        assertThat(jdbc.queryForList("""
                SELECT category_id
                  FROM auction_source_category_memberships
                 WHERE auction_id = 935 AND membership_type = 'ROOT'
                 ORDER BY category_id
                """, Integer.class)).containsExactly(42, 99);
        assertThat(jdbc.queryForList("""
                SELECT category_id
                  FROM auction_source_category_memberships
                 WHERE auction_id = 935 AND membership_type = 'CHILD'
                 ORDER BY category_id
                """, Integer.class)).containsExactly(420, 990);
        assertThat(jdbc.queryForObject("""
                SELECT last_successful_sync_run_id
                  FROM auction_source_category_memberships
                 WHERE auction_id = 935
                   AND category_id = 420
                   AND membership_type = 'CHILD'
                """, java.util.UUID.class)).isEqualTo(root42.runId());
        assertThat(jdbc.queryForObject("""
                SELECT last_successful_sync_run_id
                  FROM auction_source_category_memberships
                 WHERE auction_id = 935
                   AND category_id = 990
                   AND membership_type = 'CHILD'
                """, java.util.UUID.class)).isEqualTo(seed.runId());
        assertThat(jdbc.queryForObject("""
                SELECT category_name
                  FROM auction_source_category_memberships
                 WHERE auction_id = 935
                   AND category_id = 420
                   AND membership_type = 'CHILD'
                """, String.class)).isEqualTo("Child 420 refreshed");

        SyncRunClaimResult root99 = runs.claim(new SyncRunClaimRequest(
                "cross-scope-root-99", List.of(99), 3000, SyncTriggerKind.MANUAL));
        captureTaxonomy(root99.runId(), SCOPED_TAXONOMY_HASH);
        runs.recordRootResult(root99.runId(), completeRoot(99, 0));
        runs.recordChildResult(root99.runId(), completeChild(99, 990, 0));
        runs.updateProgress(root99.runId(), completeProgress(
                SCOPED_TAXONOMY_HASH, 2, 2, 0, 0, 0, 0));
        promotion.promote(root99.runId(), SCOPED_TAXONOMY_HASH, OBSERVED_AT, List.of());

        assertThat(auctions.findById(935L).orElseThrow().getAbsenceCount()).isOne();
    }

    @Test
    void wrongCompleteRootsWithTheSameCardinalityCannotPassThePromotionGate() throws Exception {
        runs.recordTaxonomy(new TaxonomySnapshot(
                TAXONOMY_HASH,
                "taxonomy-v1",
                objectMapper.readTree(DEFAULT_TAXONOMY_JSON),
                OBSERVED_AT));
        SyncRunClaimResult claim = runs.claim(claim("wrong-root-set"));

        // Bypass only the early ledger-scope guard to prove the final
        // promotion gate independently compares identities, not cardinality.
        jdbc.execute("""
                ALTER TABLE sync_run_root_results
                DISABLE TRIGGER trg_sync_root_result_scope
                """);
        try {
            runs.recordRootResult(claim.runId(), completeRoot(7, 1));
            runs.recordRootResult(claim.runId(), completeRoot(99, 0));
        } finally {
            jdbc.execute("""
                    ALTER TABLE sync_run_root_results
                    ENABLE TRIGGER trg_sync_root_result_scope
                    """);
        }
        runs.updateProgress(claim.runId(), completeProgress(1, 1, 1, 0));

        AuctionPromotionCandidate candidate = candidate(
                auction(940L, "must-not-promote"),
                EnrichmentReason.NEW,
                new CategoryMembership(7, CategoryMembershipType.ROOT, "Непокретности"));
        assertThatThrownBy(() -> promotion.promote(
                claim.runId(), TAXONOMY_HASH, OBSERVED_AT, List.of(candidate)))
                .isInstanceOf(SyncRunStateException.class)
                .hasMessageContaining("not every configured root");

        assertThat(runs.find(claim.runId()).orElseThrow().status()).isEqualTo(SyncRunStatus.RUNNING);
        assertThat(auctions.findById(940L)).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sync_run_auction_observations", Long.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sync_enrichment_queue", Long.class)).isZero();
    }

    @Test
    void failedSuccessGateRollsBackAuctionAbsenceMembershipObservationAndQueue() throws Exception {
        Auction existing = auction(910L, "old-state");
        existing.setListingFingerprint("c".repeat(64));
        existing.setSaleScope(SaleScope.IMMOVABLE);
        existing.setNormalizedPropertyKind(NormalizedPropertyKind.PARCEL);
        existing.setAbsenceCount(4);
        auctions.saveAndFlush(existing);

        SyncRunClaimResult claim = runs.claim(claim("partial-1"));
        prepareCompleteRun(claim.runId(), 1, 1, 1, 1);
        runs.appendError(claim.runId(), new SyncRunErrorEvidence(
                SyncRunStage.DETAILS, 7, null, 1, 910L, 503,
                "DETAIL_RETRY_EXHAUSTED", true, 3));

        Auction changed = auction(910L, "must-not-publish");
        AuctionPromotionCandidate candidate = candidate(
                changed,
                EnrichmentReason.LISTING_CHANGED,
                new CategoryMembership(7, CategoryMembershipType.ROOT, "Непокретности"));

        assertThatThrownBy(() -> promotion.promote(
                claim.runId(), TAXONOMY_HASH, OBSERVED_AT, List.of(candidate)))
                .isInstanceOf(SyncRunStateException.class)
                .hasMessageContaining("success completeness gates");

        Auction afterRollback = auctions.findById(910L).orElseThrow();
        assertThat(afterRollback.getAuctionNumber()).isEqualTo("old-state");
        assertThat(afterRollback.getAbsenceCount()).isEqualTo(4);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM auction_source_category_memberships", Long.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sync_run_auction_observations", Long.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sync_enrichment_queue", Long.class)).isZero();

        runs.finishIncomplete(claim.runId(), SyncRunStatus.PARTIAL,
                completeProgress(1, 1, 1, 1));
        assertThat(runs.find(claim.runId()).orElseThrow().status()).isEqualTo(SyncRunStatus.PARTIAL);
        assertThat(runs.errors(claim.runId()))
                .singleElement()
                .extracting(PersistedSyncRunError::errorCode)
                .isEqualTo("DETAIL_RETRY_EXHAUSTED");

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO sync_enrichment_queue (run_id, auction_id, reason)
                VALUES (?, 910, 'LISTING_CHANGED')
                """, claim.runId()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("successful sync run");
    }

    @Test
    void staleRecoveryPreservesAYoungHeartbeatAndRecoversItOnlyAfterTheBoundary() {
        SyncRunClaimResult claim = runs.claim(claim("recovery-heartbeat-boundary"));
        Duration staleAfter = Duration.ofMinutes(15);

        try (WorkerLockLease lease = runs.tryAcquireWorkerLock().orElseThrow()) {
            jdbc.update("""
                    UPDATE sync_runs
                       SET heartbeat_at = CURRENT_TIMESTAMP - INTERVAL '14 minutes'
                     WHERE id = ?
                    """, claim.runId());

            assertThat(runs.recoverOrphanedRunningRuns(lease, staleAfter)).isEmpty();
            assertThat(runs.find(claim.runId()).orElseThrow().status())
                    .isEqualTo(SyncRunStatus.RUNNING);
            assertThat(runs.errors(claim.runId())).isEmpty();

            jdbc.update("""
                    UPDATE sync_runs
                       SET heartbeat_at = CURRENT_TIMESTAMP - INTERVAL '16 minutes'
                     WHERE id = ?
                    """, claim.runId());

            assertThat(runs.recoverOrphanedRunningRuns(lease, staleAfter))
                    .containsExactly(claim.runId());
        }

        assertThat(runs.find(claim.runId()).orElseThrow().status())
                .isEqualTo(SyncRunStatus.FAILED);
        assertThat(runs.errors(claim.runId()))
                .singleElement()
                .extracting(PersistedSyncRunError::errorCode)
                .isEqualTo("STALE_RUN_RECOVERED");
    }

    @Test
    void advisoryLockSerializesWorkersAndStaleRecoveryLeavesCurrentStateUntouched() {
        Auction existing = auction(920L, "unchanged");
        existing.setAbsenceCount(6);
        auctions.saveAndFlush(existing);
        SyncRunClaimResult claim = runs.claim(claim("recovery-1"));

        try (WorkerLockLease lease = runs.tryAcquireWorkerLock().orElseThrow()) {
            assertThat(runs.tryAcquireWorkerLock()).isEmpty();
            assertThat(runs.recoverOrphanedRunningRuns(lease, Duration.ZERO))
                    .containsExactly(claim.runId());
        }

        assertThat(runs.find(claim.runId()).orElseThrow().status()).isEqualTo(SyncRunStatus.FAILED);
        assertThat(runs.errors(claim.runId()))
                .singleElement()
                .extracting(PersistedSyncRunError::errorCode)
                .isEqualTo("STALE_RUN_RECOVERED");
        assertThat(auctions.findById(920L).orElseThrow().getAbsenceCount()).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sync_enrichment_queue", Long.class)).isZero();

        SyncRunClaimResult partialClaim = runs.claim(claim("recovery-with-progress"));
        runs.updateProgress(partialClaim.runId(), new SyncRunProgress(
                SyncRunStage.LISTINGS, null, null,
                1, 1, 1, 1, 0, 0,
                0, 0, 0, 0, 0, 0, 0));
        try (WorkerLockLease lease = runs.tryAcquireWorkerLock().orElseThrow()) {
            assertThat(runs.recoverOrphanedRunningRuns(lease, Duration.ZERO))
                    .containsExactly(partialClaim.runId());
        }
        assertThat(runs.find(partialClaim.runId()).orElseThrow().status())
                .isEqualTo(SyncRunStatus.PARTIAL);

        SyncRunClaimResult taxonomyOnly = runs.claim(claim("recovery-with-taxonomy"));
        runs.recordTaxonomy(new TaxonomySnapshot(
                TAXONOMY_HASH,
                "taxonomy-v1",
                objectMapper.createArrayNode(),
                OBSERVED_AT));
        runs.updateProgress(taxonomyOnly.runId(), new SyncRunProgress(
                SyncRunStage.CATEGORIES, TAXONOMY_HASH, OBSERVED_AT,
                0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0));
        try (WorkerLockLease lease = runs.tryAcquireWorkerLock().orElseThrow()) {
            assertThat(runs.recoverOrphanedRunningRuns(lease, Duration.ZERO))
                    .containsExactly(taxonomyOnly.runId());
        }
        assertThat(runs.find(taxonomyOnly.runId()).orElseThrow().status())
                .isEqualTo(SyncRunStatus.PARTIAL);

        SyncRunClaimResult withCommittedError = runs.claim(claim("recovery-with-error-evidence"));
        runs.appendError(withCommittedError.runId(), new SyncRunErrorEvidence(
                SyncRunStage.CATEGORIES, null, null, null, null, 503,
                "SOURCE_UNAVAILABLE", true, 3));
        SyncRunView beforeRecovery = runs.find(withCommittedError.runId()).orElseThrow();
        assertThat(beforeRecovery.errorCount()).isOne();
        assertThat(beforeRecovery.retryCount()).isEqualTo(2);
        try (WorkerLockLease lease = runs.tryAcquireWorkerLock().orElseThrow()) {
            assertThat(runs.recoverOrphanedRunningRuns(lease, Duration.ZERO))
                    .containsExactly(withCommittedError.runId());
        }
        SyncRunView recoveredWithTwoErrors = runs.find(withCommittedError.runId()).orElseThrow();
        assertThat(recoveredWithTwoErrors.status()).isEqualTo(SyncRunStatus.FAILED);
        assertThat(recoveredWithTwoErrors.errorCount()).isEqualTo(2);
        assertThat(recoveredWithTwoErrors.unresolvedErrorCount()).isEqualTo(2);
        assertThat(recoveredWithTwoErrors.retryCount()).isEqualTo(2);
        assertThat(runs.errors(withCommittedError.runId()))
                .extracting(PersistedSyncRunError::errorCode)
                .containsExactly("SOURCE_UNAVAILABLE", "STALE_RUN_RECOVERED");
    }

    private void prepareCompleteRun(
            java.util.UUID runId,
            long uniqueAuctions,
            long detailsRequired,
            long detailsSucceeded,
            long unresolvedErrors) throws Exception {
        runs.recordTaxonomy(new TaxonomySnapshot(
                TAXONOMY_HASH,
                "taxonomy-v1",
                objectMapper.readTree(DEFAULT_TAXONOMY_JSON),
                OBSERVED_AT));
        runs.updateProgress(runId, new SyncRunProgress(
                SyncRunStage.CATEGORIES, TAXONOMY_HASH, OBSERVED_AT,
                0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0));
        runs.recordRootResult(runId, new SyncRunRootResult(
                7, uniqueAuctions, uniqueAuctions, uniqueAuctions, 0,
                1, 1, true, true));
        runs.recordRootResult(runId, new SyncRunRootResult(
                8, 0, 0, 0, 0,
                1, 1, true, true));
        runs.recordChildResult(runId, completeChild(7, 47, uniqueAuctions));
        runs.recordChildResult(runId, completeChild(8, 121, 0));
        runs.updateProgress(runId, completeProgress(
                TAXONOMY_HASH, 4, 4, uniqueAuctions,
                detailsRequired, detailsSucceeded, unresolvedErrors));
    }

    private void captureTaxonomy(java.util.UUID runId, String taxonomyHash) {
        runs.updateProgress(runId, new SyncRunProgress(
                SyncRunStage.CATEGORIES, taxonomyHash, OBSERVED_AT,
                0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0));
    }

    private static SyncRunProgress completeProgress(
            long uniqueAuctions,
            long detailsRequired,
            long detailsSucceeded,
            long unresolvedErrors) {
        return completeProgress(
                TAXONOMY_HASH, 2, 2, uniqueAuctions,
                detailsRequired, detailsSucceeded, unresolvedErrors);
    }

    private static SyncRunProgress completeProgress(
            String taxonomyHash,
            int pagesExpected,
            int pagesCompleted,
            long uniqueAuctions,
            long detailsRequired,
            long detailsSucceeded,
            long unresolvedErrors) {
        return new SyncRunProgress(
                SyncRunStage.PROMOTING,
                taxonomyHash,
                OBSERVED_AT,
                pagesExpected,
                pagesCompleted,
                uniqueAuctions,
                uniqueAuctions,
                0,
                0,
                detailsRequired,
                detailsRequired,
                detailsSucceeded,
                detailsRequired - detailsSucceeded,
                0,
                unresolvedErrors,
                unresolvedErrors);
    }

    private static SyncRunRootResult completeRoot(int rootCategoryId, long sourceTotalCount) {
        return new SyncRunRootResult(
                rootCategoryId,
                sourceTotalCount,
                sourceTotalCount,
                sourceTotalCount,
                0,
                1,
                1,
                true,
                true);
    }

    private static SyncRunChildResult completeChild(
            int parentRootCategoryId,
            int childCategoryId,
            long sourceTotalCount) {
        return new SyncRunChildResult(
                parentRootCategoryId,
                childCategoryId,
                sourceTotalCount,
                sourceTotalCount,
                sourceTotalCount,
                0,
                1,
                1,
                true,
                true,
                true);
    }

    private void assertUnpublished(java.util.UUID runId, long auctionId) {
        assertThat(runs.find(runId).orElseThrow().status()).isEqualTo(SyncRunStatus.RUNNING);
        assertThat(auctions.findById(auctionId)).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sync_run_auction_observations WHERE run_id = ?",
                Long.class,
                runId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sync_enrichment_queue WHERE run_id = ?",
                Long.class,
                runId)).isZero();
    }

    private static SyncRunClaimRequest claim(String key) {
        return new SyncRunClaimRequest(key, List.of(7, 8), 3000, SyncTriggerKind.MANUAL);
    }

    private static AuctionPromotionCandidate candidate(
            Auction auction,
            EnrichmentReason reason,
            CategoryMembership... memberships) {
        return new AuctionPromotionCandidate(
                auction,
                LISTING_HASH,
                OBSERVED_AT.minus(Duration.ofHours(1)),
                47,
                SaleScope.IMMOVABLE,
                NormalizedPropertyKind.PARCEL,
                List.of(memberships),
                true,
                reason);
    }

    private static Auction auction(long id, String number) {
        Auction auction = new Auction();
        auction.setId(id);
        auction.setAuctionNumber(number);
        auction.setDetailsFetched(true);
        return auction;
    }

    private void truncateSyncState() {
        jdbc.execute("""
                TRUNCATE TABLE
                    sync_enrichment_queue,
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
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = Auction.class)
    @EnableJpaRepositories(basePackageClasses = AuctionRepository.class)
    @Import({SyncRunRepository.class, AuctionPromotionService.class})
    static class PersistenceTestApplication {
    }
}
