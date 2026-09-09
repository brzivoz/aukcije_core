package rs.sud.eaukcija.testsupport;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import rs.sud.eaukcija.addressregistry.SerbianNameNormalizer;

/** Synthetic already-imported registry. Import/schema/CRS fidelity has its own real GPKG suite. */
public final class RegistryResolutionFixture {
    private RegistryResolutionFixture() { }

    public static UUID snapshot(JdbcTemplate jdbc) {
        UUID id = UUID.randomUUID();
        String sha = rs.sud.eaukcija.enrichment.EnrichmentHashing.sha256(id.toString());
        jdbc.update("""
                INSERT INTO address_registry_snapshots (
                    id, canonical_url, download_uri, downloaded_at, source_date, source_bytes, source_sha256,
                    gpkg_bytes, gpkg_sha256, schema_sha256, source_table, geometry_column, source_crs, target_crs,
                    source_row_count, imported_row_count, active_source_row_count, inactive_source_row_count,
                    retired_source_row_count, rejected_row_count, duplicate_parcel_identities,
                    unnormalized_parcel_rows, ambiguous_parent_identities
                ) VALUES (?, 'https://fixture.invalid/registry', 'file:///synthetic.gpkg', now(), '2026-09-09', 1, ?,
                          1, ?, ?, 'addresses', 'geom', 25834, 4326, 1, 1, 1, 0, 0, 0, 0, 0, 0)
                """, id, sha, sha, sha);
        jdbc.update("""
                INSERT INTO address_registry_active_snapshot (singleton, snapshot_id, activated_at)
                VALUES (TRUE, ?, now()) ON CONFLICT (singleton) DO UPDATE SET
                    previous_snapshot_id = address_registry_active_snapshot.snapshot_id,
                    snapshot_id = EXCLUDED.snapshot_id, activated_at = EXCLUDED.activated_at
                """, id);
        return id;
    }

    public static void point(JdbcTemplate jdbc, UUID snapshot, long id, String ko, String place,
                             String street, String house, String parcel, String streetId) {
        jdbc.update("""
                INSERT INTO address_registry_points (
                    snapshot_id, source_fid, source_primary_key, house_number, house_number_normalized,
                    street_id, street_name, street_name_normalized, parcel_number, parcel_number_normalized,
                    ko_id, ko_name, ko_name_normalized, settlement_id, settlement_name, settlement_name_normalized,
                    municipality_id, municipality_name, municipality_name_normalized, location
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'S', ?, ?, 'M', ?, ?, ST_SetSRID(ST_Point(20.495,44.775),4326))
                """, snapshot, id, id, house, house == null ? null : SerbianNameNormalizer.normalize(house).replace(" ", ""),
                streetId, street, SerbianNameNormalizer.normalize(street), parcel, parcel, ko, place,
                SerbianNameNormalizer.normalize(place), place, SerbianNameNormalizer.normalize(place),
                place, SerbianNameNormalizer.normalize(place));
    }
}
