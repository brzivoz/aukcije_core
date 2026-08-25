package rs.sud.eaukcija.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import rs.sud.eaukcija.operations.PipelineStatusRepository.PersistedEvidence;
import rs.sud.eaukcija.testsupport.PostgisApplication;
import rs.sud.eaukcija.testsupport.PostgisTestContainer;

class PipelineStatusRepositoryIntegrationTest {

    @Test
    void retainedAttemptSuccessDeltaSnapshotAndImportEvidenceSurvivesApplicationRestart() {
        DatabaseFixture database = migratedDatabase();
        JdbcTemplate jdbc = database.jdbc();
        seedEvidence(jdbc);

        PersistedEvidence firstRead;
        try (ConfigurableApplicationContext first = start(database)) {
            firstRead = first.getBean(PipelineStatusRepository.class).read();
        }
        PersistedEvidence reread;
        try (ConfigurableApplicationContext restarted = start(database)) {
            reread = restarted.getBean(PipelineStatusRepository.class).read();
        }

        assertThat(firstRead).isEqualTo(reread);
        assertThat(reread.database().schemaVersion()).isEqualTo("14");
        assertThat(reread.database().expectedSchemaVersion()).isEqualTo("14");
        assertThat(reread.database().migrationsCurrent()).isTrue();
        assertThat(reread.lastSyncAttempt().status()).isEqualTo("PARTIAL");
        assertThat(reread.lastSyncAttempt().sourceDelta()).isNull();
        assertThat(reread.lastSyncAttempt().errorClasses()).isEqualTo(Map.of("TIMEOUT", 1L));
        assertThat(reread.lastSuccessfulSync().status()).isEqualTo("SUCCEEDED");
        assertThat(reread.lastSuccessfulSync().sourceCount()).isEqualTo(2);
        assertThat(reread.lastSuccessfulSync().sourceDelta()).isEqualTo(1);
        assertThat(reread.lastSuccessfulSync().rawSnapshotChanges())
                .isEqualTo(new PipelineStatus.SnapshotChanges(0, 1, 0));
        assertThat(reread.lastEnrichmentAttempt().parserVersion()).isEqualTo("parser-v2");
        assertThat(reread.lastImportAttempt().outcome()).isEqualTo("FAILED");
        assertThat(reread.lastImportAttempt().errorCode()).isEqualTo("SCHEMA_MISMATCH");
        assertThat(reread.lastSuccessfulImport().action()).isEqualTo("IMPORT");
        assertThat(reread.lastSuccessfulImport().durationMillis()).isEqualTo(61_000);
        assertThat(reread.lastRetentionJob().outcome()).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("""
                SELECT retention_millis FROM address_registry_import_runs
                 WHERE id = '55555555-5555-4555-8555-555555555555'
                """, Long.class)).isNull();
    }

    @Test
    void flywayPendingMigrationsDefineCurrentnessWithoutAHardcodedVersion() {
        DatabaseFixture database = migratedDatabase();
        Flyway flywayWithPendingTestMigration = Flyway.configure()
                .dataSource(database.jdbc().getDataSource())
                .locations("classpath:db/migration", "classpath:db/broken")
                .load();

        PersistedEvidence evidence = new PipelineStatusRepository(
                database.jdbc(), flywayWithPendingTestMigration).read();

        assertThat(evidence.database().schemaVersion()).isEqualTo("14");
        assertThat(evidence.database().expectedSchemaVersion()).isEqualTo("900");
        assertThat(evidence.database().migrationsCurrent()).isFalse();
    }

    @Test
    void successfulLatestSyncReusesOneMetricIncludingSnapshotChanges() {
        DatabaseFixture database = migratedDatabase();
        JdbcTemplate jdbc = database.jdbc();
        seedSyncPrerequisites(jdbc);
        successfulSync(jdbc,
                "11111111-1111-4111-8111-111111111111", "1", "2026-08-25T08:00:00Z",
                1, "b", "c");

        PersistedEvidence evidence = new PipelineStatusRepository(jdbc, database.flyway()).read();

        assertThat(evidence.lastSyncAttempt()).isSameAs(evidence.lastSuccessfulSync());
        assertThat(evidence.lastSuccessfulSync().rawSnapshotChanges()).isNotNull();
    }

    @Test
    void applicationReadyRecoveryFinalizesAnImportLeftRunningBeforeRestart() {
        DatabaseFixture database = migratedDatabase();
        UUID runId = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        database.jdbc().update("""
                INSERT INTO address_registry_import_runs (
                    id, action, outcome, started_at, source_date, canonical_url
                ) VALUES (?, 'IMPORT', 'RUNNING', CURRENT_TIMESTAMP - INTERVAL '1 minute',
                          '2026-08-25', 'https://example.invalid/address.gpkg')
                """, runId);

        try (ConfigurableApplicationContext ignored = start(database)) {
            assertThat(database.jdbc().queryForMap("""
                    SELECT outcome, error_code, finished_at IS NOT NULL AS finished
                      FROM address_registry_import_runs WHERE id = ?
                    """, runId))
                    .containsEntry("outcome", "FAILED")
                    .containsEntry("error_code", "IMPORT_PROCESS_RESTARTED")
                    .containsEntry("finished", true);
        }
    }

    @Test
    void terminalImportAndCompletedResolverEvidenceCannotBeRewrittenOrDeleted() {
        JdbcTemplate jdbc = migratedDatabase().jdbc();
        jdbc.update("""
                INSERT INTO address_registry_import_runs (
                    id, action, outcome, started_at, finished_at, error_code
                ) VALUES (
                    'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'IMPORT', 'FAILED',
                    CURRENT_TIMESTAMP - INTERVAL '1 minute', CURRENT_TIMESTAMP, 'SCHEMA_MISMATCH'
                )
                """);
        jdbc.update("""
                INSERT INTO structured_ko_match_runs (
                    id, started_at, finished_at, dictionary_version,
                    dictionary_source_sha256, normalizer_version,
                    alias_dataset_version, alias_sha256,
                    population_count, processed_count, unchanged_count,
                    matched_count, ambiguous_count, not_found_count, invalid_count, method_counts,
                    municipality_alias_dataset_version, municipality_alias_sha256
                ) VALUES (
                    'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                    'dictionary-v1', repeat('b', 64), 'normalizer-v1', 'aliases-v1', repeat('c', 64),
                    0, 0, 0, 0, 0, 0, 0, '{}'::jsonb, 'municipality-v1', repeat('d', 64)
                )
                """);
        jdbc.update("""
                INSERT INTO coarse_location_resolution_runs (
                    id, started_at, finished_at, resolver_version,
                    extract_version, extract_source_sha256,
                    population_count, processed_count, unchanged_count,
                    cadastral_municipality_count, settlement_count, municipality_count, none_count,
                    municipality_alias_ko_count, structured_ko_status_counts, rationale_counts
                ) VALUES (
                    'cccccccc-cccc-4ccc-8ccc-cccccccccccc', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                    'resolver-v1', 'extract-v1', repeat('e', 64),
                    0, 0, 0, 0, 0, 0, 0, 0, '{}'::jsonb, '{}'::jsonb
                )
                """);

        assertThatThrownBy(() -> jdbc.update("""
                UPDATE address_registry_import_runs SET error_code = 'OTHER'
                 WHERE id = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
                """)).hasMessageContaining("terminal address registry import run evidence is immutable");
        assertThatThrownBy(() -> jdbc.update("""
                DELETE FROM structured_ko_match_runs
                 WHERE id = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'
                """)).hasMessageContaining("completed pipeline run evidence is immutable");
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE coarse_location_resolution_runs SET resolver_version = 'rewritten'
                 WHERE id = 'cccccccc-cccc-4ccc-8ccc-cccccccccccc'
                """)).hasMessageContaining("completed pipeline run evidence is immutable");
    }

    private static DatabaseFixture migratedDatabase() {
        PostgreSQLContainer<?> container = PostgisTestContainer.shared();
        String jdbcUrl = PostgisTestContainer.createEmptyDatabase();
        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl, container.getUsername(), container.getPassword())
                .locations("classpath:db/migration")
                .load();
        flyway.migrate();
        return new DatabaseFixture(
                new JdbcTemplate(new DriverManagerDataSource(
                        jdbcUrl, container.getUsername(), container.getPassword())),
                flyway,
                jdbcUrl,
                container.getUsername(),
                container.getPassword());
    }

    private static ConfigurableApplicationContext start(DatabaseFixture database) {
        return PostgisApplication.start(
                database.jdbcUrl(), database.username(), database.password(),
                "--eaukcija.sync.enabled=false",
                "--eaukcija.enrichment.enabled=false");
    }

    private static void seedEvidence(JdbcTemplate jdbc) {
        seedSyncPrerequisites(jdbc);
        successfulSync(jdbc,
                "11111111-1111-4111-8111-111111111111", "1", "2026-08-25T08:00:00Z",
                1, "b", "c");
        successfulSync(jdbc,
                "22222222-2222-4222-8222-222222222222", "2", "2026-08-25T09:00:00Z",
                2, "d", "e");
        jdbc.update("""
                INSERT INTO sync_runs (
                    id, idempotency_key_sha256, trigger_kind, status, stage,
                    started_at, heartbeat_at, configured_roots, page_size
                ) VALUES (
                    '33333333-3333-4333-8333-333333333333', repeat('3', 64), 'SCHEDULED',
                    'RUNNING', 'LISTINGS', '2026-08-25T10:00:00Z', '2026-08-25T10:00:30Z',
                    '[7]'::jsonb, 3000
                )
                """);
        jdbc.update("""
                INSERT INTO sync_run_errors (
                    run_id, ordinal, occurred_at, stage, error_code,
                    retryable, attempt_number, resolved
                ) VALUES (
                    '33333333-3333-4333-8333-333333333333', 1,
                    '2026-08-25T10:00:30Z', 'LISTINGS', 'TIMEOUT', TRUE, 3, FALSE
                )
                """);
        jdbc.update("""
                UPDATE sync_runs
                   SET status = 'PARTIAL', finished_at = '2026-08-25T10:01:00Z',
                       error_count = 1, unresolved_error_count = 1, retry_count = 2
                 WHERE id = '33333333-3333-4333-8333-333333333333'
                """);
        jdbc.update("""
                INSERT INTO enrichment_runs (
                    id, idempotency_key_sha256, trigger_kind, status,
                    started_at, heartbeat_at, finished_at,
                    parser_version, resolver_version, dataset_version, max_items
                ) VALUES (
                    '44444444-4444-4444-8444-444444444444', repeat('4', 64), 'SCHEDULED',
                    'SUCCEEDED', '2026-08-25T09:05:00Z', '2026-08-25T09:06:00Z',
                    '2026-08-25T09:06:00Z', 'parser-v2', 'resolver-v2', 'dataset-v2', 1000
                )
                """);
        jdbc.update("""
                INSERT INTO address_registry_import_runs (
                    id, action, outcome, started_at, finished_at, total_millis
                ) VALUES (
                    '55555555-5555-4555-8555-555555555555', 'IMPORT', 'SUCCEEDED',
                    '2026-08-25T06:00:00Z', '2026-08-25T06:01:00Z', 60000
                )
                """);
        jdbc.update("""
                INSERT INTO address_registry_import_runs (
                    id, action, outcome, started_at, finished_at, total_millis
                ) VALUES (
                    '77777777-7777-4777-8777-777777777777', 'ROLLBACK', 'SUCCEEDED',
                    '2026-08-25T10:30:00Z', '2026-08-25T10:30:01Z', 1000
                )
                """);
        jdbc.update("""
                INSERT INTO address_registry_import_runs (
                    id, action, outcome, started_at, finished_at, total_millis, error_code
                ) VALUES (
                    '66666666-6666-4666-8666-666666666666', 'IMPORT', 'FAILED',
                    '2026-08-25T11:00:00Z', '2026-08-25T11:00:05Z', 5000, 'SCHEMA_MISMATCH'
                )
                """);
        jdbc.update("""
                INSERT INTO address_registry_retention_jobs (
                    import_run_id, started_at, finished_at, outcome,
                    retained_snapshot_count, duration_millis
                ) VALUES (
                    '55555555-5555-4555-8555-555555555555',
                    '2026-08-25T06:01:00Z', '2026-08-25T06:01:01Z',
                    'SUCCEEDED', 2, 1000
                )
                """);
    }

    private static void seedSyncPrerequisites(JdbcTemplate jdbc) {
        jdbc.update("""
                INSERT INTO eaukcija_taxonomies (
                    tree_sha256, normalizer_version, canonical_tree, first_observed_at
                ) VALUES (repeat('a', 64), 'taxonomy-v1', '[]'::jsonb, '2026-08-25T07:00:00Z')
                """);
        jdbc.update("""
                INSERT INTO auctions (id, auction_number, first_sale, details_fetched)
                VALUES (30, 'A-30', FALSE, TRUE)
                """);
    }

    private record DatabaseFixture(
            JdbcTemplate jdbc,
            Flyway flyway,
            String jdbcUrl,
            String username,
            String password) {
    }

    private static void successfulSync(
            JdbcTemplate jdbc,
            String runId,
            String keyDigit,
            String startedAt,
            long sourceCount,
            String listingHashDigit,
            String snapshotHashDigit) {
        jdbc.update("""
                INSERT INTO sync_runs (
                    id, idempotency_key_sha256, trigger_kind, status, stage,
                    started_at, heartbeat_at, configured_roots, page_size,
                    category_tree_sha256, category_tree_observed_at,
                    unique_auction_count
                ) VALUES (?::uuid, repeat(?, 64), 'SCHEDULED', 'RUNNING', 'PROMOTING',
                          ?::timestamptz, ?::timestamptz, '[7]'::jsonb, 3000,
                          repeat('a', 64), ?::timestamptz, ?)
                """, runId, keyDigit, startedAt, startedAt, startedAt, sourceCount);
        jdbc.update("""
                INSERT INTO sync_run_auction_observations (
                    run_id, auction_id, listing_fingerprint, detail_refreshed,
                    enrichment_eligible, enrichment_reason
                ) VALUES (?::uuid, 30, repeat(?, 64), FALSE, FALSE, 'NONE')
                """, runId, listingHashDigit);
        jdbc.update("""
                UPDATE sync_runs
                   SET status = 'SUCCEEDED', stage = 'COMPLETED',
                       finished_at = started_at + INTERVAL '5 minutes',
                       heartbeat_at = started_at + INTERVAL '5 minutes'
                 WHERE id = ?::uuid
                """, runId);
        jdbc.update("""
                INSERT INTO auction_enrichment_input_snapshots (
                    auction_id, snapshot_sha256, canonical_input
                ) VALUES (30, repeat(?, 64), '{}'::jsonb)
                """, snapshotHashDigit);
        jdbc.update("""
                INSERT INTO auction_enrichment_snapshot_observations (
                    source_sync_run_id, auction_id, snapshot_sha256, observed_at
                ) VALUES (?::uuid, 30, repeat(?, 64), ?::timestamptz + INTERVAL '5 minutes')
                """, runId, snapshotHashDigit, startedAt);
    }
}
