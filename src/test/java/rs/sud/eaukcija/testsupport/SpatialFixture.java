package rs.sud.eaukcija.testsupport;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.testcontainers.containers.PostgreSQLContainer;

/**
 * A disposable, pre-loaded point table for exercising spatial queries.
 *
 * <p>This is scaffolding, not schema. The application's own geometry columns,
 * SRID choices, and index strategy are #20's decision, and nothing here may be
 * read as evidence about them: a test that creates a geometry column and then
 * asserts that geometry column exists proves only that CREATE TABLE works.
 *
 * <p>What this fixture is for is the other half — giving #20 and #26 a ready
 * table of known points so their tests can assert query <em>results</em>
 * against coordinates that were verified independently in #13.
 */
public final class SpatialFixture implements AutoCloseable {

    public record Place(String name, double longitude, double latitude) {
    }

    /** The three #13/#32 sample locations, spread across Serbia. */
    public static final List<Place> PLACES = List.of(
            new Place("DIMITROVGRAD", 22.780484, 43.013322),
            new Place("ČAJETINA", 19.693799, 43.734697),
            new Place("VOŽDOVAC", 20.495631, 44.770078));

    private final Connection connection;
    private final String table;

    private SpatialFixture(Connection connection, String table) {
        this.connection = connection;
        this.table = table;
    }

    public static SpatialFixture loadPoints(String table) throws SQLException {
        PostgreSQLContainer<?> container = PostgisTestContainer.shared();
        Connection connection = DriverManager.getConnection(
                container.getJdbcUrl(), container.getUsername(), container.getPassword());

        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE EXTENSION IF NOT EXISTS postgis");
            statement.execute("DROP TABLE IF EXISTS " + table);
            statement.execute("""
                    CREATE TABLE %s (
                        name     VARCHAR(64) PRIMARY KEY,
                        location geometry(Point, 4326) NOT NULL
                    )
                    """.formatted(table));
            statement.execute(
                    "CREATE INDEX idx_%s_location ON %s USING GIST (location)".formatted(table, table));
        }

        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO %s (name, location) VALUES (?, ST_SetSRID(ST_MakePoint(?, ?), 4326))"
                        .formatted(table))) {
            for (Place place : PLACES) {
                insert.setString(1, place.name());
                insert.setDouble(2, place.longitude());
                insert.setDouble(3, place.latitude());
                insert.addBatch();
            }
            insert.executeBatch();
        }
        return new SpatialFixture(connection, table);
    }

    public Connection connection() {
        return connection;
    }

    /** Names whose point falls inside the given WGS 84 bounding box, sorted. */
    public List<String> namesInBox(double minLon, double minLat, double maxLon, double maxLat)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT name FROM %s
                WHERE location && ST_MakeEnvelope(?, ?, ?, ?, 4326)
                  AND ST_Intersects(location, ST_MakeEnvelope(?, ?, ?, ?, 4326))
                ORDER BY name
                """.formatted(table))) {
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

    /** Names ordered by spheroidal distance from a point, nearest first. */
    public List<String> namesByDistanceFrom(double longitude, double latitude) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT name FROM %s
                ORDER BY ST_Distance(location::geography, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography)
                """.formatted(table))) {
            statement.setDouble(1, longitude);
            statement.setDouble(2, latitude);
            try (ResultSet rs = statement.executeQuery()) {
                List<String> names = new ArrayList<>();
                while (rs.next()) {
                    names.add(rs.getString("name"));
                }
                return names;
            }
        }
    }

    /** Spheroidal distance in metres from a point to one named place. */
    public double distanceMetres(String name, double longitude, double latitude) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT ST_Distance(location::geography, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography) AS metres
                FROM %s WHERE name = ?
                """.formatted(table))) {
            statement.setDouble(1, longitude);
            statement.setDouble(2, latitude);
            statement.setString(3, name);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("no such place in the fixture: " + name);
                }
                return rs.getDouble("metres");
            }
        }
    }

    @Override
    public void close() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS " + table);
        } finally {
            connection.close();
        }
    }
}
