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
                        "V7__spatial_resolution_model.sql", "V8__coarse_location_resolution_runs.sql",
                        "V9__coarse_location_upstream_provenance.sql",
                        "V10__eaukcija_sync_runs.sql",
                        "V11__eaukcija_detail_quarantine.sql",
                        "V12__eaukcija_listing_quarantine.sql",
                        "V13__deterministic_enrichment_reprocessing.sql")
                .allSatisfy(script -> assertThat(script).endsWith(".sql"));

        Integer failures = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE NOT success", Integer.class);
        assertThat(failures).isZero();
    }

    @Test
    void flywayOwnsTheDurableSyncLedgerAndSuccessPublicationContract() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        assertThat(jdbc.queryForList("""
                SELECT table_name
                  FROM information_schema.tables
                 WHERE table_schema = 'public'
                   AND table_name IN (
                       'eaukcija_taxonomies', 'sync_runs', 'sync_run_root_results',
                       'sync_run_child_results',
                       'sync_run_detail_quarantines',
                       'sync_run_listing_quarantines', 'sync_run_errors',
                       'auction_source_category_memberships',
                       'sync_run_auction_observations', 'sync_enrichment_queue'
                   )
                 ORDER BY table_name
                """, String.class)).containsExactly(
                        "auction_source_category_memberships",
                        "eaukcija_taxonomies",
                        "sync_enrichment_queue",
                        "sync_run_auction_observations",
                        "sync_run_child_results",
                        "sync_run_detail_quarantines",
                        "sync_run_errors",
                        "sync_run_listing_quarantines",
                        "sync_run_root_results",
                        "sync_runs");
        assertThat(jdbc.queryForList("""
                SELECT column_name
                  FROM information_schema.columns
                 WHERE table_schema = 'public' AND table_name = 'sync_run_errors'
                 ORDER BY ordinal_position
                """, String.class)).containsExactly(
                "run_id", "ordinal", "occurred_at", "stage",
                        "root_category_id", "child_category_id", "page_number", "auction_id", "http_status",
                        "error_code", "retryable", "attempt_number", "resolved");
        assertThat(jdbc.queryForList("""
                SELECT column_name
                  FROM information_schema.columns
                 WHERE table_schema = 'public'
                   AND table_name = 'sync_run_detail_quarantines'
                 ORDER BY ordinal_position
                """, String.class)).containsExactly(
                        "run_id", "auction_id", "listing_fingerprint",
                        "error_code", "occurred_at");
        assertThat(jdbc.queryForList("""
                SELECT column_name
                  FROM information_schema.columns
                 WHERE table_schema = 'public'
                   AND table_name = 'sync_run_listing_quarantines'
                 ORDER BY ordinal_position
                """, String.class)).containsExactly(
                        "run_id", "auction_id", "source_row_sha256", "error_code",
                        "root_category_id", "child_category_id", "page_number", "occurred_at");
        assertThat(jdbc.queryForList("""
                SELECT column_name
                  FROM information_schema.columns
                 WHERE table_schema = 'public' AND table_name = 'sync_runs'
                """, String.class)).contains("details_quarantined", "listing_rows_quarantined");
        assertThat(jdbc.queryForList("""
                SELECT column_name
                  FROM information_schema.columns
                 WHERE table_schema = 'public' AND table_name = 'sync_run_child_results'
                 ORDER BY ordinal_position
                """, String.class)).containsExactly(
                        "run_id", "parent_root_category_id", "child_category_id",
                        "source_total_count", "rows_observed", "unique_ids", "duplicate_ids",
                        "pages_expected", "pages_completed", "total_consistent",
                        "subset_of_parent_root", "complete");
        assertThat(jdbc.queryForList("""
                SELECT column_name
                  FROM information_schema.columns
                 WHERE table_schema = 'public' AND table_name = 'auctions'
                """, String.class)).contains(
                        "listing_fingerprint", "details_fetched_at", "source_detail_category_id",
                        "sale_scope", "normalized_property_kind", "taxonomy_sha256",
                        "last_successful_sync_run_id", "absence_count", "last_seen_at");
        assertThat(jdbc.queryForList("""
                SELECT indexname
                  FROM pg_indexes
                 WHERE schemaname = 'public'
                   AND tablename IN (
                       'sync_runs', 'sync_enrichment_queue',
                       'sync_run_detail_quarantines',
                       'sync_run_listing_quarantines'
                   )
                """, String.class)).contains(
                        "uq_sync_runs_single_running",
                        "idx_sync_enrichment_queue_pending",
                        "idx_sync_detail_quarantines_auction",
                        "idx_sync_listing_quarantines_auction",
                        "idx_sync_listing_quarantines_source_location");
        assertThat(jdbc.queryForObject("""
                SELECT delete_rule = 'NO ACTION'
                  FROM information_schema.referential_constraints
                 WHERE constraint_name = 'sync_run_auction_observations_auction_id_fkey'
                """, Boolean.class)).isTrue();
    }

    @Test
    void flywayOwnsTheDeterministicEnrichmentStateAndEvidenceContract() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        assertThat(jdbc.queryForList("""
                SELECT table_name
                  FROM information_schema.tables
                 WHERE table_schema = 'public'
                   AND table_name IN (
                       'auction_enrichment_input_snapshots',
                       'auction_enrichment_snapshot_observations',
                       'enrichment_control', 'enrichment_runs',
                       'enrichment_state', 'enrichment_run_items'
                   )
                 ORDER BY table_name
                """, String.class)).containsExactly(
                        "auction_enrichment_input_snapshots",
                        "auction_enrichment_snapshot_observations",
                        "enrichment_control",
                        "enrichment_run_items",
                        "enrichment_runs",
                        "enrichment_state");
        assertThat(jdbc.queryForList("""
                SELECT column_name
                  FROM information_schema.columns
                 WHERE table_schema = 'public' AND table_name = 'enrichment_state'
                 ORDER BY ordinal_position
                """, String.class)).containsExactly(
                        "auction_id", "source_sync_run_id", "snapshot_sha256",
                        "parser_version", "resolver_version", "dataset_version",
                        "dependency_sha256", "work_key_sha256", "status", "attempt_count",
                        "pending_since", "last_attempt_at", "completed_at",
                        "last_enrichment_run_id", "last_stage", "output_sha256",
                        "error_class", "error_message");
        assertThat(jdbc.queryForList("""
                SELECT indexname
                  FROM pg_indexes
                 WHERE schemaname = 'public'
                   AND tablename IN ('enrichment_runs', 'enrichment_state')
                """, String.class)).contains(
                        "uq_enrichment_runs_single_running",
                        "idx_enrichment_runs_started",
                        "idx_enrichment_state_status",
                        "idx_enrichment_state_versions");
        assertThat(jdbc.queryForList("""
                SELECT tgname
                  FROM pg_trigger
                 WHERE tgrelid IN (
                       'auction_enrichment_input_snapshots'::regclass,
                       'auction_enrichment_snapshot_observations'::regclass,
                       'enrichment_runs'::regclass,
                       'enrichment_run_items'::regclass
                   )
                   AND NOT tgisinternal
                 ORDER BY tgname
                """, String.class)).containsExactly(
                        "trg_enrichment_input_snapshots_immutable",
                        "trg_enrichment_run_items_terminal_immutable",
                        "trg_enrichment_runs_terminal_immutable",
                        "trg_enrichment_snapshot_observations_immutable",
                        "trg_enrichment_snapshot_success_only");
        assertThat(jdbc.queryForObject("""
                SELECT paused FROM enrichment_control WHERE singleton
                """, Boolean.class)).isFalse();
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
    void flywayOwnsTheCoarseLocationPopulationReport() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                 WHERE table_schema = 'public' AND table_name = 'coarse_location_resolution_runs'
                """, Integer.class)).isOne();
        assertThat(jdbc.queryForList("""
                SELECT indexname FROM pg_indexes
                 WHERE schemaname = 'public' AND tablename = 'coarse_location_resolution_runs'
                """, String.class)).contains("idx_coarse_location_resolution_runs_finished");
        assertThat(jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                 WHERE table_schema = 'public'
                   AND table_name = 'coarse_location_resolution_runs'
                """, String.class)).contains(
                        "dictionary_version", "dictionary_source_sha256", "normalizer_version",
                        "alias_dataset_version", "alias_sha256",
                        "municipality_alias_dataset_version", "municipality_alias_sha256");
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
