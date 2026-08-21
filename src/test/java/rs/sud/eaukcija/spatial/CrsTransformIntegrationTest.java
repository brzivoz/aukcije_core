package rs.sud.eaukcija.spatial;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import rs.sud.eaukcija.testsupport.Fixtures;
import rs.sud.eaukcija.testsupport.PostgisTestContainer;

/**
 * Re-derives the committed CRS fixtures through PostGIS.
 *
 * <p>#13 already proved these coordinates with pyproj. Proving them a second
 * time through the database catches the failure this project actually cares
 * about: a PostGIS image whose PROJ data is missing or wrong would otherwise
 * silently place every auction in the wrong location.
 */
class CrsTransformIntegrationTest {

    /** One centimetre, the tolerance #13 used against pyproj. */
    private static final double TOLERANCE_METRES = 0.01;

    private static Connection connection;
    private static List<Sample> samples;

    private record Sample(String ko, String parcel, double longitude, double latitude,
                          double easting25834, double northing25834,
                          double easting32634, double northing32634) {
    }

    @BeforeAll
    static void openConnection() throws Exception {
        PostgreSQLContainer<?> container = PostgisTestContainer.shared();
        connection = DriverManager.getConnection(
                container.getJdbcUrl(), container.getUsername(), container.getPassword());
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE EXTENSION IF NOT EXISTS postgis");
        }

        JsonNode root = new ObjectMapper().readTree(Fixtures.read("spatial/crs-samples.json"));
        samples = new ArrayList<>();
        for (JsonNode node : root.get("samples")) {
            samples.add(new Sample(
                    node.get("ko").asText(),
                    node.get("parcel").asText(),
                    node.get("longitude").asDouble(),
                    node.get("latitude").asDouble(),
                    node.get("epsg25834").get("easting").asDouble(),
                    node.get("epsg25834").get("northing").asDouble(),
                    node.get("epsg32634").get("easting").asDouble(),
                    node.get("epsg32634").get("northing").asDouble()));
        }
    }

    @AfterAll
    static void closeConnection() throws SQLException {
        if (connection != null) {
            connection.close();
        }
    }

    @Test
    void theFixtureCarriesEverySampleTheSpikeMeasured() {
        assertThat(samples).hasSize(3);
        assertThat(samples).extracting(Sample::ko)
                .containsExactlyInAnyOrder("DIMITROVGRAD", "ČAJETINA", "VOŽDOVAC");
    }

    @TestFactory
    List<DynamicTest> transformsEachSampleToBothProjectedCrs() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Sample sample : samples) {
            tests.add(DynamicTest.dynamicTest(sample.ko() + "/" + sample.parcel() + " -> EPSG:25834",
                    () -> assertTransform(sample, 25834, sample.easting25834(), sample.northing25834())));
            tests.add(DynamicTest.dynamicTest(sample.ko() + "/" + sample.parcel() + " -> EPSG:32634",
                    () -> assertTransform(sample, 32634, sample.easting32634(), sample.northing32634())));
        }
        return tests;
    }

    @TestFactory
    List<DynamicTest> roundTripsEachSampleBackToWgs84() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Sample sample : samples) {
            for (int srid : new int[] {25834, 32634}) {
                int target = srid;
                tests.add(DynamicTest.dynamicTest(sample.ko() + " via EPSG:" + srid, () -> {
                    String sql = """
                            SELECT ST_X(back) AS lon, ST_Y(back) AS lat
                            FROM (
                              SELECT ST_Transform(
                                       ST_Transform(ST_SetSRID(ST_MakePoint(?, ?), 4326), ?),
                                       4326) AS back
                            ) t
                            """;
                    try (PreparedStatement statement = connection.prepareStatement(sql)) {
                        statement.setDouble(1, sample.longitude());
                        statement.setDouble(2, sample.latitude());
                        statement.setInt(3, target);
                        try (ResultSet rs = statement.executeQuery()) {
                            assertThat(rs.next()).isTrue();
                            // 1e-7 degrees is roughly a centimetre of latitude.
                            assertThat(rs.getDouble("lon")).isCloseTo(sample.longitude(), within(1e-7));
                            assertThat(rs.getDouble("lat")).isCloseTo(sample.latitude(), within(1e-7));
                        }
                    }
                }));
            }
        }
        return tests;
    }

    private static void assertTransform(Sample sample, int srid, double expectedEasting, double expectedNorthing)
            throws SQLException {
        String sql = """
                SELECT ST_X(projected) AS easting, ST_Y(projected) AS northing
                FROM (
                  SELECT ST_Transform(ST_SetSRID(ST_MakePoint(?, ?), 4326), ?) AS projected
                ) t
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setDouble(1, sample.longitude());
            statement.setDouble(2, sample.latitude());
            statement.setInt(3, srid);
            try (ResultSet rs = statement.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getDouble("easting"))
                        .as("%s easting in EPSG:%d", sample.ko(), srid)
                        .isCloseTo(expectedEasting, within(TOLERANCE_METRES));
                assertThat(rs.getDouble("northing"))
                        .as("%s northing in EPSG:%d", sample.ko(), srid)
                        .isCloseTo(expectedNorthing, within(TOLERANCE_METRES));
            }
        }
    }

    private static org.assertj.core.data.Offset<Double> within(double tolerance) {
        return org.assertj.core.data.Offset.offset(tolerance);
    }
}
