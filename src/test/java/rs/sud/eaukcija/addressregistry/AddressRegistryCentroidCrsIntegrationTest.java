package rs.sud.eaukcija.addressregistry;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import rs.sud.eaukcija.testsupport.PostgisTestContainer;

/** Cross-checks the database-free production transform against PostGIS. */
class AddressRegistryCentroidCrsIntegrationTest {

    private static Connection connection;

    @BeforeAll
    static void openConnection() throws Exception {
        var container = PostgisTestContainer.shared();
        connection = DriverManager.getConnection(
                container.getJdbcUrl(), container.getUsername(), container.getPassword());
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE EXTENSION IF NOT EXISTS postgis");
        }
    }

    @AfterAll
    static void closeConnection() throws Exception {
        if (connection != null) {
            connection.close();
        }
    }

    @Test
    void etrs89Utm34TransformationMatchesPostgisForCommittedFixturePoints() {
        assertMatchesPostgis(645_094.618, 4_763_832.342);
        assertMatchesPostgis(394_810.562, 4_843_235.894);
        assertMatchesPostgis(438_254.940, 4_958_030.580);
    }

    private void assertMatchesPostgis(double easting, double northing) {
        Etrs89Utm34ToWgs84.Point actual = Etrs89Utm34ToWgs84.transform(easting, northing);
        double expectedLongitude;
        double expectedLatitude;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT ST_X(transformed), ST_Y(transformed)
                FROM (SELECT ST_Transform(ST_SetSRID(ST_MakePoint(?, ?), 25834), 4326) transformed) value
                """)) {
            statement.setDouble(1, easting);
            statement.setDouble(2, northing);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                expectedLongitude = result.getDouble(1);
                expectedLatitude = result.getDouble(2);
            }
        } catch (Exception e) {
            throw new AssertionError(e);
        }

        assertThat(actual.longitude()).isCloseTo(expectedLongitude, org.assertj.core.data.Offset.offset(1e-8));
        assertThat(actual.latitude()).isCloseTo(expectedLatitude, org.assertj.core.data.Offset.offset(1e-8));
    }
}
