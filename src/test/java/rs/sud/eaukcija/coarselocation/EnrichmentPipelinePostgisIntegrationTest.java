package rs.sud.eaukcija.coarselocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;

import rs.sud.eaukcija.client.EAukcijaClient;
import rs.sud.eaukcija.enrichment.EnrichmentHashing;
import rs.sud.eaukcija.enrichment.EnrichmentInputSnapshot;
import rs.sud.eaukcija.enrichment.EnrichmentItemResult;
import rs.sud.eaukcija.enrichment.EnrichmentPipeline;
import rs.sud.eaukcija.enrichment.EnrichmentRunClaim;
import rs.sud.eaukcija.enrichment.EnrichmentRunStatus;
import rs.sud.eaukcija.enrichment.EnrichmentRunView;
import rs.sud.eaukcija.enrichment.EnrichmentService;
import rs.sud.eaukcija.enrichment.EnrichmentStateStatus;
import rs.sud.eaukcija.enrichment.EnrichmentVersions;
import rs.sud.eaukcija.enrichment.EnrichmentWorkItem;
import rs.sud.eaukcija.komatching.KoDictionaryTestArtifact;
import rs.sud.eaukcija.snapshot.AuctionSourceCanonicalJson;
import rs.sud.eaukcija.testsupport.PostgisTestContainer;

