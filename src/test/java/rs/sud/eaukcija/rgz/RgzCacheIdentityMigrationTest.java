package rs.sud.eaukcija.rgz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import rs.sud.eaukcija.enrichment.EnrichmentHashing;
import rs.sud.eaukcija.testsupport.PostgisTestContainer;

class RgzCacheIdentityMigrationTest {

    @Test
    void upgradeBindsTheFirstLegacyResultWithoutDeletingDuplicateProvenance() {
        var container = PostgisTestContainer.shared();
        var dataSource = new DriverManagerDataSource(PostgisTestContainer.createEmptyDatabase(),
                container.getUsername(), container.getPassword());
        Flyway.configure().dataSource(dataSource).target("20").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String identity = fingerprint("dataset-v1");
        String nextIdentity = fingerprint("dataset-v2");
        UUID first = UUID.randomUUID();
        UUID later = UUID.randomUUID();
        UUID next = UUID.randomUUID();
        seed(jdbc, first, identity, "dataset-v1", "rgz-parcel-v1", "a", "NOT_FOUND", 1);
        seed(jdbc, later, identity, "dataset-v1", "rgz-parcel-v2", "b", "INVALID", 2);
        seed(jdbc, next, nextIdentity, "dataset-v2", "rgz-parcel-v2", "b", "NOT_FOUND", 3);
        String before = records(jdbc);

        Flyway flyway = Flyway.configure().dataSource(dataSource).load();
        flyway.migrate();
        flyway.validate();

        assertThat(records(jdbc)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM rgz_parcel_cache_keys", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT cache_record_id FROM rgz_parcel_cache_keys WHERE input_fingerprint = ?
                """, UUID.class, identity)).isEqualTo(first);
        assertThat(jdbc.queryForObject("""
                SELECT cache_record_id FROM rgz_parcel_cache_keys WHERE input_fingerprint = ?
                """, UUID.class, nextIdentity)).isEqualTo(next);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO rgz_parcel_cache_keys (input_fingerprint, cache_record_id) VALUES (?, ?)
                """, identity, later)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO rgz_parcel_cache_keys (input_fingerprint, cache_record_id) VALUES (?, ?)
                """, "f".repeat(64), first)).isInstanceOf(DataIntegrityViolationException.class);
    }

    private static String fingerprint(String dataset) {
        return EnrichmentHashing.sha256("dkp:dkp_parcels_weekly_only_utm", dataset, "743968", "4577/337");
    }

    private static void seed(JdbcTemplate jdbc, UUID id, String input, String dataset,
            String resolverVersion, String hashCharacter, String status, int day) {
        OffsetDateTime timestamp = OffsetDateTime.parse("2026-09-01T00:00:00Z").plusDays(day);
        jdbc.update("""
                INSERT INTO location_resolution_cache_records (
                    id, resolver, resolver_version, input_fingerprint, source_dataset,
                    source_dataset_version, source_dataset_sha256, resolution_status,
                    location_precision, confidence_reason, candidate_evidence, resolved_at, cached_at
                ) VALUES (?, 'RGZ_WFS_PARCEL', ?, ?, 'RGZ_REGDKP_WFS', ?, ?, ?,
                          'NONE', 'legacy fixture', jsonb_build_object('capabilitiesSha256', ?::text), ?, ?)
                """, id, resolverVersion, input, dataset, hashCharacter.repeat(64), status,
                hashCharacter.repeat(64), timestamp, timestamp);
    }

    private static String records(JdbcTemplate jdbc) {
        return jdbc.queryForObject("""
                SELECT jsonb_agg(to_jsonb(cache) ORDER BY id)::text FROM location_resolution_cache_records cache
                """, String.class);
    }
}
