package rs.sud.eaukcija.addressregistry;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rs.sud.eaukcija.enrichment.EnrichmentHashing;
import rs.sud.eaukcija.enrichment.EnrichmentVersionPin;
import rs.sud.eaukcija.enrichment.EnrichmentWorkItem;
import rs.sud.eaukcija.snapshot.AuctionSourceCanonicalJson;
import rs.sud.eaukcija.spatial.LocationSelectionSql;

/** #23/#55: local, exact registry fallback. Never calls a third-party geocoder. */
@Service
public class AddressRegistryResolutionService {
    public static final String RESOLVER = "OFFICIAL_ADDRESS_REGISTRY";
    public static final String VERSION = "address-registry-resolution-v1";
    private static final int STREET_POINT_LIMIT = 10_000;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ThreadLocal<Snapshot> pinned = new ThreadLocal<>();

    public AddressRegistryResolutionService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public String activeVersion() { return snapshot().version(); }

    public EnrichmentVersionPin pinActiveVersion() {
        if (pinned.get() != null) throw new IllegalStateException("registry snapshot already pinned");
        pinned.set(loadSnapshot());
        return pinned::remove;
    }

    private Snapshot snapshot() { return pinned.get() == null ? loadSnapshot() : pinned.get(); }

    private Snapshot loadSnapshot() {
        var rows = jdbc.query("""
                SELECT s.id, btrim(s.gpkg_sha256) AS sha FROM address_registry_active_snapshot a
                JOIN address_registry_snapshots s ON s.id = a.snapshot_id WHERE a.singleton
                """, (rs, n) -> new Snapshot(rs.getObject("id", UUID.class), rs.getString("sha")));
        return rows.isEmpty() ? new Snapshot(null, EnrichmentHashing.sha256("REGISTRY_UNAVAILABLE")) : rows.get(0);
    }

    @Transactional
    public String resolveAuction(EnrichmentWorkItem item) {
        Snapshot snapshot = snapshot();
        if (snapshot.id() != null && jdbc.queryForList(
                "SELECT id FROM address_registry_snapshots WHERE id = ? FOR SHARE", UUID.class, snapshot.id()).isEmpty()) {
            // A pinned artifact was retired. Retry, never silently use a different dataset.
            throw new org.springframework.dao.TransientDataAccessResourceException("pinned registry snapshot unavailable");
        }
        List<Reference> references = jdbc.query("""
                SELECT r.id, r.reference_type, r.canonical_parcel_number, r.address_street,
                       r.address_house_number, r.address_municipality, r.address_settlement,
                       r.extraction_status, r.input_snapshot_sha256,
                       k.status AS ko_status, k.matched_ko_code, k.reconciliation_status,
                       c.input_fingerprint AS ko_fingerprint,
                       (NOT r.user_reviewed OR r.ko_code IS NULL OR r.ko_code = k.matched_ko_code) AS reviewed_agrees
                  FROM current_property_reference_extractions e
                  JOIN property_reference_extraction_memberships m
                    ON m.extraction_run_id = e.extraction_run_id AND m.auction_id = e.auction_id
                  JOIN property_references r ON r.id = m.reference_id
                  LEFT JOIN current_property_reference_ko_matches c ON c.reference_id = r.id
                  LEFT JOIN property_reference_ko_match_results k
                    ON k.reference_id = c.reference_id AND k.input_fingerprint = c.input_fingerprint
                 WHERE e.auction_id = ? AND r.reference_type IN ('PARCEL', 'ADDRESS')
                 ORDER BY m.reference_order, r.id
                """, (rs, n) -> new Reference(rs.getObject("id", UUID.class), rs.getString("reference_type"),
                rs.getString("canonical_parcel_number"), rs.getString("address_street"),
                rs.getString("address_house_number"), rs.getString("address_municipality"),
                rs.getString("address_settlement"), rs.getString("extraction_status"),
                rs.getString("input_snapshot_sha256"), rs.getString("ko_status"),
                rs.getString("matched_ko_code"), rs.getString("reconciliation_status"),
                rs.getString("ko_fingerprint"), rs.getBoolean("reviewed_agrees")), item.auctionId());
        List<String> hashes = new ArrayList<>(List.of(VERSION, snapshot.version()));
        for (Reference reference : references) {
            String fingerprint = EnrichmentHashing.sha256(VERSION, snapshot.version(), reference.id().toString(),
                    reference.inputSha(), reference.koFingerprint(), reference.status(), Boolean.toString(reference.reviewedAgrees()),
                    reference.parcel(), reference.street(), reference.house(), reference.municipality(), reference.settlement(), reference.koCode());
            UUID attemptId = id("address-attempt:" + fingerprint);
            var existing = jdbc.queryForList("SELECT resolution_status FROM location_resolution_attempts WHERE id = ?",
                    String.class, attemptId);
            if (existing.isEmpty()) {
                Decision decision = decide(reference, snapshot);
                persist(item, reference, snapshot, fingerprint, attemptId, decision);
            }
            select(attemptId);
            hashes.add(fingerprint);
        }
        if (references.isEmpty()) hashes.add("NO_FINER_REFERENCE");
        return EnrichmentHashing.sha256(hashes.toArray(String[]::new));
    }

