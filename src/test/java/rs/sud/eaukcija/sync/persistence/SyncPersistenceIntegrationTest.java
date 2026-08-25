package rs.sud.eaukcija.sync.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
        assertThat(runs.find(first.runId()).orElseThrow().stage()).isEqualTo(SyncRunStage.CLAIMED);
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
    void boundedErrorEvidenceStillCountsEveryResolvedFailureCrashConsistently() {
        SyncRunClaimResult claim = runs.claim(claim("bounded-errors"));
        SyncRunErrorEvidence first = new SyncRunErrorEvidence(
                SyncRunStage.DETAILS, 7, null, 1, 981L, 404,
                "HTTP_STATUS", false, 1);
        SyncRunErrorEvidence second = new SyncRunErrorEvidence(
                SyncRunStage.DETAILS, 7, null, 1, 982L, 410,
                "HTTP_STATUS", false, 2);

        runs.appendError(claim.runId(), first, true, true);
        runs.appendError(claim.runId(), second, true, false);

        SyncRunView persisted = runs.find(claim.runId()).orElseThrow();
        assertThat(persisted.errorCount()).isEqualTo(2);
        assertThat(persisted.unresolvedErrorCount()).isZero();
        assertThat(persisted.retryCount()).isOne();
        assertThat(runs.errors(claim.runId()))
                .singleElement()
                .satisfies(error -> {
                    assertThat(error.auctionId()).isEqualTo(981L);
                    assertThat(error.resolved()).isTrue();
                });
    }

    @Test
    void acceptedListingQuarantineCanTerminalizeAfterFatalRowBeforePageCountersAdvance() {
        SyncRunClaimResult claim = runs.claim(claim("listing-quarantine-partial-progress"));
        runs.appendError(claim.runId(), new SyncRunErrorEvidence(
                SyncRunStage.LISTINGS, 7, null, 1, 981L, null,
                "INVALID_DATA", false, 1), true, true);
        SyncRunProgress partial = new SyncRunProgress(
                SyncRunStage.LISTINGS, null, null,
                1, 0, 0, 1, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 1, 1);

        runs.finishIncomplete(claim.runId(), SyncRunStatus.PARTIAL, partial);

        SyncRunView persisted = runs.find(claim.runId()).orElseThrow();
        assertThat(persisted.status()).isEqualTo(SyncRunStatus.PARTIAL);
        assertThat(persisted.stage()).isEqualTo(SyncRunStage.LISTINGS);
        assertThat(persisted.listingRowsObserved()).isZero();
        assertThat(persisted.uniqueAuctionCount()).isZero();
        assertThat(persisted.listingRowsQuarantined()).isOne();
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
                "SELECT COUNT(*) FROM auction_enrichment_input_snapshots WHERE auction_id = 901",
                Long.class)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM auction_enrichment_snapshot_observations
                 WHERE source_sync_run_id = ? AND auction_id = 901
                """, Long.class, claim.runId())).isOne();
        String snapshotSha256 = jdbc.queryForObject("""
                SELECT current_enrichment_snapshot_sha256 FROM auctions WHERE id = 901
                """, String.class);
        assertThat(snapshotSha256).matches("[0-9a-f]{64}");
        assertThat(jdbc.queryForObject("""
                SELECT snapshot_sha256
                  FROM auction_enrichment_snapshot_observations
                 WHERE source_sync_run_id = ? AND auction_id = 901
                """, String.class, claim.runId())).isEqualTo(snapshotSha256);
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
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE auction_enrichment_input_snapshots
                   SET canonical_input = '{"changed":true}'::jsonb
                 WHERE auction_id = 901 AND snapshot_sha256 = ?
                """, snapshotSha256))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("enrichment input snapshots are immutable");
        assertThatThrownBy(() -> jdbc.update("""
                DELETE FROM auction_enrichment_snapshot_observations
                 WHERE source_sync_run_id = ? AND auction_id = 901
                """, claim.runId()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("enrichment snapshot observations are immutable");

        assertThat(jdbc.queryForObject("""
                SELECT delete_rule
                  FROM information_schema.referential_constraints
                 WHERE constraint_name = 'sync_run_auction_observations_auction_id_fkey'
                """, String.class)).isEqualTo("NO ACTION");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM auctions WHERE id = 901"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("sync_run_auction_observations");
        assertThat(auctions.findById(901L)).isPresent();

        SyncRunClaimResult runningTarget = runs.claim(claim("terminal-child-reparent-target"));
        jdbc.update("""
                INSERT INTO sync_run_auction_observations (
                    run_id, auction_id, listing_fingerprint, detail_refreshed,
                    enrichment_eligible, enrichment_reason
                ) VALUES (?, 901, ?, FALSE, FALSE, 'NONE')
                """, runningTarget.runId(), LISTING_HASH);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO auction_enrichment_snapshot_observations (
                    source_sync_run_id, auction_id, snapshot_sha256
                ) VALUES (?, 901, ?)
                """, runningTarget.runId(), snapshotSha256))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("only be published by a successful sync run");
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE sync_run_auction_observations
                   SET run_id = ?
                 WHERE run_id = ? AND auction_id = 901
                """, runningTarget.runId(), claim.runId()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("child evidence identity is immutable");
    }

    @Test
    void promotionChunksLargeMultiRowUpsertsBelowBindLimitsAndPersistsEveryAuctionColumn() throws Exception {
        int candidateCount = 1_005;
        SyncRunClaimResult claim = runs.claim(claim("chunked-multi-row-promotion"));
        prepareCompleteRun(claim.runId(), candidateCount, candidateCount, candidateCount, 0);

        List<AuctionPromotionCandidate> candidates = new ArrayList<>(candidateCount);
        for (int index = 0; index < candidateCount; index++) {
            long auctionId = 20_000L + index;
            Auction auction = auction(auctionId, "bulk-" + index);
            if (index == 0) {
                populateAllAuctionColumns(auction);
            }
            candidates.add(candidate(
                    auction,
                    EnrichmentReason.NEW,
                    new CategoryMembership(7, CategoryMembershipType.ROOT, "Непокретности"),
                    new CategoryMembership(47, CategoryMembershipType.CHILD, "Земљиште")));
        }

        promotion.promote(claim.runId(), TAXONOMY_HASH, OBSERVED_AT, candidates);

        assertThat(runs.find(claim.runId()).orElseThrow().status())
                .isEqualTo(SyncRunStatus.SUCCEEDED);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM auctions WHERE id BETWEEN 20000 AND 21004
                """, Long.class)).isEqualTo(candidateCount);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM auction_source_category_memberships
                 WHERE auction_id BETWEEN 20000 AND 21004
                """, Long.class)).isEqualTo(candidateCount * 2L);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM sync_run_auction_observations
                 WHERE run_id = ?
                """, Long.class, claim.runId())).isEqualTo(candidateCount);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM sync_enrichment_queue
                 WHERE run_id = ?
                """, Long.class, claim.runId())).isEqualTo(candidateCount);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM auction_enrichment_input_snapshots
                 WHERE auction_id BETWEEN 20000 AND 21004
                """, Long.class)).isEqualTo(candidateCount);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM auction_enrichment_snapshot_observations
                 WHERE source_sync_run_id = ?
                """, Long.class, claim.runId())).isEqualTo(candidateCount);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM auctions
                 WHERE id BETWEEN 20000 AND 21004
                   AND current_enrichment_snapshot_sha256 IS NOT NULL
                """, Long.class)).isEqualTo(candidateCount);

        Auction rich = auctions.findById(20_000L).orElseThrow();
        assertThat(rich.getAuctionNumber()).isEqualTo("bulk-all-columns");
        assertThat(rich.getStartDate()).isEqualTo(OBSERVED_AT.minus(Duration.ofDays(3)));
        assertThat(rich.getEndDate()).isEqualTo(OBSERVED_AT.plus(Duration.ofDays(4)));
        assertThat(rich.getPublicationDate()).isEqualTo(OBSERVED_AT.minus(Duration.ofDays(7)));
        assertThat(rich.getStartingPrice()).isEqualByComparingTo("100.01");
        assertThat(rich.getEstimatedPrice()).isEqualByComparingTo("200.02");
        assertThat(rich.getCurrentPrice()).isEqualByComparingTo("300.03");
        assertThat(rich.getMaxOfferedPrice()).isEqualByComparingTo("400.04");
        assertThat(rich.getBidStep()).isEqualByComparingTo("5.05");
        assertThat(rich.getShortDescription()).isEqualTo("short-all-columns");
        assertThat(rich.getDescription()).isEqualTo("description-all-columns");
        assertThat(rich.getStatus()).isEqualTo("ACTIVE_ALL_COLUMNS");
        assertThat(rich.isFirstSale()).isTrue();
        assertThat(rich.getPropertyType()).isEqualTo("property-all-columns");
        assertThat(rich.getExecutorName()).isEqualTo("executor-all-columns");
        assertThat(rich.getCategoryName()).isEqualTo("category-all-columns");
        assertThat(rich.getPlaceName()).isEqualTo("place-all-columns");
        assertThat(rich.getPlaceZipCode()).isEqualTo("11000");
        assertThat(rich.getMunicipality()).isEqualTo("municipality-all-columns");
        assertThat(rich.getCadastral()).isEqualTo("cadastral-all-columns");
        assertThat(rich.isDetailsFetched()).isTrue();
        assertThat(rich.getListingFingerprint()).isEqualTo(LISTING_HASH);
        assertThat(rich.getDetailsFetchedAt()).isEqualTo(OBSERVED_AT.minus(Duration.ofHours(1)));
        assertThat(rich.getSourceDetailCategoryId()).isEqualTo(47);
        assertThat(rich.getSaleScope()).isEqualTo(SaleScope.IMMOVABLE);
        assertThat(rich.getNormalizedPropertyKind()).isEqualTo(NormalizedPropertyKind.PARCEL);
        assertThat(rich.getTaxonomySha256()).isEqualTo(TAXONOMY_HASH);
        assertThat(rich.getLastSuccessfulSyncRunId()).isEqualTo(claim.runId());
        assertThat(rich.getAbsenceCount()).isZero();
        assertThat(rich.getLastSeenAt()).isEqualTo(OBSERVED_AT);
    }

    @Test
    void quarantinePublishesGoodCandidatesWithoutMutatingOrAgingBadExistingOrInsertingBadNew() throws Exception {
        SyncRunClaimResult seed = runs.claim(claim("quarantine-seed"));
        prepareCompleteRun(seed.runId(), 1, 1, 1, 0);
        promotion.promote(seed.runId(), TAXONOMY_HASH, OBSERVED_AT, List.of(
                candidate(
                        auction(970L, "existing-before-quarantine"),
                        EnrichmentReason.NEW,
                        new CategoryMembership(7, CategoryMembershipType.ROOT, "Непокретности"),
                        new CategoryMembership(47, CategoryMembershipType.CHILD, "Земљиште"))));
        String existingBefore = jdbc.queryForObject(
                "SELECT row_to_json(auction)::text FROM auctions auction WHERE id = 970",
                String.class);

        SyncRunClaimResult claim = runs.claim(claim("quarantine-success"));
        captureTaxonomy(claim.runId(), TAXONOMY_HASH);
        runs.recordRootResult(claim.runId(), completeRoot(7, 3));
        runs.recordRootResult(claim.runId(), completeRoot(8, 0));
        runs.recordChildResult(claim.runId(), completeChild(7, 47, 3));
        runs.recordChildResult(claim.runId(), completeChild(8, 121, 0));
        runs.appendError(claim.runId(), new SyncRunErrorEvidence(
                SyncRunStage.DETAILS, 7, null, 1, 970L, 404,
                "HTTP_STATUS", false, 1), true, true);
        runs.appendError(claim.runId(), new SyncRunErrorEvidence(
                SyncRunStage.DETAILS, 7, null, 1, 999L, null,
                "INVALID_DATA", false, 1), true, true);
        runs.updateProgress(claim.runId(), new SyncRunProgress(
                SyncRunStage.PROMOTING, TAXONOMY_HASH, OBSERVED_AT,
                4, 4, 3, 3, 0, 0,
                2, 2, 0, 2, 0, 0, 2, 0));

        AuctionPromotionCandidate good = candidate(
                auction(971L, "valid-neighbor"),
                EnrichmentReason.NEW,
                new CategoryMembership(7, CategoryMembershipType.ROOT, "Непокретности"),
                new CategoryMembership(47, CategoryMembershipType.CHILD, "Земљиште"));
        List<AuctionDetailQuarantine> quarantines = List.of(
                new AuctionDetailQuarantine(970L, "c".repeat(64), "HTTP_STATUS"),
                new AuctionDetailQuarantine(999L, "d".repeat(64), "INVALID_DATA"));

        promotion.promote(
                claim.runId(), TAXONOMY_HASH, OBSERVED_AT,
                List.of(good), quarantines);

        SyncRunView succeeded = runs.find(claim.runId()).orElseThrow();
        assertThat(succeeded.status()).isEqualTo(SyncRunStatus.SUCCEEDED);
        assertThat(succeeded.stage()).isEqualTo(SyncRunStage.COMPLETED);
        assertThat(succeeded.detailsQuarantined()).isEqualTo(2);
        assertThat(succeeded.errorCount()).isEqualTo(2);
        assertThat(succeeded.unresolvedErrorCount()).isZero();
        assertThat(runs.detailQuarantines(claim.runId()))
                .extracting(PersistedAuctionDetailQuarantine::auctionId)
                .containsExactly(970L, 999L);
        assertThat(runs.errors(claim.runId()))
                .allSatisfy(error -> assertThat(error.resolved()).isTrue());

        assertThat(jdbc.queryForObject(
                "SELECT row_to_json(auction)::text FROM auctions auction WHERE id = 970",
                String.class)).isEqualTo(existingBefore);
        assertThat(auctions.findById(970L).orElseThrow().getAbsenceCount()).isZero();
        assertThat(auctions.findById(999L)).isEmpty();
        assertThat(auctions.findById(971L)).isPresent();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM sync_run_auction_observations
                 WHERE run_id = ? AND auction_id IN (970, 999)
                """, Long.class, claim.runId())).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM sync_enrichment_queue
                 WHERE run_id = ? AND auction_id IN (970, 999)
                """, Long.class, claim.runId())).isZero();

        assertThatThrownBy(() -> jdbc.update("""
                UPDATE sync_run_detail_quarantines
                   SET error_code = 'OTHER'
                 WHERE run_id = ? AND auction_id = 970
                """, claim.runId()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("terminal sync run child evidence is immutable");
    }

    @Test
    void listingQuarantinePublishesValidNeighborWithoutMutatingAgingOrInsertingRejectedRows()
            throws Exception {
        SyncRunClaimResult seed = runs.claim(claim("listing-quarantine-seed"));
        prepareCompleteRun(seed.runId(), 1, 1, 1, 0);
        promotion.promote(seed.runId(), TAXONOMY_HASH, OBSERVED_AT, List.of(
                candidate(
                        auction(972L, "existing-before-listing-quarantine"),
                        EnrichmentReason.NEW,
                        new CategoryMembership(7, CategoryMembershipType.ROOT, "Непокретности"),
                        new CategoryMembership(47, CategoryMembershipType.CHILD, "Земљиште"))));
        String existingBefore = jdbc.queryForObject(
                "SELECT row_to_json(auction)::text FROM auctions auction WHERE id = 972",
                String.class);

        SyncRunClaimResult claim = runs.claim(claim("listing-quarantine-success"));
        captureTaxonomy(claim.runId(), TAXONOMY_HASH);
        runs.recordRootResult(claim.runId(), completeRoot(7, 3));
        runs.recordRootResult(claim.runId(), completeRoot(8, 0));
        runs.recordChildResult(claim.runId(), completeChild(7, 47, 3));
        runs.recordChildResult(claim.runId(), completeChild(8, 121, 0));
        runs.appendError(claim.runId(), new SyncRunErrorEvidence(
                SyncRunStage.LISTINGS, 7, 47, 1, 972L, null,
                "INVALID_DATA", false, 1), true, true);
        runs.appendError(claim.runId(), new SyncRunErrorEvidence(
                SyncRunStage.LISTINGS, 7, null, 1, 998L, null,
                "INVALID_DATA", false, 1), true, true);
        runs.updateProgress(claim.runId(), new SyncRunProgress(
                SyncRunStage.PROMOTING, TAXONOMY_HASH, OBSERVED_AT,
                4, 4, 3, 2, 3, 0, 0,
                1, 1, 1, 0, 0, 0, 2, 0));

        AuctionPromotionCandidate good = candidate(
                auction(973L, "valid-listing-neighbor"),
                EnrichmentReason.NEW,
                new CategoryMembership(7, CategoryMembershipType.ROOT, "Непокретности"),
                new CategoryMembership(47, CategoryMembershipType.CHILD, "Земљиште"));
        List<AuctionListingQuarantine> listingQuarantines = List.of(
                new AuctionListingQuarantine(
                        972L, "e".repeat(64), "INVALID_DATA", 7, 47, 1),
                new AuctionListingQuarantine(
                        998L, "f".repeat(64), "INVALID_DATA", 7, null, 1));

        promotion.promote(
                claim.runId(), TAXONOMY_HASH, OBSERVED_AT,
                List.of(good), List.of(), listingQuarantines);

        SyncRunView succeeded = runs.find(claim.runId()).orElseThrow();
        assertThat(succeeded.status()).isEqualTo(SyncRunStatus.SUCCEEDED);
        assertThat(succeeded.stage()).isEqualTo(SyncRunStage.COMPLETED);
        assertThat(succeeded.listingRowsQuarantined()).isEqualTo(2);
        assertThat(succeeded.detailsQuarantined()).isZero();
        assertThat(succeeded.errorCount()).isEqualTo(2);
        assertThat(succeeded.unresolvedErrorCount()).isZero();
        assertThat(runs.listingQuarantines(claim.runId()))
                .extracting(PersistedAuctionListingQuarantine::auctionId)
                .containsExactly(972L, 998L);

        assertThat(jdbc.queryForObject(
                "SELECT row_to_json(auction)::text FROM auctions auction WHERE id = 972",
                String.class)).isEqualTo(existingBefore);
        assertThat(auctions.findById(972L).orElseThrow().getAbsenceCount()).isZero();
        assertThat(auctions.findById(998L)).isEmpty();
        assertThat(auctions.findById(973L)).isPresent();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM sync_run_auction_observations
                 WHERE run_id = ? AND auction_id IN (972, 998)
                """, Long.class, claim.runId())).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM sync_enrichment_queue
                 WHERE run_id = ? AND auction_id IN (972, 998)
                """, Long.class, claim.runId())).isZero();

        assertThatThrownBy(() -> jdbc.update("""
                UPDATE sync_run_listing_quarantines
                   SET page_number = 2
                 WHERE run_id = ? AND auction_id = 972
                """, claim.runId()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("terminal sync run child evidence is immutable");
    }

    @Test
    void failedSuccessGateRollsBackListingQuarantineAndAbsenceUpdates() throws Exception {
        Auction quarantinedExisting = auction(977L, "listing-quarantine-rollback");
        Auction otherwiseAbsent = auction(978L, "listing-absence-rollback");
        otherwiseAbsent.setAbsenceCount(3);
        auctions.saveAllAndFlush(List.of(quarantinedExisting, otherwiseAbsent));

        SyncRunClaimResult claim = runs.claim(claim("listing-quarantine-rollback"));
        runs.recordTaxonomy(new TaxonomySnapshot(
                TAXONOMY_HASH,
                "taxonomy-v1",
                objectMapper.readTree(DEFAULT_TAXONOMY_JSON),
                OBSERVED_AT));
        captureTaxonomy(claim.runId(), TAXONOMY_HASH);
        runs.recordRootResult(claim.runId(), completeRoot(7, 1));
        runs.recordRootResult(claim.runId(), completeRoot(8, 0));
        runs.recordChildResult(claim.runId(), completeChild(7, 47, 1));
        runs.recordChildResult(claim.runId(), completeChild(8, 121, 0));
        runs.appendError(claim.runId(), new SyncRunErrorEvidence(
                SyncRunStage.LISTINGS, 7, null, 1, 977L, null,
                "INVALID_DATA", false, 1), false, true);
        runs.updateProgress(claim.runId(), new SyncRunProgress(
                SyncRunStage.PROMOTING, TAXONOMY_HASH, OBSERVED_AT,
                4, 4, 1, 1, 1, 0, 0,
                0, 0, 0, 0, 0, 0, 1, 1));

        assertThatThrownBy(() -> promotion.promote(
                claim.runId(), TAXONOMY_HASH, OBSERVED_AT,
                List.of(), List.of(), List.of(new AuctionListingQuarantine(
                        977L, "1".repeat(64), "INVALID_DATA", 7, null, 1))))
                .isInstanceOf(SyncRunStateException.class)
                .hasMessageContaining("success completeness gates");

        assertThat(runs.find(claim.runId()).orElseThrow().status())
                .isEqualTo(SyncRunStatus.RUNNING);
        assertThat(runs.listingQuarantines(claim.runId())).isEmpty();
        assertThat(auctions.findById(977L).orElseThrow().getAbsenceCount()).isZero();
        assertThat(auctions.findById(978L).orElseThrow().getAbsenceCount()).isEqualTo(3);
    }

    @Test
    void listingQuarantineCoordinatesMustBelongToConfiguredRootAndCapturedChild() throws Exception {
        SyncRunClaimResult claim = runs.claim(claim("listing-quarantine-scope"));
        runs.recordTaxonomy(new TaxonomySnapshot(
                TAXONOMY_HASH,
                "taxonomy-v1",
                objectMapper.readTree(DEFAULT_TAXONOMY_JSON),
                OBSERVED_AT));
        captureTaxonomy(claim.runId(), TAXONOMY_HASH);
        runs.recordRootResult(claim.runId(), completeRoot(7, 1));
        runs.recordRootResult(claim.runId(), completeRoot(8, 0));
        runs.recordChildResult(claim.runId(), completeChild(7, 47, 1));
        runs.recordChildResult(claim.runId(), completeChild(8, 121, 0));
        runs.appendError(claim.runId(), new SyncRunErrorEvidence(
                SyncRunStage.LISTINGS, 7, null, 1, 979L, null,
                "INVALID_DATA", false, 1), true, true);
        runs.updateProgress(claim.runId(), new SyncRunProgress(
                SyncRunStage.PROMOTING, TAXONOMY_HASH, OBSERVED_AT,
                4, 4, 1, 1, 1, 0, 0,
                0, 0, 0, 0, 0, 0, 1, 0));

        assertThatThrownBy(() -> promotion.promote(
                claim.runId(), TAXONOMY_HASH, OBSERVED_AT,
                List.of(), List.of(), List.of(new AuctionListingQuarantine(
                        979L, "2".repeat(64), "INVALID_DATA", 99, null, 1))))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("listing quarantine root is outside configured roots");
        assertThat(runs.listingQuarantines(claim.runId())).isEmpty();

        assertThatThrownBy(() -> promotion.promote(
                claim.runId(), TAXONOMY_HASH, OBSERVED_AT,
                List.of(), List.of(), List.of(new AuctionListingQuarantine(
                        979L, "3".repeat(64), "INVALID_DATA", 7, 999, 1))))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("listing quarantine child is outside captured taxonomy");
        assertThat(runs.listingQuarantines(claim.runId())).isEmpty();

        AuctionPromotionCandidate overlapping = candidate(
                auction(979L, "overlapping-listing-evidence"),
                EnrichmentReason.NEW,
                new CategoryMembership(7, CategoryMembershipType.ROOT, "Непокретности"));
        assertThatThrownBy(() -> promotion.promote(
                claim.runId(), TAXONOMY_HASH, OBSERVED_AT,
                List.of(overlapping), List.of(), List.of(new AuctionListingQuarantine(
                        979L, "4".repeat(64), "INVALID_DATA", 7, null, 1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate auction id 979");
        assertThat(auctions.findById(979L)).isEmpty();
        assertThat(runs.find(claim.runId()).orElseThrow().status())
                .isEqualTo(SyncRunStatus.RUNNING);
    }

    @Test
    void failedSuccessGateRollsBackQuarantineEvidenceAndScopedAbsenceUpdates() throws Exception {
        Auction quarantinedExisting = auction(975L, "quarantine-rollback");
        Auction otherwiseAbsent = auction(976L, "absence-rollback");
        otherwiseAbsent.setAbsenceCount(3);
        auctions.saveAllAndFlush(List.of(quarantinedExisting, otherwiseAbsent));

        SyncRunClaimResult claim = runs.claim(claim("quarantine-rollback"));
        prepareCompleteRun(claim.runId(), 1, 1, 0, 1);
        runs.updateProgress(claim.runId(), new SyncRunProgress(
                SyncRunStage.PROMOTING, TAXONOMY_HASH, OBSERVED_AT,
                4, 4, 1, 1, 0, 0,
                1, 1, 0, 1, 0, 0, 1, 1));

        assertThatThrownBy(() -> promotion.promote(
                claim.runId(), TAXONOMY_HASH, OBSERVED_AT, List.of(),
                List.of(new AuctionDetailQuarantine(
                        975L, "e".repeat(64), "INVALID_DATA"))))
                .isInstanceOf(SyncRunStateException.class)
                .hasMessageContaining("success completeness gates");

        assertThat(runs.find(claim.runId()).orElseThrow().status())
                .isEqualTo(SyncRunStatus.RUNNING);
        assertThat(runs.detailQuarantines(claim.runId())).isEmpty();
        assertThat(auctions.findById(976L).orElseThrow().getAbsenceCount()).isEqualTo(3);
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
        SyncRunView partial = runs.find(claim.runId()).orElseThrow();
        assertThat(partial.status()).isEqualTo(SyncRunStatus.PARTIAL);
        assertThat(partial.stage()).isEqualTo(SyncRunStage.PROMOTING);
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
            assertThat(runs.isStale(claim.runId(), staleAfter)).isFalse();
            assertThat(runs.find(claim.runId()).orElseThrow().status())
                    .isEqualTo(SyncRunStatus.RUNNING);
            assertThat(runs.errors(claim.runId())).isEmpty();

            jdbc.update("""
                    UPDATE sync_runs
                       SET heartbeat_at = CURRENT_TIMESTAMP - INTERVAL '16 minutes'
                     WHERE id = ?
                    """, claim.runId());

            assertThat(runs.isStale(claim.runId(), staleAfter)).isTrue();
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
    void staleRecoveryHonorsTheRetainedErrorCapWhileCountingTheRecoveryFailure() {
        SyncRunClaimResult claim = runs.claim(claim("bounded-recovery-errors"));
        runs.updateProgress(claim.runId(), new SyncRunProgress(
                SyncRunStage.LISTINGS, null, null,
                1, 1, 1, 1, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0));
        runs.appendError(claim.runId(), new SyncRunErrorEvidence(
                SyncRunStage.LISTINGS, 7, null, 1, 981L, null,
                "INVALID_DATA", true, 3), true, true);
        ageHeartbeat(claim.runId());

        try (WorkerLockLease lease = runs.tryAcquireWorkerLock().orElseThrow()) {
            assertThat(runs.recoverOrphanedRunningRuns(lease, Duration.ZERO, 1))
                    .containsExactly(claim.runId());
        }

        SyncRunView recovered = runs.find(claim.runId()).orElseThrow();
        assertThat(recovered.status()).isEqualTo(SyncRunStatus.PARTIAL);
        assertThat(recovered.stage()).isEqualTo(SyncRunStage.LISTINGS);
        assertThat(recovered.errorCount()).isEqualTo(2);
        assertThat(recovered.unresolvedErrorCount()).isOne();
        assertThat(recovered.retryCount()).isEqualTo(2);
        assertThat(runs.errors(claim.runId()))
                .singleElement()
                .satisfies(error -> {
                    assertThat(error.errorCode()).isEqualTo("INVALID_DATA");
                    assertThat(error.resolved()).isTrue();
                });
    }

    @Test
    void advisoryLockSerializesWorkersAndStaleRecoveryLeavesCurrentStateUntouched() {
        Auction existing = auction(920L, "unchanged");
        existing.setAbsenceCount(6);
        auctions.saveAndFlush(existing);
        SyncRunClaimResult claim = runs.claim(claim("recovery-1"));
        ageHeartbeat(claim.runId());

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
        ageHeartbeat(partialClaim.runId());
        try (WorkerLockLease lease = runs.tryAcquireWorkerLock().orElseThrow()) {
            assertThat(runs.recoverOrphanedRunningRuns(lease, Duration.ZERO))
                    .containsExactly(partialClaim.runId());
        }
        assertThat(runs.find(partialClaim.runId()).orElseThrow().status())
                .isEqualTo(SyncRunStatus.PARTIAL);
        assertThat(runs.find(partialClaim.runId()).orElseThrow().stage())
                .isEqualTo(SyncRunStage.LISTINGS);

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
        ageHeartbeat(taxonomyOnly.runId());
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
        ageHeartbeat(withCommittedError.runId());
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

    private void ageHeartbeat(java.util.UUID runId) {
        jdbc.update("""
                UPDATE sync_runs
                   SET heartbeat_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                 WHERE id = ?
                """, runId);
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

    private static void populateAllAuctionColumns(Auction auction) {
        auction.setAuctionNumber("bulk-all-columns");
        auction.setStartDate(OBSERVED_AT.minus(Duration.ofDays(3)));
        auction.setEndDate(OBSERVED_AT.plus(Duration.ofDays(4)));
        auction.setPublicationDate(OBSERVED_AT.minus(Duration.ofDays(7)));
        auction.setStartingPrice(new BigDecimal("100.01"));
        auction.setEstimatedPrice(new BigDecimal("200.02"));
        auction.setCurrentPrice(new BigDecimal("300.03"));
        auction.setMaxOfferedPrice(new BigDecimal("400.04"));
        auction.setBidStep(new BigDecimal("5.05"));
        auction.setShortDescription("short-all-columns");
        auction.setDescription("description-all-columns");
        auction.setStatus("ACTIVE_ALL_COLUMNS");
        auction.setFirstSale(true);
        auction.setPropertyType("property-all-columns");
        auction.setExecutorName("executor-all-columns");
        auction.setCategoryName("category-all-columns");
        auction.setPlaceName("place-all-columns");
        auction.setPlaceZipCode("11000");
        auction.setMunicipality("municipality-all-columns");
        auction.setCadastral("cadastral-all-columns");
    }

    private void truncateSyncState() {
        jdbc.execute("""
                TRUNCATE TABLE
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
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = Auction.class)
    @EnableJpaRepositories(basePackageClasses = AuctionRepository.class)
    @Import({SyncRunRepository.class, AuctionPromotionService.class})
    static class PersistenceTestApplication {
    }
}
