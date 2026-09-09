package rs.sud.eaukcija.rgz;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import rs.sud.eaukcija.enrichment.EnrichmentHashing;
import rs.sud.eaukcija.enrichment.EnrichmentWorkItem;
import rs.sud.eaukcija.snapshot.AuctionSourceCanonicalJson;
import rs.sud.eaukcija.spatial.ParcelIdentityNormalizer;

/** Issue #21 cache-first persistence and selection orchestration for automatic parcels. */
@Service
public class RgzParcelResolutionService {

    public static final String RESOLVER = "RGZ_WFS_PARCEL";
    public static final String RESOLVER_VERSION = "rgz-parcel-v5";
    public static final String SOURCE_DATASET = "RGZ_REGDKP_WFS";
    public static final String DECISION_VERSION =
            "2026-09-08-issue-41-private-poc-v4";

    private static final int MAX_TRACKED_RUNS = 128;

    private final JdbcTemplate jdbc;
    private final RgzParcelClient client;
    private final RgzParcelProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final LinkedHashMap<UUID, DirectRunUsage> runLookups = new LinkedHashMap<>();

    @Autowired
    public RgzParcelResolutionService(
            JdbcTemplate jdbc,
            RgzParcelClient client,
            RgzParcelProperties properties,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this(jdbc, client, properties, objectMapper, Clock.systemUTC(), transactionManager);
    }

