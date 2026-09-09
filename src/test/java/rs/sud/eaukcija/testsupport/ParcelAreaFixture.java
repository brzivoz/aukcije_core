package rs.sud.eaukcija.testsupport;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import rs.sud.eaukcija.enrichment.EnrichmentHashing;

/** Synthetic retained RGZ evidence with real extraction/KO/current-selection constraints.
 * No source client, parser, cache lookup or enrichment is invoked by filter fixtures. */
public final class ParcelAreaFixture implements AutoCloseable {
    private final JdbcTemplate jdbc;
    private final UUID sourceRun = UUID.randomUUID();
    public static final String POINT = "POINT(20.46 44.79)";

    public ParcelAreaFixture(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        jdbc.update("""
                INSERT INTO sync_runs(id, idempotency_key_sha256, trigger_kind, status, stage,
                    started_at, heartbeat_at, finished_at, configured_roots, page_size)
                VALUES (?, ?, 'MANUAL', 'RUNNING', 'PROMOTING', now(), now(), NULL, '[7]'::jsonb, 100)
                """, sourceRun, hash(sourceRun));
    }

    public void auction(long id) {
        jdbc.update("""
                INSERT INTO auctions(id, auction_number, end_date, starting_price, category_name, status,
                    short_description, description, municipality, place_name, first_sale, details_fetched)
                VALUES (?, ?, '2099-08-30T08:00:00Z', 125000, 'Кућа', 'Verified',
                    'Кућа и њива', 'Продаје се 1/2 удела. Стан 60 m².', 'Београд', 'Вождовац', true, true)
                """, id, "Н57-" + id);
    }

    public UUID parcel(long auction, int order, String parcel) {
        return reference(auction, order, "PARCEL", parcel);
    }

    public UUID reference(long auction, int order, String type, String parcel) {
        UUID reference = UUID.randomUUID();
        Long identity = parcel == null ? null : jdbc.queryForObject("""
                INSERT INTO parcel_identities(ko_code, canonical_parcel_number) VALUES ('702013', ?)
                ON CONFLICT (ko_code, canonical_parcel_number) DO UPDATE SET ko_code = EXCLUDED.ko_code RETURNING id
                """, Long.class, parcel);
        jdbc.update("""
                INSERT INTO property_references(id, auction_id, reference_order, reference_type, ko_code,
                    canonical_parcel_number, parcel_identity_id, source_field, parser_version, extraction_status, canonical_key)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'fixture', 'parcel-size-v1', 'EXTRACTED', ?)
                """, reference, auction, order, type, parcel == null ? null : "702013", parcel, identity, reference.toString());
        koMatch(reference, hash(reference));
        return reference;
    }

    public void koMatch(UUID reference, String fingerprint) {
        jdbc.update("""
                INSERT INTO property_reference_ko_match_results(reference_id, auction_id, input_fingerprint,
                    ko_provenance, status, method, rationale, matched_ko_code, text_status, text_method,
                    text_matched_ko_code, reconciliation_status, structured_match_input_fingerprint,
                    structured_status, structured_method, dictionary_version, dictionary_source_sha256,
                    normalizer_version, alias_dataset_version, alias_sha256, municipality_alias_dataset_version,
                    municipality_alias_sha256, candidates, reconciliation_evidence, resolved_at)
                SELECT id, auction_id, ?, 'TEXT_EXTRACTED', 'MATCHED', 'EXACT_CODE', 'synthetic retained match',
                    '702013', 'MATCHED', 'EXACT_CODE', '702013', 'TEXT_ONLY', repeat('a',64), 'NOT_FOUND', 'NONE',
                    'fixture', repeat('b',64), 'fixture', 'fixture', repeat('c',64), 'fixture', repeat('d',64),
                    '[{"koCode":"702013"}]'::jsonb, '{}'::jsonb, now() FROM property_references WHERE id = ?
                """, fingerprint, reference);
        jdbc.update("""
                INSERT INTO current_property_reference_ko_matches(reference_id, auction_id, input_fingerprint)
                SELECT id, auction_id, ? FROM property_references WHERE id = ?
                ON CONFLICT (reference_id) DO UPDATE SET input_fingerprint = EXCLUDED.input_fingerprint
                """, fingerprint, reference);
    }

