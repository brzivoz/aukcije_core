package rs.sud.eaukcija.enrichment;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import rs.sud.eaukcija.model.Auction;
import rs.sud.eaukcija.repository.AuctionRepository;

/** PostgreSQL authority for deterministic work discovery and retained run state. */
@Repository
public class EnrichmentRunRepository {

    static final long CLAIM_LOCK_ID = 29_000_001L;

    private final JdbcTemplate jdbc;
    private final AuctionRepository auctions;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public EnrichmentRunRepository(
            JdbcTemplate jdbc,
            AuctionRepository auctions,
            ObjectMapper objectMapper) {
        this(jdbc, auctions, objectMapper, Clock.systemUTC());
    }

    EnrichmentRunRepository(
            JdbcTemplate jdbc,
            AuctionRepository auctions,
            ObjectMapper objectMapper,
            Clock clock) {
        this.jdbc = jdbc;
        this.auctions = auctions;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public Optional<EnrichmentRunClaim> findByIdempotencyKey(String idempotencyKey) {
        String keyHash = idempotencyKeyHash(idempotencyKey);
        UUID runId = jdbc.query("""
                SELECT id FROM enrichment_runs WHERE idempotency_key_sha256 = ?
                """, result -> result.next() ? result.getObject(1, UUID.class) : null, keyHash);
        return runId == null
                ? Optional.empty()
                : Optional.of(new EnrichmentRunClaim(runId, true));
    }

    @Transactional
    public EnrichmentRunClaim claim(
            String idempotencyKey,
            EnrichmentTriggerKind triggerKind,
            EnrichmentVersions versions,
            EnrichmentSelector selector,
            int maxItems) {
        if (maxItems < 1 || maxItems > 1_000) {
            throw new IllegalArgumentException("maxItems must be between 1 and 1000");
        }
        String keyHash = idempotencyKeyHash(idempotencyKey);
        jdbc.execute("SELECT pg_advisory_xact_lock(" + CLAIM_LOCK_ID + ")");
        UUID replay = jdbc.query("""
                SELECT id FROM enrichment_runs WHERE idempotency_key_sha256 = ?
                """, result -> result.next() ? result.getObject(1, UUID.class) : null, keyHash);
        if (replay != null) {
            return new EnrichmentRunClaim(replay, true);
        }
        if (isPaused()) {
            throw new EnrichmentUnavailableException("enrichment is paused");
        }
        Optional<UUID> active = activeRunId();
        if (active.isPresent()) {
            throw new EnrichmentAlreadyRunningException(active.orElseThrow());
        }
        UUID runId = UUID.randomUUID();
        Instant now = Instant.now(clock);
        jdbc.update("""
                INSERT INTO enrichment_runs (
                    id, idempotency_key_sha256, trigger_kind, status,
                    started_at, heartbeat_at,
                    parser_version, resolver_version, dataset_version,
                    selector_type, selector_value, max_items
                ) VALUES (?, ?, ?, 'RUNNING', ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                runId,
                keyHash,
                triggerKind.name(),
                databaseTime(now),
                databaseTime(now),
                versions.parserVersion(),
                versions.resolverVersion(),
                versions.datasetVersion(),
                selector.type().name(),
                selector.value(),
                maxItems);
        return new EnrichmentRunClaim(runId, false);
    }

    public Optional<UUID> activeRunId() {
        return jdbc.query("""
                SELECT id FROM enrichment_runs WHERE status = 'RUNNING'
                """, result -> result.next()
                ? Optional.of(result.getObject(1, UUID.class)) : Optional.empty());
    }

    public boolean isRunning(UUID runId) {
        Boolean running = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM enrichment_runs WHERE id = ? AND status = 'RUNNING')
                """, Boolean.class, runId);
        return Boolean.TRUE.equals(running);
    }

    public boolean isStale(UUID runId, Duration staleAfter) {
        if (staleAfter == null || staleAfter.isNegative() || staleAfter.isZero()) {
            throw new IllegalArgumentException("staleAfter must be positive");
        }
        Boolean stale = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM enrichment_runs
                     WHERE id = ? AND status = 'RUNNING' AND heartbeat_at <= ?
                )
                """, Boolean.class, runId, databaseTime(Instant.now(clock).minus(staleAfter)));
        return Boolean.TRUE.equals(stale);
    }

    public Optional<EnrichmentRunView> find(UUID runId) {
        return jdbc.query(runViewSql() + " WHERE id = ?", result -> result.next()
                ? Optional.of(mapRun(result)) : Optional.empty(), runId);
    }

    public List<EnrichmentRunItemView> items(UUID runId) {
        return jdbc.query("""
                SELECT ordinal, auction_id, work_key_sha256, attempt_number,
                       status, last_stage, started_at, finished_at,
                       output_sha256, error_class, error_message
                  FROM enrichment_run_items
                 WHERE run_id = ?
                 ORDER BY ordinal
                """, (result, row) -> new EnrichmentRunItemView(
                result.getInt("ordinal"),
                result.getLong("auction_id"),
                result.getString("work_key_sha256"),
                result.getInt("attempt_number"),
                EnrichmentStateStatus.valueOf(result.getString("status")),
                nullableStage(result.getString("last_stage")),
                instant(result, "started_at"),
                instant(result, "finished_at"),
                result.getString("output_sha256"),
                result.getString("error_class"),
                result.getString("error_message")), runId);
    }

    /** Backfills only pre-V13 rows already accepted by a successful #17 run. */
    @Transactional
    public int bootstrapMissingInputSnapshots() {
        Set<Long> missing = new HashSet<>(jdbc.queryForList("""
                SELECT auction.id
                  FROM auctions auction
                  JOIN sync_runs run ON run.id = auction.last_successful_sync_run_id
                  JOIN sync_run_auction_observations observation
                    ON observation.run_id = run.id AND observation.auction_id = auction.id
                 WHERE auction.current_enrichment_snapshot_sha256 IS NULL
                   AND run.status = 'SUCCEEDED'
                """, Long.class));
        if (missing.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (Auction auction : auctions.findAllById(missing)) {
            UUID sourceRunId = auction.getLastSuccessfulSyncRunId();
            if (sourceRunId == null) {
                continue;
            }
            EnrichmentInputSnapshot snapshot = EnrichmentInputSnapshot.from(auction, objectMapper);
            jdbc.update("""
                    INSERT INTO auction_enrichment_input_snapshots (
                        auction_id, snapshot_sha256, canonical_input
                    ) VALUES (?, ?, CAST(? AS jsonb))
                    ON CONFLICT (auction_id, snapshot_sha256) DO NOTHING
                    """, auction.getId(), snapshot.sha256(), json(snapshot.canonicalInput()));
            jdbc.update("""
                    INSERT INTO auction_enrichment_snapshot_observations (
                        source_sync_run_id, auction_id, snapshot_sha256
                    ) VALUES (?, ?, ?)
                    ON CONFLICT (source_sync_run_id, auction_id) DO NOTHING
                    """, sourceRunId, auction.getId(), snapshot.sha256());
            int changed = jdbc.update("""
                    UPDATE auctions SET current_enrichment_snapshot_sha256 = ?
                     WHERE id = ? AND current_enrichment_snapshot_sha256 IS NULL
                    """, snapshot.sha256(), auction.getId());
            count += changed;
        }
        return count;
    }

    public List<EnrichmentCandidate> discoverCandidates(
            EnrichmentVersions versions,
            EnrichmentSelector selector,
            int maxAttempts,
            int limit) {
        if (maxAttempts < 1 || limit < 1 || limit > 1_000) {
            throw new IllegalArgumentException("invalid candidate discovery bounds");
        }
        Set<Long> selected = selectedAuctionIds(selector);
        List<CandidateRow> rows = currentRows();
        List<EnrichmentCandidate> candidates = new ArrayList<>();
        for (CandidateRow row : rows) {
            String dependencyHash = EnrichmentHashing.sha256(
                    "upstream-parcel-evidence-v1", row.dependencyMaterial());
            String workKey = versions.workKey(row.auctionId(), row.snapshotSha256(), dependencyHash);
            boolean explicit = selector.explicitReplay();
            boolean matches = !explicit || selected.contains(row.auctionId());
            boolean retryable = row.stateStatus() == EnrichmentStateStatus.RETRYABLE_FAILURE
                    && row.retryableFailureCount() < maxAttempts;
            boolean pending = row.stateStatus() == EnrichmentStateStatus.PENDING;
            boolean changed = row.stateWorkKey() == null || !row.stateWorkKey().equals(workKey);
            if (matches && (explicit || changed || retryable || pending)) {
                Instant availableSince = !changed && row.statePendingSince() != null
                        ? row.statePendingSince() : row.observedAt();
                candidates.add(new EnrichmentCandidate(
                        new EnrichmentWorkItem(
                                row.auctionId(),
                                row.sourceSyncRunId(),
                                row.snapshotSha256(),
                                dependencyHash,
                                workKey,
                                row.canonicalInput()),
                        availableSince,
                        explicit));
            }
            if (candidates.size() == limit) {
                break;
            }
        }
        return List.copyOf(candidates);
    }

    public EnrichmentBacklogMeasure measureBacklog(
            EnrichmentVersions versions,
            int maxAttempts) {
        long count = 0;
        Instant oldest = null;
        for (CandidateRow row : currentRows()) {
            String dependencyHash = EnrichmentHashing.sha256(
                    "upstream-parcel-evidence-v1", row.dependencyMaterial());
            String workKey = versions.workKey(row.auctionId(), row.snapshotSha256(), dependencyHash);
            boolean retryable = row.stateStatus() == EnrichmentStateStatus.RETRYABLE_FAILURE
                    && row.retryableFailureCount() < maxAttempts;
            boolean pending = row.stateStatus() == EnrichmentStateStatus.PENDING;
            boolean changed = row.stateWorkKey() == null || !row.stateWorkKey().equals(workKey);
            if (changed || retryable || pending) {
                count++;
                Instant availableSince = !changed && row.statePendingSince() != null
                        ? row.statePendingSince() : row.observedAt();
                if (oldest == null || availableSince.isBefore(oldest)) {
                    oldest = availableSince;
                }
            }
        }
        return new EnrichmentBacklogMeasure(count, oldest);
    }

    /** Successful-sync auctions whose current enrichment lineage cannot be discovered. */
    public long countPopulationGaps() {
        Long count = jdbc.queryForObject("""
                WITH latest_observation AS (
                    SELECT DISTINCT ON (observation.auction_id)
                           observation.auction_id,
                           observation.snapshot_sha256
                      FROM auction_enrichment_snapshot_observations observation
                      JOIN sync_runs run ON run.id = observation.source_sync_run_id
                     WHERE run.status = 'SUCCEEDED'
                     ORDER BY observation.auction_id, run.started_at DESC,
                              observation.source_sync_run_id DESC
                )
                SELECT COUNT(*)
                  FROM auctions auction
                  JOIN sync_runs accepted
                    ON accepted.id = auction.last_successful_sync_run_id
                   AND accepted.status = 'SUCCEEDED'
                  LEFT JOIN latest_observation observation
                    ON observation.auction_id = auction.id
                  LEFT JOIN auction_enrichment_input_snapshots snapshot
                    ON snapshot.auction_id = auction.id
                   AND snapshot.snapshot_sha256 = auction.current_enrichment_snapshot_sha256
                 WHERE auction.current_enrichment_snapshot_sha256 IS NULL
                    OR observation.auction_id IS NULL
                    OR observation.snapshot_sha256 IS DISTINCT FROM
                       auction.current_enrichment_snapshot_sha256
                    OR snapshot.auction_id IS NULL
                """, Long.class);
        return count == null ? 0 : count;
    }

    @Transactional
    public void setCandidateCount(UUID runId, long candidateCount) {
        int changed = jdbc.update("""
                UPDATE enrichment_runs
                   SET candidate_count = ?, heartbeat_at = CURRENT_TIMESTAMP
                 WHERE id = ? AND status = 'RUNNING'
                """, candidateCount, runId);
        requireOne(changed, "enrichment run is not RUNNING");
    }

    @Transactional
    public EnrichmentItemAttempt startItem(
            UUID runId,
            int ordinal,
            EnrichmentCandidate candidate,
            EnrichmentVersions versions) {
        EnrichmentWorkItem item = candidate.item();
        StateAttempt prior = jdbc.query("""
                SELECT work_key_sha256, attempt_count,
                       retryable_failure_count, interruption_count
                  FROM enrichment_state
                 WHERE auction_id = ?
                 FOR UPDATE
                """, result -> result.next()
                ? new StateAttempt(
                        result.getString(1), result.getInt(2), result.getInt(3), result.getInt(4))
                : null,
                item.auctionId());
        boolean sameWork = prior != null && item.workKeySha256().equals(prior.workKey());
        int attempt = sameWork ? prior.attemptCount() + 1 : 1;
        int retryableFailures = sameWork ? prior.retryableFailureCount() : 0;
        int interruptions = sameWork ? prior.interruptionCount() : 0;
        Instant now = Instant.now(clock);
        jdbc.update("""
                INSERT INTO enrichment_state (
                    auction_id, source_sync_run_id, snapshot_sha256,
                    parser_version, resolver_version, dataset_version,
                    dependency_sha256, work_key_sha256, status, attempt_count,
                    retryable_failure_count, interruption_count,
                    pending_since, last_attempt_at, completed_at,
                    last_enrichment_run_id, last_stage, output_sha256,
                    error_class, error_message
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'RUNNING', ?, ?, ?, ?, ?, NULL, ?, NULL, NULL, NULL, NULL)
                ON CONFLICT (auction_id) DO UPDATE SET
                    source_sync_run_id = EXCLUDED.source_sync_run_id,
                    snapshot_sha256 = EXCLUDED.snapshot_sha256,
                    parser_version = EXCLUDED.parser_version,
                    resolver_version = EXCLUDED.resolver_version,
                    dataset_version = EXCLUDED.dataset_version,
                    dependency_sha256 = EXCLUDED.dependency_sha256,
                    work_key_sha256 = EXCLUDED.work_key_sha256,
                    status = 'RUNNING',
                    attempt_count = EXCLUDED.attempt_count,
                    retryable_failure_count = EXCLUDED.retryable_failure_count,
                    interruption_count = EXCLUDED.interruption_count,
                    pending_since = CASE
                        WHEN enrichment_state.work_key_sha256 = EXCLUDED.work_key_sha256
                        THEN enrichment_state.pending_since ELSE EXCLUDED.pending_since END,
                    last_attempt_at = EXCLUDED.last_attempt_at,
                    completed_at = NULL,
                    last_enrichment_run_id = EXCLUDED.last_enrichment_run_id,
                    last_stage = NULL,
                    output_sha256 = NULL,
                    error_class = NULL,
                    error_message = NULL
                """,
                item.auctionId(),
                item.sourceSyncRunId(),
                item.snapshotSha256(),
                versions.parserVersion(),
                versions.resolverVersion(),
                versions.datasetVersion(),
                item.dependencySha256(),
                item.workKeySha256(),
                attempt,
                retryableFailures,
                interruptions,
                databaseTime(candidate.availableSince()),
                databaseTime(now),
                runId);
        jdbc.update("""
                INSERT INTO enrichment_run_items (
                    run_id, ordinal, auction_id, work_key_sha256,
                    attempt_number, status, started_at
                ) VALUES (?, ?, ?, ?, ?, 'RUNNING', ?)
                """, runId, ordinal, item.auctionId(), item.workKeySha256(), attempt, databaseTime(now));
        return new EnrichmentItemAttempt(attempt, retryableFailures + 1);
    }

    @Transactional
    public void completeItem(
            UUID runId,
            long auctionId,
            EnrichmentStateStatus status,
            EnrichmentStageName lastStage,
            String outputSha256,
            String errorClass,
            String errorMessage) {
        if (status == EnrichmentStateStatus.RUNNING || status == EnrichmentStateStatus.PENDING) {
            throw new IllegalArgumentException("item completion requires a terminal attempt status");
        }
        Instant now = Instant.now(clock);
        boolean completedState = status != EnrichmentStateStatus.RETRYABLE_FAILURE;
        boolean successfulOutcome = status == EnrichmentStateStatus.SUCCEEDED
                || status == EnrichmentStateStatus.TERMINAL_NOT_FOUND
                || status == EnrichmentStateStatus.AMBIGUOUS;
        int itemChanged = jdbc.update("""
                UPDATE enrichment_run_items
                   SET status = ?, last_stage = ?, finished_at = ?,
                       output_sha256 = ?, error_class = ?, error_message = ?
                 WHERE run_id = ? AND auction_id = ? AND status = 'RUNNING'
                """,
                status.name(),
                lastStage == null ? null : lastStage.name(),
                databaseTime(now),
                outputSha256,
                errorClass,
                errorMessage,
                runId,
                auctionId);
        requireOne(itemChanged, "enrichment item is not RUNNING");
        int stateChanged = jdbc.update("""
                UPDATE enrichment_state
                   SET status = ?, last_stage = ?, output_sha256 = ?,
                       error_class = ?, error_message = ?, completed_at = ?,
                       retryable_failure_count = CASE WHEN ? THEN 0
                           ELSE retryable_failure_count + ? END,
                       interruption_count = CASE WHEN ? THEN 0 ELSE interruption_count END
                 WHERE auction_id = ? AND last_enrichment_run_id = ? AND status = 'RUNNING'
                """,
                status.name(),
                lastStage == null ? null : lastStage.name(),
                outputSha256,
                errorClass,
                errorMessage,
                completedState ? databaseTime(now) : null,
                successfulOutcome,
                status == EnrichmentStateStatus.RETRYABLE_FAILURE
                                || status == EnrichmentStateStatus.ATTEMPT_LIMIT_REACHED ? 1 : 0,
                successfulOutcome,
                auctionId,
                runId);
        requireOne(stateChanged, "enrichment state is not RUNNING");
        String counter = switch (status) {
            case SUCCEEDED -> "succeeded_count";
            case RETRYABLE_FAILURE -> "retryable_failure_count";
            case TERMINAL_NOT_FOUND -> "terminal_not_found_count";
            case AMBIGUOUS -> "ambiguous_count";
            case PERMANENT_FAILURE -> "permanent_failure_count";
            case ATTEMPT_LIMIT_REACHED -> "attempt_limit_count";
            default -> throw new IllegalArgumentException("unsupported completion status " + status);
        };
        int runChanged = jdbc.update("""
                UPDATE enrichment_runs
                   SET attempted_count = attempted_count + 1,
                       %s = %s + 1,
                       heartbeat_at = CURRENT_TIMESTAMP
                 WHERE id = ? AND status = 'RUNNING'
                """.formatted(counter, counter), runId);
        requireOne(runChanged, "enrichment run is not RUNNING");
    }

    @Transactional
    public void finish(UUID runId, EnrichmentRunStatus status) {
        if (status == EnrichmentRunStatus.RUNNING || status == EnrichmentRunStatus.INTERRUPTED) {
            throw new IllegalArgumentException("invalid normal terminal run status");
        }
        int changed = jdbc.update("""
                UPDATE enrichment_runs
                   SET status = ?, heartbeat_at = CURRENT_TIMESTAMP, finished_at = CURRENT_TIMESTAMP
                 WHERE id = ? AND status = 'RUNNING'
                """, status.name(), runId);
        requireOne(changed, "enrichment run is not RUNNING");
    }

    @Transactional
    public void fail(UUID runId, int maxInterruptions) {
        validateMaxInterruptions(maxInterruptions);
        jdbc.update("""
                UPDATE enrichment_run_items
                   SET status = 'INTERRUPTED',
                       finished_at = GREATEST(CURRENT_TIMESTAMP, started_at),
                       last_stage = COALESCE(last_stage, 'PARSE'),
                       output_sha256 = NULL,
                       error_class = 'PROCESS_INTERRUPTED',
                       error_message = 'PROCESS_INTERRUPTED'
                 WHERE run_id = ? AND status = 'RUNNING'
                """, runId);
        jdbc.update("""
                UPDATE enrichment_state
                   SET interruption_count = interruption_count + 1,
                       status = CASE WHEN interruption_count + 1 >= ?
                                     THEN 'ATTEMPT_LIMIT_REACHED' ELSE 'PENDING' END,
                       completed_at = CASE WHEN interruption_count + 1 >= ?
                                           THEN CURRENT_TIMESTAMP ELSE NULL END,
                       last_stage = NULL, output_sha256 = NULL,
                       error_class = CASE WHEN interruption_count + 1 >= ?
                                          THEN 'ATTEMPT_LIMIT_REACHED' ELSE NULL END,
                       error_message = CASE WHEN interruption_count + 1 >= ?
                                            THEN 'INTERRUPTION_LIMIT_REACHED' ELSE NULL END
                 WHERE last_enrichment_run_id = ? AND status = 'RUNNING'
                """, maxInterruptions, maxInterruptions, maxInterruptions, maxInterruptions, runId);
        int changed = jdbc.update("""
                UPDATE enrichment_runs
                   SET status = 'FAILED', heartbeat_at = CURRENT_TIMESTAMP,
                       finished_at = CURRENT_TIMESTAMP
                 WHERE id = ? AND status = 'RUNNING'
                """, runId);
        requireOne(changed, "enrichment run is not RUNNING");
    }

    @Transactional
    public void skip(UUID runId) {
        finish(runId, EnrichmentRunStatus.SKIPPED);
    }

    @Transactional
    public List<UUID> recoverInterruptedRuns(int maxInterruptions) {
        return recoverInterruptedRuns(maxInterruptions, null);
    }

    @Transactional
    public List<UUID> recoverStaleRuns(Duration staleAfter, int maxInterruptions) {
        if (staleAfter == null || staleAfter.isNegative() || staleAfter.isZero()) {
            throw new IllegalArgumentException("staleAfter must be positive");
        }
        return recoverInterruptedRuns(maxInterruptions, Instant.now(clock).minus(staleAfter));
    }

    private List<UUID> recoverInterruptedRuns(int maxInterruptions, Instant staleBefore) {
        validateMaxInterruptions(maxInterruptions);
        List<UUID> running = staleBefore == null
                ? jdbc.queryForList("""
                        SELECT id FROM enrichment_runs WHERE status = 'RUNNING' FOR UPDATE
                        """, UUID.class)
                : jdbc.queryForList("""
                        SELECT id FROM enrichment_runs
                         WHERE status = 'RUNNING' AND heartbeat_at <= ?
                         FOR UPDATE
                        """, UUID.class, databaseTime(staleBefore));
        for (UUID runId : running) {
            jdbc.update("""
                    UPDATE enrichment_run_items
                       SET status = 'INTERRUPTED',
                           finished_at = GREATEST(CURRENT_TIMESTAMP, started_at),
                           last_stage = COALESCE(last_stage, 'PARSE'),
                           output_sha256 = NULL,
                           error_class = 'PROCESS_INTERRUPTED',
                           error_message = 'PROCESS_INTERRUPTED'
                     WHERE run_id = ? AND status = 'RUNNING'
                    """, runId);
            jdbc.update("""
                    UPDATE enrichment_state
                       SET interruption_count = interruption_count + 1,
                           status = CASE WHEN interruption_count + 1 >= ?
                                         THEN 'ATTEMPT_LIMIT_REACHED' ELSE 'PENDING' END,
                           completed_at = CASE WHEN interruption_count + 1 >= ?
                                               THEN CURRENT_TIMESTAMP ELSE NULL END,
                           last_stage = NULL, output_sha256 = NULL,
                           error_class = CASE WHEN interruption_count + 1 >= ?
                                              THEN 'ATTEMPT_LIMIT_REACHED' ELSE NULL END,
                           error_message = CASE WHEN interruption_count + 1 >= ?
                                                THEN 'INTERRUPTION_LIMIT_REACHED' ELSE NULL END
                     WHERE last_enrichment_run_id = ? AND status = 'RUNNING'
                    """, maxInterruptions, maxInterruptions,
                    maxInterruptions, maxInterruptions, runId);
            jdbc.update("""
                    UPDATE enrichment_runs
                       SET status = 'INTERRUPTED', heartbeat_at = CURRENT_TIMESTAMP,
                           finished_at = CURRENT_TIMESTAMP
                     WHERE id = ? AND status = 'RUNNING'
                    """, runId);
        }
        return List.copyOf(running);
    }

    @Transactional
    public boolean setPaused(boolean paused) {
        jdbc.execute("SELECT pg_advisory_xact_lock(" + CLAIM_LOCK_ID + ")");
        jdbc.update("""
                UPDATE enrichment_control
                   SET paused = ?, changed_at = CURRENT_TIMESTAMP, change_code = ?
                 WHERE singleton
                """, paused, paused ? "OPERATOR_PAUSE" : "OPERATOR_RESUME");
        return paused;
    }

    public boolean isPaused() {
        Boolean paused = jdbc.queryForObject(
                "SELECT paused FROM enrichment_control WHERE singleton", Boolean.class);
        return Boolean.TRUE.equals(paused);
    }

    public Map<EnrichmentStateStatus, Long> statusDistribution() {
        EnumMap<EnrichmentStateStatus, Long> counts = new EnumMap<>(EnrichmentStateStatus.class);
        for (EnrichmentStateStatus status : EnrichmentStateStatus.values()) {
            if (status != EnrichmentStateStatus.INTERRUPTED) {
                counts.put(status, 0L);
            }
        }
        jdbc.query("""
                SELECT status, COUNT(*) AS count FROM enrichment_state GROUP BY status
                """, result -> {
            while (result.next()) {
                counts.put(EnrichmentStateStatus.valueOf(result.getString("status")),
                        result.getLong("count"));
            }
            return null;
        });
        return Map.copyOf(counts);
    }

    private List<CandidateRow> currentRows() {
        return jdbc.query("""
                WITH latest_observation AS (
                    SELECT DISTINCT ON (observation.auction_id)
                           observation.auction_id,
                           observation.source_sync_run_id,
                           observation.snapshot_sha256,
                           observation.observed_at
                      FROM auction_enrichment_snapshot_observations observation
                      JOIN sync_runs run ON run.id = observation.source_sync_run_id
                     WHERE run.status = 'SUCCEEDED'
                     ORDER BY observation.auction_id, run.started_at DESC, observation.source_sync_run_id DESC
                ),
                parcel_dependencies AS (
                    SELECT reference.auction_id,
                           jsonb_agg(jsonb_build_array(
                               attempt.resolver,
                               attempt.resolver_version,
                               attempt.input_fingerprint,
                               attempt.source_dataset,
                               attempt.source_dataset_version,
                               attempt.source_dataset_sha256,
                               attempt.geometry_id::text
                           ) ORDER BY attempt.id)::text AS material
                      FROM property_references reference
                      JOIN current_location_resolutions current
                        ON current.property_reference_id = reference.id
                      JOIN location_resolution_attempts attempt
                        ON attempt.id = current.resolution_attempt_id
                     WHERE attempt.location_precision = 'PARCEL'
                     GROUP BY reference.auction_id
                )
                SELECT auction.id AS auction_id,
                       observation.source_sync_run_id,
                       auction.current_enrichment_snapshot_sha256 AS snapshot_sha256,
                       snapshot.canonical_input::text,
                       observation.observed_at,
                       COALESCE(dependency.material, '') AS dependency_material,
                       state.work_key_sha256 AS state_work_key,
                       state.status AS state_status,
                       COALESCE(state.retryable_failure_count, 0) AS retryable_failure_count,
                       state.pending_since AS state_pending_since
                  FROM auctions auction
                  JOIN latest_observation observation ON observation.auction_id = auction.id
                  JOIN auction_enrichment_input_snapshots snapshot
                    ON snapshot.auction_id = auction.id
                   AND snapshot.snapshot_sha256 = auction.current_enrichment_snapshot_sha256
                  LEFT JOIN parcel_dependencies dependency ON dependency.auction_id = auction.id
                  LEFT JOIN enrichment_state state ON state.auction_id = auction.id
                 WHERE observation.snapshot_sha256 = auction.current_enrichment_snapshot_sha256
                 ORDER BY observation.observed_at, auction.id
                """, (result, row) -> new CandidateRow(
                result.getLong("auction_id"),
                result.getObject("source_sync_run_id", UUID.class),
                result.getString("snapshot_sha256"),
                parseJson(result.getString("canonical_input")),
                instant(result, "observed_at"),
                result.getString("dependency_material"),
                result.getString("state_work_key"),
                nullableStatus(result.getString("state_status")),
                result.getInt("retryable_failure_count"),
                instant(result, "state_pending_since")));
    }

    private Set<Long> selectedAuctionIds(EnrichmentSelector selector) {
        if (!selector.explicitReplay()) {
            return Set.of();
        }
        return switch (selector.type()) {
            case SOURCE_SYNC_RUN -> Set.copyOf(jdbc.queryForList("""
                    SELECT auction_id FROM auction_enrichment_snapshot_observations
                     WHERE source_sync_run_id = ?
                    """, Long.class, uuid(selector.value())));
            case ENRICHMENT_RUN -> Set.copyOf(jdbc.queryForList("""
                    SELECT auction_id FROM enrichment_run_items WHERE run_id = ?
                    """, Long.class, uuid(selector.value())));
            case AUCTION -> Set.of(positiveLong(selector.value()));
            case VERSION -> Set.copyOf(jdbc.queryForList("""
                    SELECT auction_id FROM enrichment_state
                     WHERE parser_version = ? OR resolver_version = ?
                        OR dataset_version = ? OR work_key_sha256 = ?
                    """, Long.class,
                    selector.value(), selector.value(), selector.value(), selector.value()));
            case NONE -> Set.of();
        };
    }

    private static String runViewSql() {
        return """
                SELECT id, trigger_kind, status, started_at, heartbeat_at, finished_at,
                       parser_version, resolver_version, dataset_version,
                       selector_type, selector_value, max_items,
                       candidate_count, attempted_count, succeeded_count,
                       retryable_failure_count, terminal_not_found_count,
                       ambiguous_count, permanent_failure_count, attempt_limit_count
                  FROM enrichment_runs
                """;
    }

    private static EnrichmentRunView mapRun(ResultSet result) throws SQLException {
        return new EnrichmentRunView(
                result.getObject("id", UUID.class),
                EnrichmentTriggerKind.valueOf(result.getString("trigger_kind")),
                EnrichmentRunStatus.valueOf(result.getString("status")),
                instant(result, "started_at"),
                instant(result, "heartbeat_at"),
                instant(result, "finished_at"),
                new EnrichmentVersions(
                        result.getString("parser_version"),
                        result.getString("resolver_version"),
                        result.getString("dataset_version")),
                new EnrichmentSelector(
                        EnrichmentSelectorType.valueOf(result.getString("selector_type")),
                        result.getString("selector_value")),
                result.getInt("max_items"),
                result.getLong("candidate_count"),
                result.getLong("attempted_count"),
                result.getLong("succeeded_count"),
                result.getLong("retryable_failure_count"),
                result.getLong("terminal_not_found_count"),
                result.getLong("ambiguous_count"),
                result.getLong("permanent_failure_count"),
                result.getLong("attempt_limit_count"));
    }

    private JsonNode parseJson(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("stored enrichment input is invalid", invalid);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("could not serialize enrichment input", invalid);
        }
    }

    private static UUID uuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("selector requires a UUID value");
        }
    }

    private static long positiveLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) {
                throw new IllegalArgumentException("selector requires a positive auction id");
            }
            return parsed;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("selector requires a positive auction id");
        }
    }

    private static void requireOne(int changed, String message) {
        if (changed != 1) {
            throw new IllegalStateException(message);
        }
    }

    private static String idempotencyKeyHash(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey is required");
        }
        return EnrichmentHashing.sha256(idempotencyKey);
    }

    private static OffsetDateTime databaseTime(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        OffsetDateTime value = result.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static EnrichmentStageName nullableStage(String value) {
        return value == null ? null : EnrichmentStageName.valueOf(value);
    }

    private static EnrichmentStateStatus nullableStatus(String value) {
        return value == null ? null : EnrichmentStateStatus.valueOf(value);
    }

    private static void validateMaxInterruptions(int maxInterruptions) {
        if (maxInterruptions < 1 || maxInterruptions > 20) {
            throw new IllegalArgumentException("maxInterruptions must be between 1 and 20");
        }
    }

    private record StateAttempt(
            String workKey,
            int attemptCount,
            int retryableFailureCount,
            int interruptionCount) {
    }

    private record CandidateRow(
            long auctionId,
            UUID sourceSyncRunId,
            String snapshotSha256,
            JsonNode canonicalInput,
            Instant observedAt,
            String dependencyMaterial,
            String stateWorkKey,
            EnrichmentStateStatus stateStatus,
            int retryableFailureCount,
            Instant statePendingSince) {
    }
}
