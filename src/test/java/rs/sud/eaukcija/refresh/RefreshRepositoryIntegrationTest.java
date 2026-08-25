package rs.sud.eaukcija.refresh;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;

import rs.sud.eaukcija.enrichment.EnrichmentVersions;
import rs.sud.eaukcija.testsupport.PostgisTestContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class RefreshRepositoryIntegrationTest {

    private static final Duration STALE_AFTER = Duration.ofMinutes(15);

    @ServiceConnection(name = "postgresql")
    static final PostgreSQLContainer<?> POSTGIS = PostgisTestContainer.shared();

    @Autowired
    private RefreshRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void reset() {
        jdbc.execute("""
                TRUNCATE TABLE coarse_location_resolution_runs, refresh_runs,
                    sync_runs, auctions CASCADE
                """);
    }

    @Test
    void concurrentManualClicksAndScheduledCollisionHaveOneDurableWinner() throws Exception {
        int callers = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(callers);
        try {
            List<Callable<RefreshClaim>> tasks = new ArrayList<>();
            for (int index = 0; index < callers; index++) {
                UUID key = UUID.randomUUID();
                tasks.add(() -> {
                    start.await();
                    return repository.claim(key, RefreshTriggerKind.MANUAL, STALE_AFTER);
                });
            }
            List<Future<RefreshClaim>> futures = tasks.stream().map(executor::submit).toList();
            start.countDown();
            List<RefreshClaim> claims = new ArrayList<>();
            for (Future<RefreshClaim> future : futures) {
                claims.add(future.get());
            }

            assertThat(claims).extracting(RefreshClaim::workflowId).containsOnly(
                    claims.get(0).workflowId());
            assertThat(claims).filteredOn(claim -> !claim.alreadyRunning()).hasSize(1);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM refresh_runs WHERE status = 'RUNNING'", Long.class))
                    .isOne();

            RefreshClaim scheduled = repository.claim(
                    UUID.randomUUID(), RefreshTriggerKind.SCHEDULED, STALE_AFTER);
            assertThat(scheduled.workflowId()).isEqualTo(claims.get(0).workflowId());
            assertThat(scheduled.alreadyRunning()).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void terminalFailureIsAppendOnlyAndAllowsAnExplicitRetryWorkflow() {
        RefreshClaim first = repository.claim(
                UUID.randomUUID(), RefreshTriggerKind.MANUAL, STALE_AFTER);
        assertThat(repository.fail(first.workflowId(), "SOURCE_DETAILS_FAILED")).isTrue();
        RefreshRunView failed = repository.find(first.workflowId()).orElseThrow();
        assertThat(failed.status()).isEqualTo(RefreshStatus.FAILED);
        assertThat(failed.failureCode()).isEqualTo("SOURCE_DETAILS_FAILED");

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE refresh_runs SET failure_code = 'REFRESH_INTERNAL' WHERE id = ?",
                first.workflowId()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM refresh_runs WHERE id = ?", first.workflowId()))
                .isInstanceOf(DataAccessException.class);

        RefreshClaim retry = repository.claim(
                UUID.randomUUID(), RefreshTriggerKind.MANUAL, STALE_AFTER);
        assertThat(retry.workflowId()).isNotEqualTo(first.workflowId());
        assertThat(retry.alreadyRunning()).isFalse();
    }

    @Test
    void claimReclaimsAnExpiredHeartbeatBeforeCreatingTheNewWinner() {
        RefreshClaim stale = repository.claim(
                UUID.randomUUID(), RefreshTriggerKind.MANUAL, STALE_AFTER);
        jdbc.update("""
                UPDATE refresh_runs
                   SET heartbeat_at = CURRENT_TIMESTAMP - INTERVAL '16 minutes'
                 WHERE id = ?
                """, stale.workflowId());

        RefreshClaim replacement = repository.claim(
                UUID.randomUUID(), RefreshTriggerKind.MANUAL, STALE_AFTER);

        RefreshRunView recovered = repository.find(stale.workflowId()).orElseThrow();
        assertThat(recovered.status()).isEqualTo(RefreshStatus.FAILED);
        assertThat(recovered.failureCode()).isEqualTo("REFRESH_STALE_RECLAIMED");
        assertThat(replacement.workflowId()).isNotEqualTo(stale.workflowId());
        assertThat(replacement.alreadyRunning()).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM refresh_runs WHERE status = 'RUNNING'", Long.class))
                .isOne();
    }

    @Test
    void eligibleSourceObservationWithoutAPinnedSnapshotFailsCompletenessClosed() {
        UUID sourceRun = UUID.randomUUID();
        String taxonomyHash = "a".repeat(64);
        String listingHash = "b".repeat(64);
        jdbc.update("""
                INSERT INTO eaukcija_taxonomies (
                    tree_sha256, normalizer_version, canonical_tree, first_observed_at
                ) VALUES (?, 'test-v1', '[]'::jsonb, CURRENT_TIMESTAMP)
                ON CONFLICT (tree_sha256) DO NOTHING
                """, taxonomyHash);
        jdbc.update("""
                INSERT INTO sync_runs (
                    id, idempotency_key_sha256, trigger_kind, status, stage,
                    started_at, heartbeat_at, finished_at, configured_roots, page_size,
                    category_tree_sha256, category_tree_observed_at,
                    pages_expected, pages_completed, listing_rows_observed,
                    unique_auction_count, details_required, details_attempted,
                    details_succeeded
                ) VALUES (?, ?, 'MANUAL', 'RUNNING', 'PROMOTING',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL,
                    '[7]'::jsonb, 3000, ?, CURRENT_TIMESTAMP,
                    1, 1, 1, 1, 1, 1, 1)
                """, sourceRun, "c".repeat(64), taxonomyHash);
        jdbc.update("""
                INSERT INTO auctions (
                    id, auction_number, first_sale, details_fetched,
                    listing_fingerprint, last_successful_sync_run_id
                ) VALUES (40040, 'N-40040', FALSE, TRUE, ?, ?)
                """, listingHash, sourceRun);
        jdbc.update("""
                INSERT INTO sync_run_auction_observations (
                    run_id, auction_id, listing_fingerprint, detail_refreshed,
                    enrichment_eligible, enrichment_reason
                ) VALUES (?, 40040, ?, TRUE, TRUE, 'NEW')
                """, sourceRun, listingHash);
        jdbc.update("""
                UPDATE sync_runs
                   SET status = 'SUCCEEDED', stage = 'COMPLETED',
                       heartbeat_at = CURRENT_TIMESTAMP, finished_at = CURRENT_TIMESTAMP
                 WHERE id = ?
                """, sourceRun);

        assertThat(repository.sourceIsFullyEnriched(
                sourceRun, new EnrichmentVersions("parser-v1", "resolver-v1", "dataset-v1")))
                .isFalse();
    }
}
