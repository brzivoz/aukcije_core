package rs.sud.eaukcija.sync.persistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL authority for durable sync claims, progress, evidence, and recovery. */
@Repository
public class SyncRunRepository {

    static final long CLAIM_LOCK_ID = 17_000_001L;
    static final long WORKER_LOCK_ID = 17_000_002L;

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

    public Optional<SyncRunView> find(UUID runId) {
        return jdbc.query("""
                SELECT id, trigger_kind, status, stage,
                       started_at, heartbeat_at, finished_at,
                       configured_roots::text, page_size,
                       category_tree_sha256, category_tree_observed_at,
                       pages_expected, pages_completed, listing_rows_observed,
                       unique_auction_count, duplicate_auction_count,
                       unknown_property_kind_count,
                       details_required, details_attempted, details_succeeded, details_failed,
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
                       unique_auction_count, duplicate_auction_count,
                       unknown_property_kind_count,
                       details_required, details_attempted, details_succeeded, details_failed,
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
                       error_code, retryable, attempt_number
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
                result.getInt("attempt_number")), runId);
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
        lockRunning(runId);
        Integer ordinal = jdbc.queryForObject("""
                SELECT COALESCE(MAX(ordinal), 0) + 1
                  FROM sync_run_errors
                 WHERE run_id = ?
                """, Integer.class, runId);
        jdbc.update("""
                INSERT INTO sync_run_errors (
                    run_id, ordinal, occurred_at, stage,
                    root_category_id, child_category_id, page_number, auction_id, http_status,
                    error_code, retryable, attempt_number
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                evidence.attemptNumber());
        int updated = jdbc.update("""
                UPDATE sync_runs run
                   SET error_count = GREATEST(
                           run.error_count,
                           (SELECT COUNT(*) FROM sync_run_errors error
                             WHERE error.run_id = run.id)
                       ),
                       unresolved_error_count = GREATEST(
                           run.unresolved_error_count,
                           (SELECT COUNT(*) FROM sync_run_errors error
                             WHERE error.run_id = run.id)
                       ),
                       retry_count = run.retry_count + ?,
                       heartbeat_at = CURRENT_TIMESTAMP
                 WHERE run.id = ? AND run.status = 'RUNNING'
                """, evidence.attemptNumber() - 1, runId);
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
        if (lease == null || !lease.isHeld()) {
            throw new IllegalArgumentException("a held worker lock lease is required for recovery");
        }
        if (staleAfter == null || staleAfter.isNegative()) {
            throw new IllegalArgumentException("staleAfter must not be null or negative");
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
                        insertRecoveryError(connection, runId, recoveryErrorStage(stage));
                        terminalizeRecovered(connection, runId,
                                partial ? SyncRunStatus.PARTIAL : SyncRunStatus.FAILED);
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
                       unique_auction_count, duplicate_auction_count,
                       unknown_property_kind_count,
                       details_required, details_attempted, details_succeeded, details_failed,
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

    @Transactional(propagation = Propagation.MANDATORY)
    public void replaceMemberships(
            UUID runId,
            String taxonomySha256,
            AuctionPromotionCandidate candidate) {
        jdbc.update("""
                DELETE FROM auction_source_category_memberships membership
                      USING sync_runs run
                 WHERE run.id = ?
                   AND membership.auction_id = ?
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
                                 FROM eaukcija_taxonomies taxonomy
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
                                WHERE taxonomy.tree_sha256 = membership.taxonomy_sha256
                                  AND (child_node ->> 'value')::integer = membership.category_id
                                  AND run.configured_roots @> jsonb_build_array(
                                      (root_node ->> 'value')::integer
                                  )
                           )
                       )
                   )
                """, runId, candidate.auction().getId());
        for (CategoryMembership membership : candidate.memberships()) {
            jdbc.update("""
                    INSERT INTO auction_source_category_memberships (
                        auction_id, category_id, membership_type, category_name,
                        taxonomy_sha256, last_successful_sync_run_id
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT (auction_id, category_id, membership_type) DO UPDATE
                       SET category_name = EXCLUDED.category_name,
                           taxonomy_sha256 = EXCLUDED.taxonomy_sha256,
                           last_successful_sync_run_id = EXCLUDED.last_successful_sync_run_id
                    """,
                    candidate.auction().getId(),
                    membership.categoryId(),
                    membership.type().name(),
                    membership.categoryName(),
                    taxonomySha256,
                    runId);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void insertSuccessObservation(UUID runId, AuctionPromotionCandidate candidate) {
        jdbc.update("""
                INSERT INTO sync_run_auction_observations (
                    run_id, auction_id, listing_fingerprint, detail_refreshed,
                    enrichment_eligible, enrichment_reason
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                runId,
                candidate.auction().getId(),
                candidate.listingFingerprint(),
                candidate.detailRefreshed(),
                candidate.enrichmentEligible(),
                candidate.enrichmentReason().name());
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
                   AND details_succeeded = details_required
                   AND details_failed = 0
                   AND unresolved_error_count = 0
                """, runId);
        if (changed != 1) {
            throw new SyncRunStateException("run does not satisfy success completeness gates: " + runId);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void insertEnrichmentWork(UUID runId, AuctionPromotionCandidate candidate) {
        if (!candidate.enrichmentEligible()) {
            return;
        }
        jdbc.update("""
                INSERT INTO sync_enrichment_queue (run_id, auction_id, status, reason)
                VALUES (?, ?, 'PENDING', ?)
                """, runId, candidate.auction().getId(), candidate.enrichmentReason().name());
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
                       listing_rows_observed = ?, unique_auction_count = ?,
                       duplicate_auction_count = ?, unknown_property_kind_count = ?,
                       details_required = ?, details_attempted = ?,
                       details_succeeded = ?, details_failed = ?,
                       retry_count = ?, error_count = ?, unresolved_error_count = ?
                 WHERE id = ? AND status = 'RUNNING'
                """,
                terminal ? terminalStatus.name() : SyncRunStatus.RUNNING.name(),
                terminal ? SyncRunStage.COMPLETED.name() : progress.stage().name(),
                terminal,
                progress.categoryTreeSha256(),
                databaseTime(progress.categoryTreeObservedAt()),
                progress.pagesExpected(),
                progress.pagesCompleted(),
                progress.listingRowsObserved(),
                progress.uniqueAuctionCount(),
                progress.duplicateAuctionCount(),
                progress.unknownPropertyKindCount(),
                progress.detailsRequired(),
                progress.detailsAttempted(),
                progress.detailsSucceeded(),
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

    private void insertRecoveryError(Connection connection, UUID runId, SyncRunStage stage) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO sync_run_errors (
                    run_id, ordinal, occurred_at, stage, error_code, retryable, attempt_number
                ) SELECT ?, COALESCE(MAX(ordinal), 0) + 1, ?, ?,
                         'STALE_RUN_RECOVERED', TRUE, 1
                    FROM sync_run_errors
                   WHERE run_id = ?
                """)) {
            insert.setObject(1, runId);
            insert.setObject(2, databaseTime(Instant.now(clock)));
            insert.setString(3, stage.name());
            insert.setObject(4, runId);
            insert.executeUpdate();
        }
    }

    private void terminalizeRecovered(
            Connection connection,
            UUID runId,
            SyncRunStatus terminalStatus) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE sync_runs
                   SET status = ?, stage = 'COMPLETED',
                       heartbeat_at = ?, finished_at = ?,
                       error_count = GREATEST(
                           error_count + 1,
                           (SELECT COUNT(*) FROM sync_run_errors error
                             WHERE error.run_id = sync_runs.id)
                       ),
                       unresolved_error_count = GREATEST(
                           unresolved_error_count + 1,
                           (SELECT COUNT(*) FROM sync_run_errors error
                             WHERE error.run_id = sync_runs.id)
                       )
                 WHERE id = ? AND status = 'RUNNING'
                """)) {
            OffsetDateTime now = databaseTime(Instant.now(clock));
            update.setString(1, terminalStatus.name());
            update.setObject(2, now);
            update.setObject(3, now);
            update.setObject(4, runId);
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
                result.getLong("unique_auction_count"),
                result.getLong("duplicate_auction_count"),
                result.getLong("unknown_property_kind_count"),
                result.getLong("details_required"),
                result.getLong("details_attempted"),
                result.getLong("details_succeeded"),
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