    RgzParcelResolutionService(
            JdbcTemplate jdbc,
            RgzParcelClient client,
            RgzParcelProperties properties,
            ObjectMapper objectMapper,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.client = client;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public String activeVersion() {
        properties.validate();
        return String.join(":",
                properties.getAccessMode(),
                properties.getFeatureType(),
                properties.getDatasetVersion(),
                properties.getCapabilitiesSha256(),
                properties.getSchemaSha256(),
                Boolean.toString(properties.isEnabled()),
                properties.getInvalidResultRecheckVersion());
    }

    public AuctionResult resolveAuction(EnrichmentWorkItem item) {
        properties.validate();
        // A disabled network must not disable reuse of the configured dataset.
        // Without a dataset identity there is no safe cache key to look up.
        if (properties.getDatasetVersion() == null || properties.getDatasetVersion().isBlank()) {
            return AuctionResult.disabled(item.auctionId());
        }
        List<Candidate> candidates = inTransaction(() -> candidates(item.auctionId()));
        List<ReferenceResult> results = new ArrayList<>(candidates.size());
        for (Candidate candidate : candidates) {
            results.add(resolve(item, candidate));
        }
        long resolved = results.stream()
                .filter(result -> result.status() == RgzParcelResult.Status.RESOLVED)
                .count();
        long cacheHits = results.stream().filter(ReferenceResult::cacheHit).count();
        List<String> evidence = new ArrayList<>();
        evidence.add(RESOLVER_VERSION);
        evidence.add(Long.toString(item.auctionId()));
        evidence.add(properties.getDatasetVersion());
        if (results.isEmpty()) {
            evidence.add("NO_CURRENT_MATCHED_PARCEL_REFERENCE");
        } else {
            for (ReferenceResult result : results) {
                evidence.add(result.referenceId().toString());
                evidence.add(result.inputFingerprint());
                evidence.add(result.koMatchInputFingerprint());
                evidence.add(result.status().name());
            }
        }
        return new AuctionResult(
                candidates.size(), resolved, cacheHits,
                EnrichmentHashing.sha256(evidence.toArray(String[]::new)));
    }

    private ReferenceResult resolve(EnrichmentWorkItem item, Candidate candidate) {
        String inputFingerprint = EnrichmentHashing.sha256(
                properties.getFeatureType(),
                properties.getDatasetVersion(),
                candidate.koCode(),
                candidate.parcelNumber());
        LookupPreparation preparation = inTransaction(
                () -> prepareLookup(item, candidate, inputFingerprint));
        if (preparation.completedResult() != null) {
            return preparation.completedResult();
        }

        // The claim is committed; the fetch must not run inside any caller's
        // transaction, or a slow endpoint would hold a connection and its locks
        // for the whole rate-limited retry window.
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "RGZ lookup must not run inside a database transaction");
        }
        RgzParcelResult fetched = client.fetch(
                candidate.koCode(), candidate.parcelNumber(), properties::networkAllowed);
        return inTransaction(() -> persistLookupResult(
                item, candidate, inputFingerprint, fetched));
    }

    private LookupPreparation prepareLookup(
            EnrichmentWorkItem item,
            Candidate candidate,
            String inputFingerprint) {
        CacheRecord cached = cache(inputFingerprint);
        if (cached != null) {
            if (cached.status() == RgzParcelResult.Status.RESOLVED) {
                attachParcelIdentity(candidate);
            }
            useCache(candidate, inputFingerprint, cached, item.enrichmentRunId());
            return new LookupPreparation(outcome(candidate, inputFingerprint, cached.status(), true));
        }

        if (!properties.networkAllowed()) {
            RgzParcelResult skipped = error(properties.isEnabled() ? "KILL_SWITCH_ENGAGED" : "RGZ_DISABLED");
            // Disabled configurations may omit source pins. There was no request
            // whose provenance could justify an attempt with those missing pins.
            if (properties.sourceContractReady()) {
                createAttempt(candidate, inputFingerprint, null, skipped, item.enrichmentRunId());
            }
            return new LookupPreparation(new ReferenceResult(
                    candidate.referenceId(), inputFingerprint, candidate.koMatchInputFingerprint(),
                    skipped.status(), false));
        }

        Reservation reservation = reserveLogicalLookup(item, inputFingerprint);
        if (reservation != Reservation.RESERVED) {
            String reason = reservation == Reservation.ALREADY_CLAIMED
                    ? "LOGICAL_LOOKUP_ALREADY_CLAIMED"
                    : "RUN_REQUEST_CEILING_REACHED";
            RgzParcelResult skipped = error(reason);
            createAttempt(candidate, inputFingerprint, null, skipped, item.enrichmentRunId());
            return new LookupPreparation(new ReferenceResult(
                    candidate.referenceId(), inputFingerprint, candidate.koMatchInputFingerprint(),
                    skipped.status(), false));
        }
        return new LookupPreparation(null);
    }

    private ReferenceResult persistLookupResult(
            EnrichmentWorkItem item,
            Candidate candidate,
            String inputFingerprint,
            RgzParcelResult fetched) {
        CacheRecord cached = cache(inputFingerprint);
        if (cached != null) {
            if (cached.status() == RgzParcelResult.Status.RESOLVED) {
                attachParcelIdentity(candidate);
            }
            useCache(candidate, inputFingerprint, cached, item.enrichmentRunId());
            return outcome(candidate, inputFingerprint, cached.status(), true);
        }
        CacheRecord persisted = fetched.cacheable()
                ? persistCache(inputFingerprint, candidate, fetched)
                : null;
        createAttempt(candidate, inputFingerprint, persisted, fetched, item.enrichmentRunId());
        return outcome(candidate, inputFingerprint, fetched.status(), false);
    }

    private ReferenceResult outcome(
            Candidate candidate, String fingerprint, RgzParcelResult.Status status, boolean cacheHit) {
        if (status == RgzParcelResult.Status.RESOLVED && !Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM current_location_resolutions selection
                    JOIN location_resolution_attempts attempt ON attempt.id = selection.resolution_attempt_id
                    WHERE selection.property_reference_id = ? AND attempt.input_fingerprint = ?
                      AND attempt.upstream_ko_match_input_fingerprint = ?
                      AND attempt.resolver = 'RGZ_WFS_PARCEL'
                )
                """, Boolean.class, candidate.referenceId(), fingerprint, candidate.koMatchInputFingerprint()))) {
            // A valid late response remains cached evidence, not a resolution
            // of an auction whose current KO premise has already been revoked.
            status = RgzParcelResult.Status.ERROR;
        }
        return new ReferenceResult(candidate.referenceId(), fingerprint,
                candidate.koMatchInputFingerprint(), status, cacheHit);
    }

    private List<Candidate> candidates(long auctionId) {
        return jdbc.query("""
                SELECT reference.id, reference.canonical_parcel_number,
                       match_result.input_fingerprint,
                       match_result.matched_ko_code
                  FROM current_property_reference_extractions current_extraction
                  JOIN property_reference_extraction_memberships membership
                    ON membership.extraction_run_id = current_extraction.extraction_run_id
                   AND membership.auction_id = current_extraction.auction_id
                  JOIN property_references reference ON reference.id = membership.reference_id
                  JOIN current_property_reference_ko_matches current_match
                    ON current_match.reference_id = reference.id
                   AND current_match.auction_id = reference.auction_id
                  JOIN property_reference_ko_match_results match_result
                    ON match_result.reference_id = current_match.reference_id
                   AND match_result.input_fingerprint = current_match.input_fingerprint
                 WHERE reference.auction_id = ?
                   AND reference.canonical_parcel_number IS NOT NULL
                   AND reference.extraction_status IN ('EXTRACTED', 'USER_CONFIRMED')
                   AND match_result.status = 'MATCHED'
                   AND match_result.reconciliation_status <> 'STRUCTURED_ONLY'
                   AND match_result.matched_ko_code ~ '^[0-9]{1,16}$'
                   AND (
                       NOT reference.user_reviewed
                       OR reference.ko_code IS NULL
                       OR reference.ko_code = match_result.matched_ko_code
                   )
                 ORDER BY membership.reference_order, reference.id
                """, (result, row) -> new Candidate(
                result.getObject("id", UUID.class),
                result.getString("matched_ko_code"),
                result.getString("canonical_parcel_number"),
                result.getString("input_fingerprint").trim()), auctionId);
    }

    private CacheRecord cache(String inputFingerprint) {
        List<CacheRecord> rows = jdbc.query("""
                SELECT cache.id, cache.resolution_status, cache.geometry_id, cache.source_feature_id,
                       cache.confidence_reason, cache.candidate_evidence::text,
                       cache.resolver_version, cache.source_dataset_sha256
                  FROM rgz_parcel_cache_keys cache_key
                  JOIN location_resolution_cache_records cache
                    ON cache.id = cache_key.cache_record_id
                   AND cache.input_fingerprint = cache_key.input_fingerprint
                 WHERE cache_key.input_fingerprint = ?
                   AND cache.resolver = ? AND cache.source_dataset = ?
                   AND cache.source_dataset_version = ?
                   AND (cache.resolution_status <> 'INVALID' OR ? = ''
                        OR cache.candidate_evidence ->> 'invalidResultRecheckVersion' = ?)
                """, (result, row) -> new CacheRecord(
                result.getObject("id", UUID.class),
                RgzParcelResult.Status.valueOf(result.getString("resolution_status")),
                result.getObject("geometry_id", UUID.class),
                result.getString("source_feature_id"),
                result.getString("confidence_reason"),
                result.getString("candidate_evidence"),
                result.getString("resolver_version"),
                result.getString("source_dataset_sha256").trim()),
                inputFingerprint, RESOLVER, SOURCE_DATASET, properties.getDatasetVersion(),
                properties.getInvalidResultRecheckVersion(), properties.getInvalidResultRecheckVersion());
        return rows.isEmpty() ? null : rows.get(0);
    }

    private CacheRecord persistCache(
            String inputFingerprint,
            Candidate candidate,
            RgzParcelResult fetched) {
        Instant now = Instant.now(clock);
        UUID geometryId = null;
        if (fetched.status() == RgzParcelResult.Status.RESOLVED) {
            geometryId = UUID.nameUUIDFromBytes(
                    ("rgz-geometry:" + fetched.rawResponseSha256())
                            .getBytes(StandardCharsets.UTF_8));
            jdbc.update("""
                    INSERT INTO spatial_resolution_geometries (
                        id, source_geometry, source_crs_authority, source_crs_code,
                        source_crs_definition, canonical_geometry,
                        original_geometry_valid, make_valid_applied, make_valid_reason
                    ) VALUES (
                        ?, ST_SetSRID(ST_GeomFromGeoJSON(?), 4326), 'EPSG', 4326,
                        'EPSG:4326', ST_SetSRID(ST_GeomFromGeoJSON(?), 4326),
                        TRUE, FALSE, NULL
                    ) ON CONFLICT (id) DO NOTHING
                    """, geometryId, fetched.geometryJson(), fetched.geometryJson());
        }

        String evidenceJson = evidenceJson(fetched, candidate);
        UUID cacheId = UUID.nameUUIDFromBytes(
                ("rgz-cache:" + inputFingerprint + (properties.getInvalidResultRecheckVersion().isEmpty()
                        ? "" : ":recheck:" + properties.getInvalidResultRecheckVersion()))
                        .getBytes(StandardCharsets.UTF_8));
        jdbc.update("""
                INSERT INTO location_resolution_cache_records (
                    id, resolver, resolver_version, input_fingerprint,
                    source_dataset, source_dataset_version, source_dataset_sha256,
                    source_feature_id, resolution_status, location_precision,
                    geometry_id, confidence_reason, candidate_evidence,
                    member_point_count, resolved_at, cached_at, expires_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), NULL, ?, ?, NULL)
                ON CONFLICT DO NOTHING
                """,
                cacheId, RESOLVER, RESOLVER_VERSION, inputFingerprint,
                SOURCE_DATASET, properties.getDatasetVersion(), properties.getCapabilitiesSha256(),
                fetched.sourceFeatureId(), fetched.status().name(),
                fetched.status() == RgzParcelResult.Status.RESOLVED ? "PARCEL" : "NONE",
                geometryId, fetched.reason(), evidenceJson,
                databaseTime(now), databaseTime(now));
        jdbc.update("""
                INSERT INTO rgz_parcel_cache_keys (input_fingerprint, cache_record_id)
                VALUES (?, ?)
                ON CONFLICT (input_fingerprint) DO UPDATE SET cache_record_id = EXCLUDED.cache_record_id
                WHERE ? <> '' AND EXISTS (
                    SELECT 1 FROM location_resolution_cache_records old_cache
                     WHERE old_cache.id = rgz_parcel_cache_keys.cache_record_id
                       AND old_cache.resolution_status = 'INVALID'
                       AND old_cache.candidate_evidence ->> 'invalidResultRecheckVersion' IS DISTINCT FROM ?
                )
                """, inputFingerprint, cacheId,
                properties.getInvalidResultRecheckVersion(), properties.getInvalidResultRecheckVersion());
        CacheRecord persisted = cache(inputFingerprint);
        if (persisted == null) {
            throw new IllegalStateException("RGZ cache result was not persisted");
        }
        if (persisted.status() == RgzParcelResult.Status.RESOLVED) {
            attachParcelIdentity(candidate);
        }
        return persisted;
    }

    private void attachParcelIdentity(Candidate candidate) {
        // #33 can run independently while HTTP is in flight. Lock its current
        // pointer until the attach/selection transaction commits; do not restore
        // an obsolete KO merely because valid geometry arrived late.
        if (jdbc.queryForList("""
                SELECT current_match.reference_id
                  FROM current_property_reference_ko_matches current_match
                  JOIN property_reference_ko_match_results result
                    ON result.reference_id = current_match.reference_id
                   AND result.input_fingerprint = current_match.input_fingerprint
                 WHERE current_match.reference_id = ? AND current_match.input_fingerprint = ?
                   AND result.status = 'MATCHED'
                   AND result.reconciliation_status <> 'STRUCTURED_ONLY'
                 FOR SHARE OF current_match
                """, UUID.class, candidate.referenceId(), candidate.koMatchInputFingerprint()).isEmpty()) {
            return;
        }
        String koCode = ParcelIdentityNormalizer.canonicalKoCode(candidate.koCode());
        String parcelNumber = ParcelIdentityNormalizer.canonicalParcelNumber(candidate.parcelNumber());
        List<Long> inserted = jdbc.query("""
                INSERT INTO parcel_identities (ko_code, canonical_parcel_number)
                VALUES (?, ?)
                ON CONFLICT (ko_code, canonical_parcel_number) DO NOTHING
                RETURNING id
                """, (result, row) -> result.getLong(1), koCode, parcelNumber);
        Long identityId = inserted.isEmpty()
                ? jdbc.queryForObject("""
                        SELECT id FROM parcel_identities
                         WHERE ko_code = ? AND canonical_parcel_number = ?
                        """, Long.class, koCode, parcelNumber)
                : inserted.get(0);
        int updated = jdbc.update("""
                UPDATE property_references
                   SET ko_code = ?, parcel_identity_id = ?
                 WHERE id = ? AND canonical_parcel_number = ?
                   AND (NOT user_reviewed OR ko_code IS NULL OR ko_code = ?)
                """, koCode, identityId, candidate.referenceId(), parcelNumber, koCode);
        if (updated != 1) {
            throw new IllegalStateException("RGZ parcel identity no longer matches its reference");
        }
    }

    private void createAttempt(
            Candidate candidate,
            String inputFingerprint,
            CacheRecord cached,
            RgzParcelResult fetched,
            UUID enrichmentRunId) {
        Instant now = Instant.now(clock);
        RgzParcelResult.Status status = cached != null ? cached.status() : fetched.status();
        String reason = cached != null ? cached.reason() : fetched.reason();
        String evidence = cached != null
                ? cached.evidenceJson()
                : evidenceJson(fetched, candidate);
        UUID attemptId = UUID.randomUUID();
        UUID geometryId = cached == null ? null : cached.geometryId();
        jdbc.update("""
                INSERT INTO location_resolution_attempts (
                    id, property_reference_id, used_cache_record_id, enrichment_run_id,
                    resolver, resolver_version, input_fingerprint,
                    source_dataset, source_dataset_version, source_dataset_sha256,
                    source_feature_id, resolution_status, location_precision,
                    geometry_id, confidence_reason, candidate_evidence,
                    member_point_count, attempted_at, completed_at, resolved_at,
                    upstream_ko_match_input_fingerprint
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb),
                          NULL, ?, ?, ?, ?)
                """,
                attemptId, candidate.referenceId(), cached == null ? null : cached.id(),
                enrichmentRunId,
                RESOLVER, cached == null ? RESOLVER_VERSION : cached.resolverVersion(), inputFingerprint,
                SOURCE_DATASET, properties.getDatasetVersion(),
                cached == null ? properties.getCapabilitiesSha256() : cached.datasetSha256(),
                cached == null ? fetched.sourceFeatureId() : cached.sourceFeatureId(),
                status.name(), status == RgzParcelResult.Status.RESOLVED ? "PARCEL" : "NONE",
                geometryId, reason, evidence,
                databaseTime(now), databaseTime(now),
                status == RgzParcelResult.Status.RESOLVED ? databaseTime(now) : null,
                candidate.koMatchInputFingerprint());
        if (status == RgzParcelResult.Status.RESOLVED) {
            jdbc.update("""
                    INSERT INTO current_location_resolutions (
                        property_reference_id, resolution_attempt_id, selected_at, selection_reason
                    ) VALUES (?, ?, ?, 'exact automatic RGZ KO and parcel match')
                    ON CONFLICT (property_reference_id) DO UPDATE SET
                        resolution_attempt_id = EXCLUDED.resolution_attempt_id,
                        selected_at = EXCLUDED.selected_at,
                        selection_reason = EXCLUDED.selection_reason
                    """, candidate.referenceId(), attemptId, databaseTime(now));
        }
    }

    private void useCache(
            Candidate candidate,
            String inputFingerprint,
            CacheRecord cached,
            UUID enrichmentRunId) {
        List<UUID> existing = jdbc.query("""
                SELECT id FROM location_resolution_attempts
                 WHERE property_reference_id = ? AND used_cache_record_id = ?
                   AND upstream_ko_match_input_fingerprint = ?
                   AND resolver = ? AND input_fingerprint = ?
                 ORDER BY completed_at DESC, id LIMIT 1
                """, (result, row) -> result.getObject("id", UUID.class),
                candidate.referenceId(), cached.id(), candidate.koMatchInputFingerprint(),
                RESOLVER, inputFingerprint);
        if (existing.isEmpty()) {
            createAttempt(candidate, inputFingerprint, cached, null, enrichmentRunId);
        } else if (cached.status() == RgzParcelResult.Status.RESOLVED) {
            selectCurrent(candidate.referenceId(), existing.get(0));
        }
    }

    private void selectCurrent(UUID referenceId, UUID attemptId) {
        Instant now = Instant.now(clock);
        jdbc.update("""
                INSERT INTO current_location_resolutions (
                    property_reference_id, resolution_attempt_id, selected_at, selection_reason
                ) VALUES (?, ?, ?, 'exact automatic RGZ KO and parcel match')
                ON CONFLICT (property_reference_id) DO UPDATE SET
                    resolution_attempt_id = EXCLUDED.resolution_attempt_id,
                    selected_at = EXCLUDED.selected_at,
                    selection_reason = EXCLUDED.selection_reason
                WHERE current_location_resolutions.resolution_attempt_id
                      IS DISTINCT FROM EXCLUDED.resolution_attempt_id
                """, referenceId, attemptId, databaseTime(now));
    }

    private synchronized Reservation reserveLogicalLookup(
            EnrichmentWorkItem item,
            String inputFingerprint) {
        if (item.enrichmentRunId() != null) {
            List<Integer> claims = jdbc.query("""
                    INSERT INTO rgz_enrichment_run_lookup_claims (
                        enrichment_run_id, input_fingerprint, source_dataset_version
                    ) VALUES (?, ?, ?)
                    ON CONFLICT (enrichment_run_id, input_fingerprint) DO NOTHING
                    RETURNING 1
                    """, (result, row) -> result.getInt(1),
                    item.enrichmentRunId(), inputFingerprint, properties.getDatasetVersion());
            if (claims.isEmpty()) {
                return Reservation.ALREADY_CLAIMED;
            }
            List<Integer> reserved = jdbc.query("""
                    INSERT INTO rgz_enrichment_run_usage (
                        enrichment_run_id, logical_lookup_count, updated_at
                    ) VALUES (?, 1, CURRENT_TIMESTAMP)
                    ON CONFLICT (enrichment_run_id) DO UPDATE SET
                        logical_lookup_count = rgz_enrichment_run_usage.logical_lookup_count + 1,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE rgz_enrichment_run_usage.logical_lookup_count < ?
                    RETURNING logical_lookup_count
                    """, (result, row) -> result.getInt(1),
                    item.enrichmentRunId(), properties.getMaxLogicalLookupsPerRun());
            return reserved.isEmpty() ? Reservation.CEILING_REACHED : Reservation.RESERVED;
        }
        UUID runKey = UUID.nameUUIDFromBytes(("direct-rgz-run:" + item.workKeySha256())
                .getBytes(StandardCharsets.UTF_8));
        DirectRunUsage usage = runLookups.computeIfAbsent(runKey, ignored -> new DirectRunUsage());
        if (!usage.claimedFingerprints.add(inputFingerprint)) {
            return Reservation.ALREADY_CLAIMED;
        }
        if (usage.count >= properties.getMaxLogicalLookupsPerRun()) {
            return Reservation.CEILING_REACHED;
        }
        usage.count++;
        while (runLookups.size() > MAX_TRACKED_RUNS) {
            runLookups.remove(runLookups.keySet().iterator().next());
        }
        return Reservation.RESERVED;
    }

    private <T> T inTransaction(Supplier<T> work) {
        T result = transactions.execute(status -> work.get());
        if (result == null) {
            throw new IllegalStateException("RGZ transaction returned no result");
        }
        return result;
    }

    private String evidenceJson(RgzParcelResult result, Candidate candidate) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        for (String key : List.of("schemaVersion", "reason", "returnedKoCode", "returnedParcelNumber",
                "geometryType", "areaSquareMetres", "sourceProjection", "scale",
                "rawResponseSha256", "physicalAttempts", "retrievedAt")) {
            if (result.evidence().containsKey(key)) {
                evidence.put(key, result.evidence().get(key));
            }
        }
        evidence.put("requestedKoCode", candidate.koCode());
        evidence.put("requestedParcelNumber", candidate.parcelNumber());
        evidence.put("wfsVersion", "2.0.0");
        evidence.put("decisionVersion", DECISION_VERSION);
        evidence.put("accessMode", properties.getAccessMode());
        evidence.put("featureType", properties.getFeatureType());
        evidence.put("datasetVersion", properties.getDatasetVersion());
        evidence.put("datasetVersionPolicy", properties.datasetVersionPolicy());
        evidence.put("invalidResultRecheckVersion", properties.getInvalidResultRecheckVersion());
        if (properties.discoveredContract() != null) {
            evidence.put("sourceContractObservedAt", properties.discoveredContract().observedAt().toString());
        }
        evidence.put("capabilitiesSha256", properties.getCapabilitiesSha256());
        evidence.put("schemaSha256", properties.getSchemaSha256());
        evidence.put("propertyWhitelistVersion", "issue-41-rgz-parcel-v1");
        return AuctionSourceCanonicalJson.write(objectMapper.valueToTree(evidence));
    }

    private static RgzParcelResult error(String reason) {
        return new RgzParcelResult(
                RgzParcelResult.Status.ERROR,
                reason,
                null, null, null, null, null, null, null, 0,
                Map.of("schemaVersion", "rgz-parcel-evidence-v1", "reason", reason));
    }

    private static OffsetDateTime databaseTime(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private record Candidate(
            UUID referenceId,
            String koCode,
            String parcelNumber,
            String koMatchInputFingerprint) {
    }

    private record CacheRecord(
            UUID id,
            RgzParcelResult.Status status,
            UUID geometryId,
            String sourceFeatureId,
            String reason,
            String evidenceJson,
            String resolverVersion,
            String datasetSha256) {
    }

    private record ReferenceResult(
            UUID referenceId,
            String inputFingerprint,
            String koMatchInputFingerprint,
            RgzParcelResult.Status status,
            boolean cacheHit) {
    }

    private record LookupPreparation(ReferenceResult completedResult) {
    }

    private enum Reservation {
        RESERVED,
        ALREADY_CLAIMED,
        CEILING_REACHED
    }

    private static final class DirectRunUsage {

        private int count;
        private final Set<String> claimedFingerprints = new LinkedHashSet<>();
    }

    public record AuctionResult(
            long candidateCount,
            long resolvedCount,
            long cacheHitCount,
            String evidenceSha256) {

        static AuctionResult disabled(long auctionId) {
            return new AuctionResult(
                    0, 0, 0,
                    EnrichmentHashing.sha256(
                            RESOLVER_VERSION, Long.toString(auctionId), "RGZ_DISABLED"));
        }
    }
}
