package rs.sud.eaukcija.map;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads the latest transactionally completed coarse-location population run. */
@Repository
@Profile("!local-h2")
public class MapDataStatusRepository {

    private static final String LATEST_RUN = """
            SELECT resolver_version,
                   extract_version,
                   extract_source_sha256,
                   finished_at,
                   population_count,
                   none_count
              FROM coarse_location_resolution_runs
             ORDER BY finished_at DESC, id DESC
             LIMIT 1
            """;

    private final JdbcTemplate jdbc;

    public MapDataStatusRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<LatestRun> findLatest() {
        List<LatestRun> rows = jdbc.query(LATEST_RUN, (resultSet, rowNumber) -> new LatestRun(
                resultSet.getString("resolver_version"),
                resultSet.getString("extract_version"),
                resultSet.getString("extract_source_sha256"),
                resultSet.getObject("finished_at", OffsetDateTime.class).toInstant(),
                resultSet.getLong("population_count"),
                resultSet.getLong("none_count")));
        return rows.stream().findFirst();
    }

    public record LatestRun(
            String resolverVersion,
            String extractVersion,
            String extractSourceSha256,
            Instant finishedAt,
            long populationCount,
            long noneCount) {
    }
}
