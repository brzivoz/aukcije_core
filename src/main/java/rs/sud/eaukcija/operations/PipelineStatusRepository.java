package rs.sud.eaukcija.operations;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Read-only queries over immutable sync/job/import evidence. */
@Repository
@Profile("!local-h2")
public class PipelineStatusRepository {

    private static final String SYNC_COLUMNS = """
            SELECT id, trigger_kind, status, stage, started_at, finished_at,
                   duration_millis, source_count, source_delta,
                   listing_rows_observed, listing_rows_quarantined,
                   duplicate_auction_count, details_succeeded,
                   details_quarantined, retry_count, error_count,
                   unresolved_error_count
              FROM pipeline_sync_run_metrics
            """;

    private static final String ENRICHMENT_COLUMNS = """
            SELECT id, trigger_kind, status, started_at, finished_at,
                   CASE WHEN finished_at IS NULL THEN NULL
                        ELSE GREATEST(0, FLOOR(EXTRACT(EPOCH FROM (finished_at - started_at)) * 1000))::bigint
                   END AS duration_millis,
                   parser_version, resolver_version, dataset_version,
                   candidate_count, attempted_count, succeeded_count,
                   retryable_failure_count, terminal_not_found_count,
                   ambiguous_count, permanent_failure_count, attempt_limit_count
              FROM enrichment_runs
            """;

    private final JdbcTemplate jdbc;
    private final Flyway flyway;