    private Decision decide(Reference reference, Snapshot snapshot) {
        List<Map<String, Object>> tiers = new ArrayList<>();
        if (!LocationSelectionSql.publishableExtractionStatus(reference.status())) {
            return declined("INVALID", "REFERENCE_NEEDS_REVIEW", tiers);
        }
        if (!"MATCHED".equals(reference.koStatus()) || "STRUCTURED_ONLY".equals(reference.reconciliation())
                || !reference.reviewedAgrees()) {
            return declined("AMBIGUOUS".equals(reference.koStatus()) ? "AMBIGUOUS" : "INVALID", "KO_UNRESOLVED", tiers);
        }
        if (snapshot.id() == null) return declined("ERROR", "REGISTRY_UNAVAILABLE", tiers);
        if ("PARCEL".equals(reference.type())) {
            String parcel = AddressRegistryNormalizer.parcel(reference.parcel());
            if (parcel == null) return declined("INVALID", "INVALID_REGISTRY_PARCEL", tiers);
            List<Point> points = points("""
                    SELECT source_primary_key::text AS feature_id, ST_AsGeoJSON(location) AS geometry
                      FROM address_registry_points
                     WHERE snapshot_id = ? AND ko_id = ? AND parcel_number_normalized = ?
                     ORDER BY source_primary_key LIMIT 2
                    """, snapshot.id(), reference.koCode(), parcel);
            tiers.add(tier("REGISTRY_PARCEL", points.size()));
            return pointDecision(points, "REGISTRY_PARCEL_ADDRESS", tiers);
        }
        String municipality = AddressRegistryNormalizer.name(reference.municipality());
        String settlement = AddressRegistryNormalizer.name(reference.settlement());
        String street = AddressRegistryNormalizer.name(reference.street());
        String house = AddressRegistryNormalizer.houseNumber(reference.house());
        if (municipality == null || settlement == null || street == null) {
            return declined("INVALID", "INCOMPLETE_ADDRESS", tiers);
        }
        if (house != null) {
            List<Point> points = points("""
                    SELECT source_primary_key::text AS feature_id, ST_AsGeoJSON(location) AS geometry
                      FROM address_registry_points
                     WHERE snapshot_id = ? AND ko_id = ? AND municipality_name_normalized = ?
                       AND settlement_name_normalized = ? AND street_name_normalized = ? AND house_number_normalized = ?
                     ORDER BY source_primary_key LIMIT 2
                    """, snapshot.id(), reference.koCode(), municipality, settlement, street, house);
            tiers.add(tier("EXACT_ADDRESS", points.size()));
            if (!points.isEmpty()) return pointDecision(points, "EXACT_REGISTRY_ADDRESS", tiers);
        } else {
            tiers.add(Map.of("tier", "EXACT_ADDRESS", "reason", "NO_HOUSE_NUMBER"));
        }
        // One official street identity, not an arbitrary point from competing streets.
        var rows = jdbc.query("""
                WITH members AS (
                    SELECT street_id, location FROM address_registry_points
                     WHERE snapshot_id = ? AND ko_id = ? AND municipality_name_normalized = ?
                       AND settlement_name_normalized = ? AND street_name_normalized = ?
                     ORDER BY source_primary_key LIMIT ?
                ) SELECT count(*) AS points, count(DISTINCT street_id) AS streets,
                         count(*) FILTER (WHERE street_id IS NULL) AS missing_ids,
                         min(street_id) AS street_id,
                         ST_AsGeoJSON(ST_PointOnSurface(ST_Collect(location))) AS geometry FROM members
                """, (rs, n) -> new Street(rs.getInt("points"), rs.getInt("streets"), rs.getInt("missing_ids"),
                rs.getString("street_id"), rs.getString("geometry")), snapshot.id(), reference.koCode(),
                municipality, settlement, street, STREET_POINT_LIMIT + 1);
        Street candidate = rows.get(0);
        tiers.add(Map.of("tier", "STREET", "pointCount", candidate.points(), "streetCount", candidate.streets()));
        if (candidate.points() == 0) return declined("NOT_FOUND", "REGISTRY_ADDRESS_NOT_FOUND", tiers);
        if (candidate.points() > STREET_POINT_LIMIT) return declined("ERROR", "STREET_CANDIDATE_LIMIT", tiers);
        if (candidate.streets() != 1 || candidate.missingIds() != 0) {
            return declined("AMBIGUOUS", "REGISTRY_STREET_AMBIGUOUS", tiers);
        }
        return new Decision("RESOLVED", "STREET", "UNAMBIGUOUS_REGISTRY_STREET", candidate.geometry(),
                candidate.id(), candidate.points(), tiers);
    }

    private List<Point> points(String sql, Object... args) {
        return jdbc.query(sql, (rs, n) -> new Point(rs.getString("feature_id"), rs.getString("geometry")), args);
    }

