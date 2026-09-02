package rs.sud.eaukcija.refresh;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import rs.sud.eaukcija.enrichment.EnrichmentRunView;
import rs.sud.eaukcija.enrichment.EnrichmentVersions;
import rs.sud.eaukcija.map.MapDataStatus;
import rs.sud.eaukcija.sync.persistence.SyncRunView;

/** Persistence authority for the durable one-click refresh aggregate. */
@Repository
@Profile("!local-h2")
public class RefreshRepository {

    private static final String VIEW_COLUMNS = """
            SELECT id, trigger_kind, status, stage, started_at, heartbeat_at, finished_at,
                   source_sync_run_id, enrichment_run_id, map_resolution_run_id,
                   parser_version, resolver_version, dataset_version,
                   listings_processed, listings_total, details_processed, details_total,
                   locations_processed, locations_total, mapped_count, population_count,
                   precision_counts::text, map_data_version, map_ready_at, failure_code
              FROM refresh_runs
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public RefreshRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this(jdbc, objectMapper, Clock.systemUTC());
    }

    RefreshRepository(JdbcTemplate jdbc, ObjectMapper objectMapper, Clock clock) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public RefreshClaim claim(
            UUID idempotencyKey,
            RefreshTriggerKind triggerKind,
            Duration runningStaleAfter) {
        recoverStaleActive(runningStaleAfter);
        String hash = sha256(idempotencyKey.toString());
        Optional<RefreshRunView> replay = findByIdempotencyHash(hash);
        if (replay.isPresent()) {
            RefreshRunView run = replay.orElseThrow();
            return new RefreshClaim(
                    run.workflowId(), run.status() == RefreshStatus.RUNNING, true);
        }
        Optional<RefreshRunView> active = findActive();
        if (active.isPresent()) {
            return new RefreshClaim(active.orElseThrow().workflowId(), true, false);
        }

        UUID workflowId = UUID.randomUUID();
        OffsetDateTime now = databaseTime(clock.instant());
        try {
            jdbc.update("""
                    INSERT INTO refresh_runs (
                        id, idempotency_key_sha256, trigger_kind, status, stage,
                        started_at, heartbeat_at
                    ) VALUES (?, ?, ?, 'RUNNING', 'DOWNLOAD_LISTINGS', ?, ?)
                    """, workflowId, hash, triggerKind.name(), now, now);
            return new RefreshClaim(workflowId, false, false);
        } catch (DuplicateKeyException overlap) {
            Optional<RefreshRunView> concurrentReplay = findByIdempotencyHash(hash);
            if (concurrentReplay.isPresent()) {
                RefreshRunView run = concurrentReplay.orElseThrow();
                return new RefreshClaim(
                        run.workflowId(), run.status() == RefreshStatus.RUNNING, true);
            }
            RefreshRunView concurrentActive = findActive()
                    .orElseThrow(() -> new IllegalStateException(
                            "refresh claim conflict has no retained winner"));
            return new RefreshClaim(concurrentActive.workflowId(), true, false);
        }
    }

    /** Atomically terminalizes the active workflow once its heartbeat lease expires. */
    public boolean recoverStaleActive(Duration runningStaleAfter) {
        OffsetDateTime now = databaseTime(clock.instant());
        return jdbc.update("""
                UPDATE refresh_runs
                   SET status = 'FAILED', failure_code = 'REFRESH_STALE_RECLAIMED',
                       heartbeat_at = ?, finished_at = GREATEST(?, started_at)
                 WHERE status = 'RUNNING' AND heartbeat_at <= ?
                """, now, now, staleBoundary(runningStaleAfter)) == 1;
    }

    /** Reconciles one retained status read without changing a live workflow. */
    public boolean recoverIfStale(UUID workflowId, Duration runningStaleAfter) {
        OffsetDateTime now = databaseTime(clock.instant());
        return jdbc.update("""
                UPDATE refresh_runs
                   SET status = 'FAILED', failure_code = 'REFRESH_STALE_RECLAIMED',
                       heartbeat_at = ?, finished_at = GREATEST(?, started_at)
                 WHERE id = ? AND status = 'RUNNING' AND heartbeat_at <= ?
                """, now, now, workflowId, staleBoundary(runningStaleAfter)) == 1;
    }

    public Optional<RefreshRunView> find(UUID workflowId) {
        return one(VIEW_COLUMNS + " WHERE id = ?", workflowId);
    }

    public Optional<RefreshRunView> findActive() {
        return one(VIEW_COLUMNS
                + " WHERE status = 'RUNNING' ORDER BY started_at DESC, id DESC LIMIT 1");
    }

    public Optional<RefreshRunView> findLatest() {
        return one(VIEW_COLUMNS + " ORDER BY started_at DESC, id DESC LIMIT 1");
    }

    public Optional<RefreshRunView> findLatestSuccessful() {
        return one(VIEW_COLUMNS
                + " WHERE status = 'SUCCEEDED' ORDER BY finished_at DESC, id DESC LIMIT 1");
    }

    public void linkSourceRun(UUID workflowId, UUID sourceRunId) {
        requireRunningUpdate(jdbc.update("""
                UPDATE refresh_runs
                   SET source_sync_run_id = ?, heartbeat_at = ?
                 WHERE id = ? AND status = 'RUNNING'
                """, sourceRunId, databaseTime(clock.instant()), workflowId), workflowId);
    }

    public void updateSourceProgress(UUID workflowId, RefreshStage stage, SyncRunView run) {
        long listingTotal = Math.max(run.pagesExpected(), run.pagesCompleted());
        long listingProcessed = Math.min(listingTotal, run.pagesCompleted());
        long detailProcessed = Math.min(
                run.detailsRequired(), run.detailsSucceeded() + run.detailsQuarantined());
        requireRunningUpdate(jdbc.update("""
                UPDATE refresh_runs
                   SET stage = ?, listings_processed = ?, listings_total = ?,
                       details_processed = ?, details_total = ?, heartbeat_at = ?
                 WHERE id = ? AND status = 'RUNNING'
                """,
                stage.name(), listingProcessed, listingTotal,
                detailProcessed, run.detailsRequired(),
                databaseTime(clock.instant()), workflowId), workflowId);
    }

    public void pinEnrichmentVersions(UUID workflowId, EnrichmentVersions versions) {
        requireRunningUpdate(jdbc.update("""
                UPDATE refresh_runs
                   SET stage = 'PROCESS_LOCATIONS', parser_version = ?, resolver_version = ?,
                       dataset_version = ?, heartbeat_at = ?
                 WHERE id = ? AND status = 'RUNNING'
                """,
                versions.parserVersion(), versions.resolverVersion(), versions.datasetVersion(),
                databaseTime(clock.instant()), workflowId), workflowId);
    }

    public void linkEnrichmentRun(UUID workflowId, UUID enrichmentRunId) {
        requireRunningUpdate(jdbc.update("""
                UPDATE refresh_runs
                   SET enrichment_run_id = ?, heartbeat_at = ?
                 WHERE id = ? AND status = 'RUNNING'
                """, enrichmentRunId, databaseTime(clock.instant()), workflowId), workflowId);
    }

    public void updateEnrichmentProgress(UUID workflowId, EnrichmentRunView run) {
        long total = Math.max(run.candidateCount(), run.attemptedCount());
        requireRunningUpdate(jdbc.update("""
                UPDATE refresh_runs
                   SET stage = 'PROCESS_LOCATIONS', locations_processed = ?,
                       locations_total = ?, heartbeat_at = ?
                 WHERE id = ? AND status = 'RUNNING'
                """, Math.min(total, run.attemptedCount()), total,
                databaseTime(clock.instant()), workflowId), workflowId);
    }

    public void markPreparingMap(UUID workflowId) {
        requireRunningUpdate(jdbc.update("""
                UPDATE refresh_runs
                   SET stage = 'PREPARE_MAP', heartbeat_at = ?
                 WHERE id = ? AND status = 'RUNNING'
                """, databaseTime(clock.instant()), workflowId), workflowId);
    }

    public void complete(UUID workflowId, MapDataStatus status) {
        OffsetDateTime now = databaseTime(clock.instant());
        requireRunningUpdate(jdbc.update("""
                UPDATE refresh_runs
                   SET status = 'SUCCEEDED', stage = 'COMPLETED',
                       map_resolution_run_id = ?, mapped_count = ?, population_count = ?,
                       precision_counts = CAST(? AS jsonb), map_data_version = ?,
                       map_ready_at = ?, heartbeat_at = ?,
                       finished_at = GREATEST(?, started_at)
                 WHERE id = ? AND status = 'RUNNING'
                """,
                status.successfulResolutionRunId(),
                status.mappedAuctionCount(), status.populationCount(),
                json(status.precisionSummary()), status.dataVersion(),
                databaseTime(status.lastSuccessfulSync()), now, now,
                workflowId), workflowId);
    }

    public boolean fail(UUID workflowId, String failureCode) {
        OffsetDateTime now = databaseTime(clock.instant());
        return jdbc.update("""
                UPDATE refresh_runs
                   SET status = 'FAILED', failure_code = ?,
                       heartbeat_at = ?, finished_at = GREATEST(?, started_at)
                 WHERE id = ? AND status = 'RUNNING'
                """, safeCode(failureCode), now, now, workflowId) == 1;
    }

    public boolean sourceIsFullyEnriched(UUID sourceRunId, EnrichmentVersions versions) {
        Long incomplete = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM sync_run_auction_observations observation
                  LEFT JOIN auction_enrichment_snapshot_observations input
                    ON input.source_sync_run_id = observation.run_id
                   AND input.auction_id = observation.auction_id
                  LEFT JOIN enrichment_state state ON state.auction_id = observation.auction_id
                 WHERE observation.run_id = ?
                   AND observation.enrichment_eligible
                   AND (
                       input.auction_id IS NULL
                       OR state.auction_id IS NULL
                       OR state.snapshot_sha256 <> input.snapshot_sha256
                       OR state.parser_version <> ?
                       OR state.resolver_version <> ?
                       OR state.dataset_version <> ?
                       OR state.status NOT IN ('SUCCEEDED', 'TERMINAL_NOT_FOUND', 'AMBIGUOUS')
                   )
                """, Long.class, sourceRunId,
                versions.parserVersion(), versions.resolverVersion(), versions.datasetVersion());
        return incomplete != null && incomplete == 0;
    }

    private Optional<RefreshRunView> findByIdempotencyHash(String hash) {
        return one(VIEW_COLUMNS + " WHERE idempotency_key_sha256 = ?", hash);
    }

    private Optional<RefreshRunView> one(String sql, Object... parameters) {
        List<RefreshRunView> rows = jdbc.query(sql, this::map, parameters);
        return rows.stream().findFirst();
    }

    private RefreshRunView map(ResultSet result, int row) throws SQLException {
        String parser = result.getString("parser_version");
        EnrichmentVersions versions = parser == null ? null : new EnrichmentVersions(
                parser, result.getString("resolver_version"), result.getString("dataset_version"));
        return new RefreshRunView(
                result.getObject("id", UUID.class),
                RefreshTriggerKind.valueOf(result.getString("trigger_kind")),
                RefreshStatus.valueOf(result.getString("status")),
                RefreshStage.valueOf(result.getString("stage")),
                instant(result, "started_at"), instant(result, "heartbeat_at"),
                instant(result, "finished_at"),
                result.getObject("source_sync_run_id", UUID.class),
                result.getObject("enrichment_run_id", UUID.class),
                result.getObject("map_resolution_run_id", UUID.class),
                versions,
                result.getLong("listings_processed"), result.getLong("listings_total"),
                result.getLong("details_processed"), result.getLong("details_total"),
                result.getLong("locations_processed"), result.getLong("locations_total"),
                result.getLong("mapped_count"), result.getLong("population_count"),
                precisionCounts(result.getString("precision_counts")),
                result.getString("map_data_version"), instant(result, "map_ready_at"),
                result.getString("failure_code"));
    }

    private Map<String, Long> precisionCounts(String value) {
        try {
            JsonNode root = objectMapper.readTree(value);
            LinkedHashMap<String, Long> counts = new LinkedHashMap<>();
            root.fields().forEachRemaining(entry -> counts.put(entry.getKey(), entry.getValue().asLong()));
            return counts;
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("retained precision counts are invalid", invalid);
        }
    }

    private String json(Map<String, Long> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("could not encode precision counts", impossible);
        }
    }

    private static void requireRunningUpdate(int changed, UUID workflowId) {
        if (changed != 1) {
            throw new IllegalStateException("refresh workflow is no longer running: " + workflowId);
        }
    }

    private static String safeCode(String code) {
        return code != null && code.matches("[A-Z0-9_]{1,64}") ? code : "REFRESH_INTERNAL";
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    private static OffsetDateTime databaseTime(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private OffsetDateTime staleBoundary(Duration runningStaleAfter) {
        if (runningStaleAfter == null || runningStaleAfter.isNegative()
                || runningStaleAfter.isZero()) {
            throw new IllegalArgumentException("runningStaleAfter must be positive");
        }
        return databaseTime(clock.instant().minus(runningStaleAfter));
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        OffsetDateTime value = result.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