/** Real-PostGIS proof that all five production stages are local and idempotent. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class EnrichmentPipelinePostgisIntegrationTest {

    @ServiceConnection(name = "postgresql")
    static final PostgreSQLContainer<?> POSTGIS = PostgisTestContainer.shared();

    private static final Path CENTROIDS = createCentroids();
    private static final Path DICTIONARY = createDictionary();
    private static final UUID SOURCE_RUN =
            UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final String TAXONOMY_SHA256 = "f".repeat(64);

    @DynamicPropertySource
    static void localArtifacts(DynamicPropertyRegistry registry) {
        registry.add("coarse.location.centroid-directory", CENTROIDS::toString);
        registry.add("ko.structured-match.dictionary-directory", DICTIONARY::toString);
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EnrichmentPipeline pipeline;

    @Autowired
    private EnrichmentService service;

    @MockitoBean
    private EAukcijaClient sourceClient;

    @BeforeEach
    @AfterEach
    void resetPopulation() {
        jdbc.execute("""
                TRUNCATE TABLE
                    enrichment_run_items, enrichment_state, enrichment_runs,
                    auction_enrichment_snapshot_observations,
                    auction_enrichment_input_snapshots,
                    sync_enrichment_queue, sync_run_listing_quarantines,
                    sync_run_detail_quarantines, sync_run_auction_observations,
                    auction_source_category_memberships, sync_run_errors,
                    sync_run_child_results, sync_run_root_results,
                    structured_ko_match_runs, coarse_location_resolution_runs,
                    auctions, sync_runs, eaukcija_taxonomies,
                    parcel_identities, spatial_resolution_geometries,
                    location_resolution_cache_records
                RESTART IDENTITY CASCADE
                """);
        jdbc.update("""
                UPDATE enrichment_control
                   SET paused = FALSE, changed_at = CURRENT_TIMESTAMP,
                       change_code = 'TEST_RESET'
                 WHERE singleton
                """);
        jdbc.update("""
                INSERT INTO sync_runs (
                    id, idempotency_key_sha256, trigger_kind, status, stage,
                    started_at, heartbeat_at, configured_roots, page_size
                ) VALUES (?, ?, 'MANUAL', 'RUNNING', 'PROMOTING',
                          CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '[7]'::jsonb, 3000)
                """, SOURCE_RUN, EnrichmentHashing.sha256("direct-pipeline-source-run"));
    }

    @Test
    void unchangedProductionReplayWritesNoNewRowsAndMakesZeroSourceCalls() {
        insertAuction(29_001L, "ГРАД", "Насеље Б", "Општина Б-град");
        EnrichmentWorkItem item = item(
                29_001L, "ГРАД", "Насеље Б", "Општина Б-град", "snapshot-1");

        EnrichmentVersions versions = pipeline.activeVersions();
        EnrichmentItemResult first = pipeline.process(item);
        String rowsAfterFirst = derivedRows(29_001L);
        EnrichmentItemResult replay = pipeline.process(item);

        assertThat(versions).isEqualTo(pipeline.activeVersions());
        assertThat(versions.parserVersion()).isEqualTo("property-reference-v1");
        assertThat(first.status()).isEqualTo(EnrichmentStateStatus.SUCCEEDED);
        assertThat(replay).isEqualTo(first);
        assertThat(derivedRows(29_001L)).isEqualTo(rowsAfterFirst);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM property_references WHERE auction_id = 29001
                """, Long.class)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM auction_structured_ko_matches WHERE auction_id = 29001
                """, Long.class)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM location_resolution_attempts attempt
                  JOIN property_references reference ON reference.id = attempt.property_reference_id
                 WHERE reference.auction_id = 29001
                """, Long.class)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT attempt.location_precision
                  FROM current_location_resolutions current
                  JOIN property_references reference ON reference.id = current.property_reference_id
                  JOIN location_resolution_attempts attempt ON attempt.id = current.resolution_attempt_id
                 WHERE reference.auction_id = 29001
                """, String.class)).isEqualTo("CADASTRAL_MUNICIPALITY");
        verifyNoInteractions(sourceClient);
    }

    @Test
    void productionKoStageMatchesCurrentTextReferencesAndObservesTheirImmutableResultsOnce() {
        insertAuction(29_004L, "ГРАД", "Насеље Б", "Општина Б-град");
        EnrichmentWorkItem base = itemWithDescription(
                29_004L,
                "ГРАД",
                "Насеље Б",
                "Општина Б-град",
                "КО Grad; парцела број 1572",
                "snapshot-text-ko");
        UUID runId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO enrichment_runs (
                    id, idempotency_key_sha256, trigger_kind, status,
                    started_at, heartbeat_at, parser_version, resolver_version,
                    dataset_version, max_items, candidate_count
                ) VALUES (?, ?, 'MANUAL', 'RUNNING', ?, ?, ?, ?, ?, 1, 1)
                """,
                runId,
                EnrichmentHashing.sha256("issue-33-observation", runId.toString()),
                now,
                now,
                pipeline.activeVersions().parserVersion(),
                pipeline.activeVersions().resolverVersion(),
                pipeline.activeVersions().datasetVersion());
        jdbc.update("""
                INSERT INTO enrichment_run_items (
                    run_id, ordinal, auction_id, work_key_sha256,
                    attempt_number, status, started_at
                ) VALUES (?, 1, ?, ?, 1, 'RUNNING', ?)
                """, runId, base.auctionId(), base.workKeySha256(), now);
        EnrichmentWorkItem observed = base.forRun(runId);

        EnrichmentItemResult first = pipeline.process(observed);
        EnrichmentItemResult replay = pipeline.process(observed);

        assertThat(replay).isEqualTo(first);
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                  FROM property_reference_ko_match_results result
                 WHERE result.auction_id = 29004
                """, Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                  FROM property_reference_ko_match_observations
                 WHERE enrichment_run_id = ? AND auction_id = 29004
                """, Long.class, runId)).isEqualTo(2);
        assertThat(jdbc.queryForList("""
                SELECT result.status
                  FROM current_property_reference_ko_matches current_match
                  JOIN property_reference_ko_match_results result
                    ON result.reference_id = current_match.reference_id
                   AND result.input_fingerprint = current_match.input_fingerprint
                 WHERE result.auction_id = 29004
                 ORDER BY result.reference_id
                """, String.class)).containsOnly("MATCHED");
        assertThat(jdbc.queryForList("""
                SELECT ko_code FROM property_references
                 WHERE auction_id = 29004 AND reference_type <> 'STRUCTURED_LOCATION'
                 ORDER BY id
                """, String.class)).containsExactly("300002", "300002");
        verifyNoInteractions(sourceClient);
    }

    @Test
    void verifiedParcelEvidenceWinsWithoutBeingDowngradedByTheFallbackStage() {
        insertAuction(29_002L, "ГРАД", "Насеље Б", "Општина Б-град");
        EnrichmentWorkItem initial = item(
                29_002L, "ГРАД", "Насеље Б", "Општина Б-град", "snapshot-2");
        assertThat(pipeline.process(initial).status()).isEqualTo(EnrichmentStateStatus.SUCCEEDED);
        UUID referenceId = jdbc.queryForObject("""
                SELECT id FROM property_references WHERE auction_id = 29002
                """, UUID.class);
        UUID parcelAttempt = installVerifiedParcel(referenceId);
        EnrichmentWorkItem withParcel = item(
                29_002L, "ГРАД", "Насеље Б", "Општина Б-град", "snapshot-2-parcel");

        EnrichmentItemResult first = pipeline.process(withParcel);
        EnrichmentItemResult replay = pipeline.process(withParcel);

        assertThat(first.status()).isEqualTo(EnrichmentStateStatus.SUCCEEDED);
        assertThat(replay).isEqualTo(first);
        assertThat(jdbc.queryForObject("""
                SELECT resolution_attempt_id
                  FROM current_location_resolutions
                 WHERE property_reference_id = ?
                """, UUID.class, referenceId)).isEqualTo(parcelAttempt);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM location_resolution_attempts
                 WHERE property_reference_id = ? AND location_precision = 'PARCEL'
                """, Long.class, referenceId)).isOne();
        verifyNoInteractions(sourceClient);
    }

    @Test
    void unresolvedAmbiguousKoRemainsAmbiguousRatherThanBeingGuessed() {
        insertAuction(29_003L, "ГРАД", "Непознато", null);
        EnrichmentItemResult result = pipeline.process(
                item(29_003L, "ГРАД", "Непознато", null, "snapshot-3"));

        assertThat(result.status()).isEqualTo(EnrichmentStateStatus.AMBIGUOUS);
        assertThat(jdbc.queryForObject("""
                SELECT status FROM auction_structured_ko_matches WHERE auction_id = 29003
                """, String.class)).isEqualTo("AMBIGUOUS");
        assertThat(jdbc.queryForObject("""
                SELECT attempt.location_precision
                  FROM current_location_resolutions current
                  JOIN property_references reference ON reference.id = current.property_reference_id
                  JOIN location_resolution_attempts attempt ON attempt.id = current.resolution_attempt_id
                 WHERE reference.auction_id = 29003
                """, String.class)).isEqualTo("NONE");
        verifyNoInteractions(sourceClient);
    }

    @Test
    void coldProductionRunIsolatesOneRealStageFailureAndCompletesTheOtherSixHundred()
            throws Exception {
        seedAcceptedPopulation(601, 30_000L, 30_333L);

        long started = System.nanoTime();
        EnrichmentRunClaim claim = service.startManual(UUID.randomUUID());
        EnrichmentRunView completed = awaitTerminal(claim.runId());
        long durationMillis = (System.nanoTime() - started) / 1_000_000L;

        assertThat(completed.status()).isEqualTo(EnrichmentRunStatus.PARTIAL);
        assertThat(completed.candidateCount()).isEqualTo(601);
        assertThat(completed.attemptedCount()).isEqualTo(601);
        assertThat(completed.succeededCount()).isEqualTo(600);
        assertThat(completed.permanentFailureCount()).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT last_stage FROM enrichment_state WHERE auction_id = 30333
                """, String.class)).isEqualTo("ADDRESS_FALLBACK");
        assertThat(jdbc.queryForObject("""
                SELECT error_message FROM enrichment_state WHERE auction_id = 30333
                """, String.class)).isEqualTo("MATCHED_KO_CENTROID_MISSING");
        assertThat(durationMillis).isLessThan(30L * 60L * 1_000L);

        EnrichmentRunClaim unchanged = service.startManual(UUID.randomUUID());
        EnrichmentRunView replay = awaitTerminal(unchanged.runId());
        assertThat(replay.status()).isEqualTo(EnrichmentRunStatus.SUCCEEDED);
        assertThat(replay.candidateCount()).isZero();
        assertThat(replay.attemptedCount()).isZero();
        verifyNoInteractions(sourceClient);
        System.out.printf("ISSUE_29_COLD_REPROCESS auctions=601 succeeded=600 failed=1 duration_ms=%d%n",
                durationMillis);
    }

    private void insertAuction(long id, String cadastral, String place, String municipality) {
        jdbc.update("""
                INSERT INTO auctions (
                    id, auction_number, cadastral, place_name, municipality,
                    first_sale, details_fetched
                ) VALUES (?, ?, ?, ?, ?, FALSE, TRUE)
                """, id, "N" + id, cadastral, place, municipality);
    }

    private EnrichmentWorkItem item(
            long auctionId,
            String cadastral,
            String place,
            String municipality,
            String salt) {
        String snapshot = EnrichmentHashing.sha256(salt);
        String dependency = EnrichmentHashing.sha256("dependency", salt);
        ObjectNode source = sourcePayload(auctionId, cadastral, place, municipality, salt);
        String sourceSha256 = sourceSha256(source);
        jdbc.update("""
                INSERT INTO auction_source_snapshots (
                    auction_id, content_sha256, schema_version, minimization_policy_version,
                    listing_endpoint, detail_endpoint, canonical_payload,
                    fetched_at, listing_fetched_at, detail_fetched_at,
                    source_start_at, source_end_at, ingest_run_id
                ) VALUES (?, ?, 'eaukcija-source-snapshot-v1', 'eaukcija-minimization-v1',
                          '/api/auction/search', ?, ?::jsonb,
                          CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                          CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?)
                ON CONFLICT (auction_id, content_sha256) DO NOTHING
                """, auctionId, sourceSha256, "/api/auction/" + auctionId,
                AuctionSourceCanonicalJson.write(source), SOURCE_RUN);
        jdbc.update("""
                UPDATE auctions SET current_source_snapshot_sha256 = ? WHERE id = ?
                """, sourceSha256, auctionId);
        ObjectNode input = objectMapper.createObjectNode()
                .put("schemaVersion", EnrichmentInputSnapshot.SCHEMA_VERSION)
                .put("sourceSnapshotSha256", sourceSha256)
                .put("auctionId", auctionId)
                .put("cadastral", cadastral)
                .put("placeName", place)
                .put("description", "Synthetic fixture " + salt)
                .putNull("shortDescription");
        if (municipality == null) {
            input.putNull("municipality");
        } else {
            input.put("municipality", municipality);
        }
        snapshot = EnrichmentHashing.sha256(json(input));
        jdbc.update("""
                INSERT INTO auction_enrichment_input_snapshots (
                    auction_id, snapshot_sha256, canonical_input
                ) VALUES (?, ?, ?::jsonb)
                ON CONFLICT (auction_id, snapshot_sha256) DO NOTHING
                """, auctionId, snapshot, json(input));
        jdbc.update("""
                UPDATE auctions SET current_enrichment_snapshot_sha256 = ? WHERE id = ?
                """, snapshot, auctionId);
        return new EnrichmentWorkItem(
                auctionId,
                SOURCE_RUN,
                snapshot,
                dependency,
                EnrichmentHashing.sha256("work", Long.toString(auctionId), snapshot, dependency),
                input);
    }

    private EnrichmentWorkItem itemWithDescription(
            long auctionId,
            String cadastral,
            String place,
            String municipality,
            String description,
            String salt) {
        String dependency = EnrichmentHashing.sha256("dependency", salt);
        ObjectNode source = sourcePayload(auctionId, cadastral, place, municipality, salt);
        ((ObjectNode) source.path("detail")).put("Description", description);
        String sourceSha256 = sourceSha256(source);
        jdbc.update("""
                INSERT INTO auction_source_snapshots (
                    auction_id, content_sha256, schema_version, minimization_policy_version,
                    listing_endpoint, detail_endpoint, canonical_payload,
                    fetched_at, listing_fetched_at, detail_fetched_at,
                    source_start_at, source_end_at, ingest_run_id
                ) VALUES (?, ?, 'eaukcija-source-snapshot-v1', 'eaukcija-minimization-v1',
                          '/api/auction/search', ?, ?::jsonb,
                          CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                          CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?)
                """, auctionId, sourceSha256, "/api/auction/" + auctionId,
                AuctionSourceCanonicalJson.write(source), SOURCE_RUN);
        ObjectNode input = objectMapper.createObjectNode()
                .put("schemaVersion", EnrichmentInputSnapshot.SCHEMA_VERSION)
                .put("sourceSnapshotSha256", sourceSha256)
                .put("auctionId", auctionId)
                .put("cadastral", cadastral)
                .put("placeName", place)
                .put("municipality", municipality)
                .put("description", description)
                .putNull("shortDescription");
        String snapshot = EnrichmentHashing.sha256(json(input));
        jdbc.update("""
                INSERT INTO auction_enrichment_input_snapshots (
                    auction_id, snapshot_sha256, canonical_input
                ) VALUES (?, ?, ?::jsonb)
                """, auctionId, snapshot, json(input));
        jdbc.update("""
                UPDATE auctions SET current_source_snapshot_sha256 = ?,
                                    current_enrichment_snapshot_sha256 = ?
                 WHERE id = ?
                """, sourceSha256, snapshot, auctionId);
        return new EnrichmentWorkItem(
                auctionId,
                SOURCE_RUN,
                snapshot,
                dependency,
                EnrichmentHashing.sha256("work", Long.toString(auctionId), snapshot, dependency),
                input);
    }

    private ObjectNode sourcePayload(
            long auctionId,
            String cadastral,
            String place,
            String municipality,
            String salt) {
        ObjectNode source = objectMapper.createObjectNode();
        source.putObject("listing").putNull("ShortDescription");
        ObjectNode detail = source.putObject("detail");
        detail.put("Description", "Synthetic fixture " + salt);
        detail.putNull("ShortDescription");
        ObjectNode structuredPlace = detail.putObject("Place");
        putNullable(structuredPlace, "Cadastral", cadastral);
        putNullable(structuredPlace, "Name", place);
        putNullable(structuredPlace, "Municipality", municipality);
        detail.put("AuctionId", auctionId);
        return source;
    }

    private static void putNullable(ObjectNode node, String field, String value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private String json(ObjectNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static String sourceSha256(ObjectNode source) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    AuctionSourceCanonicalJson.write(source).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private UUID installVerifiedParcel(UUID referenceId) {
        UUID geometryId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO spatial_resolution_geometries (
                    id, source_geometry, source_crs_authority, source_crs_code,
                    original_geometry_valid, make_valid_applied
                ) VALUES (
                    ?, ST_GeomFromText('POLYGON((20 44,20.01 44,20.01 44.01,20 44.01,20 44))', 4326),
                    'EPSG', 4326, TRUE, FALSE
                )
                """, geometryId);
        UUID attemptId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("""
                INSERT INTO location_resolution_attempts (
                    id, property_reference_id, resolver, resolver_version,
                    input_fingerprint, source_dataset, source_dataset_version,
                    source_dataset_sha256, source_feature_id,
                    resolution_status, location_precision, geometry_id,
                    confidence_reason, candidate_evidence,
                    attempted_at, completed_at, resolved_at
                ) VALUES (
                    ?, ?, 'private-parcel-import', 'parcel-v1', ?,
                    'private-rgz-artifact', 'fixture-v1', ?, 'K100/1572',
                    'RESOLVED', 'PARCEL', ?, 'validated private fixture', '[]'::jsonb,
                    ?, ?, ?
                )
                """,
                attemptId,
                referenceId,
                "d".repeat(64),
                "e".repeat(64),
                geometryId,
                now,
                now,
                now);
        jdbc.update("""
                UPDATE current_location_resolutions
                   SET resolution_attempt_id = ?, selected_at = ?,
                       selection_reason = 'verified private parcel fixture'
                 WHERE property_reference_id = ?
                """, attemptId, now, referenceId);
        return attemptId;
    }

    private void seedAcceptedPopulation(int count, long firstAuctionId, long failingAuctionId) {
        Instant acceptedAt = Instant.parse("2026-08-24T12:00:00Z");
        UUID sourceRunId = UUID.randomUUID();
        jdbc.update("""
                UPDATE sync_runs
                   SET status = 'FAILED', stage = 'PROMOTING',
                       heartbeat_at = CURRENT_TIMESTAMP, finished_at = CURRENT_TIMESTAMP
                 WHERE id = ?
                """, SOURCE_RUN);
        jdbc.update("""
                INSERT INTO eaukcija_taxonomies (
                    tree_sha256, normalizer_version, canonical_tree, first_observed_at
                ) VALUES (?, 'test-taxonomy-v1', '[{"value":7,"children":[]}]'::jsonb, ?)
                """, TAXONOMY_SHA256, databaseTime(acceptedAt));
        jdbc.update("""
                INSERT INTO sync_runs (
                    id, idempotency_key_sha256, trigger_kind, status, stage,
                    started_at, heartbeat_at, configured_roots, page_size,
                    category_tree_sha256, category_tree_observed_at,
                    unique_auction_count
                ) VALUES (
                    ?, ?, 'MANUAL', 'RUNNING', 'PROMOTING', ?, ?, '[7]'::jsonb, 3000,
                    ?, ?, ?
                )
                """,
                sourceRunId,
                EnrichmentHashing.sha256("production-cold-source", sourceRunId.toString()),
                databaseTime(acceptedAt),
                databaseTime(acceptedAt),
                TAXONOMY_SHA256,
                databaseTime(acceptedAt),
                count);

        List<Object[]> auctionRows = new ArrayList<>(count);
        List<Object[]> observationRows = new ArrayList<>(count);
        List<Object[]> sourceSnapshotRows = new ArrayList<>(count);
        List<Object[]> sourceCurrentRows = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            long auctionId = firstAuctionId + index;
            boolean failure = auctionId == failingAuctionId;
            String cadastral = failure ? "ЧАЈЕТИНА" : "ГРАД";
            String place = failure ? "Насеље А" : "Насеље Б";
            String municipality = failure ? "Општина А" : "Општина Б-град";
            String listingHash = EnrichmentHashing.sha256("listing", Long.toString(auctionId));
            ObjectNode source = sourcePayload(
                    auctionId, cadastral, place, municipality, "cold-" + auctionId);
            String sourceSha256 = sourceSha256(source);
            auctionRows.add(new Object[]{
                    auctionId, "N" + auctionId, cadastral, place, municipality,
                    listingHash, sourceRunId
            });
            sourceSnapshotRows.add(new Object[]{
                    auctionId, sourceSha256, AuctionSourceCanonicalJson.write(source),
                    databaseTime(acceptedAt), databaseTime(acceptedAt),
                    databaseTime(acceptedAt), databaseTime(acceptedAt),
                    databaseTime(acceptedAt), sourceRunId
            });
            sourceCurrentRows.add(new Object[]{sourceSha256, auctionId});
            observationRows.add(new Object[]{
                    sourceRunId, auctionId, listingHash, sourceSha256
            });
        }
        jdbc.batchUpdate("""
                INSERT INTO auctions (
                    id, auction_number, cadastral, place_name, municipality,
                    first_sale, details_fetched, listing_fingerprint,
                    last_successful_sync_run_id
                ) VALUES (?, ?, ?, ?, ?, FALSE, TRUE, ?, ?)
                """, auctionRows);
        jdbc.batchUpdate("""
                INSERT INTO auction_source_snapshots (
                    auction_id, content_sha256, schema_version, minimization_policy_version,
                    listing_endpoint, detail_endpoint, canonical_payload,
                    fetched_at, listing_fetched_at, detail_fetched_at,
                    source_start_at, source_end_at, ingest_run_id
                ) VALUES (?, ?, 'eaukcija-source-snapshot-v1', 'eaukcija-minimization-v1',
                          '/api/auction/search', '/api/auction/detail', ?::jsonb,
                          ?, ?, ?, ?, ?, ?)
                """, sourceSnapshotRows);
        jdbc.batchUpdate("""
                UPDATE auctions SET current_source_snapshot_sha256 = ? WHERE id = ?
                """, sourceCurrentRows);
        jdbc.batchUpdate("""
                INSERT INTO sync_run_auction_observations (
                    run_id, auction_id, listing_fingerprint, detail_refreshed,
                    enrichment_eligible, enrichment_reason, source_snapshot_sha256
                ) VALUES (?, ?, ?, TRUE, TRUE, 'NEW', ?)
                """, observationRows);

        List<Object[]> snapshotRows = new ArrayList<>(count);
        List<Object[]> snapshotObservationRows = new ArrayList<>(count);
        List<Object[]> currentRows = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            long auctionId = firstAuctionId + index;
            boolean failure = auctionId == failingAuctionId;
            String cadastral = failure ? "ЧАЈЕТИНА" : "ГРАД";
            String place = failure ? "Насеље А" : "Насеље Б";
            String municipality = failure ? "Општина А" : "Општина Б-град";
            String sourceSha256 = sourceSha256(sourcePayload(
                    auctionId, cadastral, place, municipality, "cold-" + auctionId));
            ObjectNode canonical = objectMapper.createObjectNode()
                    .put("schemaVersion", EnrichmentInputSnapshot.SCHEMA_VERSION)
                    .put("sourceSnapshotSha256", sourceSha256)
                    .put("auctionId", auctionId)
                    .put("cadastral", cadastral)
                    .put("placeName", place)
                    .put("municipality", municipality)
                    .put("description", "Synthetic fixture cold-" + auctionId)
                    .putNull("shortDescription");
            String canonicalJson;
            try {
                canonicalJson = objectMapper.writeValueAsString(canonical);
            } catch (Exception failureToSerialize) {
                throw new IllegalStateException(failureToSerialize);
            }
            String snapshotHash = EnrichmentHashing.sha256(canonicalJson);
            snapshotRows.add(new Object[]{auctionId, snapshotHash, canonicalJson, databaseTime(acceptedAt)});
            snapshotObservationRows.add(new Object[]{
                    sourceRunId, auctionId, snapshotHash, databaseTime(acceptedAt)
            });
            currentRows.add(new Object[]{snapshotHash, auctionId});
        }
        jdbc.batchUpdate("""
                INSERT INTO auction_enrichment_input_snapshots (
                    auction_id, snapshot_sha256, canonical_input, created_at
                ) VALUES (?, ?, ?::jsonb, ?)
                """, snapshotRows);
        jdbc.update("""
                UPDATE sync_runs
                   SET status = 'SUCCEEDED', stage = 'COMPLETED',
                       heartbeat_at = ?, finished_at = ?
                 WHERE id = ?
                """, databaseTime(acceptedAt.plusSeconds(1)),
                databaseTime(acceptedAt.plusSeconds(1)), sourceRunId);
        jdbc.batchUpdate("""
                INSERT INTO auction_enrichment_snapshot_observations (
                    source_sync_run_id, auction_id, snapshot_sha256, observed_at
                ) VALUES (?, ?, ?, ?)
                """, snapshotObservationRows);
        jdbc.batchUpdate("""
                UPDATE auctions SET current_enrichment_snapshot_sha256 = ? WHERE id = ?
                """, currentRows);
    }

    private EnrichmentRunView awaitTerminal(UUID runId) throws InterruptedException {
        long deadline = System.nanoTime() + 60_000_000_000L;
        while (System.nanoTime() < deadline) {
            EnrichmentRunView run = service.findRun(runId).orElseThrow();
            if (run.status() != EnrichmentRunStatus.RUNNING) {
                return run;
            }
            Thread.sleep(25L);
        }
        throw new AssertionError("enrichment run did not finish within 60 seconds: " + runId);
    }

    private static OffsetDateTime databaseTime(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private String derivedRows(long auctionId) {
        return jdbc.queryForObject("""
                SELECT jsonb_build_object(
                    'reference', (
                        SELECT to_jsonb(reference) - 'created_at' - 'updated_at'
                          FROM property_references reference
                         WHERE reference.auction_id = auction.id
                    ),
                    'ko', (
                        SELECT to_jsonb(match) - 'resolved_at'
                          FROM auction_structured_ko_matches match
                         WHERE match.auction_id = auction.id
                    ),
                    'attempts', (
                        SELECT jsonb_agg(
                            to_jsonb(attempt)
                              - 'attempted_at' - 'completed_at' - 'resolved_at'
                            ORDER BY attempt.id
                        )
                          FROM property_references reference
                          JOIN location_resolution_attempts attempt
                            ON attempt.property_reference_id = reference.id
                         WHERE reference.auction_id = auction.id
                    ),
                    'current', (
                        SELECT to_jsonb(current) - 'selected_at'
                          FROM property_references reference
                          JOIN current_location_resolutions current
                            ON current.property_reference_id = reference.id
                         WHERE reference.auction_id = auction.id
                    )
                )::text
                  FROM auctions auction WHERE auction.id = ?
                """, String.class, auctionId);
    }

    private static Path createCentroids() {
        try {
            return CentroidTestArtifact.create(
                    Files.createTempDirectory("enrichment-centroids-it-"),
                    new ObjectMapper().findAndRegisterModules());
        } catch (Exception failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static Path createDictionary() {
        try {
            return KoDictionaryTestArtifact.create(
                    Files.createTempDirectory("enrichment-ko-it-"),
                    new ObjectMapper().findAndRegisterModules());
        } catch (Exception failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }
}