    private static Map<String, Object> tier(String tier, int count) {
        return Map.of("tier", tier, "candidateCountLowerBound", count,
                "reason", count == 0 ? "NOT_FOUND" : count == 1 ? "UNIQUE" : "AMBIGUOUS");
    }

    private static Decision pointDecision(List<Point> points, String reason, List<Map<String, Object>> tiers) {
        if (points.isEmpty()) return declined("NOT_FOUND", "REGISTRY_ADDRESS_NOT_FOUND", tiers);
        if (points.size() != 1) return declined("AMBIGUOUS", "REGISTRY_ADDRESS_AMBIGUOUS", tiers);
        return new Decision("RESOLVED", "ADDRESS", reason, points.get(0).geometry(), points.get(0).id(), 1, tiers);
    }

    private static Decision declined(String status, String reason, List<Map<String, Object>> tiers) {
        return new Decision(status, "NONE", reason, null, null, 0, tiers);
    }

    private void persist(EnrichmentWorkItem item, Reference reference, Snapshot snapshot, String fingerprint,
                         UUID attemptId, Decision decision) {
        UUID geometryId = decision.geometry() == null ? null : id("address-geometry:" + fingerprint);
        if (geometryId != null) {
            jdbc.update("""
                    INSERT INTO spatial_resolution_geometries (
                        id, source_geometry, source_crs_authority, source_crs_code, source_crs_definition,
                        canonical_geometry, original_geometry_valid, make_valid_applied
                    ) VALUES (?, ST_SetSRID(ST_GeomFromGeoJSON(?),4326), 'EPSG', 4326, 'EPSG:4326',
                              ST_SetSRID(ST_GeomFromGeoJSON(?),4326), TRUE, FALSE) ON CONFLICT DO NOTHING
                    """, geometryId, decision.geometry(), decision.geometry());
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("schemaVersion", "address-fallback-evidence-v1");
        evidence.put("tiers", decision.tiers());
        evidence.put("reason", decision.reason());
        evidence.put("registrySnapshot", snapshot.id());
        evidence.put("upstreamKoMatchFingerprint", reference.koFingerprint());
        jdbc.update("""
                INSERT INTO location_resolution_attempts (
                    id, property_reference_id, enrichment_run_id, resolver, resolver_version, input_fingerprint,
                    source_dataset, source_dataset_version, source_dataset_sha256, source_feature_id,
                    resolution_status, location_precision, geometry_id, confidence_reason, candidate_evidence,
                    member_point_count, attempted_at, completed_at, resolved_at, upstream_ko_match_input_fingerprint
                ) VALUES (?, ?, ?, ?, ?, ?, 'RGZ_ADDRESS_REGISTRY', ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?,
                          CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                          CASE WHEN ? = 'RESOLVED' THEN CURRENT_TIMESTAMP ELSE NULL END, ?)
                ON CONFLICT (id) DO NOTHING
                """, attemptId, reference.id(), item.enrichmentRunId(), RESOLVER, VERSION, fingerprint,
                snapshot.version(), snapshot.sha(), decision.featureId(), decision.status(), decision.precision(),
                geometryId, decision.reason(), AuctionSourceCanonicalJson.write(mapper.valueToTree(evidence)),
                decision.members() == 0 ? null : decision.members(), decision.status(), reference.koFingerprint());
    }

    private void select(UUID attemptId) {
        jdbc.update("""
                INSERT INTO current_location_resolutions (property_reference_id, resolution_attempt_id, selected_at, selection_reason)
                SELECT property_reference_id, id, CURRENT_TIMESTAMP, 'verified official registry fallback'
                  FROM location_resolution_attempts WHERE id = ? AND resolution_status = 'RESOLVED'
                ON CONFLICT (property_reference_id) DO UPDATE SET
                    resolution_attempt_id = EXCLUDED.resolution_attempt_id, selected_at = EXCLUDED.selected_at,
                    selection_reason = EXCLUDED.selection_reason
                WHERE current_location_resolutions.resolution_attempt_id <> EXCLUDED.resolution_attempt_id
                  AND (SELECT %s FROM location_resolution_attempts old
                       WHERE old.id = current_location_resolutions.resolution_attempt_id)
                      <= (SELECT %s FROM location_resolution_attempts proposed WHERE proposed.id = ?)
                """.formatted(LocationSelectionSql.precisionRank("old.location_precision"),
                        LocationSelectionSql.precisionRank("proposed.location_precision")), attemptId, attemptId);
    }

    private static UUID id(String value) { return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)); }
    private record Snapshot(UUID id, String sha) { String version() { return id == null ? "REGISTRY_UNAVAILABLE" : id + ":" + sha; } }
    private record Point(String id, String geometry) { }
    private record Street(int points, int streets, int missingIds, String id, String geometry) { }
    private record Decision(String status, String precision, String reason, String geometry, String featureId,
                            int members, List<Map<String, Object>> tiers) { }
    private record Reference(UUID id, String type, String parcel, String street, String house, String municipality,
                             String settlement, String status, String inputSha, String koStatus, String koCode,
                             String reconciliation, String koFingerprint, boolean reviewedAgrees) { }
}