    /** Publish all references after adding siblings/duplicates, before selecting RGZ attempts. */
    public void publish(long auction) {
        UUID extraction = UUID.randomUUID();
        String fingerprint = hash(extraction);
        jdbc.update("""
                INSERT INTO auction_source_snapshots(auction_id, content_sha256, schema_version, minimization_policy_version,
                    listing_endpoint, detail_endpoint, canonical_payload, fetched_at, listing_fetched_at, detail_fetched_at,
                    source_start_at, source_end_at, ingest_run_id)
                VALUES (?, ?, 'fixture', 'fixture', 'listing', 'detail', '{}'::jsonb, now(), now(), now(), now(), now(), ?)
                """, auction, fingerprint, sourceRun);
        jdbc.update("""
                INSERT INTO auction_enrichment_input_snapshots(auction_id, snapshot_sha256, canonical_input)
                VALUES (?, ?, '{}'::jsonb)
                """, auction, fingerprint);
        jdbc.update("""
                INSERT INTO property_reference_extraction_runs(id, auction_id, source_sync_run_id, source_snapshot_sha256,
                    input_snapshot_sha256, parser_version, result_sha256, result_json, generated_reference_count,
                    selected_reference_count, text_reference_count, no_structured_count, ko_conflict_count,
                    quality_corpus_version, quality_metrics_sha256, held_out_precision, held_out_recall, held_out_negative_fp)
                SELECT ?, ?, ?, ?, ?, 'fixture', ?, '{}'::jsonb, count(*), count(*), count(*), 0, 0,
                    'fixture', repeat('e',64), 1, 1, 0 FROM property_references WHERE auction_id = ?
                """, extraction, auction, sourceRun, fingerprint, fingerprint, fingerprint, auction);
        jdbc.update("""
                INSERT INTO property_reference_extraction_memberships(extraction_run_id, auction_id, reference_id, reference_order)
                SELECT ?, auction_id, id, reference_order FROM property_references WHERE auction_id = ?
                """, extraction, auction);
        jdbc.update("""
                INSERT INTO current_property_reference_extractions(auction_id, extraction_run_id) VALUES (?, ?)
                ON CONFLICT (auction_id) DO UPDATE SET extraction_run_id = EXCLUDED.extraction_run_id
                """, auction, extraction);
    }

    public UUID rgz(UUID reference, String area) {
        return attempt(reference, "RGZ_WFS_PARCEL", "PARCEL", "{\"areaSquareMetres\":" + area + "}", POINT, true);
    }

    public UUID attempt(UUID reference, String resolver, String precision, String evidence, String wkt, boolean selected) {
        UUID geometry = UUID.randomUUID(), attempt = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO spatial_resolution_geometries(id, source_geometry, source_crs_authority, source_crs_code,
                    original_geometry_valid, make_valid_applied)
                VALUES (?, ST_GeomFromText(?, 4326), 'EPSG', 4326, true, false)
                """, geometry, wkt);
        jdbc.update("""
                INSERT INTO location_resolution_attempts(id, property_reference_id, resolver, resolver_version,
                    input_fingerprint, source_dataset, source_dataset_version, source_dataset_sha256,
                    resolution_status, location_precision, geometry_id, confidence_reason, candidate_evidence,
                    upstream_ko_match_input_fingerprint, attempted_at, completed_at, resolved_at)
                VALUES (?, ?, ?, 'fixture', ?, 'fixture', 'fixture', repeat('f',64), 'RESOLVED', ?, ?,
                    'synthetic retained evidence', CAST(? AS jsonb), ?, now(), now(), now())
                """, attempt, reference, resolver, hash(attempt), precision, geometry, evidence,
                resolver.equals("RGZ_WFS_PARCEL") || resolver.equals("OFFICIAL_ADDRESS_REGISTRY") ? hash(reference) : null);
        if (selected) select(reference, attempt);
        return attempt;
    }

    public void select(UUID reference, UUID attempt) {
        jdbc.update("""
                INSERT INTO current_location_resolutions(property_reference_id, resolution_attempt_id, selected_at, selection_reason)
                VALUES (?, ?, now(), 'fixture') ON CONFLICT (property_reference_id) DO UPDATE SET
                    resolution_attempt_id = EXCLUDED.resolution_attempt_id
                """, reference, attempt);
    }

    @Override public void close() {
        jdbc.update("UPDATE sync_runs SET status='PARTIAL', finished_at=now() WHERE id=? AND status='RUNNING'", sourceRun);
    }

    public static String hash(UUID id) { return EnrichmentHashing.sha256(id.toString()); }
}