    public PipelineStatusRepository(JdbcTemplate jdbc, Flyway flyway) {
        this.jdbc = jdbc;
        this.flyway = flyway;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PersistedEvidence read() {
        DatabaseEvidence database = databaseEvidence();
        PipelineStatus.RunMetric lastSyncAttempt = syncRun(false).orElse(null);
        PipelineStatus.RunMetric lastSuccessfulSync = lastSyncAttempt != null
                && "SUCCEEDED".equals(lastSyncAttempt.status())
                ? lastSyncAttempt
                : syncRun(true).orElse(null);
        PipelineStatus.EnrichmentRunMetric lastEnrichmentAttempt = enrichmentRun(false).orElse(null);
        PipelineStatus.EnrichmentRunMetric lastSuccessfulEnrichment = enrichmentRun(true).orElse(null);
        String qualityParserVersion = lastSuccessfulEnrichment != null
                ? lastSuccessfulEnrichment.parserVersion()
                : lastEnrichmentAttempt == null ? null : lastEnrichmentAttempt.parserVersion();
        Backlog backlog = backlog();
        return new PersistedEvidence(
                database,
                lastSyncAttempt,
                lastSuccessfulSync,
                lastEnrichmentAttempt,
                lastSuccessfulEnrichment,
                backlog,
                counts("SELECT status, COUNT(*) FROM enrichment_state GROUP BY status ORDER BY status"),
                qualityParserVersion,
                qualityParserVersion == null ? Map.of() : counts(
                        "SELECT extraction_status, COUNT(*) FROM property_references "
                                + "WHERE parser_version = ? GROUP BY extraction_status ORDER BY extraction_status",
                        qualityParserVersion),
                counts("""
                        SELECT attempt.location_precision, COUNT(*)
                          FROM current_location_resolutions current_resolution
                          JOIN location_resolution_attempts attempt
                            ON attempt.id = current_resolution.resolution_attempt_id
                         GROUP BY attempt.location_precision
                         ORDER BY attempt.location_precision
                        """),
                counts("""
                        SELECT attempt.resolver || ':' || attempt.source_dataset, COUNT(*)
                          FROM current_location_resolutions current_resolution
                          JOIN location_resolution_attempts attempt
                            ON attempt.id = current_resolution.resolution_attempt_id
                         GROUP BY attempt.resolver, attempt.source_dataset
                         ORDER BY attempt.resolver, attempt.source_dataset
                        """),
                counts("""
                        SELECT error_class, COUNT(*)
                          FROM enrichment_state
                         WHERE error_class IS NOT NULL
                         GROUP BY error_class ORDER BY error_class
                        """),
                importRun("run.outcome = 'RUNNING'", "run.started_at DESC, run.id DESC").orElse(null),
                importRun("TRUE", "run.started_at DESC, run.id DESC").orElse(null),
                importRun("run.action = 'IMPORT' AND run.outcome IN ('SUCCEEDED', 'UNCHANGED')",
                        "run.finished_at DESC, run.id DESC")
                        .orElse(null),
                retentionJob().orElse(null),
                activeAddressArtifact().orElse(null),
                latestResolverArtifact().orElse(null));
    }

    private DatabaseEvidence databaseEvidence() {
        Integer probe = jdbc.queryForObject("SELECT 1", Integer.class);
        MigrationInfoService info = flyway.info();
        MigrationInfo current = info.current();
        String version = current == null || current.getVersion() == null
                ? null : current.getVersion().getVersion();
        String expectedVersion = Arrays.stream(info.all())
                .filter(migration -> migration.getVersion() != null)
                .max(Comparator.comparing(MigrationInfo::getVersion))
                .map(migration -> migration.getVersion().getVersion())
                .orElse(null);
        return new DatabaseEvidence(
                probe != null && probe == 1,
                version,
                expectedVersion,
                info.pending().length == 0);
    }

    private Optional<PipelineStatus.RunMetric> syncRun(boolean successfulOnly) {
        String sql = SYNC_COLUMNS
                + (successfulOnly ? " WHERE status = 'SUCCEEDED'" : "")
                + " ORDER BY started_at DESC, id DESC LIMIT 1";
        List<SyncBase> rows = jdbc.query(sql, (result, row) -> new SyncBase(
                result.getObject("id", UUID.class),
                result.getString("trigger_kind"),
                result.getString("status"),
                result.getString("stage"),
                instant(result, "started_at"),
                instant(result, "finished_at"),
                nullableLong(result, "duration_millis"),
                result.getLong("source_count"),
                nullableLong(result, "source_delta"),
                result.getLong("listing_rows_observed"),
                result.getLong("listing_rows_quarantined"),
                result.getLong("duplicate_auction_count"),
                result.getLong("details_succeeded"),
                result.getLong("details_quarantined"),
                result.getLong("retry_count"),
                result.getLong("error_count"),
                result.getLong("unresolved_error_count")));
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        SyncBase run = rows.get(0);
        PipelineStatus.SnapshotChanges changes = "SUCCEEDED".equals(run.status())
                ? snapshotChanges(run.runId()) : null;
        return Optional.of(new PipelineStatus.RunMetric(
                run.runId(), run.triggerKind(), run.status(), run.stage(),
                run.startedAt(), run.finishedAt(), run.durationMillis(),
                run.sourceCount(), run.sourceDelta(), run.listingRowsObserved(),
                run.listingRowsQuarantined(), run.duplicateCount(),
                run.detailsSucceeded(), run.detailsQuarantined(), run.retryCount(),
                run.errorCount(), run.unresolvedErrorCount(), changes,
                counts("""
                        SELECT error_code, COUNT(*)
                          FROM sync_run_errors
                         WHERE run_id = ?
                         GROUP BY error_code ORDER BY error_code
                        """, run.runId())));
    }

    private PipelineStatus.SnapshotChanges snapshotChanges(UUID runId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FILTER (WHERE prior.snapshot_sha256 IS NULL) AS new_count,
                       COUNT(*) FILTER (
                           WHERE prior.snapshot_sha256 IS NOT NULL
                             AND prior.snapshot_sha256 <> current.snapshot_sha256
                       ) AS changed_count,
                       COUNT(*) FILTER (
                           WHERE prior.snapshot_sha256 = current.snapshot_sha256
                       ) AS unchanged_count
                  FROM auction_enrichment_snapshot_observations current
                  JOIN sync_runs current_run ON current_run.id = current.source_sync_run_id
                  LEFT JOIN LATERAL (
                      SELECT previous.snapshot_sha256
                        FROM auction_enrichment_snapshot_observations previous
                        JOIN sync_runs previous_run
                          ON previous_run.id = previous.source_sync_run_id
                       WHERE previous.auction_id = current.auction_id
                         AND (previous_run.started_at, previous_run.id)
                             < (current_run.started_at, current_run.id)
                       ORDER BY previous_run.started_at DESC, previous_run.id DESC
                       LIMIT 1
                  ) prior ON TRUE
                 WHERE current.source_sync_run_id = ?
                """, (result, row) -> new PipelineStatus.SnapshotChanges(
                result.getLong("new_count"),
                result.getLong("changed_count"),
                result.getLong("unchanged_count")), runId);
    }

    private Optional<PipelineStatus.EnrichmentRunMetric> enrichmentRun(boolean successfulOnly) {
        String sql = ENRICHMENT_COLUMNS
                + (successfulOnly ? " WHERE status = 'SUCCEEDED'" : "")
                + " ORDER BY started_at DESC, id DESC LIMIT 1";
        return jdbc.query(sql, result -> result.next()
                ? Optional.of(new PipelineStatus.EnrichmentRunMetric(
                        result.getObject("id", UUID.class),
                        result.getString("trigger_kind"),
                        result.getString("status"),
                        instant(result, "started_at"),
                        instant(result, "finished_at"),
                        nullableLong(result, "duration_millis"),
                        result.getString("parser_version"),
                        result.getString("resolver_version"),
                        result.getString("dataset_version"),
                        result.getLong("candidate_count"),
                        result.getLong("attempted_count"),
                        result.getLong("succeeded_count"),
                        result.getLong("retryable_failure_count"),
                        result.getLong("terminal_not_found_count"),
                        result.getLong("ambiguous_count"),
                        result.getLong("permanent_failure_count"),
                        result.getLong("attempt_limit_count")))
                : Optional.empty());
    }

    private Backlog backlog() {
        return jdbc.queryForObject("""
                SELECT COUNT(*) AS depth, MIN(pending_since) AS oldest
                  FROM enrichment_state
                 WHERE status IN ('PENDING', 'RUNNING', 'RETRYABLE_FAILURE')
                """, (result, row) -> new Backlog(
                result.getLong("depth"), instant(result, "oldest")));
    }

    private Optional<PipelineStatus.ImportRunMetric> importRun(String predicate, String ordering) {
        return jdbc.query("""
                SELECT run.id, run.action, run.outcome, run.started_at, run.finished_at,
                       run.total_millis + CASE
                           WHEN retention.outcome = 'SUCCEEDED' THEN retention.duration_millis
                           ELSE 0
                       END AS total_millis,
                       run.snapshot_id, run.source_date, run.source_row_count,
                       run.imported_row_count, run.error_code
                  FROM address_registry_import_runs run
                  LEFT JOIN address_registry_retention_jobs retention
                    ON retention.import_run_id = run.id
                 WHERE """ + " " + predicate + " ORDER BY " + ordering + " LIMIT 1",
                result -> result.next()
                        ? Optional.of(new PipelineStatus.ImportRunMetric(
                                result.getObject("id", UUID.class),
                                result.getString("action"),
                                result.getString("outcome"),
                                instant(result, "started_at"),
                                instant(result, "finished_at"),
                                nullableLong(result, "total_millis"),
                                result.getObject("snapshot_id", UUID.class),
                                result.getString("source_date"),
                                nullableLong(result, "source_row_count"),
                                nullableLong(result, "imported_row_count"),
                                result.getString("error_code")))
                        : Optional.empty());
    }

    private Optional<PipelineStatus.AddressRegistryArtifact> activeAddressArtifact() {
        return jdbc.query("""
                SELECT snapshot.id, snapshot.source_date, snapshot.gpkg_sha256,
                       snapshot.imported_row_count, active.activated_at
                  FROM address_registry_active_snapshot active
                  JOIN address_registry_snapshots snapshot ON snapshot.id = active.snapshot_id
                 WHERE active.singleton = TRUE
                """, result -> result.next()
                ? Optional.of(new PipelineStatus.AddressRegistryArtifact(
                        result.getObject("id", UUID.class),
                        result.getString("source_date"),
                        result.getString("gpkg_sha256").trim(),
                        result.getLong("imported_row_count"),
                        instant(result, "activated_at")))
                : Optional.empty());
    }

    private Optional<PipelineStatus.RetentionJobMetric> retentionJob() {
        return jdbc.query("""
                SELECT import_run_id, outcome, started_at, finished_at,
                       duration_millis, retained_snapshot_count, error_code
                  FROM address_registry_retention_jobs
                 ORDER BY finished_at DESC, import_run_id DESC
                 LIMIT 1
                """, result -> result.next()
                ? Optional.of(new PipelineStatus.RetentionJobMetric(
                        result.getObject("import_run_id", UUID.class),
                        result.getString("outcome"),
                        instant(result, "started_at"),
                        instant(result, "finished_at"),
                        result.getLong("duration_millis"),
                        nullableInteger(result, "retained_snapshot_count"),
                        result.getString("error_code")))
                : Optional.empty());
    }

    private Optional<PipelineStatus.ResolverArtifact> latestResolverArtifact() {
        return jdbc.query("""
                SELECT id, finished_at, resolver_version, extract_version,
                       extract_source_sha256, population_count, none_count
                  FROM coarse_location_resolution_runs
                 ORDER BY finished_at DESC, id DESC
                 LIMIT 1
                """, result -> result.next()
                ? Optional.of(new PipelineStatus.ResolverArtifact(
                        result.getObject("id", UUID.class),
                        instant(result, "finished_at"),
                        result.getString("resolver_version"),
                        result.getString("extract_version"),
                        result.getString("extract_source_sha256").trim(),
                        result.getLong("population_count"),
                        result.getLong("none_count")))
                : Optional.empty());
    }

    private Map<String, Long> counts(String sql, Object... arguments) {
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbc.query(sql, (RowCallbackHandler) result ->
                counts.put(result.getString(1), result.getLong(2)), arguments);
        return Map.copyOf(counts);
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        OffsetDateTime value = result.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static Long nullableLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private static Integer nullableInteger(ResultSet result, String column) throws SQLException {
        int value = result.getInt(column);
        return result.wasNull() ? null : value;
    }

    public record PersistedEvidence(
            DatabaseEvidence database,
            PipelineStatus.RunMetric lastSyncAttempt,
            PipelineStatus.RunMetric lastSuccessfulSync,
            PipelineStatus.EnrichmentRunMetric lastEnrichmentAttempt,
            PipelineStatus.EnrichmentRunMetric lastSuccessfulEnrichment,
            Backlog backlog,
            Map<String, Long> enrichmentStateDistribution,
            String qualityParserVersion,
            Map<String, Long> parserResults,
            Map<String, Long> precisionDistribution,
            Map<String, Long> resolutionSourceDistribution,
            Map<String, Long> retryErrorClasses,
            PipelineStatus.ImportRunMetric activeImport,
            PipelineStatus.ImportRunMetric lastImportAttempt,
            PipelineStatus.ImportRunMetric lastSuccessfulImport,
            PipelineStatus.RetentionJobMetric lastRetentionJob,
            PipelineStatus.AddressRegistryArtifact addressRegistryArtifact,
            PipelineStatus.ResolverArtifact resolverArtifact) {
    }

    public record DatabaseEvidence(
            boolean available,
            String schemaVersion,
            String expectedSchemaVersion,
            boolean migrationsCurrent) {
    }

    public record Backlog(long depth, Instant oldestAt) {
    }

    private record SyncBase(
            UUID runId,
            String triggerKind,
            String status,
            String stage,
            Instant startedAt,
            Instant finishedAt,
            Long durationMillis,
            long sourceCount,
            Long sourceDelta,
            long listingRowsObserved,
            long listingRowsQuarantined,
            long duplicateCount,
            long detailsSucceeded,
            long detailsQuarantined,
            long retryCount,
            long errorCount,
            long unresolvedErrorCount) {
    }
}
