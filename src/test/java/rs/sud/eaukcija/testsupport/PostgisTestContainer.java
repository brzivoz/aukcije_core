package rs.sud.eaukcija.testsupport;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.UUID;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One PostGIS container shared by every integration test in the build.
 *
 * <p>The image tag is the same one #15 pins for production. Starting a
 * container per test class costs more than the tests themselves, so this is the
 * Testcontainers singleton pattern: started once on first use, torn down by
 * Ryuk when the JVM exits.
 */
public final class PostgisTestContainer {

    /** The production image. Changing this must be a deliberate, reviewed step. */
    public static final DockerImageName IMAGE =
            DockerImageName.parse("postgis/postgis:18-3.6@sha256:db8c151a4e1f4686b1a985a3490cf96f9f8c8c2725f58a46ef7a57e52f167cc3")
                    .asCompatibleSubstituteFor("postgres");

    /** A stock PostgreSQL image with no PostGIS, used only as a negative control. */
    public static final DockerImageName IMAGE_WITHOUT_POSTGIS = DockerImageName.parse(
            "postgres:18-alpine@sha256:d3e1620b530c944afa6e887d22eb899824da68e19c52024bf98f5220c88a65b2")
                    .asCompatibleSubstituteFor("postgres");

    private static PostgreSQLContainer<?> shared;

    private PostgisTestContainer() {
    }

    public static synchronized PostgreSQLContainer<?> shared() {
        if (shared == null) {
            shared = new PostgreSQLContainer<>(IMAGE);
            shared.start();
        }
        return shared;
    }

    /**
     * Creates an empty database inside the shared container and returns its JDBC
     * URL. Tests that must observe a migration failing need a database no other
     * test has already migrated.
     */
    public static String createEmptyDatabase() {
        PostgreSQLContainer<?> container = shared();
        String name = "it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toLowerCase(Locale.ROOT);
        try (Connection connection = DriverManager.getConnection(
                container.getJdbcUrl(), container.getUsername(), container.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        } catch (SQLException e) {
            throw new IllegalStateException("could not create an empty test database", e);
        }
        return container.getJdbcUrl().replaceFirst("/" + container.getDatabaseName() + "(\\?|$)", "/" + name + "$1");
    }
}
