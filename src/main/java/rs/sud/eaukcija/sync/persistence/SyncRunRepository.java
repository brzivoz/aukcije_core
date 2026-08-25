package rs.sud.eaukcija.sync.persistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import rs.sud.eaukcija.enrichment.EnrichmentInputSnapshot;

/** PostgreSQL authority for durable sync claims, progress, evidence, and recovery. */
@Repository
public class SyncRunRepository {

    static final long CLAIM_LOCK_ID = 17_000_001L;
    static final long WORKER_LOCK_ID = 17_000_002L;
    private static final int MAX_MULTI_ROW_BIND_PARAMETERS = 60_000;
    private static final int MAX_MULTI_ROW_ROWS = 1_000;

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public SyncRunRepository(DataSource dataSource, ObjectMapper objectMapper) {
        this(dataSource, objectMapper, Clock.systemUTC());
    }

    SyncRunRepository(DataSource dataSource, ObjectMapper objectMapper, Clock clock) {
        this.dataSource = dataSource;
        this.jdbc = new JdbcTemplate(dataSource);
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** Claims a new durable run, or replays the prior ID for the same idempotency key. */
    @Transactional
    public SyncRunClaimResult claim(SyncRunClaimRequest request) {
        String keyHash = sha256(request.idempotencyKey());

        // A short transaction lock serializes the empty-to-one RUNNING transition.
        // The worker lifetime uses the distinct session lock below.
        jdbc.execute("SELECT pg_advisory_xact_lock(" + CLAIM_LOCK_ID + ")");

        UUID replay = jdbc.query(
                "SELECT id FROM sync_runs WHERE idempotency_key_sha256 = ?",
                result -> result.next() ? result.getObject(1, UUID.class) : null,
                keyHash);
        if (replay != null) {
            return new SyncRunClaimResult(replay, true);
        }

        UUID active = activeRunId().orElse(null);
        if (active != null) {
            throw new SyncAlreadyRunningException(active);
        }

        UUID runId = UUID.randomUUID();
        Instant now = Instant.now(clock);
        jdbc.update("""
                INSERT INTO sync_runs (
                    id, idempotency_key_sha256, trigger_kind, status, stage,
                    started_at, heartbeat_at, configured_roots, page_size
                ) VALUES (?, ?, ?, 'RUNNING', 'CLAIMED', ?, ?, CAST(? AS jsonb), ?)
                """,
                runId,
                keyHash,
                request.triggerKind().name(),
                databaseTime(now),
                databaseTime(now),
                json(request.configuredRoots()),
                request.pageSize());
        return new SyncRunClaimResult(runId, false);
    }

    public Optional<UUID> activeRunId() {
        return jdbc.query(
                "SELECT id FROM sync_runs WHERE status = 'RUNNING'",
                result -> result.next() ? Optional.of(result.getObject(1, UUID.class)) : Optional.empty());
    }

    public boolean isRunning(UUID runId) {
        Boolean running = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM sync_runs WHERE id = ? AND status = 'RUNNING')",
                Boolean.class,
                runId);
        return Boolean.TRUE.equals(running);
    }

    /** Returns whether the exact active run has exceeded the heartbeat lease. */
    public boolean isStale(UUID runId, Duration staleAfter) {
        SyncPersistenceValidation.required(runId, "runId");
        SyncPersistenceValidation.required(staleAfter, "staleAfter");
        if (staleAfter.isNegative()) {
            throw new IllegalArgumentException("staleAfter must not be negative");
        }
        Boolean stale = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                      FROM sync_runs
                     WHERE id = ?
                       AND status = 'RUNNING'
                       AND heartbeat_at <= ?
                )
                """, Boolean.class, runId,
                databaseTime(Instant.now(clock).minus(staleAfter)));
        return Boolean.TRUE.equals(stale);
    }

    public Optional<SyncRunView> find(UUID runId) {
        return jdbc.query("""
                SELECT id, trigger_kind, status, stage,
                       started_at, heartbeat_at, finished_at,
                       configured_roots::text, page_size,
                       category_tree_sha256, category_tree_observed_at,
                       pages_expected, pages_completed, listing_rows_observed,
                       listing_rows_quarantined,
                       unique_auction_count, duplicate_auction_count,
                       unknown_property_kind_count,
                       details_required, details_attempted, details_succeeded,
                       details_quarantined, details_failed,
                       retry_count, error_count, unresolved_error_count
                  FROM sync_runs
                 WHERE id = ?
                """,
                result -> result.next() ? Optional.of(mapRun(result)) : Optional.empty(),
                runId);
    }

    public Optional<SyncRunView> findLatest() {
        return jdbc.query("""
                SELECT id, trigger_kind, status, stage,
                       started_at, heartbeat_at, finished_at,
                       configured_roots::text, page_size,
                       category_tree_sha256, category_tree_observed_at,
                       pages_expected, pages_completed, listing_rows_observed,
                       listing_rows_quarantined,
                       unique_auction_count, duplicate_auction_count,
                       unknown_property_kind_count,
                       details_required, details_attempted, details_succeeded,
                       details_quarantined, details_failed,
                       retry_count, error_count, unresolved_error_count
                  FROM sync_runs
                 ORDER BY started_at DESC, id DESC
                 LIMIT 1
                """, result -> result.next() ? Optional.of(mapRun(result)) : Optional.empty());
    }

    public List<PersistedSyncRunError> errors(UUID runId) {
        return jdbc.query("""
                SELECT ordinal, occurred_at, stage,
                       root_category_id, child_category_id, page_number, auction_id, http_status,
                       error_code, retryable, attempt_number, resolved
                  FROM sync_run_errors
                 WHERE run_id = ?
                 ORDER BY ordinal
                """, (result, row) -> new PersistedSyncRunError(
                result.getInt("ordinal"),
                instant(result, "occurred_at"),
                SyncRunStage.valueOf(result.getString("stage")),
                nullableInteger(result, "root_category_id"),
                nullableInteger(result, "child_category_id"),
                nullableInteger(result, "page_number"),
                nullableLong(result, "auction_id"),
                nullableInteger(result, "http_status"),
                result.getString("error_code"),
                result.getBoolean("retryable"),
                result.getInt("attempt_number"),
                result.getBoolean("resolved")), runId);
    }

    public List<PersistedAuctionDetailQuarantine> detailQuarantines(UUID runId) {
        return jdbc.query("""
                SELECT auction_id, listing_fingerprint, error_code, occurred_at
                  FROM sync_run_detail_quarantines
                 WHERE run_id = ?
                 ORDER BY auction_id
                """, (result, row) -> new PersistedAuctionDetailQuarantine(
                result.getLong("auction_id"),
                result.getString("listing_fingerprint"),
                result.getString("error_code"),
                instant(result, "occurred_at")), runId);
    }

    public List<PersistedAuctionListingQuarantine> listingQuarantines(UUID runId) {
        return jdbc.query("""
                SELECT auction_id, source_row_sha256, error_code,
                       root_category_id, child_category_id, page_number, occurred_at
                  FROM sync_run_listing_quarantines
                 WHERE run_id = ?
                 ORDER BY auction_id
                """, (result, row) -> new PersistedAuctionListingQuarantine(
                result.getLong("auction_id"),
                result.getString("source_row_sha256"),
                result.getString("error_code"),
                result.getInt("root_category_id"),
                nullableInteger(result, "child_category_id"),
                result.getInt("page_number"),
                instant(result, "occurred_at")), runId);
    }

    public List<SyncRunRootResult> rootResults(UUID runId) {
        return jdbc.query("""
                SELECT root_category_id, source_total_count, rows_observed,
                       unique_ids, duplicate_ids, pages_expected, pages_completed,
                       total_consistent, complete
                  FROM sync_run_root_results
                 WHERE run_id = ?
                 ORDER BY root_category_id
                """, (result, row) -> new SyncRunRootResult(
                result.getInt("root_category_id"),
                result.getLong("source_total_count"),
                result.getLong("rows_observed"),
                result.getLong("unique_ids"),
                result.getLong("duplicate_ids"),
                result.getInt("pages_expected"),
                result.getInt("pages_completed"),
                result.getBoolean("total_consistent"),
                result.getBoolean("complete")), runId);
    }

    public List<SyncRunChildResult> childResults(UUID runId) {
        return jdbc.query("""
                SELECT parent_root_category_id, child_category_id,
                       source_total_count, rows_observed, unique_ids, duplicate_ids,
                       pages_expected, pages_completed, total_consistent,
                       subset_of_parent_root, complete
                  FROM sync_run_child_results
                 WHERE run_id = ?
                 ORDER BY parent_root_category_id, child_category_id
                """, (result, row) -> new SyncRunChildResult(
                result.getInt("parent_root_category_id"),
                result.getInt("child_category_id"),
                result.getLong("source_total_count"),
                result.getLong("rows_observed"),
                result.getLong("unique_ids"),
                result.getLong("duplicate_ids"),
                result.getInt("pages_expected"),
                result.getInt("pages_completed"),
                result.getBoolean("total_consistent"),
                result.getBoolean("subset_of_parent_root"),
                result.getBoolean("complete")), runId);
    }

    /** Retains a canonical category tree once; repeated observations reuse its hash. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordTaxonomy(TaxonomySnapshot taxonomy) {
        jdbc.update("""
                INSERT INTO eaukcija_taxonomies (
                    tree_sha256, normalizer_version, canonical_tree, first_observed_at
                ) VALUES (?, ?, CAST(? AS jsonb), ?)
                ON CONFLICT (tree_sha256) DO NOTHING
                """,
                taxonomy.treeSha256(),
                taxonomy.normalizerVersion(),
                json(taxonomy.canonicalTree()),
                databaseTime(taxonomy.observedAt()));

        TaxonomyIdentity stored = jdbc.queryForObject("""
                SELECT normalizer_version, canonical_tree::text
                  FROM eaukcija_taxonomies
                 WHERE tree_sha256 = ?
                """, (result, row) -> new TaxonomyIdentity(
                result.getString("normalizer_version"), parseJson(result.getString("canonical_tree"))),
                taxonomy.treeSha256());
        if (stored == null
                || !stored.normalizerVersion().equals(taxonomy.normalizerVersion())
                || !stored.canonicalTree().equals(taxonomy.canonicalTree())) {
            throw new SyncRunStateException("taxonomy hash already identifies different canonical evidence");
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateProgress(UUID runId, SyncRunProgress progress) {
        int changed = updateProgressRow(runId, progress, false, null);
        if (changed != 1) {
            throw new SyncRunStateException("sync run is no longer RUNNING: " + runId);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordRootResult(UUID runId, SyncRunRootResult result) {
        lockRunning(runId);
        jdbc.update("""
                INSERT INTO sync_run_root_results (
                    run_id, root_category_id, source_total_count, rows_observed,
                    unique_ids, duplicate_ids, pages_expected, pages_completed,
                    total_consistent, complete
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (run_id, root_category_id) DO UPDATE SET
                    source_total_count = EXCLUDED.source_total_count,
                    rows_observed = EXCLUDED.rows_observed,
                    unique_ids = EXCLUDED.unique_ids,
                    duplicate_ids = EXCLUDED.duplicate_ids,
                    pages_expected = EXCLUDED.pages_expected,
                    pages_completed = EXCLUDED.pages_completed,
                    total_consistent = EXCLUDED.total_consistent,
                    complete = EXCLUDED.complete
                """,
                runId,
                result.rootCategoryId(),
                result.sourceTotalCount(),
                result.rowsObserved(),
                result.uniqueIds(),
                result.duplicateIds(),
                result.pagesExpected(),
                result.pagesCompleted(),
                result.totalConsistent(),
                result.complete());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordChildResult(UUID runId, SyncRunChildResult result) {
        lockRunning(runId);
        jdbc.update("""
                INSERT INTO sync_run_child_results (
                    run_id, parent_root_category_id, child_category_id,
                    source_total_count, rows_observed, unique_ids, duplicate_ids,
                    pages_expected, pages_completed, total_consistent,
                    subset_of_parent_root, complete
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (run_id, parent_root_category_id, child_category_id) DO UPDATE SET
                    source_total_count = EXCLUDED.source_total_count,
                    rows_observed = EXCLUDED.rows_observed,
                    unique_ids = EXCLUDED.unique_ids,
                    duplicate_ids = EXCLUDED.duplicate_ids,
                    pages_expected = EXCLUDED.pages_expected,
                    pages_completed = EXCLUDED.pages_completed,
                    total_consistent = EXCLUDED.total_consistent,
                    subset_of_parent_root = EXCLUDED.subset_of_parent_root,
                    complete = EXCLUDED.complete
                """,
                runId,
                result.parentRootCategoryId(),
                result.childCategoryId(),
                result.sourceTotalCount(),
                result.rowsObserved(),
                result.uniqueIds(),
                result.duplicateIds(),
                result.pagesExpected(),
                result.pagesCompleted(),
                result.totalConsistent(),
                result.subsetOfParentRoot(),
                result.complete());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void appendError(UUID runId, SyncRunErrorEvidence evidence) {
        appendError(runId, evidence, false, true);
    }

    /**
     * Retains one bounded redacted error. Resolved detail errors remain part of
     * the aggregate error count but do not block successful publication.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void appendError(UUID runId, SyncRunErrorEvidence evidence, boolean resolved) {
        appendError(runId, evidence, resolved, true);
    }

    /**
     * Atomically counts every failure while retaining only the configured
     * bounded evidence prefix. This makes aggregate counters crash-consistent
     * even after the evidence retention cap is reached.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void appendError(
            UUID runId,
            SyncRunErrorEvidence evidence,
            boolean resolved,
            boolean retainEvidence) {
        lockRunning(runId);
        if (retainEvidence) {
            Integer ordinal = jdbc.queryForObject("""
                    SELECT COALESCE(MAX(ordinal), 0) + 1
                      FROM sync_run_errors
                     WHERE run_id = ?
                    """, Integer.class, runId);
            jdbc.update("""
                    INSERT INTO sync_run_errors (
                        run_id, ordinal, occurred_at, stage,
                        root_category_id, child_category_id, page_number, auction_id, http_status,
                        error_code, retryable, attempt_number, resolved
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    runId,
                    ordinal,
                    databaseTime(Instant.now(clock)),
                    evidence.stage().name(),
                    evidence.rootCategoryId(),
                    evidence.childCategoryId(),
                    evidence.pageNumber(),
                    evidence.auctionId(),
                    evidence.httpStatus(),
                    evidence.errorCode(),
                    evidence.retryable(),
                    evidence.attemptNumber(),
                    resolved);
        }
        int updated = jdbc.update("""
                UPDATE sync_runs run
                   SET error_count = run.error_count + 1,
                       unresolved_error_count = run.unresolved_error_count + ?,
                       retry_count = run.retry_count + ?,
                       heartbeat_at = CURRENT_TIMESTAMP
                 WHERE run.id = ? AND run.status = 'RUNNING'
                """, resolved ? 0 : 1, evidence.attemptNumber() - 1, runId);
        if (updated != 1) {
            throw new SyncRunStateException("sync run is no longer RUNNING: " + runId);
        }
    }

    /** Ends a run without publishing any current auction or enrichment state. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finishIncomplete(UUID runId, SyncRunStatus status, SyncRunProgress progress) {
        if (status != SyncRunStatus.PARTIAL && status != SyncRunStatus.FAILED) {
            throw new IllegalArgumentException("incomplete run status must be PARTIAL or FAILED");
        }
        int changed = updateProgressRow(runId, progress, true, status);
        if (changed != 1) {
            throw new SyncRunStateException("sync run is no longer RUNNING: " + runId);
        }
    }

    /**
     * Acquires the process-wide worker lock on a dedicated PostgreSQL session.
     * The caller must retain and close the returned lease around all source work.
     */
    public Optional<WorkerLockLease> tryAcquireWorkerLock() {
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            try (PreparedStatement statement = connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                statement.setLong(1, WORKER_LOCK_ID);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next() || !result.getBoolean(1)) {
                        connection.close();
                        return Optional.empty();
                    }
                }
            }
            return Optional.of(new WorkerLockLease(connection, WORKER_LOCK_ID));
        } catch (SQLException e) {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException closeFailure) {
                    e.addSuppressed(closeFailure);
                }
            }
            throw new SyncRunStateException("could not acquire PostgreSQL sync worker lock", e);
        }
    }

    /**
     * Terminalizes every orphan RUNNING row while the caller owns the session
     * worker lock. Counts decide PARTIAL versus FAILED; no current state changes.
     */
    public List<UUID> recoverOrphanedRunningRuns(WorkerLockLease lease, Duration staleAfter) {
        return recoverOrphanedRunningRuns(lease, staleAfter, Integer.MAX_VALUE);
    }

    /**
     * Terminalizes every orphan RUNNING row and retains the recovery error only
     * when the run has room under {@code maxRetainedErrors}. Aggregate counters
     * advance in the same transaction even when the redacted evidence prefix is
     * already full.
     */
    public List<UUID> recoverOrphanedRunningRuns(
            WorkerLockLease lease,
            Duration staleAfter,
            int maxRetainedErrors) {
        if (lease == null || !lease.isHeld()) {
            throw new IllegalArgumentException("a held worker lock lease is required for recovery");
        }
        if (staleAfter == null || staleAfter.isNegative()) {
            throw new IllegalArgumentException("staleAfter must not be null or negative");
        }
        if (maxRetainedErrors < 1) {
            throw new IllegalArgumentException("maxRetainedErrors must be positive");
        }
        Connection connection = lease.connection();
        List<UUID> recovered = new ArrayList<>();
        boolean previousAutoCommit;
        try {
            previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement select = connection.prepareStatement("""
                    SELECT id, stage,
                           category_tree_sha256,
                           pages_completed, listing_rows_observed, details_succeeded
                      FROM sync_runs
                     WHERE status = 'RUNNING'
                       AND heartbeat_at <= ?
                     FOR UPDATE
                    """)) {
                select.setObject(1, databaseTime(Instant.now(clock).minus(staleAfter)));
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        UUID runId = rows.getObject("id", UUID.class);
                        SyncRunStage stage = SyncRunStage.valueOf(rows.getString("stage"));
                        boolean partial = rows.getString("category_tree_sha256") != null
                                || rows.getInt("pages_completed") > 0
                                || rows.getLong("listing_rows_observed") > 0
                                || rows.getLong("details_succeeded") > 0;
                        insertRecoveryError(
                                connection,
                                runId,
                                recoveryErrorStage(stage),
                                maxRetainedErrors);
                        terminalizeRecovered(connection, runId,
                                partial ? SyncRunStatus.PARTIAL : SyncRunStatus.FAILED,
                                recoveryTerminalStage(stage));
                        recovered.add(runId);
                    }
                }
            }
            connection.commit();
            connection.setAutoCommit(previousAutoCommit);
            return List.copyOf(recovered);
        } catch (SQLException | RuntimeException failure) {
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw new SyncRunStateException("could not recover orphaned sync runs", failure);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public SyncRunView lockRunningForPromotion(UUID runId) {
        return jdbc.query("""
                SELECT id, trigger_kind, status, stage,
                       started_at, heartbeat_at, finished_at,
                       configured_roots::text, page_size,
                       category_tree_sha256, category_tree_observed_at,
                       pages_expected, pages_completed, listing_rows_observed,
                       listing_rows_quarantined,
                       unique_auction_count, duplicate_auction_count,
                       unknown_property_kind_count,
                       details_required, details_attempted, details_succeeded,
                       details_quarantined, details_failed,
                       retry_count, error_count, unresolved_error_count
                  FROM sync_runs
                 WHERE id = ? AND status = 'RUNNING'
                 FOR UPDATE
                """, result -> {
            if (!result.next()) {
                throw new SyncRunStateException("sync run is not RUNNING: " + runId);
            }
            return mapRun(result);
        }, runId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void assertCompleteRoots(UUID runId, int expectedRootCount) {
        RootCompleteness completeness = jdbc.query("""
                SELECT jsonb_array_length(run.configured_roots) AS configured_count,
                       COUNT(result.root_category_id) AS result_count,
                       COUNT(result.root_category_id) FILTER (
                           WHERE result.complete
                             AND run.configured_roots
                                 @> jsonb_build_array(result.root_category_id)
                       ) AS matching_complete_count
                  FROM sync_runs run
                  LEFT JOIN sync_run_root_results result ON result.run_id = run.id
                 WHERE run.id = ?
                 GROUP BY run.configured_roots
                """, result -> result.next()
                        ? new RootCompleteness(
                                result.getInt("configured_count"),
                                result.getInt("result_count"),
                                result.getInt("matching_complete_count"))
                        : null,
                runId);
        if (completeness == null
                || completeness.configuredCount() != expectedRootCount
                || completeness.resultCount() != expectedRootCount
                || completeness.matchingCompleteCount() != expectedRootCount) {
            throw new SyncRunStateException("not every configured root has a complete result");
        }
    }

    /**
     * Requires an exact, complete result for every direct child of every
     * configured root in the taxonomy captured by this run. Extra, missing,
     * reparented, incomplete, or non-subset evidence all fail closed.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void assertCompleteChildren(UUID runId) {
        ChildCompleteness completeness = jdbc.query("""
                WITH run_context AS (
                    SELECT run.configured_roots, taxonomy.canonical_tree
                      FROM sync_runs run
                      JOIN eaukcija_taxonomies taxonomy
                        ON taxonomy.tree_sha256 = run.category_tree_sha256
                     WHERE run.id = ?
                ),
                captured_roots AS (
                    SELECT root_node
                      FROM run_context context
                     CROSS JOIN LATERAL jsonb_array_elements(context.canonical_tree) root_node
                     WHERE context.configured_roots
                           @> jsonb_build_array((root_node ->> 'value')::integer)
                ),
                expected AS (
                    SELECT (root_node ->> 'value')::integer AS parent_root_category_id,
                           (child_node ->> 'value')::integer AS child_category_id
                      FROM captured_roots
                     CROSS JOIN LATERAL jsonb_array_elements(
                         CASE
                             WHEN jsonb_typeof(root_node -> 'children') = 'array'
                             THEN root_node -> 'children'
                             ELSE '[]'::jsonb
                         END
                     ) child_node
                ),
                actual AS (
                    SELECT parent_root_category_id, child_category_id,
                           complete, subset_of_parent_root
                      FROM sync_run_child_results
                     WHERE run_id = ?
                )
                SELECT (SELECT jsonb_array_length(configured_roots) FROM run_context)
                           AS configured_root_count,
                       (SELECT COUNT(*) FROM captured_roots) AS captured_root_count,
                       (SELECT COUNT(*) FROM expected) AS expected_count,
                       (SELECT COUNT(*) FROM actual) AS actual_count,
                       (SELECT COUNT(*)
                          FROM expected
                          JOIN actual USING (parent_root_category_id, child_category_id)
                         WHERE actual.complete AND actual.subset_of_parent_root
                       ) AS matching_complete_count
                """, result -> result.next()
                        ? new ChildCompleteness(
                                result.getInt("configured_root_count"),
                                result.getInt("captured_root_count"),
                                result.getInt("expected_count"),
                                result.getInt("actual_count"),
                                result.getInt("matching_complete_count"))
                        : null,
                runId,
                runId);
        if (completeness == null
                || completeness.capturedRootCount() != completeness.configuredRootCount()
                || completeness.actualCount() != completeness.expectedCount()
                || completeness.matchingCompleteCount() != completeness.expectedCount()) {
            throw new SyncRunStateException(
                    "not every captured direct child has one complete subset result");
        }
    }

    /** Publishes all auction columns with bounded PostgreSQL multi-row upserts. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void upsertAuctions(List<AuctionPromotionCandidate> candidates) {
        List<Object[]> arguments = candidates.stream()
                .map(candidate -> {
                    var auction = candidate.auction();
                    return new Object[] {
                            auction.getId(),
                            auction.getAuctionNumber(),
                            databaseTime(auction.getStartDate()),
                            databaseTime(auction.getEndDate()),
                            databaseTime(auction.getPublicationDate()),
                            auction.getStartingPrice(),
                            auction.getEstimatedPrice(),
                            auction.getCurrentPrice(),
                            auction.getMaxOfferedPrice(),
                            auction.getBidStep(),
                            auction.getShortDescription(),
                            auction.getDescription(),
                            auction.getStatus(),
                            auction.isFirstSale(),
                            auction.getPropertyType(),
                            auction.getExecutorName(),
                            auction.getCategoryName(),
                            auction.getPlaceName(),
                            auction.getPlaceZipCode(),
                            auction.getMunicipality(),
                            auction.getCadastral(),
                            auction.isDetailsFetched(),
                            auction.getListingFingerprint(),
                            databaseTime(auction.getDetailsFetchedAt()),
                            auction.getSourceDetailCategoryId(),
                            enumName(auction.getSaleScope()),
                            enumName(auction.getNormalizedPropertyKind()),
                            auction.getTaxonomySha256(),
                            auction.getLastSuccessfulSyncRunId(),
                            auction.getAbsenceCount(),
                            databaseTime(auction.getLastSeenAt())
                    };
                })
                .toList();
        multiRowUpdate("""
                INSERT INTO auctions (
                    id, auction_number, start_date, end_date, publication_date,
                    starting_price, estimated_price, current_price, max_offered_price, bid_step,
                    short_description, description, status, first_sale, property_type,
                    executor_name, category_name, place_name, place_zip_code, municipality,
                    cadastral, details_fetched, listing_fingerprint, details_fetched_at,
                    source_detail_category_id, sale_scope, normalized_property_kind,
                    taxonomy_sha256, last_successful_sync_run_id, absence_count, last_seen_at
                ) VALUES
                """, 31, """
                ON CONFLICT (id) DO UPDATE SET
                    auction_number = EXCLUDED.auction_number,
                    start_date = EXCLUDED.start_date,
                    end_date = EXCLUDED.end_date,
                    publication_date = EXCLUDED.publication_date,
                    starting_price = EXCLUDED.starting_price,
                    estimated_price = EXCLUDED.estimated_price,
                    current_price = EXCLUDED.current_price,
                    max_offered_price = EXCLUDED.max_offered_price,
                    bid_step = EXCLUDED.bid_step,
                    short_description = EXCLUDED.short_description,
                    description = EXCLUDED.description,
                    status = EXCLUDED.status,
                    first_sale = EXCLUDED.first_sale,
                    property_type = EXCLUDED.property_type,
                    executor_name = EXCLUDED.executor_name,
                    category_name = EXCLUDED.category_name,
                    place_name = EXCLUDED.place_name,
                    place_zip_code = EXCLUDED.place_zip_code,
                    municipality = EXCLUDED.municipality,
                    cadastral = EXCLUDED.cadastral,
                    details_fetched = EXCLUDED.details_fetched,
                    listing_fingerprint = EXCLUDED.listing_fingerprint,
                    details_fetched_at = EXCLUDED.details_fetched_at,
                    source_detail_category_id = EXCLUDED.source_detail_category_id,
                    sale_scope = EXCLUDED.sale_scope,
                    normalized_property_kind = EXCLUDED.normalized_property_kind,
                    taxonomy_sha256 = EXCLUDED.taxonomy_sha256,
                    last_successful_sync_run_id = EXCLUDED.last_successful_sync_run_id,
                    absence_count = EXCLUDED.absence_count,
                    last_seen_at = EXCLUDED.last_seen_at
                """, arguments);
    }

    /**
     * Replaces all in-scope memberships with one scoped delete and bounded
     * multi-row inserts. The taxonomy tree is expanded once per relevant
     * taxonomy rather than once for every auction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void replaceMemberships(
            UUID runId,
            String taxonomySha256,
            List<AuctionPromotionCandidate> candidates) {
        if (candidates.isEmpty()) {
            return;
        }
        String deleteSql = """
                WITH target_auctions(auction_id) AS (
                    SELECT unnest(CAST(? AS bigint[]))
                ),
                run_context AS (
                    SELECT configured_roots
                      FROM sync_runs
                     WHERE id = ?
                ),
                relevant_taxonomies AS (
                    SELECT DISTINCT membership.taxonomy_sha256
                      FROM auction_source_category_memberships membership
                      JOIN target_auctions target
                        ON target.auction_id = membership.auction_id
                     WHERE membership.membership_type = 'CHILD'
                ),
                scoped_child_categories AS MATERIALIZED (
                    SELECT taxonomy.tree_sha256 AS taxonomy_sha256,
                           (child_node ->> 'value')::integer AS category_id
                      FROM relevant_taxonomies relevant
                      JOIN eaukcija_taxonomies taxonomy
                        ON taxonomy.tree_sha256 = relevant.taxonomy_sha256
                     CROSS JOIN run_context run
                     CROSS JOIN LATERAL jsonb_array_elements(
                         taxonomy.canonical_tree
                     ) root_node
                     CROSS JOIN LATERAL jsonb_array_elements(
                         CASE
                             WHEN jsonb_typeof(root_node -> 'children') = 'array'
                             THEN root_node -> 'children'
                             ELSE '[]'::jsonb
                         END
                     ) child_node
                     WHERE run.configured_roots @> jsonb_build_array(
                         (root_node ->> 'value')::integer
                     )
                )
                DELETE FROM auction_source_category_memberships membership
                      USING target_auctions target, run_context run
                 WHERE membership.auction_id = target.auction_id
                   AND (
                       membership.membership_type = 'DETAIL'
                       OR (
                           membership.membership_type = 'ROOT'
                           AND run.configured_roots
                               @> jsonb_build_array(membership.category_id)
                       )
                       OR (
                           membership.membership_type = 'CHILD'
                           AND EXISTS (
                               SELECT 1
                                 FROM scoped_child_categories child
                                WHERE child.taxonomy_sha256 = membership.taxonomy_sha256
                                  AND child.category_id = membership.category_id
                           )
                       )
                   )
                """;
        Long[] auctionIds = candidates.stream()
                .map(candidate -> candidate.auction().getId())
                .toArray(Long[]::new);
        jdbc.execute((ConnectionCallback<Integer>) connection -> {
            Array targetIds = connection.createArrayOf("bigint", auctionIds);
            try (PreparedStatement delete = connection.prepareStatement(deleteSql)) {
                delete.setArray(1, targetIds);
                delete.setObject(2, runId);
                return delete.executeUpdate();
            } finally {
                targetIds.free();
            }
        });

        List<Object[]> membershipArguments = new ArrayList<>();
        for (AuctionPromotionCandidate candidate : candidates) {
            for (CategoryMembership membership : candidate.memberships()) {
                membershipArguments.add(new Object[] {
                        candidate.auction().getId(),
                        membership.categoryId(),
                        membership.type().name(),
                        membership.categoryName(),
                        taxonomySha256,
                        runId
                });
            }
        }
        multiRowUpdate("""
                INSERT INTO auction_source_category_memberships (
                    auction_id, category_id, membership_type, category_name,
                    taxonomy_sha256, last_successful_sync_run_id
                ) VALUES
                """, 6, """
                ON CONFLICT (auction_id, category_id, membership_type) DO UPDATE
                   SET category_name = EXCLUDED.category_name,
                       taxonomy_sha256 = EXCLUDED.taxonomy_sha256,
                       last_successful_sync_run_id = EXCLUDED.last_successful_sync_run_id
                """, membershipArguments);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void insertSuccessObservations(UUID runId, List<AuctionPromotionCandidate> candidates) {
        List<Object[]> arguments = candidates.stream()
                .map(candidate -> new Object[] {
                        runId,
                        candidate.auction().getId(),
                        candidate.listingFingerprint(),
                        candidate.detailRefreshed(),
                        candidate.enrichmentEligible(),
                        candidate.enrichmentReason().name()
                })
                .toList();
        multiRowUpdate("""
                INSERT INTO sync_run_auction_observations (
                    run_id, auction_id, listing_fingerprint, detail_refreshed,
                    enrichment_eligible, enrichment_reason
                ) VALUES
                """, 6, "", arguments);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void insertDetailQuarantines(
            UUID runId,
            List<AuctionDetailQuarantine> quarantines) {
        List<Object[]> arguments = quarantines.stream()
                .map(quarantine -> new Object[] {
                        runId,
                        quarantine.auctionId(),
                        quarantine.listingFingerprint(),
                        quarantine.errorCode()
                })
                .toList();
        multiRowUpdate("""
                INSERT INTO sync_run_detail_quarantines (
                    run_id, auction_id, listing_fingerprint, error_code
                ) VALUES
                """, 4, "", arguments);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void insertListingQuarantines(
            UUID runId,
            List<AuctionListingQuarantine> quarantines) {
        List<Object[]> arguments = quarantines.stream()
                .map(quarantine -> new Object[] {
                        runId,
                        quarantine.auctionId(),
                        quarantine.sourceRowSha256(),
                        quarantine.errorCode(),
                        quarantine.rootCategoryId(),
                        quarantine.childCategoryId(),
                        quarantine.pageNumber()
                })
                .toList();
        multiRowUpdate("""
                INSERT INTO sync_run_listing_quarantines (
                    run_id, auction_id, source_row_sha256, error_code,
                    root_category_id, child_category_id, page_number
                ) VALUES
                """, 7, "", arguments);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void incrementAbsencesForUnobservedInScope(UUID runId) {
        jdbc.update("""
                UPDATE auctions auction
                   SET absence_count = absence_count + 1
                  FROM sync_runs run
                 WHERE run.id = ?
                   AND NOT EXISTS (
                       SELECT 1
                         FROM sync_run_auction_observations observation
                        WHERE observation.run_id = run.id
                          AND observation.auction_id = auction.id
                   )
                   AND NOT EXISTS (
                       SELECT 1
                         FROM sync_run_detail_quarantines quarantine
                        WHERE quarantine.run_id = run.id
                          AND quarantine.auction_id = auction.id
                   )
                   AND NOT EXISTS (
                       SELECT 1
                         FROM sync_run_listing_quarantines quarantine
                        WHERE quarantine.run_id = run.id
                          AND quarantine.auction_id = auction.id
                   )
                   AND (
                       EXISTS (
                           SELECT 1
                             FROM auction_source_category_memberships membership
                            WHERE membership.auction_id = auction.id
                              AND membership.membership_type = 'ROOT'
                              AND run.configured_roots
                                  @> jsonb_build_array(membership.category_id)
                       )
                       OR (
                           -- V1-V9 rows have no normalized scope or retained
                           -- ROOT membership. Conservatively treat only those
                           -- historical rows as belonging to legacy root 7.
                           auction.sale_scope IS NULL
                           AND NOT EXISTS (
                               SELECT 1
                                 FROM auction_source_category_memberships membership
                                WHERE membership.auction_id = auction.id
                                  AND membership.membership_type = 'ROOT'
                           )
                           AND run.configured_roots @> '[7]'::jsonb
                       )
                   )
                """, runId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void markSucceeded(UUID runId) {
        int changed = jdbc.update("""
                UPDATE sync_runs
                   SET status = 'SUCCEEDED', stage = 'COMPLETED',
                       heartbeat_at = CURRENT_TIMESTAMP,
                       finished_at = CURRENT_TIMESTAMP
                 WHERE id = ?
                   AND status = 'RUNNING'
                   AND category_tree_sha256 IS NOT NULL
                   AND pages_completed = pages_expected
                   AND details_succeeded + details_quarantined = details_required
                   AND details_failed = 0
                   AND unresolved_error_count = 0
                   AND error_count >= details_quarantined + listing_rows_quarantined
                   AND details_quarantined = (
                       SELECT COUNT(*)
                         FROM sync_run_detail_quarantines quarantine
                        WHERE quarantine.run_id = sync_runs.id
                   )
                   AND listing_rows_quarantined = (
                       SELECT COUNT(*)
                         FROM sync_run_listing_quarantines quarantine
                        WHERE quarantine.run_id = sync_runs.id
                   )
                """, runId);
        if (changed != 1) {
            throw new SyncRunStateException("run does not satisfy success completeness gates: " + runId);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void insertEnrichmentWork(UUID runId, List<AuctionPromotionCandidate> candidates) {
        List<Object[]> arguments = candidates.stream()
                .filter(AuctionPromotionCandidate::enrichmentEligible)
                .map(candidate -> new Object[] {
                        runId,
                        candidate.auction().getId(),
                        "PENDING",
                        candidate.enrichmentReason().name()
                })
                .toList();
        multiRowUpdate("""
                INSERT INTO sync_enrichment_queue (run_id, auction_id, status, reason)
                VALUES
                """, 4, "", arguments);
    }

    /**
     * Publishes the immutable local input consumed by #29. The caller invokes
     * this only after the parent run has passed every success gate; all writes
     * still share the promotion transaction and therefore roll back together.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishEnrichmentInputSnapshots(
            UUID runId,
            List<AuctionPromotionCandidate> candidates) {
        record Published(long auctionId, EnrichmentInputSnapshot snapshot) {
        }
        List<Published> published = candidates.stream()
                .map(candidate -> new Published(
                        candidate.auction().getId(),
                        EnrichmentInputSnapshot.from(candidate.auction(), objectMapper)))
                .toList();

        multiRowUpdate("""
                INSERT INTO auction_enrichment_input_snapshots (
                    auction_id, snapshot_sha256, canonical_input
                )
                SELECT incoming.auction_id,
                       incoming.snapshot_sha256,
                       CAST(incoming.canonical_input AS jsonb)
                  FROM (VALUES
                """, 3, """
                ) AS incoming(auction_id, snapshot_sha256, canonical_input)
                ON CONFLICT (auction_id, snapshot_sha256) DO NOTHING
                """, published.stream()
                .map(row -> new Object[] {
                        row.auctionId(), row.snapshot().sha256(), json(row.snapshot().canonicalInput())
                })
                .toList());

        multiRowUpdate("""
                INSERT INTO auction_enrichment_snapshot_observations (
                    source_sync_run_id, auction_id, snapshot_sha256
                ) VALUES
                """, 3, "", published.stream()
                .map(row -> new Object[] {runId, row.auctionId(), row.snapshot().sha256()})
                .toList());

        for (Published row : published) {
            int changed = jdbc.update("""
                    UPDATE auctions
                       SET current_enrichment_snapshot_sha256 = ?
                     WHERE id = ?
                    """, row.snapshot().sha256(), row.auctionId());
            if (changed != 1) {
                throw new SyncRunStateException(
                        "could not select enrichment input for auction " + row.auctionId());
            }
        }
    }

    private void multiRowUpdate(
            String sqlPrefix,
            int columnCount,
            String sqlSuffix,
            List<Object[]> rows) {
        if (rows.isEmpty()) {
            return;
        }
        if (columnCount <= 0 || columnCount > MAX_MULTI_ROW_BIND_PARAMETERS) {
            throw new IllegalArgumentException("invalid multi-row column count");
        }
        int chunkSize = Math.min(
                MAX_MULTI_ROW_ROWS,
                MAX_MULTI_ROW_BIND_PARAMETERS / columnCount);
        String rowPlaceholders = "(" + String.join(", ",
                Collections.nCopies(columnCount, "?")) + ")";
        for (int start = 0; start < rows.size(); start += chunkSize) {
            int end = Math.min(start + chunkSize, rows.size());
            Object[] arguments = new Object[(end - start) * columnCount];
            int argumentIndex = 0;
            for (int rowIndex = start; rowIndex < end; rowIndex++) {
                Object[] row = rows.get(rowIndex);
                if (row.length != columnCount) {
                    throw new IllegalArgumentException("multi-row argument width does not match SQL");
                }
                System.arraycopy(row, 0, arguments, argumentIndex, columnCount);
                argumentIndex += columnCount;
            }
            String values = String.join(", ",
                    Collections.nCopies(end - start, rowPlaceholders));
            jdbc.update(sqlPrefix + values + System.lineSeparator() + sqlSuffix, arguments);
        }
    }

    private int updateProgressRow(
            UUID runId,
            SyncRunProgress progress,
            boolean terminal,
            SyncRunStatus terminalStatus) {
        return jdbc.update("""
                UPDATE sync_runs
                   SET status = ?, stage = ?, heartbeat_at = CURRENT_TIMESTAMP,
                       finished_at = CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE NULL END,
                       category_tree_sha256 = ?, category_tree_observed_at = ?,
                       pages_expected = ?, pages_completed = ?,
                       listing_rows_observed = ?, listing_rows_quarantined = ?,
                       unique_auction_count = ?,
                       duplicate_auction_count = ?, unknown_property_kind_count = ?,
                       details_required = ?, details_attempted = ?,
                       details_succeeded = ?, details_quarantined = ?, details_failed = ?,
                       retry_count = ?, error_count = ?, unresolved_error_count = ?
                 WHERE id = ? AND status = 'RUNNING'
                """,
                terminal ? terminalStatus.name() : SyncRunStatus.RUNNING.name(),
                progress.stage().name(),
                terminal,
                progress.categoryTreeSha256(),
                databaseTime(progress.categoryTreeObservedAt()),
                progress.pagesExpected(),
                progress.pagesCompleted(),
                progress.listingRowsObserved(),
                progress.listingRowsQuarantined(),
                progress.uniqueAuctionCount(),
                progress.duplicateAuctionCount(),
                progress.unknownPropertyKindCount(),
                progress.detailsRequired(),
                progress.detailsAttempted(),
                progress.detailsSucceeded(),
                progress.detailsQuarantined(),
                progress.detailsFailed(),
                progress.retryCount(),
                progress.errorCount(),
                progress.unresolvedErrorCount(),
                runId);
    }

    private void lockRunning(UUID runId) {
        UUID locked = jdbc.query("""
                SELECT id FROM sync_runs
                 WHERE id = ? AND status = 'RUNNING'
                 FOR UPDATE
                """, result -> result.next() ? result.getObject(1, UUID.class) : null, runId);
        if (locked == null) {
            throw new SyncRunStateException("sync run is no longer RUNNING: " + runId);
        }
    }

    private void insertRecoveryError(
            Connection connection,
            UUID runId,
            SyncRunStage stage,
            int maxRetainedErrors) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO sync_run_errors (
                    run_id, ordinal, occurred_at, stage, error_code, retryable, attempt_number
                ) SELECT ?, COALESCE(MAX(ordinal), 0) + 1, ?, ?,
                         'STALE_RUN_RECOVERED', TRUE, 1
                    FROM sync_run_errors
                   WHERE run_id = ?
                  HAVING COUNT(*) < ?
                """)) {
            insert.setObject(1, runId);
            insert.setObject(2, databaseTime(Instant.now(clock)));
            insert.setString(3, stage.name());
            insert.setObject(4, runId);
            insert.setInt(5, maxRetainedErrors);
            insert.executeUpdate();
        }
    }

    private void terminalizeRecovered(
            Connection connection,
            UUID runId,
            SyncRunStatus terminalStatus,
            SyncRunStage failureStage) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE sync_runs
                   SET status = ?, stage = ?,
                       heartbeat_at = ?, finished_at = ?,
                       error_count = GREATEST(
                           error_count + 1,
                           (SELECT COUNT(*) FROM sync_run_errors error
                             WHERE error.run_id = sync_runs.id)
                       ),
                       unresolved_error_count = GREATEST(
                           unresolved_error_count + 1,
                           (SELECT COUNT(*) FROM sync_run_errors error
                             WHERE error.run_id = sync_runs.id
                               AND NOT error.resolved)
                       )
                 WHERE id = ? AND status = 'RUNNING'
                """)) {
            OffsetDateTime now = databaseTime(Instant.now(clock));
            update.setString(1, terminalStatus.name());
            update.setString(2, failureStage.name());
            update.setObject(3, now);
            update.setObject(4, now);
            update.setObject(5, runId);
            if (update.executeUpdate() != 1) {
                throw new SyncRunStateException("orphan run changed during recovery: " + runId);
            }
        }
    }

    private SyncRunView mapRun(ResultSet result) throws SQLException {
        return new SyncRunView(
                result.getObject("id", UUID.class),
                SyncTriggerKind.valueOf(result.getString("trigger_kind")),
                SyncRunStatus.valueOf(result.getString("status")),
                SyncRunStage.valueOf(result.getString("stage")),
                instant(result, "started_at"),
                instant(result, "heartbeat_at"),
                instant(result, "finished_at"),
                roots(result.getString("configured_roots")),
                result.getInt("page_size"),
                result.getString("category_tree_sha256"),
                instant(result, "category_tree_observed_at"),
                result.getInt("pages_expected"),
                result.getInt("pages_completed"),
                result.getLong("listing_rows_observed"),
                result.getLong("listing_rows_quarantined"),
                result.getLong("unique_auction_count"),
                result.getLong("duplicate_auction_count"),
                result.getLong("unknown_property_kind_count"),
                result.getLong("details_required"),
                result.getLong("details_attempted"),
                result.getLong("details_succeeded"),
                result.getLong("details_quarantined"),
                result.getLong("details_failed"),
                result.getLong("retry_count"),
                result.getLong("error_count"),
                result.getLong("unresolved_error_count"));
    }

    private List<Integer> roots(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() { });
        } catch (JsonProcessingException e) {
            throw new SyncRunStateException("stored configured roots are invalid JSON");
        }
    }

    private JsonNode parseJson(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException e) {
            throw new SyncRunStateException("stored taxonomy evidence is invalid JSON");
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new SyncRunStateException("could not serialize bounded sync evidence");
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static OffsetDateTime databaseTime(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        OffsetDateTime value = result.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static Integer nullableInteger(ResultSet result, String column) throws SQLException {
        int value = result.getInt(column);
        return result.wasNull() ? null : value;
    }

    private static Long nullableLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private static SyncRunStage recoveryErrorStage(SyncRunStage stage) {
        return switch (stage) {
            case CLAIMED -> SyncRunStage.CATEGORIES;
            case COMPLETED -> SyncRunStage.PROMOTING;
            default -> stage;
        };
    }

    private static SyncRunStage recoveryTerminalStage(SyncRunStage stage) {
        return stage == SyncRunStage.COMPLETED ? SyncRunStage.PROMOTING : stage;
    }

    private record TaxonomyIdentity(String normalizerVersion, JsonNode canonicalTree) {
    }

    private record RootCompleteness(
            int configuredCount,
            int resultCount,
            int matchingCompleteCount) {
    }

    private record ChildCompleteness(
            int configuredRootCount,
            int capturedRootCount,
            int expectedCount,
            int actualCount,
            int matchingCompleteCount) {
    }
}
