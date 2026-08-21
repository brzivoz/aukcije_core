package rs.sud.eaukcija.spatial;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * A reusable bounding-box query fixture for the spatial work in #20 and #26.
 *
 * <p>This is deliberately a throwaway table rather than the application schema:
 * the spatial columns themselves are #20's design decision. What #16 owes the
 * later issues is proof that a GiST-indexed geometry column, an SRID-aware
 * bbox filter, and distance ordering all behave in this exact image.
 */
class SpatialQueryIntegrationTest {

    private static Connection connection;

    private record Place(String name, double longitude, double latitude) {
    }

    // The same three sample locations the CRS fixture uses, spread across Serbia.
    private static final List<Place> PLACES = List.of(
            new Place("DIMITROVGRAD", 22.780484, 43.013322),
            new Place("ČAJETINA", 19.693799, 43.734697),
            new Place("VOŽDOVAC", 20.495631, 44.770078));

    @BeforeAll
    static void createFixtureTable() throws SQLException {
        PostgreSQLContainer<?> container = rs.sud.eaukcija.testsupport.PostgisTestContainer.shared();
        connection = DriverManager.getConnection(
                container.getJdbcUrl(), container.getUsername(), container.getPassword());

        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE EXTENSION IF NOT EXISTS postgis");
            statement.execute("DROP TABLE IF EXISTS spatial_fixture");
            statement.execute("""
                    CREATE TABLE spatial_fixture (
                        name     VARCHAR(64) PRIMARY KEY,
                        location geometry(Point, 4326) NOT NULL
                    )
                    """);
            statement.execute("CREATE INDEX idx_spatial_fixture_location ON spatial_fixture USING GIST (location)");
        }

        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO spatial_fixture (name, location) VALUES (?, ST_SetSRID(ST_MakePoint(?, ?), 4326))")) {
            for (Place place : PLACES) {
                insert.setString(1, place.name());
                insert.setDouble(2, place.longitude());
                insert.setDouble(3, place.latitude());
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    @AfterAll
    static void dropFixtureTable() throws SQLException {
        if (connection != null) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("DROP TABLE IF EXISTS spatial_fixture");
            }
            connection.close();
        }
    }

    @Test
    void theGeometryColumnIsRegisteredWithTheExpectedSrid() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT srid, type FROM geometry_columns
                WHERE f_table_name = 'spatial_fixture' AND f_geometry_column = 'location'
                """);
             ResultSet rs = statement.executeQuery()) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt("srid")).isEqualTo(4326);
            assertThat(rs.getString("type")).isEqualTo("POINT");
        }
    }

    @Test
    void aBoundingBoxSelectsOnlyThePlacesInsideIt() throws SQLException {
        // A box around Belgrade only.
        assertThat(namesInBox(20.2, 44.6, 20.8, 44.9)).containsExactly("VOŽDOVAC");

        // A box around western Serbia only.
        assertThat(namesInBox(19.4, 43.5, 20.0, 43.9)).containsExactly("ČAJETINA");

        // A box spanning the whole country returns everything.
        assertThat(namesInBox(18.0, 41.0, 24.0, 47.0))
                .containsExactlyInAnyOrder("DIMITROVGRAD", "ČAJETINA", "VOŽDOVAC");
    }

    @Test
    void aBoundingBoxOutsideSerbiaReturnsNothingRatherThanFailing() throws SQLException {
        assertThat(namesInBox(2.0, 48.0, 3.0, 49.0)).isEmpty();
    }

    @Test
    void theBoundingBoxQueryUsesTheSpatialIndex() throws SQLException {
        // Without this, a later viewport query would silently degrade to a
        // sequential scan as the table grows. #26 depends on it staying indexed.
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET enable_seqscan = off");
        }
        StringBuilder plan = new StringBuilder();
        try (PreparedStatement statement = connection.prepareStatement("""
                EXPLAIN SELECT name FROM spatial_fixture
                WHERE location && ST_MakeEnvelope(?, ?, ?, ?, 4326)
                """)) {
            statement.setDouble(1, 20.2);
            statement.setDouble(2, 44.6);
            statement.setDouble(3, 20.8);
            statement.setDouble(4, 44.9);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    plan.append(rs.getString(1)).append('\n');
                }
            }
        } finally {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET enable_seqscan = on");
            }
        }
        assertThat(plan.toString()).contains("idx_spatial_fixture_location");
    }

    @Test
    void distanceOrderingIsMeasuredInMetresOnTheSpheroid() throws SQLException {
        // Distance from central Belgrade; VOŽDOVAC must be nearest.
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT name, ST_Distance(location::geography, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography) AS metres
                FROM spatial_fixture
                ORDER BY metres
                """)) {
            statement.setDouble(1, 20.457273);
            statement.setDouble(2, 44.787197);
            try (ResultSet rs = statement.executeQuery()) {
                List<String> order = new ArrayList<>();
                double nearest = -1;
                while (rs.next()) {
                    if (order.isEmpty()) {
                        nearest = rs.getDouble("metres");
                    }
                    order.add(rs.getString("name"));
                }
                assertThat(order).containsExactly("VOŽDOVAC", "ČAJETINA", "DIMITROVGRAD");
                // Belgrade centre to Voždovac is a few kilometres, not degrees.
                assertThat(nearest).isBetween(1_000.0, 10_000.0);
            }
        }
    }

    private static List<String> namesInBox(double minLon, double minLat, double maxLon, double maxLat)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT name FROM spatial_fixture
                WHERE location && ST_MakeEnvelope(?, ?, ?, ?, 4326)
                  AND ST_Intersects(location, ST_MakeEnvelope(?, ?, ?, ?, 4326))
                ORDER BY name
                """)) {
            for (int offset : new int[] {0, 4}) {
                statement.setDouble(offset + 1, minLon);
                statement.setDouble(offset + 2, minLat);
                statement.setDouble(offset + 3, maxLon);
                statement.setDouble(offset + 4, maxLat);
            }
            try (ResultSet rs = statement.executeQuery()) {
                List<String> names = new ArrayList<>();
                while (rs.next()) {
                    names.add(rs.getString("name"));
                }
                return names;
            }
        }
    }
}
