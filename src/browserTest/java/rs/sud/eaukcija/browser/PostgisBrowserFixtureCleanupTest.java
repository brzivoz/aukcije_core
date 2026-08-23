package rs.sud.eaukcija.browser;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Verifies browser database isolation without launching a browser. */
class PostgisBrowserFixtureCleanupTest extends PostgisBrowserFixture {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void fixtureCleanupHandlesSelectedLocationAndAppendOnlyEvidence() {
        jdbc.update("""
                INSERT INTO property_references (
                    id, auction_id, reference_order, reference_type, source_field,
                    parser_version, extraction_status, canonical_key
                ) VALUES (?::uuid, ?, 0, 'STRUCTURED_LOCATION', 'fixture',
                          'browser-fixture-v1', 'EXTRACTED', 'browser-location')
                """, "34000000-0000-0000-0000-000000000001", SEEDED_AUCTION_ID);
        jdbc.update("""
                INSERT INTO spatial_resolution_geometries (
                    id, source_geometry, source_crs_authority, source_crs_code,
                    canonical_geometry, original_geometry_valid, make_valid_applied
                ) VALUES (
                    ?::uuid, ST_SetSRID(ST_MakePoint(20.5, 44.5), 4326), 'EPSG', 4326,
                    ST_SetSRID(ST_MakePoint(20.5, 44.5), 4326), true, false
                )
                """, "34000000-0000-0000-0000-000000000002");
        jdbc.update("""
                INSERT INTO location_resolution_attempts (
                    id, property_reference_id, resolver, resolver_version,
                    input_fingerprint, source_dataset, source_dataset_version,
                    source_dataset_sha256, source_feature_id, resolution_status,
                    location_precision, geometry_id, confidence_reason,
                    candidate_evidence, attempted_at, completed_at, resolved_at
                ) VALUES (
                    ?::uuid, ?::uuid, 'browser-fixture', 'v1', repeat('1', 64),
                    'fixture', 'v1', repeat('2', 64), 'fixture-location', 'RESOLVED',
                    'MUNICIPALITY', ?::uuid, 'browser cleanup regression', '[]'::jsonb,
                    '2026-08-23T09:00:00Z', '2026-08-23T09:00:01Z', '2026-08-23T09:00:01Z'
                )
                """,
                "34000000-0000-0000-0000-000000000003",
                "34000000-0000-0000-0000-000000000001",
                "34000000-0000-0000-0000-000000000002");
        jdbc.update("""
                INSERT INTO current_location_resolutions (
                    property_reference_id, resolution_attempt_id, selected_at, selection_reason
                ) VALUES (?::uuid, ?::uuid, '2026-08-23T09:00:01Z', 'browser fixture')
                """,
                "34000000-0000-0000-0000-000000000001",
                "34000000-0000-0000-0000-000000000003");

        clearBrowserData();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM auctions", Long.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM location_resolution_attempts", Long.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM current_location_resolutions", Long.class)).isZero();
    }
}
