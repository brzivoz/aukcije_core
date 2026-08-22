package rs.sud.eaukcija;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.PostgreSQLContainer;

import rs.sud.eaukcija.model.Auction;
import rs.sud.eaukcija.repository.AuctionRepository;
import rs.sud.eaukcija.testsupport.PostgisApplication;
import rs.sud.eaukcija.testsupport.PostgisTestContainer;

/** Upgrade, restart, and operator backup/restore acceptance coverage for #15. */
class DatabaseLifecycleIntegrationTest {

    @Test
    void upgradesARealVersionedDatabaseAndRecordsHistory() {
        PostgreSQLContainer<?> container = PostgisTestContainer.shared();
        String jdbcUrl = PostgisTestContainer.createEmptyDatabase();

        Flyway.configure()
                .dataSource(jdbcUrl, container.getUsername(), container.getPassword())
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("1"))
                .load()
                .migrate();

        assertThat(queryBoolean(jdbcUrl, container,
                "SELECT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'postgis')")).isTrue();
        assertThat(queryBoolean(jdbcUrl, container,
                "SELECT to_regclass('public.auctions') IS NOT NULL")).isFalse();

        try (ConfigurableApplicationContext context = PostgisApplication.start(
                jdbcUrl, container.getUsername(), container.getPassword())) {
            assertThat(context.isRunning()).isTrue();
        }

        assertThat(queryStrings(jdbcUrl, container,
                "SELECT script FROM flyway_schema_history WHERE success ORDER BY installed_rank"))
                .containsExactly("V1__enable_postgis.sql", "V2__baseline_auctions.sql",
                        "V3__auction_filter_indexes.sql", "V4__address_registry_snapshots.sql",
                        "V5__structured_ko_matches.sql");
    }

    @Test
    void dataSurvivesACompleteApplicationRestart() {
        PostgreSQLContainer<?> container = PostgisTestContainer.shared();
        String jdbcUrl = PostgisTestContainer.createEmptyDatabase();

        try (ConfigurableApplicationContext first = PostgisApplication.start(
                jdbcUrl, container.getUsername(), container.getPassword())) {
            AuctionRepository repository = first.getBean(AuctionRepository.class);
            repository.saveAndFlush(auction(1515L, "Н1515"));
        }

        try (ConfigurableApplicationContext restarted = PostgisApplication.start(
                jdbcUrl, container.getUsername(), container.getPassword())) {
            AuctionRepository repository = restarted.getBean(AuctionRepository.class);
            Auction persisted = repository.findById(1515L).orElseThrow();
            assertThat(persisted.getAuctionNumber()).isEqualTo("Н1515");
            assertThat(persisted.getStartingPrice()).isEqualByComparingTo("151500.00");
        }
    }

    @Test
    void customFormatBackupRestoresIntoACleanDatabase() throws Exception {
        PostgreSQLContainer<?> container = PostgisTestContainer.shared();
        String sourceUrl = PostgisTestContainer.createEmptyDatabase();
        String restoredUrl = PostgisTestContainer.createEmptyDatabase();

        try (ConfigurableApplicationContext source = PostgisApplication.start(
                sourceUrl, container.getUsername(), container.getPassword())) {
            source.getBean(AuctionRepository.class).saveAndFlush(auction(1516L, "Н1516"));
        }

        String sourceDatabase = databaseName(sourceUrl);
        String restoredDatabase = databaseName(restoredUrl);
        String backupPath = "/tmp/issue-15-" + sourceDatabase + ".dump";
        String password = container.getPassword();
        String username = container.getUsername();

        ExecResult dump = container.execInContainer("sh", "-c",
                "PGPASSWORD='" + password + "' pg_dump --host=127.0.0.1 --username='" + username
                        + "' --format=custom --file='" + backupPath + "' '" + sourceDatabase + "'");
        assertThat(dump.getExitCode()).as(dump.getStderr()).isZero();

        ExecResult restore = container.execInContainer("sh", "-c",
                "PGPASSWORD='" + password + "' pg_restore --host=127.0.0.1 --username='" + username
                        + "' --dbname='" + restoredDatabase + "' --exit-on-error '" + backupPath + "'");
        assertThat(restore.getExitCode()).as(restore.getStderr()).isZero();

        try (ConfigurableApplicationContext restored = PostgisApplication.start(
                restoredUrl, username, password)) {
            AuctionRepository repository = restored.getBean(AuctionRepository.class);
            assertThat(repository.findById(1516L)).isPresent();
        }
    }

    private static Auction auction(long id, String number) {
        Auction auction = new Auction();
        auction.setId(id);
        auction.setAuctionNumber(number);
        auction.setStartingPrice(new BigDecimal("151500.00"));
        auction.setFirstSale(true);
        auction.setDetailsFetched(false);
        return auction;
    }

    private static String databaseName(String jdbcUrl) {
        String withoutQuery = jdbcUrl.substring(0, jdbcUrl.indexOf('?') >= 0 ? jdbcUrl.indexOf('?') : jdbcUrl.length());
        return withoutQuery.substring(withoutQuery.lastIndexOf('/') + 1);
    }

    private static boolean queryBoolean(String jdbcUrl, PostgreSQLContainer<?> container, String sql) {
        try (Connection connection = DriverManager.getConnection(
                jdbcUrl, container.getUsername(), container.getPassword());
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getBoolean(1);
        } catch (SQLException e) {
            throw new IllegalStateException("could not run database assertion", e);
        }
    }

    private static List<String> queryStrings(String jdbcUrl, PostgreSQLContainer<?> container, String sql) {
        try (Connection connection = DriverManager.getConnection(
                jdbcUrl, container.getUsername(), container.getPassword());
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            var values = new java.util.ArrayList<String>();
            while (result.next()) {
                values.add(result.getString(1));
            }
            return values;
        } catch (SQLException e) {
            throw new IllegalStateException("could not run database assertion", e);
        }
    }
}
