package rs.sud.eaukcija.map;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import rs.sud.eaukcija.spatial.LocationSelectionSql;

/** Reads the latest transactionally completed coarse-location population run. */
@Repository
@Profile("!local-h2")
public class MapDataStatusRepository {

    private static final String LATEST_RUN = """
            SELECT id,
                   refresh_run_id,
                   resolver_version,
                   extract_version,
                   extract_source_sha256,
                   finished_at,
                   population_count,
                   none_count
              FROM coarse_location_resolution_runs
             ORDER BY finished_at DESC, id DESC
             LIMIT 1
            """;
    private static final String PRECISION_SUMMARY = """
            SELECT selected.location_precision, COUNT(*)
              FROM (
                    SELECT DISTINCT ON (reference.auction_id)
                           reference.auction_id,
                           attempt.location_precision
                      FROM current_location_resolutions current_resolution
                      JOIN property_references reference
                        ON reference.id = current_resolution.property_reference_id
                      JOIN location_resolution_attempts attempt
                        ON attempt.id = current_resolution.resolution_attempt_id
                     WHERE attempt.resolution_status = 'RESOLVED'
                       AND %s
                     ORDER BY reference.auction_id, %s
                   ) selected
             GROUP BY selected.location_precision
             ORDER BY selected.location_precision
            """.formatted(
                    LocationSelectionSql.publishableReferencePredicate(
                            "reference.extraction_status"),
                    LocationSelectionSql.bestOrder(
                            "attempt.location_precision",
                            "reference.reference_order",
                            "attempt.completed_at",
                            "attempt.id"));

    private final JdbcTemplate jdbc;

    public MapDataStatusRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Optional<LatestRun> findLatest() {
        List<LatestRun> rows = jdbc.query(LATEST_RUN, (resultSet, rowNumber) -> new LatestRun(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("refresh_run_id", UUID.class),
                resultSet.getString("resolver_version"),
                resultSet.getString("extract_version"),
                resultSet.getString("extract_source_sha256"),
                resultSet.getObject("finished_at", OffsetDateTime.class).toInstant(),
                resultSet.getLong("population_count"),
                resultSet.getLong("none_count"),
                Map.of()));
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        LinkedHashMap<String, Long> precision = new LinkedHashMap<>();
        jdbc.query(PRECISION_SUMMARY, (RowCallbackHandler) result ->
                        precision.put(result.getString(1), result.getLong(2)));
        LatestRun run = rows.get(0);
        return Optional.of(new LatestRun(
                run.runId(), run.refreshRunId(), run.resolverVersion(), run.extractVersion(),
                run.extractSourceSha256(), run.finishedAt(), run.populationCount(),
                run.noneCount(), precision));
    }

    public record LatestRun(
            UUID runId,
            UUID refreshRunId,
            String resolverVersion,
            String extractVersion,
            String extractSourceSha256,
            Instant finishedAt,
            long populationCount,
            long noneCount,
            Map<String, Long> precisionSummary) {

        public LatestRun {
            precisionSummary = Map.copyOf(precisionSummary);
        }

        public LatestRun(
                String resolverVersion,
                String extractVersion,
                String extractSourceSha256,
                Instant finishedAt,
                long populationCount,
                long noneCount) {
            this(null, null, resolverVersion, extractVersion, extractSourceSha256,
                    finishedAt, populationCount, noneCount, Map.of());
        }
    }
}
