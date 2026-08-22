package rs.sud.eaukcija;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;

import rs.sud.eaukcija.model.Auction;
import rs.sud.eaukcija.repository.AuctionRepository;
import rs.sud.eaukcija.testsupport.PostgisTestContainer;

/**
 * Proves the real wiring: Flyway migrates an empty PostGIS database, Hibernate
 * then starts against it with {@code ddl-auto=validate}, and the application's
 * own repository round-trips through the migrated schema.
 *
 * <p>The asserted properties are declared in
 * {@code src/test/resources/application-test.properties} and read back out of
 * the {@link Environment}. Nothing here sets them inline; a test that injected
 * {@code validate} and then asserted {@code validate} would prove nothing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class PostgisSchemaIntegrationTest {

    // Explicit name avoids Spring Boot trying to infer a repository name from
    // the tag+digest form, which Testcontainers otherwise parses ambiguously.
    @ServiceConnection(name = "postgresql")
    static final PostgreSQLContainer<?> POSTGIS = PostgisTestContainer.shared();

    @Autowired
    private Environment environment;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private AuctionRepository auctionRepository;

    @Test
    void theContextStartsOnTheProductionPostgisImage() {
        assertThat(POSTGIS.getDockerImageName())
                .isEqualTo("postgis/postgis:18-3.6@sha256:db8c151a4e1f4686b1a985a3490cf96f9f8c8c2725f58a46ef7a57e52f167cc3");

        String postgisVersion = new JdbcTemplate(dataSource)
                .queryForObject("SELECT postgis_version()", String.class);
        assertThat(postgisVersion).isNotBlank();

        String serverVersion = new JdbcTemplate(dataSource)
                .queryForObject("SHOW server_version", String.class);
        assertThat(serverVersion).startsWith("18");
    }

    @Test
    void hibernateValidatesRatherThanGeneratingTheSchema() {
        // Read from the profile, not from this test. If application-test.properties
        // ever regressed to update/create-drop, the context would still start and
        // only this assertion would catch it.
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(environment.getProperty("spring.flyway.enabled")).isEqualTo("true");
    }

    @Test
    void flywayAppliedEveryMigrationToAnEmptyDatabase() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        List<String> applied = jdbc.queryForList(
                "SELECT script FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class);

        assertThat(applied)
                .contains("V1__enable_postgis.sql", "V2__baseline_auctions.sql",
                        "V3__auction_filter_indexes.sql")
                .allSatisfy(script -> assertThat(script).endsWith(".sql"));

        Integer failures = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE NOT success", Integer.class);
        assertThat(failures).isZero();
    }

    @Test
    void flywayOwnsTheAuctionFilterIndexes() {
        List<String> indexes = new JdbcTemplate(dataSource).queryForList("""
                SELECT indexname
                FROM pg_indexes
                WHERE schemaname = 'public' AND tablename = 'auctions'
                ORDER BY indexname
                """, String.class);

        assertThat(indexes).contains(
                "idx_auctions_municipality",
                "idx_auctions_place_name",
                "idx_auctions_category_name",
                "idx_auctions_status",
                "idx_auctions_starting_price");
    }

    @Test
    void theMigratedSchemaBacksTheJpaEntity() {
        Auction auction = new Auction();
        auction.setId(180466L);
        auction.setAuctionNumber("Н180466");
        auction.setStartDate(Instant.parse("2026-03-10T07:00:00Z"));
        auction.setEndDate(Instant.parse("2026-03-10T11:00:00Z"));
        auction.setStartingPrice(new BigDecimal("159600.00"));
        auction.setShortDescription("парц.бр.1572 К.О.Димитровград");
        auction.setCadastral("Димитровград");
        auction.setFirstSale(true);
        auction.setDetailsFetched(false);

        auctionRepository.save(auction);
        auctionRepository.flush();

        Auction reloaded = auctionRepository.findById(180466L).orElseThrow();
        assertThat(reloaded.getAuctionNumber()).isEqualTo("Н180466");
        assertThat(reloaded.getStartDate()).isEqualTo(Instant.parse("2026-03-10T07:00:00Z"));
        assertThat(reloaded.getStartingPrice()).isEqualByComparingTo(new BigDecimal("159600.00"));
        assertThat(reloaded.getCadastral()).isEqualTo("Димитровград");
        assertThat(reloaded.isFirstSale()).isTrue();

        auctionRepository.deleteById(180466L);
        auctionRepository.flush();
    }
}
