package rs.sud.eaukcija.sync.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.sql.DataSource;

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
import rs.sud.eaukcija.snapshot.AuctionSourceSnapshotFactory;
import rs.sud.eaukcija.snapshot.AuctionSourceCanonicalJson;
import rs.sud.eaukcija.snapshot.AuctionSourceSnapshotReplayParser;
import rs.sud.eaukcija.testsupport.Fixtures;
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

    @Autowired
    private DataSource dataSource;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Autowired
    private rs.sud.eaukcija.history.SourceHistoryService history;

    @BeforeEach
    void cleanBefore() {
        truncateSyncState();
    }

    @AfterEach
    void cleanAfter() {
        truncateSyncState();
    }

    /**
     * Issue #43. The application host and the database host do not share a clock.
     * Every timestamp on a run row must come from one clock, so an hour of skew
     * cannot violate {@code ck_sync_run_time} when the run terminalizes.
     */
    @Test
    void terminalizationSurvivesADatabaseClockThatTrailsTheApplicationClock() {
        Duration skew = Duration.ofHours(1);
        SyncRunRepository skewed = skewedRuns(skew);

        SyncRunClaimResult claimed = skewed.claim(claim("clock-skew-terminal"));
        skewed.finishIncomplete(
                claimed.runId(), SyncRunStatus.FAILED, SyncRunProgress.claimed());

        SyncRunView terminal = skewed.find(claimed.runId()).orElseThrow();
        assertThat(terminal.status()).isEqualTo(SyncRunStatus.FAILED);
        assertThat(terminal.startedAt())
                .as("started_at comes from the skewed application clock")
                .isAfter(Instant.now().plus(skew).minus(Duration.ofMinutes(5)));
        assertThat(terminal.finishedAt())
                .as("ck_sync_run_time requires finished_at >= started_at")
                .isNotNull()
                .isAfterOrEqualTo(terminal.startedAt());
    }

    /**
     * Issue #43. The heartbeat lease compares a stored {@code heartbeat_at} with a
     * boundary the application computes, so both must come from the same clock or a
     * live run is reclaimed immediately and a dead one is never reclaimed at all.
     */
    @Test
    void heartbeatLeaseComparesAStoredHeartbeatAgainstTheSameClockThatWroteIt() {
        SyncRunRepository skewed = skewedRuns(Duration.ofHours(1));

        SyncRunClaimResult claimed = skewed.claim(claim("clock-skew-lease"));
        skewed.updateProgress(claimed.runId(), SyncRunProgress.claimed());

        assertThat(skewed.isStale(claimed.runId(), Duration.ofMinutes(10)))
                .as("a heartbeat just written by the application clock is not stale")
                .isFalse();
        assertThat(skewed.isStale(claimed.runId(), Duration.ZERO))
                .as("an elapsed lease is still detected")
                .isTrue();
    }

    private SyncRunRepository skewedRuns(Duration skew) {
        return new SyncRunRepository(
                dataSource, objectMapper, Clock.offset(Clock.systemUTC(), skew));
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
                "SELECT COUNT(*) FROM auction_source_snapshots WHERE auction_id = 901",
                Long.class)).isOne();
        String sourceSnapshotSha256 = jdbc.queryForObject("""
                SELECT current_source_snapshot_sha256 FROM auctions WHERE id = 901
                """, String.class);
        assertThat(sourceSnapshotSha256).matches("[0-9a-f]{64}");
        assertThat(jdbc.queryForObject("""
                SELECT source_snapshot_sha256
                  FROM sync_run_auction_observations
                 WHERE run_id = ? AND auction_id = 901
                """, String.class, claim.runId())).isEqualTo(sourceSnapshotSha256);
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
                    enrichment_eligible, enrichment_reason, source_snapshot_sha256
                ) VALUES (?, 901, ?, FALSE, FALSE, 'NONE', ?)
                """, runningTarget.runId(), LISTING_HASH, sourceSnapshotSha256);
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
    void sourceSnapshotsDeduplicateAppendCorrectionsAndRejectMutation() throws Exception {
        SyncRunClaimResult firstRun = runs.claim(claim("source-snapshot-first"));
        prepareCompleteRun(firstRun.runId(), 1, 1, 1, 0, OBSERVED_AT);
        promotion.promote(
                firstRun.runId(), TAXONOMY_HASH, OBSERVED_AT,
                List.of(candidateWithExponentMoney(
                        auction(911L, "N911"),
                        EnrichmentReason.NEW,
                        new CategoryMembership(7, CategoryMembershipType.ROOT, "Непокретности"),
                        new CategoryMembership(47, CategoryMembershipType.CHILD, "Земљиште"))));
        String original = jdbc.queryForObject("""
                SELECT current_source_snapshot_sha256 FROM auctions WHERE id = 911
                """, String.class);

        Instant unchangedAt = OBSERVED_AT.plusSeconds(60);
        SyncRunClaimResult unchangedRun = runs.claim(claim("source-snapshot-unchanged"));
        prepareCompleteRun(unchangedRun.runId(), 1, 1, 1, 0, unchangedAt);
        promotion.promote(
                unchangedRun.runId(), TAXONOMY_HASH, unchangedAt,
                List.of(candidateWithExponentMoney(
                        auction(911L, "N911"),
                        EnrichmentReason.NONE,
                        new CategoryMembership(7, CategoryMembershipType.ROOT, "Непокретности"),
                        new CategoryMembership(47, CategoryMembershipType.CHILD, "Земљиште"))));

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM auction_source_snapshots WHERE auction_id = 911",
                Long.class)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT canonical_payload::text FROM auction_source_snapshots
                 WHERE auction_id = 911
                """, String.class))
                .contains("\"StartingPrice\": 159600")
                .doesNotContain("1.596E");
        assertThat(jdbc.queryForObject("""
                SELECT source_snapshot_sha256 FROM sync_run_auction_observations
                 WHERE run_id = ? AND auction_id = 911
                """, String.class, unchangedRun.runId())).isEqualTo(original);

        Instant changedAt = OBSERVED_AT.plusSeconds(120);
        SyncRunClaimResult changedRun = runs.claim(claim("source-snapshot-changed"));
        prepareCompleteRun(changedRun.runId(), 1, 1, 1, 0, changedAt);
        promotion.promote(
                changedRun.runId(), TAXONOMY_HASH, changedAt,
                List.of(candidateWithExponentMoney(
                        auction(911L, "N911-corrected"),
                        EnrichmentReason.LISTING_CHANGED,
                        new CategoryMembership(7, CategoryMembershipType.ROOT, "Непокретности"),
                        new CategoryMembership(47, CategoryMembershipType.CHILD, "Земљиште"))));
        String corrected = jdbc.queryForObject("""
                SELECT current_source_snapshot_sha256 FROM auctions WHERE id = 911
                """, String.class);

        assertThat(corrected).isNotEqualTo(original);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM auction_source_snapshots WHERE auction_id = 911",
                Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForList("""
                SELECT content_sha256 FROM auction_source_snapshots
                 WHERE auction_id = 911 ORDER BY content_sha256
                """, String.class)).containsExactlyInAnyOrder(original, corrected);
        assertThat(runs.currentSourceSnapshots(List.of(911L)).get(911L).contentSha256())
                .isEqualTo(corrected);

        assertThatThrownBy(() -> jdbc.update("""
                UPDATE auction_source_snapshots
                   SET minimization_policy_version = 'tampered'
                 WHERE auction_id = 911 AND content_sha256 = ?
                """, original))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("auction source snapshots are immutable");
        assertThatThrownBy(() -> jdbc.update("""
                DELETE FROM auction_source_snapshots
                 WHERE auction_id = 911 AND content_sha256 = ?
                """, original))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("auction source snapshots are immutable");
    }

    @Test
    void goldenSourceSnapshotRetainsRepresentativePostgresStorageEvidence() throws Exception {
        var listingEnvelope = AuctionSourceCanonicalJson.readTree(
                Fixtures.read("eaukcija/auctions-by-category-page1.json"));
        var detailEnvelope = AuctionSourceCanonicalJson.readTree(
                Fixtures.read("eaukcija/immovable-property-detail.json"));
        var sourceSnapshot = new AuctionSourceSnapshotFactory(objectMapper).create(
                180466L,
                listingEnvelope.path("Data").path("Auctions").get(0),
                detailEnvelope.path("Data"),
                SaleScope.IMMOVABLE,
                OBSERVED_AT,
                OBSERVED_AT.plusSeconds(1));
        Auction sourceAuction = auction(180466L, "Н180466");
        AuctionPromotionCandidate sourceCandidate = new AuctionPromotionCandidate(
                sourceAuction,
                LISTING_HASH,
                OBSERVED_AT.plusSeconds(1),
                47,
                SaleScope.IMMOVABLE,
                NormalizedPropertyKind.PARCEL,
                List.of(
                        new CategoryMembership(7, CategoryMembershipType.ROOT, "Непокретности"),
                        new CategoryMembership(47, CategoryMembershipType.CHILD, "Земљиште")),
                true,
                EnrichmentReason.NEW,
                sourceSnapshot);
        SyncRunClaimResult run = runs.claim(claim("golden-source-storage"));
        prepareCompleteRun(run.runId(), 1, 1, 1, 0, OBSERVED_AT);

        promotion.promote(
                run.runId(), TAXONOMY_HASH, OBSERVED_AT, List.of(sourceCandidate));

        var storedCurrent = runs.currentSourceSnapshots(List.of(180466L)).get(180466L);
        assertThat(storedCurrent.canonicalPayload().path("detail").path("EstimatedPrice")
                .decimalValue().toPlainString()).isEqualTo("228000.00");
        AuctionSourceSnapshotFactory sourceFactory =
                new AuctionSourceSnapshotFactory(objectMapper);
        var reusedSnapshot = sourceFactory.combineWithCurrentDetail(
                180466L,
                sourceFactory.minimizeListing(
                        180466L,
                        listingEnvelope.path("Data").path("Auctions").get(0)),
                storedCurrent,
                OBSERVED_AT.plusSeconds(60));
        assertThat(reusedSnapshot.contentSha256())
                .isEqualTo(sourceSnapshot.contentSha256());

        Instant reusedAt = OBSERVED_AT.plusSeconds(60);
        SyncRunClaimResult reusedRun = runs.claim(claim("golden-source-storage-reuse"));
        prepareCompleteRun(reusedRun.runId(), 1, 0, 0, 0, reusedAt);
        AuctionPromotionCandidate reusedCandidate = new AuctionPromotionCandidate(
                auction(180466L, "Н180466"),
                LISTING_HASH,
                sourceSnapshot.detailFetchedAt(),
                47,
                SaleScope.IMMOVABLE,
                NormalizedPropertyKind.PARCEL,
                sourceCandidate.memberships(),
                false,
                EnrichmentReason.NONE,
                reusedSnapshot);
        promotion.promote(
                reusedRun.runId(), TAXONOMY_HASH, reusedAt, List.of(reusedCandidate));

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM auction_source_snapshots WHERE auction_id = 180466",
                Long.class)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT current_source_snapshot_sha256 FROM auctions WHERE id = 180466
                """, String.class)).isEqualTo(sourceSnapshot.contentSha256());
        assertThat(jdbc.queryForObject("""
                SELECT source_snapshot_sha256 FROM sync_run_auction_observations
                 WHERE run_id = ? AND auction_id = 180466
                """, String.class, reusedRun.runId()))
                .isEqualTo(sourceSnapshot.contentSha256());

        Map<String, Object> evidence = jdbc.queryForMap("""
                SELECT pg_column_size(canonical_payload) AS stored_bytes,
                       octet_length(canonical_payload::text) AS export_bytes,
                       canonical_payload::text LIKE '%redaction-sentinel%' AS leaked_binary,
                       canonical_payload::text LIKE '%UnmappedFutureField%' AS leaked_unreviewed
                  FROM auction_source_snapshots
                 WHERE auction_id = 180466 AND content_sha256 = ?
                """, sourceSnapshot.contentSha256());
        int storedBytes = ((Number) evidence.get("stored_bytes")).intValue();
        int exportBytes = ((Number) evidence.get("export_bytes")).intValue();
        assertThat(storedBytes).isBetween(500, 4_096);
        assertThat(exportBytes).isBetween(500, AuctionSourceSnapshotFactory.MAX_CANONICAL_BYTES);
        assertThat(evidence).containsEntry("leaked_binary", false)
                .containsEntry("leaked_unreviewed", false);
        String storedPayload = jdbc.queryForObject("""
                SELECT canonical_payload::text FROM auction_source_snapshots
                 WHERE auction_id = 180466 AND content_sha256 = ?
                """, String.class, sourceSnapshot.contentSha256());
        assertThat(storedPayload).contains("\"StartingPrice\": 159600.00");
        var replayed = new AuctionSourceSnapshotReplayParser(objectMapper)
                .parse(objectMapper.readTree(storedPayload));
        assertThat(replayed.listing().auctionNumber()).isEqualTo("Н180466");
        assertThat(replayed.detail().place().cadastral()).isEqualTo("Димитровград");
        System.err.printf(
                "issue10 source snapshot storage evidence storedBytes=%d exportBytes=%d%n",
                storedBytes, exportBytes);
    }

    @Test
    void promotionAcceptsTheSameObservationAfterPostgresRoundsNanosecondsToMicroseconds()
            throws Exception {
        Instant highPrecisionObservedAt = OBSERVED_AT.plusNanos(123_456_789);
        SyncRunClaimResult claim = runs.claim(claim("postgres-timestamp-precision"));
        prepareCompleteRun(claim.runId(), 1, 1, 1, 0, highPrecisionObservedAt);
        AuctionPromotionCandidate candidate = candidate(
                auction(902L, "postgres-timestamp-precision"),
                EnrichmentReason.NEW,
                new CategoryMembership(7, CategoryMembershipType.ROOT, "Непокретности"),
                new CategoryMembership(47, CategoryMembershipType.CHILD, "Земљиште"));

        promotion.promote(
                claim.runId(), TAXONOMY_HASH, highPrecisionObservedAt, List.of(candidate));

        assertThat(runs.find(claim.runId()).orElseThrow().status())
                .isEqualTo(SyncRunStatus.SUCCEEDED);
        assertThat(auctions.findById(902L)).isPresent();
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
                  FROM auctions
                 WHERE id BETWEEN 20000 AND 21004
                   AND current_source_snapshot_sha256 IS NOT NULL
                """, Long.class)).isEqualTo(candidateCount);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM auction_source_snapshots
                 WHERE auction_id BETWEEN 20000 AND 21004
                """, Long.class)).isEqualTo(candidateCount);
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

    @Test
    void orderedHistoryKeepsMondayChangesAfterUnchangedTuesdayAndRecordsReturnToAnOldHash() throws Exception {
        Instant t = OBSERVED_AT;
        Instant end = t.plus(Duration.ofDays(30));
        var first = publishHistory(t, List.of(historyCandidate(1101, "A", end)), Duration.ofDays(1));
        var reviewed = history.revisions(List.of(1101L), first.publication(), t).get(0).review();
        var refresh = publishHistory(t.plusSeconds(1), List.of(historyCandidate(1101, "A", end)), Duration.ofDays(1));
        assertThat(history.revisions(List.of(1101L), refresh.publication(), t).get(0).review()).isEqualTo(reviewed);
        // An intervening complete run can omit this identity; comparison is not global-run-to-run.
        publishHistory(t.plusSeconds(2), List.of(historyCandidate(1102, "other", end)), Duration.ofDays(1));
        var monday = publishHistory(t.plusSeconds(3), List.of(historyCandidate(1101, "B", end)), Duration.ofDays(1));
        var returned = publishHistory(t.plusSeconds(4), List.of(historyCandidate(1101, "A", end)), Duration.ofDays(1));
        var tuesday = publishHistory(t.plusSeconds(5), List.of(historyCandidate(1101, "A", end)), Duration.ofDays(1));
        var comparison = history.compare(reviewed, tuesday.publication(), t.plusSeconds(5));
        assertThat(comparison.sourceActivityCount()).isEqualTo(2);
        assertThat(comparison.net().fields()).isEmpty();
        assertThat(comparison.net().kind()).isEqualTo("UNCHANGED");
        assertThat(comparison.review().revision()).isEqualTo(returned.publication());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auction_source_snapshots WHERE auction_id=1101", Long.class)).isEqualTo(2);
        var page = history.changes(first.publication(), tuesday.publication(), t.plusSeconds(5), null, 200);
        assertThat(page.activities().stream().filter(a -> a.auctionId() == 1101).toList())
                .extracting(rs.sud.eaukcija.history.SourceHistoryService.Activity::publication)
                .containsExactly(monday.publication(), returned.publication());
        assertThat(page.activities().stream().filter(a -> a.auctionId() == 1101).toList())
                .allSatisfy(a -> {
                    assertThat(a.contentDelta()).isEqualTo("UPDATED");
                    assertThat(a.changedFields()).containsExactly("DESCRIPTION");
                });
        var revision = history.revisions(List.of(1101L), tuesday.publication(), t).get(0);
        assertThat(revision.firstObservedAt()).isEqualTo(t);
        assertThat(revision.lastSourceChange()).isEqualTo(returned.publication());
        assertThat(revision.lastObserved()).isEqualTo(tuesday.publication());
        assertThat(jdbc.queryForList("SELECT content_delta FROM sync_run_auction_observations WHERE auction_id=1101 ORDER BY publication_id", String.class))
                .containsExactly("NEW", "UNCHANGED", "UPDATED", "UPDATED", "UNCHANGED");
        var incompatible = new rs.sud.eaukcija.history.SourceHistoryService.Review(1101, reviewed.revision(), t, "future-policy");
        assertThat(history.compare(incompatible, tuesday.publication(), t).net().kind()).isEqualTo("UNSUPPORTED");
    }

    @Test
    void persistedReviewPolicySeparatesRepresentationAndStartingPriceFromLiveBidding() throws Exception {
        var membership = new CategoryMembership(7, CategoryMembershipType.ROOT, "root");
        var a = candidate(auction(1191, "price"), EnrichmentReason.DETAIL_REFRESHED, new BigDecimal("100.0"), membership);
        var b = candidate(auction(1191, "price"), EnrichmentReason.DETAIL_REFRESHED, new BigDecimal("100.00"), membership);
        var first = publishHistory(OBSERVED_AT, List.of(a), Duration.ZERO);
        var reviewed = history.revisions(List.of(1191L), first.publication(), OBSERVED_AT).get(0).review();
        var representation = publishHistory(OBSERVED_AT.plusSeconds(1), List.of(b), Duration.ZERO);
        assertThat(history.compare(reviewed, representation.publication(), OBSERVED_AT).net().kind()).isEqualTo("REPRESENTATION_ONLY");
        var event = history.auctionChanges(1191, first.publication(), representation.publication(), OBSERVED_AT, null, 10).activities().get(0);
        assertThat(event.contentDelta()).isEqualTo("UPDATED");
        assertThat(event.comparisonKind()).isEqualTo("REPRESENTATION_ONLY");
        var starting = publishHistory(OBSERVED_AT.plusSeconds(2), List.of(candidate(auction(1191, "price"),
                EnrichmentReason.NONE, new BigDecimal("101"), membership)), Duration.ZERO);
        assertThat(history.compare(reviewed, starting.publication(), OBSERVED_AT).net().fields()).containsExactly("STARTING_PRICE");
        var priced = candidate(auction(1191, "price"), EnrichmentReason.DETAIL_REFRESHED, new BigDecimal("101"), membership);
        var payload = priced.sourceSnapshot().canonicalPayload();
        ((com.fasterxml.jackson.databind.node.ObjectNode) payload.path("listing")).put("CurrentPrice", 102);
        var liveSnapshot = new AuctionSourceSnapshotFactory(objectMapper).create(1191, payload.path("listing"), payload.path("detail"),
                SaleScope.IMMOVABLE, OBSERVED_AT, OBSERVED_AT);
        var tick = new AuctionPromotionCandidate(priced.auction(), priced.listingFingerprint(), priced.detailsFetchedAt(),
                priced.sourceDetailCategoryId(), priced.saleScope(), priced.propertyKind(), priced.memberships(), true,
                priced.enrichmentReason(), liveSnapshot);
        var live = publishHistory(OBSERVED_AT.plusSeconds(3), List.of(tick), Duration.ZERO);
        var liveEvent = history.auctionChanges(1191, starting.publication(), live.publication(), OBSERVED_AT, null, 10).activities().get(0);
        assertThat(liveEvent.comparisonKind()).isEqualTo("LIVE_BIDDING_ONLY");
        assertThat(liveEvent.changedFields()).containsExactly("CURRENT_PRICE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auction_source_snapshots WHERE auction_id=1191", Long.class)).isEqualTo(4);
        // The same stored classification, including legacy coverage, is consumed by #30.
        var metrics = new rs.sud.eaukcija.operations.PipelineStatusRepository(jdbc,
                org.flywaydb.core.Flyway.configure().dataSource(dataSource).load()).read().lastSuccessfulSync();
        assertThat(metrics.rawSnapshotChanges()).isEqualTo(new rs.sud.eaukcija.operations.PipelineStatus.SnapshotChanges(0, 1, 0, 0));
        assertThat(metrics.sourcePublication().reference()).isEqualTo(live.publication());
    }

    @Test
    void lifecycleUsesInclusiveEndTimeWithoutFabricatingSourceActivityAndExtensionIsAuditable() throws Exception {
        Instant t = OBSERVED_AT;
        Instant end = t.plusSeconds(10);
        var first = publishHistory(t, List.of(historyCandidate(1111, "A", end), historyCandidate(1112, "unknown", null)), Duration.ZERO);
        var reviewed = history.revisions(List.of(1111L), first.publication(), t).get(0).review();
        assertThat(history.compare(reviewed, first.publication(), end.minusNanos(1)).elapsedEnd()).isFalse();
        var elapsed = history.compare(reviewed, first.publication(), end);
        assertThat(elapsed.elapsedEnd()).isTrue();
        assertThat(elapsed.sourceActivityCount()).isZero();
        assertThat(elapsed.lifecycleActivityCount()).isZero();
        assertThat(lifecycleState(1111)).containsEntry("lifecycle_state", "NOT_ENDED");
        assertThat(lifecycleState(1112)).containsEntry("lifecycle_state", "UNKNOWN");
        var closed = publishHistory(end, List.of(historyCandidate(1111, "A", end)), Duration.ZERO);
        var event = history.changes(first.publication(), closed.publication(), end, null, 20).activities().stream()
                .filter(a -> a.auctionId() == 1111).findFirst().orElseThrow();
        assertThat(event.contentDelta()).isEqualTo("UNCHANGED");
        assertThat(event.lifecycle().transition()).isEqualTo("CLOSED");
        assertThat(event.lifecycle().effectiveAt()).isEqualTo(end);
        assertThat(event.lifecycle().recordedAt()).isEqualTo(end);
        var extended = publishHistory(end.plusSeconds(1), List.of(historyCandidate(1111, "A", end.plusSeconds(60))), Duration.ZERO);
        var reopened = history.changes(closed.publication(), extended.publication(), end.plusSeconds(1), null, 20).activities().stream()
                .filter(a -> a.auctionId() == 1111).findFirst().orElseThrow();
        assertThat(reopened.contentDelta()).isEqualTo("UPDATED");
        assertThat(reopened.lifecycle().transition()).isEqualTo("REOPENED");
        assertThat(reopened.changedFields()).containsExactly("END_DATE");
        assertThat(jdbc.queryForObject("SELECT status FROM auctions WHERE id=1111", String.class)).isEqualTo("SOURCE_WORKFLOW");
        var changedClosed = publishHistory(end.plusSeconds(61), List.of(historyCandidate(1111, "B", end.plusSeconds(60))), Duration.ZERO);
        var both = history.changes(extended.publication(), changedClosed.publication(), end.plusSeconds(61), null, 20).activities().get(0);
        assertThat(both.contentDelta()).isEqualTo("UPDATED");
        assertThat(both.lifecycle().transition()).isEqualTo("CLOSED");
        assertThat(both.lifecycle().effectiveAt()).isEqualTo(end.plusSeconds(60));
        assertThat(both.lifecycle().recordedAt()).isEqualTo(end.plusSeconds(61));
    }

    @Test
    void twoEligibleAbsencesAndGraceAreIndependentOfFailedRunsAndRepeatedReappearance() throws Exception {
        Instant t = OBSERVED_AT;
        Instant end = t.plus(Duration.ofDays(30));
        publishHistory(t, List.of(historyCandidate(1121, "A", end)), Duration.ofDays(1));
        publishHistory(t.plusSeconds(3600), List.of(), Duration.ofDays(1));
        assertThat(lifecycleState(1121)).containsEntry("absence_count", 1L).containsEntry("lifecycle_state", "NOT_ENDED");
        var failed = runs.claim(claim("between-absences-failed"));
        runs.finishIncomplete(failed.runId(), SyncRunStatus.FAILED, SyncRunProgress.claimed());
        var partial = runs.claim(claim("between-absences-partial"));
        runs.finishIncomplete(partial.runId(), SyncRunStatus.PARTIAL, SyncRunProgress.claimed());
        publishHistory(t.plusSeconds(3600 + 86400 - 1), List.of(), Duration.ofDays(1));
        assertThat(lifecycleState(1121)).containsEntry("absence_count", 2L).containsEntry("lifecycle_state", "NOT_ENDED");
        publishHistory(t.plusSeconds(3600 + 86400), List.of(), Duration.ofDays(1));
        assertThat(lifecycleState(1121)).containsEntry("lifecycle_reason", "ABSENCE");
        publishHistory(t.plusSeconds(90001), List.of(historyCandidate(1121, "A", end)), Duration.ZERO);
        assertThat(lifecycleState(1121)).containsEntry("absence_count", 0L).containsEntry("lifecycle_state", "NOT_ENDED");
        publishHistory(t.plusSeconds(90002), List.of(), Duration.ZERO);
        assertThat(lifecycleState(1121)).containsEntry("lifecycle_state", "NOT_ENDED");
        publishHistory(t.plusSeconds(90002), List.of(), Duration.ZERO);
        assertThat(lifecycleState(1121)).containsEntry("lifecycle_reason", "ABSENCE");
        publishHistory(t.plusSeconds(90003), List.of(historyCandidate(1121, "A", end)), Duration.ZERO);
        assertThat(jdbc.queryForList("SELECT transition_kind FROM auction_lifecycle_transitions WHERE auction_id=1121 ORDER BY publication_id", String.class))
                .containsExactly("END_TIME_CHANGED", "CLOSED", "REOPENED", "CLOSED", "REOPENED");
        // Presence cannot reopen a still-past-ended auction, including combined reasons.
        publishHistory(end, List.of(historyCandidate(1121, "A", end)), Duration.ZERO);
        publishHistory(end.plusSeconds(1), List.of(), Duration.ZERO);
        publishHistory(end.plusSeconds(2), List.of(), Duration.ZERO);
        assertThat(lifecycleState(1121)).containsEntry("lifecycle_reason", "END_DATE_AND_ABSENCE");
        publishHistory(end.plusSeconds(3), List.of(historyCandidate(1121, "A", end)), Duration.ZERO);
        assertThat(lifecycleState(1121)).containsEntry("lifecycle_state", "ENDED").containsEntry("lifecycle_reason", "END_DATE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auction_lifecycle_transitions WHERE auction_id=1121 AND transition_kind='REOPENED'", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auction_source_snapshots WHERE auction_id=1121", Long.class)).isOne();
    }

    @Test
    void publicationBoundariesAreOrderedAtEqualTimestampsValidateLineageAndPaginateExclusively() throws Exception {
        Instant t = OBSERVED_AT;
        var origin = history.capture(t).publication();
        var first = publishHistory(t, List.of(historyCandidate(1131, "A", null), historyCandidate(1132, "A", null)), Duration.ZERO);
        var second = publishHistory(t, List.of(historyCandidate(1131, "B", null)), Duration.ZERO);
        assertThat(second.publication().sequence()).isGreaterThan(first.publication().sequence());
        assertThat(second.publishedAt()).isEqualTo(first.publishedAt());
        assertThat(history.atOrBefore(t)).isEqualTo(second.publication());
        var firstPage = history.changes(origin, first.publication(), t, null, 1);
        assertThat(firstPage.activities()).hasSize(1);
        assertThat(firstPage.next()).isNotNull();
        var secondPage = history.changes(origin, first.publication(), t, firstPage.next(), 1);
        assertThat(secondPage.activities()).hasSize(1);
        assertThat(secondPage.next()).isNull();
        assertThat(secondPage.activities().get(0).auctionId()).isNotEqualTo(firstPage.activities().get(0).auctionId());
        assertThat(history.changes(first.publication(), first.publication(), t, null, 1).activities()).isEmpty();
        assertThatThrownBy(() -> history.atOrBefore(t.minusNanos(1000))).hasMessage("HISTORICAL_COVERAGE_UNAVAILABLE");
        var foreign = new rs.sud.eaukcija.history.SourceHistoryService.Reference(java.util.UUID.randomUUID(), first.publication().sequence(), first.publication().runId());
        assertThatThrownBy(() -> history.changes(foreign, second.publication(), t, null, 10)).hasMessage("FOREIGN_LINEAGE");
        var restoredBranch = new rs.sud.eaukcija.history.SourceHistoryService.Reference(first.publication().lineage(), first.publication().sequence(), java.util.UUID.randomUUID());
        assertThatThrownBy(() -> history.changes(restoredBranch, second.publication(), t, null, 10)).hasMessage("UNKNOWN_PUBLICATION");
        assertThatThrownBy(() -> history.changes(second.publication(), first.publication(), t, null, 10)).hasMessage("REVERSED_BOUNDARIES");
        assertThatThrownBy(() -> history.changes(origin, second.publication(), t, null, 201)).hasMessage("INVALID_PAGE_SIZE");
        assertThatThrownBy(() -> jdbc.update("UPDATE source_publications SET published_at=published_at")).hasMessageContaining("immutable");
        var running = runs.claim(claim("not-a-publication"));
        assertThatThrownBy(() -> jdbc.update("INSERT INTO source_publications(run_id,published_at) VALUES (?,?)", running.runId(), java.time.OffsetDateTime.ofInstant(t, java.time.ZoneOffset.UTC)))
                .hasMessageContaining("requires committed successful sync");
        assertThat(history.capture(t).publication()).isEqualTo(second.publication());
    }

    @Test
    void comparisonPolicyUpgradeIsBaselineMaintenanceNotANewSourceUpdate() throws Exception {
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        var olderPolicy = new SyncRunRepository(dataSource, objectMapper, Clock.fixed(OBSERVED_AT, java.time.ZoneOffset.UTC)) {
            @Override public void publishSourceHistory(rs.sud.eaukcija.history.SourceHistoryPublisher.Publication publication, Duration grace) {
                super.publishSourceHistory(publication, grace);
                // Simulate an earlier deployed policy while its original transaction is still RUNNING.
                jdbc.update("UPDATE sync_run_auction_observations SET comparison_policy='source-review-v0' WHERE run_id=?", publication.runId());
            }
        };
        var run = tx.execute(status -> olderPolicy.claim(claim("old-comparison-policy")));
        prepareCompleteRun(run.runId(), 1, 1, 1, 0);
        var candidate = historyCandidate(1139, "A", null);
        tx.executeWithoutResult(status -> new AuctionPromotionService(olderPolicy).promote(run.runId(), TAXONOMY_HASH, OBSERVED_AT, List.of(candidate)));
        var first = history.capture(OBSERVED_AT);
        var reviewed = history.revisions(List.of(1139L), first.publication(), OBSERVED_AT).get(0).review();
        assertThat(reviewed.comparisonPolicy()).isEqualTo("source-review-v0");
        var upgraded = publishHistory(OBSERVED_AT.plusSeconds(1), List.of(candidate), Duration.ZERO);
        var maintenance = history.auctionChanges(1139, first.publication(), upgraded.publication(), OBSERVED_AT, null, 10).activities().get(0);
        assertThat(maintenance.contentDelta()).isEqualTo("UNCHANGED");
        assertThat(maintenance.comparisonKind()).isEqualTo("BASELINE");
        var comparison = history.compare(reviewed, upgraded.publication(), OBSERVED_AT);
        assertThat(comparison.sourceActivityCount()).isZero();
        assertThat(comparison.net().kind()).isEqualTo("UNSUPPORTED");
        assertThat(comparison.review().comparisonPolicy()).isEqualTo("source-review-v1");
        assertThat(history.revisions(List.of(1139L), first.publication(), OBSERVED_AT).get(0).review()).isEqualTo(reviewed);
        assertThat(history.revisions(List.of(1139L), upgraded.publication(), OBSERVED_AT).get(0).lastSourceChange()).isEqualTo(first.publication());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auction_source_snapshots WHERE auction_id=1139", Long.class)).isOne();
    }

    @Test
    void publicationClockCannotRegressWhenTheApplicationClockMovesBackwards() throws Exception {
        var first = publishHistory(OBSERVED_AT, List.of(historyCandidate(1140, "A", null)), Duration.ZERO);
        var later = publishHistory(OBSERVED_AT.minusSeconds(3600), List.of(historyCandidate(1140, "B", null)), Duration.ZERO);
        assertThat(later.publication().sequence()).isGreaterThan(first.publication().sequence());
        assertThat(later.publishedAt()).isEqualTo(first.publishedAt());
        assertThat(runs.find(later.publication().runId()).orElseThrow().finishedAt()).isEqualTo(later.publishedAt());
        assertThat(history.atOrBefore(OBSERVED_AT)).isEqualTo(later.publication());
        var metrics = new rs.sud.eaukcija.operations.PipelineStatusRepository(jdbc,
                org.flywaydb.core.Flyway.configure().dataSource(dataSource).load()).read();
        assertThat(metrics.lastSuccessfulSync().sourcePublication().reference()).isEqualTo(later.publication());
    }

    @Test
    void legacyIdentityEstablishesBaselineWithoutNewOrUnlikeEnrichmentHashComparison() throws Exception {
        auctions.saveAndFlush(auction(1141, "legacy"));
        var frame = publishHistory(OBSERVED_AT, List.of(historyCandidate(1141, "legacy", null)), Duration.ZERO);
        assertThat(jdbc.queryForObject("SELECT content_delta FROM sync_run_auction_observations WHERE auction_id=1141", String.class)).isEqualTo("BASELINE");
        assertThat(jdbc.queryForObject("SELECT last_source_change_publication FROM auctions WHERE id=1141", Long.class)).isNull();
        assertThat(history.revisions(List.of(1141L), frame.publication(), OBSERVED_AT).get(0).review()).isNotNull();
        var again = publishHistory(OBSERVED_AT.plusSeconds(1), List.of(historyCandidate(1141, "legacy", null)), Duration.ZERO);
        assertThat(history.revisions(List.of(1141L), again.publication(), OBSERVED_AT).get(0).review().revision()).isEqualTo(frame.publication());
    }

    @Test
    void historyAndLifecycleReadsAreBoundedForSixHundredAuctions() throws Exception {
        Instant t = OBSERVED_AT;
        List<AuctionPromotionCandidate> candidates = new ArrayList<>();
        for (int i=0; i<600; i++) candidates.add(historyCandidate(2000+i, "fixture", t.plusSeconds(i < 20 ? 1 : 86400 * 30)));
        var origin = history.capture(t).publication();
        publishHistory(t, candidates, Duration.ZERO);
        publishHistory(t.plusSeconds(1), List.of(), Duration.ZERO);
        var closed = publishHistory(t.plusSeconds(2), List.of(), Duration.ZERO);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auctions WHERE lifecycle_state='ENDED'", Long.class)).isEqualTo(600);
        int events = 0;
        rs.sud.eaukcija.history.SourceHistoryService.Cursor cursor = null;
        do {
            var page = history.changes(origin, closed.publication(), t.plusSeconds(2), cursor, 200);
            assertThat(page.activities()).hasSizeLessThanOrEqualTo(200);
            events += page.activities().size(); cursor = page.next();
        } while (cursor != null);
        assertThat(events).isEqualTo(1220);
        assertThat(jdbc.queryForMap("SELECT absent_count, closed_count, reopened_count FROM source_publications WHERE publication_id=?", closed.publication().sequence()))
                .containsEntry("absent_count", 600L).containsEntry("closed_count", 580L).containsEntry("reopened_count", 0L);
        jdbc.execute("ANALYZE sync_run_auction_observations");
        jdbc.execute("ANALYZE auction_lifecycle_transitions");
        var plan = jdbc.queryForList("EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) SELECT publication_id FROM sync_run_auction_observations WHERE auction_id=2001 AND publication_id <= "
                + closed.publication().sequence() + " ORDER BY publication_id DESC LIMIT 1", String.class);
        assertThat(String.join("\n", plan)).contains("Index");
        System.out.println("issue11-600-history-plan\n" + String.join("\n", plan));
        var lifecyclePlan = jdbc.queryForList("EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) SELECT * FROM auction_lifecycle_transitions WHERE auction_id=2001 AND publication_id <= "
                + closed.publication().sequence() + " ORDER BY publication_id DESC LIMIT 200", String.class);
        assertThat(String.join("\n", lifecyclePlan)).contains("Index");
        System.out.println("issue11-600-lifecycle-plan\n" + String.join("\n", lifecyclePlan));
        long started = System.nanoTime();
        assertThat(history.revisions(java.util.stream.LongStream.range(2000, 2200).boxed().toList(), closed.publication(), t).size()).isEqualTo(200);
        System.out.println("issue11-600-batch-revisions-ms=" + (System.nanoTime()-started)/1_000_000.0);
    }

    @Test
    void repeatableReadDisplayFrameCannotRaceALaterSuccessfulPublication() throws Exception {
        Instant t = OBSERVED_AT;
        var first = publishHistory(t, List.of(historyCandidate(1151, "A", null)), Duration.ZERO);
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        tx.setReadOnly(true); tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        var executor = Executors.newSingleThreadExecutor();
        try {
            tx.executeWithoutResult(status -> {
                assertThat(history.capture(t).publication()).isEqualTo(first.publication());
                try { executor.submit(() -> publishHistory(t.plusSeconds(1), List.of(historyCandidate(1151, "B", null)), Duration.ZERO)).get(); }
                catch (Exception e) { throw new RuntimeException(e); }
                assertThat(history.capture(t).publication()).isEqualTo(first.publication());
                assertThat(jdbc.queryForObject("SELECT description FROM auctions WHERE id=1151", String.class)).isEqualTo("A");
            });
        } finally { executor.shutdownNow(); }
        assertThat(history.capture(t).publication().sequence()).isGreaterThan(first.publication().sequence());
    }

    @Test
    void concurrentAndSameRunPromotionCannotDuplicateHistoryOrAccrueAbsenceTwice() throws Exception {
        auctions.saveAndFlush(auction(1160, "legacy-absent"));
        var run = runs.claim(claim("concurrent-promotion"));
        prepareCompleteRun(run.runId(), 1, 1, 1, 0);
        var candidate = historyCandidate(1161, "A", null);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Boolean> task = () -> {
                start.await();
                try { promotion.promote(run.runId(), TAXONOMY_HASH, OBSERVED_AT, List.of(candidate)); return true; }
                catch (SyncRunStateException alreadyPromoted) { return false; }
            };
            var a = executor.submit(task); var b = executor.submit(task); start.countDown();
            assertThat(List.of(a.get(), b.get())).containsExactlyInAnyOrder(true, false);
        } finally { executor.shutdownNow(); }
        assertThatThrownBy(() -> promotion.promote(run.runId(), TAXONOMY_HASH, OBSERVED_AT, List.of(candidate)))
                .isInstanceOf(SyncRunStateException.class);
        assertThat(lifecycleState(1160)).containsEntry("absence_count", 1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_publications", Long.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sync_run_auction_observations", Long.class)).isOne();
        var duplicate = runs.claim(claim("duplicate-promotion-ids"));
        prepareCompleteRun(duplicate.runId(), 2, 2, 2, 0);
        assertThatThrownBy(() -> promotion.promote(duplicate.runId(), TAXONOMY_HASH, OBSERVED_AT, List.of(candidate, candidate)))
                .hasMessageContaining("duplicate auction id");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_publications", Long.class)).isOne();
    }

    @Test
    void failureAfterSuccessGateRollsBackPublicationLifecycleAndEnrichmentTogether() throws Exception {
        Instant t = OBSERVED_AT;
        var first = publishHistory(t, List.of(historyCandidate(1171, "A", t.plusSeconds(1))), Duration.ZERO);
        var before = jdbc.queryForObject("SELECT row_to_json(a)::text FROM auctions a WHERE id=1171", String.class);
        var run = runs.claim(claim("late-rollback"));
        prepareCompleteRun(run.runId(), 1, 1, 1, 0);
        jdbc.execute("CREATE FUNCTION issue11_reject_queue() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fixture downstream failure'; END $$");
        jdbc.execute("CREATE TRIGGER issue11_reject_queue BEFORE INSERT ON sync_enrichment_queue FOR EACH ROW EXECUTE FUNCTION issue11_reject_queue()");
        try {
            assertThatThrownBy(() -> promotion.promote(run.runId(), TAXONOMY_HASH, t,
                    List.of(historyCandidate(1171, "B", t.plusSeconds(1)))))
                    .hasMessageContaining("fixture downstream failure");
            assertThat(history.capture(t).publication()).isEqualTo(first.publication());
            assertThat(jdbc.queryForObject("SELECT row_to_json(a)::text FROM auctions a WHERE id=1171", String.class)).isEqualTo(before);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM auction_source_snapshots WHERE auction_id=1171", Long.class)).isOne();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM auction_lifecycle_transitions WHERE auction_id=1171", Long.class)).isOne();
            assertThat(runs.find(run.runId()).orElseThrow().status()).isEqualTo(SyncRunStatus.RUNNING);
        } finally {
            jdbc.execute("DROP TRIGGER issue11_reject_queue ON sync_enrichment_queue");
            jdbc.execute("DROP FUNCTION issue11_reject_queue()");
        }
    }

    @Test
    void quarantineFreezesAnExistingAbsenceStreakAndDoesNotReopenIt() throws Exception {
        Instant t = OBSERVED_AT;
        publishHistory(t, List.of(historyCandidate(1181, "A", t.plusSeconds(86400))), Duration.ZERO);
        publishHistory(t.plusSeconds(1), List.of(), Duration.ZERO);
        String before = jdbc.queryForObject("SELECT row_to_json(a)::text FROM auctions a WHERE id=1181", String.class);
        var run = runs.claim(claim("history-detail-holdback"));
        captureTaxonomy(run.runId(), TAXONOMY_HASH);
        runs.recordRootResult(run.runId(), completeRoot(7, 1)); runs.recordRootResult(run.runId(), completeRoot(8, 0));
        runs.recordChildResult(run.runId(), completeChild(7, 47, 1)); runs.recordChildResult(run.runId(), completeChild(8, 121, 0));
        runs.appendError(run.runId(), new SyncRunErrorEvidence(SyncRunStage.DETAILS, 7, null, 1, 1181L,
                404, "HTTP_STATUS", false, 1), true, true);
        runs.updateProgress(run.runId(), new SyncRunProgress(SyncRunStage.PROMOTING, TAXONOMY_HASH, t,
                4,4,1,1,0,0,1,1,0,1,0,0,1,0));
        promotion.promote(run.runId(), TAXONOMY_HASH, t, List.of(), List.of(new AuctionDetailQuarantine(1181, LISTING_HASH, "HTTP_STATUS")));
        assertThat(jdbc.queryForObject("SELECT row_to_json(a)::text FROM auctions a WHERE id=1181", String.class)).isEqualTo(before);
        publishHistory(Instant.now().plusSeconds(1), List.of(), Duration.ZERO);
        assertThat(lifecycleState(1181)).containsEntry("absence_count", 2L).containsEntry("lifecycle_state", "ENDED");
    }

    private Map<String, Object> lifecycleState(long id) {
        return jdbc.queryForMap("SELECT absence_count, lifecycle_state, lifecycle_reason FROM auctions WHERE id=?", id);
    }

    private rs.sud.eaukcija.history.SourceHistoryService.Frame publishHistory(Instant at,
            List<AuctionPromotionCandidate> candidates, Duration grace) throws Exception {
        var fixed = new SyncRunRepository(dataSource, objectMapper, Clock.fixed(at, java.time.ZoneOffset.UTC));
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        var claim = tx.execute(status -> fixed.claim(claim(java.util.UUID.randomUUID().toString())));
        prepareCompleteRun(claim.runId(), candidates.size(), candidates.size(), candidates.size(), 0, at);
        var promoter = new AuctionPromotionService(fixed); promoter.setAbsenceGrace(grace);
        tx.executeWithoutResult(status -> promoter.promote(claim.runId(), TAXONOMY_HASH, at, candidates));
        return history.capture(at);
    }

    private AuctionPromotionCandidate historyCandidate(long id, String description, Instant end) {
        Auction a = auction(id, "H" + id);
        a.setStartDate(OBSERVED_AT.minusSeconds(86400)); a.setEndDate(end);
        a.setDescription(description); a.setStatus("SOURCE_WORKFLOW");
        var listing = objectMapper.createObjectNode(); listing.put("Id", id);
        listing.put("StartDate", OBSERVED_AT.minusSeconds(86400).toString());
        // Null dates can exist in legacy normalized rows; current source validation still quarantines them.
        listing.put("EndDate", (end == null ? OBSERVED_AT.plusSeconds(86400) : end).toString());
        listing.put("Status", "SOURCE_WORKFLOW");
        var detail = listing.deepCopy(); detail.put("Description", description);
        var snapshot = new AuctionSourceSnapshotFactory(objectMapper).create(id, listing, detail,
                SaleScope.IMMOVABLE, OBSERVED_AT, OBSERVED_AT);
        return new AuctionPromotionCandidate(a, LISTING_HASH, OBSERVED_AT, 47, SaleScope.IMMOVABLE,
                NormalizedPropertyKind.PARCEL, List.of(new CategoryMembership(7, CategoryMembershipType.ROOT, "root"),
                new CategoryMembership(47, CategoryMembershipType.CHILD, "child")), true,
                EnrichmentReason.DETAIL_REFRESHED, snapshot);
    }

    private void prepareCompleteRun(
            java.util.UUID runId,
            long uniqueAuctions,
            long detailsRequired,
            long detailsSucceeded,
            long unresolvedErrors) throws Exception {
        prepareCompleteRun(
                runId, uniqueAuctions, detailsRequired, detailsSucceeded,
                unresolvedErrors, OBSERVED_AT);
    }

    private void prepareCompleteRun(
            java.util.UUID runId,
            long uniqueAuctions,
            long detailsRequired,
            long detailsSucceeded,
            long unresolvedErrors,
            Instant observedAt) throws Exception {
        runs.recordTaxonomy(new TaxonomySnapshot(
                TAXONOMY_HASH,
                "taxonomy-v1",
                objectMapper.readTree(DEFAULT_TAXONOMY_JSON),
                observedAt));
        runs.updateProgress(runId, new SyncRunProgress(
                SyncRunStage.CATEGORIES, TAXONOMY_HASH, observedAt,
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
                detailsRequired, detailsSucceeded, unresolvedErrors, observedAt));
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
        return completeProgress(
                taxonomyHash, pagesExpected, pagesCompleted, uniqueAuctions,
                detailsRequired, detailsSucceeded, unresolvedErrors, OBSERVED_AT);
    }

    private static SyncRunProgress completeProgress(
            String taxonomyHash,
            int pagesExpected,
            int pagesCompleted,
            long uniqueAuctions,
            long detailsRequired,
            long detailsSucceeded,
            long unresolvedErrors,
            Instant observedAt) {
        return new SyncRunProgress(
                SyncRunStage.PROMOTING,
                taxonomyHash,
                observedAt,
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
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_publications WHERE run_id = ?", Long.class, runId)).isZero();
        assertThat(runs.find(runId).orElseThrow().status()).isEqualTo(SyncRunStatus.RUNNING);
        assertThat(auctions.findById(auctionId)).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sync_run_auction_observations WHERE run_id = ?",
                Long.class,
                runId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM auction_source_snapshots WHERE auction_id = ?",
                Long.class,
                auctionId)).isZero();
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

    private AuctionPromotionCandidate candidate(
            Auction auction,
            EnrichmentReason reason,
            CategoryMembership... memberships) {
        return candidate(auction, reason, null, memberships);
    }

    private AuctionPromotionCandidate candidateWithExponentMoney(
            Auction auction,
            EnrichmentReason reason,
            CategoryMembership... memberships) {
        return candidate(auction, reason, new BigDecimal("1.596E5"), memberships);
    }

    private AuctionPromotionCandidate candidate(
            Auction auction,
            EnrichmentReason reason,
            BigDecimal startingPrice,
            CategoryMembership... memberships) {
        var listing = objectMapper.createObjectNode();
        listing.put("Id", auction.getId());
        listing.put("AuctionNumber", auction.getAuctionNumber());
        listing.put("StartDate", "2026-08-24T09:00:00Z");
        listing.put("EndDate", "2026-08-24T11:00:00Z");
        if (startingPrice != null) {
            listing.put("StartingPrice", startingPrice);
        }
        var detail = objectMapper.createObjectNode();
        detail.put("Id", auction.getId());
        detail.put("AuctionNumber", auction.getAuctionNumber());
        detail.put("StartDate", "2026-08-24T09:00:00Z");
        detail.put("EndDate", "2026-08-24T11:00:00Z");
        detail.put("PublicationDate", "2026-08-23T09:00:00Z");
        if (startingPrice != null) {
            detail.put("StartingPrice", startingPrice);
        }
        var sourceSnapshot = new rs.sud.eaukcija.snapshot.AuctionSourceSnapshotFactory(objectMapper)
                .create(
                        auction.getId(), listing, detail, SaleScope.IMMOVABLE,
                        OBSERVED_AT, OBSERVED_AT.minus(Duration.ofHours(1)));
        return new AuctionPromotionCandidate(
                auction,
                LISTING_HASH,
                OBSERVED_AT.minus(Duration.ofHours(1)),
                47,
                SaleScope.IMMOVABLE,
                NormalizedPropertyKind.PARCEL,
                List.of(memberships),
                true,
                reason,
                sourceSnapshot);
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
    @Import({SyncRunRepository.class, AuctionPromotionService.class,
            rs.sud.eaukcija.history.SourceHistoryService.class})
    static class PersistenceTestApplication {
    }
}
