package rs.sud.eaukcija;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;

import rs.sud.eaukcija.testsupport.PostgisTestContainer;

/**
 * The negative controls for {@link PostgisSchemaIntegrationTest}.
 *
 * <p>A green integration suite only means something if it can go red. Each test
 * here breaks one guarantee the positive test relies on and proves the Spring
 * context refuses to start, rather than starting on a half-built database and
 * failing later in production.
 */
class SchemaNegativeControlTest {

    private ConfigurableApplicationContext startContext(String jdbcUrl, String username, String password,
                                                        String flywayLocations) {
        // These go in as command-line arguments, not as SpringApplicationBuilder
        // default properties: default properties lose to application.properties,
        // which would silently leave the context on H2 and prove nothing.
        //
        // Only the fault and the connection are injected. The wiring under test —
        // Flyway on, ddl-auto=validate — still comes from
        // application-postgis.properties.
        return new SpringApplicationBuilder(SudAukcijeApplication.class)
                .web(WebApplicationType.NONE)
                .profiles("postgis")
                .run(
                        "--spring.datasource.url=" + jdbcUrl,
                        "--spring.datasource.username=" + username,
                        "--spring.datasource.password=" + password,
                        "--spring.flyway.locations=" + flywayLocations);
    }

    @Test
    void theContextFailsWhenAMigrationIsInvalid() {
        PostgreSQLContainer<?> container = PostgisTestContainer.shared();
        String jdbcUrl = PostgisTestContainer.createEmptyDatabase();

        assertThatThrownBy(() -> startContext(jdbcUrl, container.getUsername(), container.getPassword(),
                "classpath:db/migration,classpath:db/broken"))
                .hasStackTraceContaining("V900__deliberately_invalid.sql");

        // The migrations before the broken one must have committed: a partly
        // migrated database has to stay visibly partly migrated rather than
        // rolling back to empty and hiding what happened. Named rather than
        // counted, so adding a real migration does not fail a control that is
        // still behaving correctly.
        assertThat(appliedMigrationScripts(jdbcUrl, container))
                .contains("V1__enable_postgis.sql", "V2__baseline_auctions.sql")
                .doesNotContain("V900__deliberately_invalid.sql");
    }

    @Test
    void theContextFailsWhenThePostgisExtensionIsUnavailable() {
        try (PostgreSQLContainer<?> plainPostgres =
                     new PostgreSQLContainer<>(PostgisTestContainer.IMAGE_WITHOUT_POSTGIS)) {
            plainPostgres.start();

            assertThatThrownBy(() -> startContext(plainPostgres.getJdbcUrl(), plainPostgres.getUsername(),
                    plainPostgres.getPassword(), "classpath:db/migration"))
                    .hasStackTraceContaining("V1__enable_postgis.sql")
                    // Named explicitly so this cannot pass for some unrelated
                    // startup failure: it must be PostgreSQL rejecting the
                    // extension, which is the whole point of the control.
                    .hasStackTraceContaining("extension \"postgis\" is not available");
        }
    }

    @Test
    void theContextFailsWhenTheSchemaDoesNotMatchTheEntity() {
        PostgreSQLContainer<?> container = PostgisTestContainer.shared();
        String jdbcUrl = PostgisTestContainer.createEmptyDatabase();

        // Migrate normally, then take a column the Auction entity needs away.
        try (ConfigurableApplicationContext context =
                     startContext(jdbcUrl, container.getUsername(), container.getPassword(), "classpath:db/migration")) {
            assertThat(context.isRunning()).isTrue();
        }
        execute(jdbcUrl, container, "ALTER TABLE auctions DROP COLUMN cadastral");

        // ddl-auto=validate must now refuse to start rather than silently
        // recreating the column, which is what ddl-auto=update would have done.
        assertThatThrownBy(() -> startContext(jdbcUrl, container.getUsername(), container.getPassword(),
                "classpath:db/migration"))
                .hasStackTraceContaining("cadastral");
    }

    private static List<String> appliedMigrationScripts(String jdbcUrl, PostgreSQLContainer<?> container) {
        try (Connection connection =
                     DriverManager.getConnection(jdbcUrl, container.getUsername(), container.getPassword());
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT script FROM flyway_schema_history WHERE success ORDER BY installed_rank")) {
            List<String> scripts = new ArrayList<>();
            while (rs.next()) {
                scripts.add(rs.getString("script"));
            }
            return scripts;
        } catch (SQLException e) {
            throw new IllegalStateException("could not read flyway history", e);
        }
    }

    private static void execute(String jdbcUrl, PostgreSQLContainer<?> container, String sql) {
        try (Connection connection =
                     DriverManager.getConnection(jdbcUrl, container.getUsername(), container.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("could not execute: " + sql, e);
        }
    }
}
