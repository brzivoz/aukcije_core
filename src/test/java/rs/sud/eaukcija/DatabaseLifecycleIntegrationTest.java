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
import org.springframework.jdbc.core.JdbcTemplate;
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

        assertThat(queryBoolean(jdbcUrl, container,
                "SELECT to_regclass('public.sync_run_child_results') IS NOT NULL")).isTrue();
        assertThat(queryStrings(jdbcUrl, container, """
                SELECT column_name
                  FROM information_schema.columns
                 WHERE table_schema = 'public'
                   AND table_name = 'sync_run_child_results'
                 ORDER BY ordinal_position
                """)).containsExactly(
                        "run_id", "parent_root_category_id", "child_category_id",
                        "source_total_count", "rows_observed", "unique_ids", "duplicate_ids",
                        "pages_expected", "pages_completed", "total_consistent",
                        "subset_of_parent_root", "complete");

        assertThat(queryStrings(jdbcUrl, container,
                "SELECT script FROM flyway_schema_history WHERE success ORDER BY installed_rank"))
                .containsExactly("V1__enable_postgis.sql", "V2__baseline_auctions.sql",
                        "V3__auction_filter_indexes.sql", "V4__address_registry_snapshots.sql",
                        "V5__structured_ko_matches.sql", "V6__municipality_alias_match_evidence.sql",
                        "V7__spatial_resolution_model.sql", "V8__coarse_location_resolution_runs.sql",
                        "V9__coarse_location_upstream_provenance.sql",
                        "V10__eaukcija_sync_runs.sql",
                        "V11__eaukcija_detail_quarantine.sql",
                        "V12__eaukcija_listing_quarantine.sql",
                        "V13__deterministic_enrichment_reprocessing.sql",
                        "V14__pipeline_observability.sql",
                        "V15__durable_refresh_workflow.sql",
                        "V16__immutable_eaukcija_source_snapshots.sql");
    }

    @Test
    void detailQuarantineMigrationUpgradesLegacyTerminalStagesAndCascadeSafely() {
        PostgreSQLContainer<?> container = PostgisTestContainer.shared();
        String jdbcUrl = PostgisTestContainer.createEmptyDatabase();

        Flyway.configure()
                .dataSource(jdbcUrl, container.getUsername(), container.getPassword())
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("10"))
                .load()
                .migrate();

        execute(jdbcUrl, container, """
                INSERT INTO sync_runs (
                    id, idempotency_key_sha256, trigger_kind, status, stage,
                    started_at, heartbeat_at, configured_roots, page_size,
                    details_required, details_attempted, details_failed,
                    error_count, unresolved_error_count
                ) VALUES (
                    '00000000-0000-0000-0000-000000000011',
                    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                    'MANUAL', 'RUNNING', 'DETAILS',
                    CURRENT_TIMESTAMP - INTERVAL '2 minutes', CURRENT_TIMESTAMP,
                    '[7]'::jsonb, 3000, 1, 1, 1, 1, 1
                );

                INSERT INTO sync_run_errors (
                    run_id, ordinal, occurred_at, stage, auction_id,
                    error_code, retryable, attempt_number
                ) VALUES (
                    '00000000-0000-0000-0000-000000000011', 1,
                    CURRENT_TIMESTAMP, 'DETAILS', 17,
                    'INVALID_DATA', FALSE, 1
                );

                UPDATE sync_runs
                   SET status = 'PARTIAL', stage = 'COMPLETED',
                       finished_at = CURRENT_TIMESTAMP
                 WHERE id = '00000000-0000-0000-0000-000000000011';

                INSERT INTO sync_runs (
                    id, idempotency_key_sha256, trigger_kind, status, stage,
                    started_at, heartbeat_at, configured_roots, page_size
                ) VALUES (
                    '00000000-0000-0000-0000-000000000012',
                    'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                    'MANUAL', 'RUNNING', 'CLAIMED',
                    CURRENT_TIMESTAMP - INTERVAL '2 minutes', CURRENT_TIMESTAMP,
                    '[7]'::jsonb, 3000
                 );

                UPDATE sync_runs
                   SET status = 'FAILED', stage = 'COMPLETED',
                       finished_at = CURRENT_TIMESTAMP
                 WHERE id = '00000000-0000-0000-0000-000000000012';
                """);

        Flyway.configure()
                .dataSource(jdbcUrl, container.getUsername(), container.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        assertThat(queryStrings(jdbcUrl, container, """
                SELECT stage
                  FROM sync_runs
                 ORDER BY id
                """)).containsExactly("DETAILS", "CLAIMED");
        assertThat(queryBoolean(jdbcUrl, container, """
                SELECT NOT resolved
                  FROM sync_run_errors
                 WHERE run_id = '00000000-0000-0000-0000-000000000011'
                """)).isTrue();
        assertThat(queryBoolean(jdbcUrl, container, """
                SELECT to_regclass('public.sync_run_detail_quarantines') IS NOT NULL
                """)).isTrue();
        assertThat(queryStrings(jdbcUrl, container, """
                SELECT delete_rule
                  FROM information_schema.referential_constraints
                 WHERE constraint_name = 'sync_run_auction_observations_auction_id_fkey'
                """)).containsExactly("NO ACTION");
        assertThat(queryStrings(jdbcUrl, container, """
                SELECT script
                  FROM flyway_schema_history
                 WHERE version = '11' AND success
                """)).containsExactly("V11__eaukcija_detail_quarantine.sql");
    }

    @Test
    void listingQuarantineMigrationUpgradesTheV11HeadWithoutRewritingPriorEvidence() {
        PostgreSQLContainer<?> container = PostgisTestContainer.shared();
        String jdbcUrl = PostgisTestContainer.createEmptyDatabase();

        Flyway.configure()
                .dataSource(jdbcUrl, container.getUsername(), container.getPassword())
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("11"))
                .load()
                .migrate();

        execute(jdbcUrl, container, """
                INSERT INTO sync_runs (
                    id, idempotency_key_sha256, trigger_kind, status, stage,
                    started_at, heartbeat_at, configured_roots, page_size
                ) VALUES (
                    '00000000-0000-0000-0000-000000000013',
                    'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc',
                    'MANUAL', 'RUNNING', 'CLAIMED',
                    CURRENT_TIMESTAMP - INTERVAL '2 minutes', CURRENT_TIMESTAMP,
                    '[7]'::jsonb, 3000
                );

                INSERT INTO sync_run_detail_quarantines (
                    run_id, auction_id, listing_fingerprint, error_code
                ) VALUES (
                    '00000000-0000-0000-0000-000000000013', 13,
                    'dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd',
                    'INVALID_DATA'
                )
                """);

        Flyway.configure()
                .dataSource(jdbcUrl, container.getUsername(), container.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        assertThat(queryBoolean(jdbcUrl, container, """
                SELECT listing_rows_quarantined = 0
                  FROM sync_runs
                 WHERE id = '00000000-0000-0000-0000-000000000013'
                """)).isTrue();
        assertThat(queryBoolean(jdbcUrl, container, """
                SELECT to_regclass('public.sync_run_listing_quarantines') IS NOT NULL
                """)).isTrue();
        assertThat(queryStrings(jdbcUrl, container, """
                SELECT auction_id || ':' || listing_fingerprint || ':' || error_code
                  FROM sync_run_detail_quarantines
                 WHERE run_id = '00000000-0000-0000-0000-000000000013'
                """)).containsExactly(
                        "13:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd:INVALID_DATA");
        assertThat(queryStrings(jdbcUrl, container, """
                SELECT script
                  FROM flyway_schema_history
                 WHERE version = '12' AND success
                """)).containsExactly("V12__eaukcija_listing_quarantine.sql");
    }

    @Test
    void spatialSchemaUpgradesThePreviousV6HeadAndHibernateStillValidates() {
        PostgreSQLContainer<?> container = PostgisTestContainer.shared();
        String jdbcUrl = PostgisTestContainer.createEmptyDatabase();

        Flyway.configure()
                .dataSource(jdbcUrl, container.getUsername(), container.getPassword())
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("6"))
                .load()
                .migrate();
        execute(jdbcUrl, container, """
                INSERT INTO auctions (id, auction_number, first_sale, details_fetched)
                VALUES (2007, 'N2007', false, true)
                """);

        try (ConfigurableApplicationContext context = PostgisApplication.start(
                jdbcUrl, container.getUsername(), container.getPassword())) {
            assertThat(context.isRunning()).isTrue();
            assertThat(context.getBean(AuctionRepository.class).findById(2007L)).isPresent();
        }

        assertThat(queryBoolean(jdbcUrl, container,
                "SELECT to_regclass('public.spatial_resolution_geometries') IS NOT NULL")).isTrue();
        assertThat(queryBoolean(jdbcUrl, container, """
                SELECT EXISTS (
                    SELECT 1 FROM pg_indexes
                    WHERE schemaname = 'public'
                      AND indexname = 'idx_spatial_resolution_geometries_canonical'
                )
                """)).isTrue();
        assertThat(queryStrings(jdbcUrl, container, """
                SELECT script FROM flyway_schema_history WHERE version = '7' AND success
                """)).containsExactly("V7__spatial_resolution_model.sql");
    }

    @Test
    void coarseProvenanceMigrationPreservesHistoricalV8Reports() {
        PostgreSQLContainer<?> container = PostgisTestContainer.shared();
        String jdbcUrl = PostgisTestContainer.createEmptyDatabase();
        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl, container.getUsername(), container.getPassword())
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("8"))
                .load();
        flyway.migrate();
        execute(jdbcUrl, container, """
                INSERT INTO coarse_location_resolution_runs (
                    id, started_at, finished_at, resolver_version,
                    extract_version, extract_source_sha256,
                    population_count, processed_count, unchanged_count,
                    cadastral_municipality_count, settlement_count, municipality_count, none_count,
                    municipality_alias_ko_count, structured_ko_status_counts, rationale_counts
                ) VALUES (
                    '00000000-0000-0000-0000-000000000038', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                    'coarse-location-v1', 'historical-v8',
                    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                    0, 0, 0, 0, 0, 0, 0, 0, '{}'::jsonb, '{}'::jsonb
                )
                """);

        Flyway.configure()
                .dataSource(jdbcUrl, container.getUsername(), container.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        assertThat(queryBoolean(jdbcUrl, container, """
                SELECT dictionary_version IS NULL
                   AND dictionary_source_sha256 IS NULL
                   AND normalizer_version IS NULL
                   AND alias_dataset_version IS NULL
                   AND alias_sha256 IS NULL
                   AND municipality_alias_dataset_version IS NULL
                   AND municipality_alias_sha256 IS NULL
                  FROM coarse_location_resolution_runs
                 WHERE id = '00000000-0000-0000-0000-000000000038'
                """)).isTrue();
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
        String sourceCanonicalEwkb;

        try (ConfigurableApplicationContext source = PostgisApplication.start(
                sourceUrl, container.getUsername(), container.getPassword())) {
            source.getBean(AuctionRepository.class).saveAndFlush(auction(1516L, "Н1516"));
            JdbcTemplate jdbc = new JdbcTemplate(source.getBean(javax.sql.DataSource.class));
            jdbc.update("""
                    INSERT INTO spatial_resolution_geometries (
                        id, source_geometry, source_crs_authority, source_crs_code,
                        original_geometry_valid, make_valid_applied
                    ) VALUES (
                        '00000000-0000-0000-0000-000000003909',
                        ST_Transform(ST_SetSRID(ST_MakePoint(20.457273, 44.787197), 4326), 3909),
                        'EPSG', 3909, true, false
                    )
                    """);
            sourceCanonicalEwkb = jdbc.queryForObject("""
                    SELECT encode(ST_AsEWKB(canonical_geometry), 'hex')
                      FROM spatial_resolution_geometries
                     WHERE id = '00000000-0000-0000-0000-000000003909'
                    """, String.class);
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
        assertThat(queryStrings(restoredUrl, container, """
                SELECT encode(ST_AsEWKB(canonical_geometry), 'hex')
                  FROM spatial_resolution_geometries
                 WHERE id = '00000000-0000-0000-0000-000000003909'
                """)).containsExactly(sourceCanonicalEwkb);
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

    private static void execute(String jdbcUrl, PostgreSQLContainer<?> container, String sql) {
        try (Connection connection = DriverManager.getConnection(
                jdbcUrl, container.getUsername(), container.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("could not execute database fixture", e);
        }
    }
}
