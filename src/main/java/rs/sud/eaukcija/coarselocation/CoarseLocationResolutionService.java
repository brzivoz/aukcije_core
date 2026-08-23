package rs.sud.eaukcija.coarselocation;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rs.sud.eaukcija.addressregistry.SerbianNameNormalizer;
import rs.sud.eaukcija.spatial.LocationPrecision;

/** Transactional #38 population orchestration over the active #36 and persisted #37 evidence. */
@Service
public class CoarseLocationResolutionService {

    private static final long ADVISORY_LOCK_ID = 38_003_600_037L;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final CentroidSnapshotLoader snapshotLoader;
    private final CoarseLocationResolutionProperties properties;
    private final Clock clock;

    @Autowired
    public CoarseLocationResolutionService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            CentroidSnapshotLoader snapshotLoader,
            CoarseLocationResolutionProperties properties) {
        this(jdbc, objectMapper, snapshotLoader, properties, Clock.systemUTC());
    }

    CoarseLocationResolutionService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            CentroidSnapshotLoader snapshotLoader,
            CoarseLocationResolutionProperties properties,
            Clock clock) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.snapshotLoader = snapshotLoader;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public RunResult run() {
        properties.validate();
        Instant started = Instant.now(clock);
        CentroidSnapshot snapshot = snapshotLoader.load(properties.getCentroidDirectory());
        List<CoarseLocationResolver.Input> inputs = readInputs();
        validateUpstreamSnapshot(inputs, snapshot);
        CoarseLocationResolver resolver = new CoarseLocationResolver(snapshot, objectMapper);

        // The unchanged decision, cache publication, append-only attempt, and
        // selected pointer move form one database-wide serial transaction.
        jdbc.execute("SELECT pg_advisory_xact_lock(" + ADVISORY_LOCK_ID + ")");

        TreeMap<String, Long> tierCounts = initializedTierCounts();
        TreeMap<String, Long> koStatusCounts = initializedKoStatusCounts();
        TreeMap<String, Long> rationaleCounts = new TreeMap<>();
        long processed = 0;
        long unchanged = 0;
        long municipalityAliasKoCount = 0;

        for (CoarseLocationResolver.Input input : inputs) {
            UUID referenceId = upsertStructuredReference(input);
            CoarseLocationResolver.Resolution resolution = resolver.resolve(input);
            increment(koStatusCounts, input.koStatus() == null ? "MISSING" : input.koStatus());
            increment(tierCounts, resolution.precision().name());
            increment(rationaleCounts, rationaleCode(resolution.rationale()));
            if (resolution.precision() == LocationPrecision.CADASTRAL_MUNICIPALITY
                    && resolution.usedMunicipalityAlias()) {
                municipalityAliasKoCount++;
            }

            if (attemptExists(referenceId, resolution.inputFingerprint(), snapshot)) {
                unchanged++;
                continue;
            }
            persistResolution(referenceId, resolution, snapshot);
            processed++;
        }

        Instant finished = Instant.now(clock);
        UUID runId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO coarse_location_resolution_runs (
                    id, started_at, finished_at, resolver_version,
                    extract_version, extract_source_sha256,
                    population_count, processed_count, unchanged_count,
                    cadastral_municipality_count, settlement_count, municipality_count, none_count,
                    municipality_alias_ko_count, structured_ko_status_counts, rationale_counts
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb))
                """,
                runId,
                databaseTime(started),
                databaseTime(finished),
                CoarseLocationResolver.RESOLVER_VERSION,
                snapshot.version(),
                snapshot.sourceGpkgSha256(),
                inputs.size(),
                processed,
                unchanged,
                tierCounts.get(LocationPrecision.CADASTRAL_MUNICIPALITY.name()),
                tierCounts.get(LocationPrecision.SETTLEMENT.name()),
                tierCounts.get(LocationPrecision.MUNICIPALITY.name()),
                tierCounts.get(LocationPrecision.NONE.name()),
                municipalityAliasKoCount,
                json(koStatusCounts),
                json(rationaleCounts));

        return new RunResult(
                runId,
                started,
                finished,
                Duration.between(started, finished).toMillis(),
                CoarseLocationResolver.RESOLVER_VERSION,
                snapshot.version(),
                snapshot.sourceDate().toString(),
                snapshot.sourceGpkgSha256(),
                inputs.size(),
                processed,
                unchanged,
                Map.copyOf(tierCounts),
                municipalityAliasKoCount,
                Map.copyOf(koStatusCounts),
                Map.copyOf(rationaleCounts));
    }

    private List<CoarseLocationResolver.Input> readInputs() {
        return jdbc.query("""
                SELECT a.id, a.cadastral, a.place_name, a.municipality,
                       match.status AS ko_status,
                       match.method AS ko_method,
                       match.rationale AS ko_rationale,
                       match.matched_ko_code,
                       match.dictionary_version,
                       match.dictionary_source_sha256,
                       match.candidates::text AS ko_candidates
                  FROM auctions a
                  LEFT JOIN auction_structured_ko_matches match ON match.auction_id = a.id
                 ORDER BY a.id
                """, (resultSet, rowNumber) -> new CoarseLocationResolver.Input(
                resultSet.getLong("id"),
                resultSet.getString("cadastral"),
                resultSet.getString("place_name"),
                resultSet.getString("municipality"),
                resultSet.getString("ko_status"),
                resultSet.getString("ko_method"),
                resultSet.getString("ko_rationale"),
                resultSet.getString("matched_ko_code"),
                resultSet.getString("dictionary_version"),
                resultSet.getString("dictionary_source_sha256"),
                parseCandidates(resultSet.getString("ko_candidates"))));
    }

    private void validateUpstreamSnapshot(
            List<CoarseLocationResolver.Input> inputs,
            CentroidSnapshot snapshot) {
        List<Long> mismatches = inputs.stream()
                .filter(input -> input.dictionarySourceSha256() != null)
                .filter(input -> !snapshot.sourceGpkgSha256().equals(input.dictionarySourceSha256()))
                .map(CoarseLocationResolver.Input::auctionId)
                .limit(10)
                .toList();
        if (!mismatches.isEmpty()) {
            throw new CoarseLocationResolutionException(
                    "STRUCTURED_KO_SNAPSHOT_MISMATCH",
                    "#37 results do not trace to active #36 source hash for auction ids " + mismatches
                            + "; republish #14 and rerun #37 before coarse resolution");
        }
    }

    private UUID upsertStructuredReference(CoarseLocationResolver.Input input) {
        UUID id = UUID.nameUUIDFromBytes(("coarse-structured-place:" + input.auctionId())
                .getBytes(StandardCharsets.UTF_8));
        String normalizedKo = SerbianNameNormalizer.normalize(input.cadastral());
        String koCode = "MATCHED".equals(input.koStatus()) ? input.matchedKoCode() : null;
        String extractionStatus = input.cadastral() == null
                && input.placeName() == null
                && input.municipality() == null
                        ? "NO_STRUCTURED_REFERENCE"
                        : "EXTRACTED";
        Map<String, Object> rawEvidence = new LinkedHashMap<>();
        rawEvidence.put("cadastral", input.cadastral());
        rawEvidence.put("placeName", input.placeName());
        rawEvidence.put("municipality", input.municipality());
        rawEvidence.put("structuredKoStatus", input.koStatus() == null ? "MISSING" : input.koStatus());
        rawEvidence.put("matchedKoCode", koCode);
        jdbc.update("""
                INSERT INTO property_references (
                    id, auction_id, reference_order, reference_type,
                    raw_ko, normalized_ko, ko_code,
                    address_municipality, address_settlement,
                    source_field, raw_evidence, parser_version,
                    extraction_status, canonical_key
                ) VALUES (?, ?, 0, 'STRUCTURED_LOCATION', ?, ?, ?, ?, ?,
                          'Place.Cadastral|Place.Name|Place.Municipality', ?, ?, ?, ?)
                ON CONFLICT (auction_id, parser_version, canonical_key) DO UPDATE SET
                    raw_ko = EXCLUDED.raw_ko,
                    normalized_ko = EXCLUDED.normalized_ko,
                    ko_code = EXCLUDED.ko_code,
                    address_municipality = EXCLUDED.address_municipality,
                    address_settlement = EXCLUDED.address_settlement,
                    raw_evidence = EXCLUDED.raw_evidence,
                    extraction_status = EXCLUDED.extraction_status
                """,
                id,
                input.auctionId(),
                input.cadastral(),
                normalizedKo,
                koCode,
                input.municipality(),
                input.placeName(),
                json(rawEvidence),
                CoarseLocationResolver.REFERENCE_PARSER_VERSION,
                extractionStatus,
                CoarseLocationResolver.REFERENCE_CANONICAL_KEY);
        return jdbc.queryForObject("""
                SELECT id FROM property_references
                 WHERE auction_id = ? AND parser_version = ? AND canonical_key = ?
                """, UUID.class,
                input.auctionId(),
                CoarseLocationResolver.REFERENCE_PARSER_VERSION,
                CoarseLocationResolver.REFERENCE_CANONICAL_KEY);
    }

    private boolean attemptExists(
            UUID referenceId,
            String fingerprint,
            CentroidSnapshot snapshot) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM location_resolution_attempts
                 WHERE property_reference_id = ?
                   AND resolver = ?
                   AND resolver_version = ?
                   AND input_fingerprint = ?
                   AND source_dataset = ?
                   AND source_dataset_version = ?
                   AND source_dataset_sha256 = ?
                """, Integer.class,
                referenceId,
                CoarseLocationResolver.RESOLVER,
                CoarseLocationResolver.RESOLVER_VERSION,
                fingerprint,
                CoarseLocationResolver.SOURCE_DATASET,
                snapshot.version(),
                snapshot.sourceGpkgSha256());
        return count != null && count > 0;
    }

    private void persistResolution(
            UUID referenceId,
            CoarseLocationResolver.Resolution resolution,
            CentroidSnapshot snapshot) {
        Instant attemptedAt = Instant.now(clock);
        CacheRecord cache = findCache(resolution.inputFingerprint(), snapshot);
        if (cache == null) {
            UUID geometryId = resolution.centroid() == null ? null : insertGeometry(resolution.centroid());
            UUID cacheId = UUID.randomUUID();
            Instant resolvedAt = Instant.now(clock);
            jdbc.update("""
                    INSERT INTO location_resolution_cache_records (
                        id, resolver, resolver_version, input_fingerprint,
                        source_dataset, source_dataset_version, source_dataset_sha256, source_feature_id,
                        resolution_status, location_precision, geometry_id,
                        confidence_reason, candidate_evidence, member_point_count, resolved_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?)
                    """,
                    cacheId,
                    CoarseLocationResolver.RESOLVER,
                    CoarseLocationResolver.RESOLVER_VERSION,
                    resolution.inputFingerprint(),
                    CoarseLocationResolver.SOURCE_DATASET,
                    snapshot.version(),
                    snapshot.sourceGpkgSha256(),
                    resolution.centroid() == null ? null : resolution.centroid().officialCode(),
                    resolution.status(),
                    resolution.precision().name(),
                    geometryId,
                    resolution.rationale(),
                    json(resolution.candidateEvidence()),
                    resolution.centroid() == null ? null : resolution.centroid().memberPointCount(),
                    databaseTime(resolvedAt));
            cache = new CacheRecord(cacheId, geometryId);
        }

        Instant completedAt = Instant.now(clock);
        UUID attemptId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO location_resolution_attempts (
                    id, property_reference_id, used_cache_record_id,
                    resolver, resolver_version, input_fingerprint,
                    source_dataset, source_dataset_version, source_dataset_sha256, source_feature_id,
                    resolution_status, location_precision, geometry_id,
                    confidence_reason, candidate_evidence, member_point_count,
                    attempted_at, completed_at, resolved_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?)
                """,
                attemptId,
                referenceId,
                cache.id(),
                CoarseLocationResolver.RESOLVER,
                CoarseLocationResolver.RESOLVER_VERSION,
                resolution.inputFingerprint(),
                CoarseLocationResolver.SOURCE_DATASET,
                snapshot.version(),
                snapshot.sourceGpkgSha256(),
                resolution.centroid() == null ? null : resolution.centroid().officialCode(),
                resolution.status(),
                resolution.precision().name(),
                cache.geometryId(),
                resolution.rationale(),
                json(resolution.candidateEvidence()),
                resolution.centroid() == null ? null : resolution.centroid().memberPointCount(),
                databaseTime(attemptedAt),
                databaseTime(completedAt),
                databaseTime(completedAt));
        selectWithoutDowngrading(referenceId, attemptId, resolution.rationale(), completedAt);
    }

    private CacheRecord findCache(String fingerprint, CentroidSnapshot snapshot) {
        List<CacheRecord> rows = jdbc.query("""
                SELECT id, geometry_id
                  FROM location_resolution_cache_records
                 WHERE resolver = ? AND resolver_version = ? AND input_fingerprint = ?
                   AND source_dataset = ? AND source_dataset_version = ? AND source_dataset_sha256 = ?
                """, (resultSet, rowNumber) -> new CacheRecord(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("geometry_id", UUID.class)),
                CoarseLocationResolver.RESOLVER,
                CoarseLocationResolver.RESOLVER_VERSION,
                fingerprint,
                CoarseLocationResolver.SOURCE_DATASET,
                snapshot.version(),
                snapshot.sourceGpkgSha256());
        return rows.isEmpty() ? null : rows.get(0);
    }

    private UUID insertGeometry(CentroidSnapshot.Centroid centroid) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO spatial_resolution_geometries (
                    id, source_geometry, source_crs_authority, source_crs_code,
                    original_geometry_valid, make_valid_applied
                ) VALUES (?, ST_SetSRID(ST_MakePoint(?, ?), 4326), 'EPSG', 4326, true, false)
                """, id, centroid.longitude(), centroid.latitude());
        return id;
    }

    private void selectWithoutDowngrading(
            UUID referenceId,
            UUID attemptId,
            String rationale,
            Instant selectedAt) {
        jdbc.update("""
                INSERT INTO current_location_resolutions (
                    property_reference_id, resolution_attempt_id, selected_at, selection_reason
                ) VALUES (?, ?, ?, ?)
                ON CONFLICT (property_reference_id) DO UPDATE SET
                    resolution_attempt_id = EXCLUDED.resolution_attempt_id,
                    selected_at = EXCLUDED.selected_at,
                    selection_reason = EXCLUDED.selection_reason
                WHERE EXISTS (
                    SELECT 1
                      FROM location_resolution_attempts current_attempt,
                           location_resolution_attempts proposed_attempt
                     WHERE current_attempt.id = current_location_resolutions.resolution_attempt_id
                       AND proposed_attempt.id = EXCLUDED.resolution_attempt_id
                       AND (
                           current_attempt.resolver = ?
                           OR CASE proposed_attempt.location_precision
                                  WHEN 'PARCEL' THEN 60 WHEN 'ADDRESS' THEN 50 WHEN 'STREET' THEN 40
                                  WHEN 'CADASTRAL_MUNICIPALITY' THEN 30 WHEN 'SETTLEMENT' THEN 20
                                  WHEN 'MUNICIPALITY' THEN 10 ELSE 0 END
                              > CASE current_attempt.location_precision
                                  WHEN 'PARCEL' THEN 60 WHEN 'ADDRESS' THEN 50 WHEN 'STREET' THEN 40
                                  WHEN 'CADASTRAL_MUNICIPALITY' THEN 30 WHEN 'SETTLEMENT' THEN 20
                                  WHEN 'MUNICIPALITY' THEN 10 ELSE 0 END
                       )
                )
                """,
                referenceId,
                attemptId,
                databaseTime(selectedAt),
                rationale,
                CoarseLocationResolver.RESOLVER);
    }

    private JsonNode parseCandidates(String value) {
        if (value == null) {
            return objectMapper.createArrayNode();
        }
        try {
            JsonNode parsed = objectMapper.readTree(value);
            if (!parsed.isArray()) {
                throw new CoarseLocationResolutionException(
                        "STRUCTURED_KO_EVIDENCE_INVALID", "#37 candidates must be a JSON array");
            }
            return parsed;
        } catch (JsonProcessingException e) {
            throw new CoarseLocationResolutionException(
                    "STRUCTURED_KO_EVIDENCE_INVALID", "could not parse #37 candidate evidence", e);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not serialize retained coarse-resolution evidence", e);
        }
    }

    private static TreeMap<String, Long> initializedTierCounts() {
        TreeMap<String, Long> counts = new TreeMap<>();
        counts.put(LocationPrecision.CADASTRAL_MUNICIPALITY.name(), 0L);
        counts.put(LocationPrecision.SETTLEMENT.name(), 0L);
        counts.put(LocationPrecision.MUNICIPALITY.name(), 0L);
        counts.put(LocationPrecision.NONE.name(), 0L);
        return counts;
    }

    private static TreeMap<String, Long> initializedKoStatusCounts() {
        TreeMap<String, Long> counts = new TreeMap<>();
        counts.put("MATCHED", 0L);
        counts.put("AMBIGUOUS", 0L);
        counts.put("NOT_FOUND", 0L);
        counts.put("INVALID", 0L);
        counts.put("MISSING", 0L);
        return counts;
    }

    private static void increment(Map<String, Long> counts, String key) {
        counts.merge(key, 1L, Long::sum);
    }

    private static String rationaleCode(String rationale) {
        int separator = rationale.indexOf(':');
        return separator < 0 ? rationale : rationale.substring(0, separator);
    }

    private static OffsetDateTime databaseTime(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private record CacheRecord(UUID id, UUID geometryId) {
    }

    public record RunResult(
            UUID runId,
            Instant startedAt,
            Instant finishedAt,
            long durationMillis,
            String resolverVersion,
            String extractVersion,
            String extractSourceDate,
            String extractSourceSha256,
            long populationCount,
            long processedCount,
            long unchangedCount,
            Map<String, Long> tierCounts,
            long municipalityAliasKoCount,
            Map<String, Long> structuredKoStatusCounts,
            Map<String, Long> rationaleCounts) {
    }
}
