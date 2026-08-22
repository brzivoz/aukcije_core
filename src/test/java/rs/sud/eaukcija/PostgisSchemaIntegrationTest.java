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
                        "V3__auction_filter_indexes.sql", "V4__address_registry_snapshots.sql",
                        "V5__structured_ko_matches.sql", "V6__municipality_alias_match_evidence.sql",
                        "V7__spatial_resolution_model.sql")
                .allSatisfy(script -> assertThat(script).endsWith(".sql"));

        Integer failures = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE NOT success", Integer.class);
        assertThat(failures).isZero();
    }

    @Test
    void flywayOwnsTheAddressRegistrySnapshotAndLookupContract() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        assertThat(jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name LIKE 'address_registry_%'
                ORDER BY table_name
                """, String.class)).containsExactly(
                        "address_registry_active_snapshot",
                        "address_registry_centroids",
                        "address_registry_import_runs",
                        "address_registry_points",
                        "address_registry_snapshots");

        assertThat(jdbc.queryForList("""
                SELECT indexname FROM pg_indexes
                WHERE schemaname = 'public' AND tablename = 'address_registry_points'
                ORDER BY indexname
                """, String.class)).contains(
                        "idx_address_registry_ko_parcel",
                        "idx_address_registry_named_ko_parcel",
                        "idx_address_registry_exact_address",
                        "idx_address_registry_street",
                        "idx_address_registry_location");

        assertThat(jdbc.queryForObject("""
                SELECT srid FROM geometry_columns
                WHERE f_table_schema = 'public'
                  AND f_table_name = 'address_registry_points'
                  AND f_geometry_column = 'location'
                """, Integer.class)).isEqualTo(4326);
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
    void flywayOwnsTheStructuredKoMatchEvidenceAndReportContract() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        assertThat(jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name IN ('auction_structured_ko_matches', 'structured_ko_match_runs')
                ORDER BY table_name
                """, String.class)).containsExactly(
                        "auction_structured_ko_matches", "structured_ko_match_runs");
        assertThat(jdbc.queryForList("""
                SELECT indexname FROM pg_indexes
                WHERE schemaname = 'public' AND tablename = 'auction_structured_ko_matches'
                ORDER BY indexname
                """, String.class)).contains(
                        "idx_structured_ko_matches_status",
                        "idx_structured_ko_matches_ko_code",
                        "idx_structured_ko_matches_dictionary",
                        "idx_structured_ko_matches_candidates");
    }

    @Test
    void flywayOwnsTheSpatialResolutionAndViewportContract() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        assertThat(jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name IN (
                      'parcel_identities', 'property_references', 'spatial_resolution_geometries',
                      'location_resolution_cache_records', 'location_resolution_attempts',
                      'current_location_resolutions'
                  )
                ORDER BY table_name
                """, String.class)).containsExactly(
                        "current_location_resolutions",
                        "location_resolution_attempts",
                        "location_resolution_cache_records",
                        "parcel_identities",
                        "property_references",
                        "spatial_resolution_geometries");
        assertThat(jdbc.queryForObject("""
                SELECT srid FROM geometry_columns
                WHERE f_table_schema = 'public'
                  AND f_table_name = 'spatial_resolution_geometries'
                  AND f_geometry_column = 'canonical_geometry'
                """, Integer.class)).isEqualTo(4326);
        assertThat(jdbc.queryForList("""
                SELECT indexname FROM pg_indexes
                WHERE schemaname = 'public' AND tablename = 'spatial_resolution_geometries'
                ORDER BY indexname
                """, String.class)).contains("idx_spatial_resolution_geometries_canonical");
        assertThat(jdbc.queryForList("""
                SELECT indexname FROM pg_indexes
                WHERE schemaname = 'public'
                  AND tablename IN ('location_resolution_attempts', 'location_resolution_cache_records')
                ORDER BY indexname
                """, String.class)).contains(
                        "idx_location_resolution_attempts_geometry",
                        "idx_location_resolution_attempts_used_cache",
                        "idx_location_resolution_cache_records_geometry");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_trigger
                WHERE tgrelid = 'spatial_resolution_geometries'::regclass
                  AND tgname = 'trg_spatial_resolution_geometry_derive_canonical'
                  AND NOT tgisinternal
                """, Integer.class)).isOne();
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
