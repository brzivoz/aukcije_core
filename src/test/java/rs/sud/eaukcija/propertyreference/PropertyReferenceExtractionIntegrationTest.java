package rs.sud.eaukcija.propertyreference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;

import rs.sud.eaukcija.enrichment.EnrichmentHashing;
import rs.sud.eaukcija.enrichment.EnrichmentWorkItem;
import rs.sud.eaukcija.testsupport.PostgisTestContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class PropertyReferenceExtractionIntegrationTest {

    @ServiceConnection(name = "postgresql")
    static final PostgreSQLContainer<?> POSTGIS = PostgisTestContainer.shared();

    private static final String SOURCE_SHA = "1".repeat(64);
    private static final String INPUT_SHA = "2".repeat(64);

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PropertyReferenceParser parser;

    @Autowired
    private PropertyReferenceExtractionRepository repository;

    private UUID sourceRunId;

    @BeforeEach
    void setUp() {
        clean();
        sourceRunId = seedSourceAndInput(19L, SOURCE_SHA, INPUT_SHA,
                "КО Долово; парцела број 870/2; ЛН 51", "КП број 871/2");
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    @Test
    void sameSnapshotAndParserAreIdempotentWithIdenticalRowsAndHashes() {
        EnrichmentWorkItem item = item(19L, sourceRunId, SOURCE_SHA, INPUT_SHA, null);
        PropertyReferenceParseResult parsed = parser.parse(item.canonicalInput());

        var first = repository.replace(item, parsed);
        String firstRows = currentRows(19L);
        OffsetDateTime firstSelectedAt = jdbc.queryForObject("""
                SELECT selected_at FROM current_property_reference_extractions
                 WHERE auction_id = 19
                """, OffsetDateTime.class);
        var replay = repository.replace(item, parsed);

        assertThat(replay).isEqualTo(first);
        assertThat(currentRows(19L)).isEqualTo(firstRows);
        assertThat(jdbc.queryForObject("""
                SELECT selected_at FROM current_property_reference_extractions
                 WHERE auction_id = 19
                """, OffsetDateTime.class)).isEqualTo(firstSelectedAt);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM property_reference_extraction_runs WHERE auction_id = 19",
                Long.class)).isOne();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM property_references WHERE auction_id = 19",
                Long.class)).isEqualTo(parsed.references().size());
        assertThat(first.resultSha256()).isEqualTo(parsed.outputSha256());
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE property_reference_extraction_runs
                   SET result_sha256 = repeat('f', 64) WHERE id = ?
                """, first.extractionRunId()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("property-reference extraction evidence is immutable");
    }

    @Test
    void sameParserChangedSnapshotsUseMembershipOrderAndRetainPriorRuns() {
        EnrichmentWorkItem originalItem = item(19L, sourceRunId, SOURCE_SHA, INPUT_SHA, null);
        var original = repository.replace(originalItem, parser.parse(originalItem.canonicalInput()));
        UUID structured = jdbc.queryForObject("""
                SELECT id FROM property_references
                 WHERE auction_id = 19 AND canonical_key = 'structured-place'
                """, UUID.class);
        jdbc.update("UPDATE property_references SET ko_code = 'K100' WHERE id = ?", structured);

        String shiftedSourceSha = "6".repeat(64);
        String shiftedInputSha = "7".repeat(64);
        String shiftedDescription = "парцела број 870/2";
        appendSourceAndInput(
                19L, sourceRunId, shiftedSourceSha, shiftedInputSha,
                shiftedDescription, null);
        EnrichmentWorkItem shiftedItem = item(
                19L, sourceRunId, shiftedSourceSha, shiftedInputSha,
                shiftedDescription, null, null);
        var shifted = repository.replace(
                shiftedItem, parser.parse(shiftedItem.canonicalInput()));

        assertThat(shifted.extractionRunId()).isNotEqualTo(original.extractionRunId());
        assertThat(jdbc.queryForList("""
                SELECT member.reference_order
                  FROM property_reference_extraction_memberships member
                 WHERE member.extraction_run_id = ?
                 ORDER BY member.reference_order
                """, Integer.class, shifted.extractionRunId())).containsExactly(0, 1);
        assertThat(jdbc.queryForObject("""
                SELECT reference.reference_order
                  FROM property_reference_extraction_memberships member
                  JOIN property_references reference ON reference.id = member.reference_id
                 WHERE member.extraction_run_id = ?
                   AND reference.canonical_key = 'parcel:DOLOVO:870/2'
                """, Integer.class, shifted.extractionRunId())).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT ko_code FROM property_references WHERE id = ?",
                String.class, structured)).isEqualTo("K100");

        String newSourceSha = "8".repeat(64);
        String newInputSha = "9".repeat(64);
        String newDescription = "парцела број 999/1";
        appendSourceAndInput(
                19L, sourceRunId, newSourceSha, newInputSha, newDescription, null);
        EnrichmentWorkItem newItem = item(
                19L, sourceRunId, newSourceSha, newInputSha,
                newDescription, null, null);
        var newest = repository.replace(newItem, parser.parse(newItem.canonicalInput()));

        assertThat(jdbc.queryForList("""
                SELECT member.reference_order
                  FROM property_reference_extraction_memberships member
                 WHERE member.extraction_run_id = ?
                 ORDER BY member.reference_order
                """, Integer.class, newest.extractionRunId())).containsExactly(0, 1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM property_references
                 WHERE auction_id = 19 AND parser_version = ? AND reference_order = 1
                """, Long.class, PropertyReferenceParser.VERSION)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM property_reference_extraction_runs WHERE auction_id = 19
                """, Long.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM property_reference_extraction_memberships
                 WHERE extraction_run_id = ?
                """, Long.class, original.extractionRunId()))
                .isEqualTo(original.selectedReferenceCount());
        assertThat(jdbc.queryForObject(
                "SELECT ko_code FROM property_references WHERE id = ?",
                String.class, structured)).isEqualTo("K100");

        String conflictSourceSha = "a".repeat(64);
        String conflictInputSha = "b".repeat(64);
        String conflictDescription = "КО Урсуле; парцела број 999/1";
        appendSourceAndInput(
                19L, sourceRunId, conflictSourceSha, conflictInputSha,
                conflictDescription, null);
        EnrichmentWorkItem conflictItem = item(
                19L, sourceRunId, conflictSourceSha, conflictInputSha,
                conflictDescription, null, null);
        repository.replace(conflictItem, parser.parse(conflictItem.canonicalInput()));

        assertThat(jdbc.queryForObject(
                "SELECT ko_code FROM property_references WHERE id = ?",
                String.class, structured)).isNull();
    }

    @Test
    void newParserVersionAtomicallyReplacesTheCurrentSetAndCarriesReviewedCorrections() {
        EnrichmentWorkItem item = item(19L, sourceRunId, SOURCE_SHA, INPUT_SHA, null);
        PropertyReferenceParseResult v1 = parser.parse(item.canonicalInput());
        var first = repository.replace(item, v1);
        UUID reviewedParcel = jdbc.queryForObject("""
                SELECT id FROM property_references
                 WHERE auction_id = 19 AND reference_type = 'PARCEL'
                 ORDER BY reference_order LIMIT 1
                """, UUID.class);
        jdbc.update("""
                UPDATE property_references
                   SET raw_ko = 'REVIEWED KO', normalized_ko = 'REVIEWED KO',
                       extraction_status = 'USER_CONFIRMED', user_reviewed = TRUE
                 WHERE id = ?
                """, reviewedParcel);

        PropertyReferenceParseResult v2 = new PropertyReferenceParseResult(
                "property-reference-v2-test",
                v1.references(),
                "3".repeat(64),
                v1.textReferenceCount(),
                v1.noStructuredReferenceCount(),
                v1.koConflictCount());
        var second = repository.replace(item, v2);

        assertThat(second.extractionRunId()).isNotEqualTo(first.extractionRunId());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM property_reference_extraction_runs WHERE auction_id = 19",
                Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT run.parser_version
                  FROM current_property_reference_extractions current_set
                  JOIN property_reference_extraction_runs run
                    ON run.id = current_set.extraction_run_id
                 WHERE current_set.auction_id = 19
                """, String.class)).isEqualTo("property-reference-v2-test");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM property_reference_extraction_memberships member
                 WHERE member.extraction_run_id = ? AND member.reference_id = ?
                """, Long.class, second.extractionRunId(), reviewedParcel)).isOne();
        assertThat(jdbc.queryForObject(
                "SELECT raw_ko FROM property_references WHERE id = ?",
                String.class, reviewedParcel)).isEqualTo("REVIEWED KO");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM property_reference_extraction_runs run,
                       jsonb_array_elements(run.result_json -> 'selectedReferences') selected
                 WHERE run.id = ?
                   AND selected ->> 'id' = ?
                   AND selected ->> 'raw_ko' = 'REVIEWED KO'
                   AND (selected ->> 'user_reviewed')::boolean
                """, Long.class, second.extractionRunId(), reviewedParcel.toString())).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT result_json ->> 'resultSha256'
                  FROM property_reference_extraction_runs WHERE id = ?
                """, String.class, first.extractionRunId())).isEqualTo(v1.outputSha256());
    }

    @Test
    void productionObservationAddsPerRunCountsAndFrozenQualityEvidenceExactlyOnce() {
        UUID enrichmentRunId = seedEnrichmentRun(19L, INPUT_SHA);
        EnrichmentWorkItem item = item(
                19L, sourceRunId, SOURCE_SHA, INPUT_SHA, enrichmentRunId);
        PropertyReferenceParseResult parsed = parser.parse(item.canonicalInput());

        var first = repository.replace(item, parsed);
        var replay = repository.replace(item, parsed);

        assertThat(replay).isEqualTo(first);
        assertThat(jdbc.queryForMap("""
                SELECT property_reference_count, text_reference_count,
                       property_reference_extraction_success_count,
                       property_reference_parse_failure_count,
                       no_structured_reference_count, ko_conflict_count,
                       property_reference_quality_corpus_version,
                       btrim(property_reference_quality_metrics_sha256) AS metrics_sha256
                  FROM enrichment_runs WHERE id = ?
                """, enrichmentRunId)).containsEntry(
                        "property_reference_extraction_success_count", 1L)
                .containsEntry("property_reference_parse_failure_count", 0L)
                .containsEntry(
                        "property_reference_count", (long) parsed.references().size())
                .containsEntry("text_reference_count", (long) parsed.textReferenceCount())
                .containsEntry("no_structured_reference_count", 0L)
                .containsEntry("ko_conflict_count", 0L)
                .containsEntry("property_reference_quality_corpus_version", "2026-09-02.2")
                .containsEntry("metrics_sha256",
                        "8468d6efe54cc3623c3eb3d161d583737e653a68ac854a924e82d5f4b90d3473");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM property_reference_extraction_observations
                 WHERE enrichment_run_id = ?
                """, Long.class, enrichmentRunId)).isOne();
    }

    private UUID seedSourceAndInput(
            long auctionId,
            String sourceSha,
            String inputSha,
            String description,
            String shortDescription) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UUID runId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO sync_runs (
                    id, idempotency_key_sha256, trigger_kind, status, stage,
                    started_at, heartbeat_at, configured_roots, page_size
                ) VALUES (?, ?, 'MANUAL', 'RUNNING', 'PROMOTING', ?, ?, '[7]'::jsonb, 3000)
                """, runId, EnrichmentHashing.sha256("source", runId.toString()), now, now);
        jdbc.update("""
                INSERT INTO auctions (id, auction_number, first_sale, details_fetched,
                                      last_successful_sync_run_id)
                VALUES (?, ?, FALSE, TRUE, ?)
                """, auctionId, "A-" + auctionId, runId);
        ObjectNode source = objectMapper.createObjectNode();
        source.putObject("listing").put("Id", auctionId);
        ObjectNode detail = source.putObject("detail");
        detail.put("Id", auctionId);
        detail.put("Description", description);
        detail.put("ShortDescription", shortDescription);
        ObjectNode place = detail.putObject("Place");
        place.put("Cadastral", "ДОЛОВО");
        place.put("Name", "Долово");
        place.put("Municipality", "Панчево");
        jdbc.update("""
                INSERT INTO auction_source_snapshots (
                    auction_id, content_sha256, schema_version, minimization_policy_version,
                    listing_endpoint, detail_endpoint, canonical_payload,
                    fetched_at, listing_fetched_at, detail_fetched_at,
                    source_start_at, source_end_at, ingest_run_id
                ) VALUES (?, ?, 'test-source-v1', 'test-policy-v1', 'listing', 'detail',
                          CAST(? AS jsonb), ?, ?, ?, ?, ?, ?)
                """, auctionId, sourceSha, source.toString(), now, now, now,
                now.minusDays(1), now.plusDays(1), runId);
        ObjectNode input = input(auctionId, sourceSha, description, shortDescription);
        jdbc.update("""
                INSERT INTO auction_enrichment_input_snapshots (
                    auction_id, snapshot_sha256, canonical_input
                ) VALUES (?, ?, CAST(? AS jsonb))
                """, auctionId, inputSha, input.toString());
        jdbc.update("""
                UPDATE auctions
                   SET current_source_snapshot_sha256 = ?,
                       current_enrichment_snapshot_sha256 = ?
                 WHERE id = ?
                """, sourceSha, inputSha, auctionId);
        return runId;
    }

    private UUID seedEnrichmentRun(long auctionId, String inputSha) {
        UUID runId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO enrichment_runs (
                    id, idempotency_key_sha256, trigger_kind, status,
                    started_at, heartbeat_at, parser_version, resolver_version,
                    dataset_version, max_items, candidate_count
                ) VALUES (?, ?, 'MANUAL', 'RUNNING', ?, ?, ?, 'resolver-test',
                          'dataset-test', 1, 1)
                """, runId, EnrichmentHashing.sha256("enrichment", runId.toString()),
                now, now, PropertyReferenceParser.VERSION);
        jdbc.update("""
                INSERT INTO enrichment_run_items (
                    run_id, ordinal, auction_id, work_key_sha256,
                    attempt_number, status, started_at
                ) VALUES (?, 1, ?, ?, 1, 'RUNNING', ?)
                """, runId, auctionId, EnrichmentHashing.sha256("work-item", runId.toString()), now);
        return runId;
    }

    private void appendSourceAndInput(
            long auctionId,
            UUID sourceRun,
            String sourceSha,
            String inputSha,
            String description,
            String shortDescription) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        ObjectNode source = objectMapper.createObjectNode();
        source.putObject("listing").put("Id", auctionId);
        ObjectNode detail = source.putObject("detail");
        detail.put("Id", auctionId);
        detail.put("Description", description);
        detail.put("ShortDescription", shortDescription);
        ObjectNode place = detail.putObject("Place");
        place.put("Cadastral", "ДОЛОВО");
        place.put("Name", "Долово");
        place.put("Municipality", "Панчево");
        jdbc.update("""
                INSERT INTO auction_source_snapshots (
                    auction_id, content_sha256, schema_version, minimization_policy_version,
                    listing_endpoint, detail_endpoint, canonical_payload,
                    fetched_at, listing_fetched_at, detail_fetched_at,
                    source_start_at, source_end_at, ingest_run_id
                ) VALUES (?, ?, 'test-source-v1', 'test-policy-v1', 'listing', 'detail',
                          CAST(? AS jsonb), ?, ?, ?, ?, ?, ?)
                """, auctionId, sourceSha, source.toString(), now, now, now,
                now.minusDays(1), now.plusDays(1), sourceRun);
        jdbc.update("""
                INSERT INTO auction_enrichment_input_snapshots (
                    auction_id, snapshot_sha256, canonical_input
                ) VALUES (?, ?, CAST(? AS jsonb))
                """, auctionId, inputSha,
                input(auctionId, sourceSha, description, shortDescription).toString());
        jdbc.update("""
                UPDATE auctions
                   SET current_source_snapshot_sha256 = ?,
                       current_enrichment_snapshot_sha256 = ?
                 WHERE id = ?
                """, sourceSha, inputSha, auctionId);
    }

    private EnrichmentWorkItem item(
            long auctionId,
            UUID sourceRun,
            String sourceSha,
            String inputSha,
            UUID enrichmentRun) {
        return item(
                auctionId, sourceRun, sourceSha, inputSha,
                "КО Долово; парцела број 870/2; ЛН 51", "КП број 871/2",
                enrichmentRun);
    }

    private EnrichmentWorkItem item(
            long auctionId,
            UUID sourceRun,
            String sourceSha,
            String inputSha,
            String description,
            String shortDescription,
            UUID enrichmentRun) {
        ObjectNode input = input(
                auctionId, sourceSha,
                description, shortDescription);
        return new EnrichmentWorkItem(
                auctionId,
                sourceRun,
                inputSha,
                "4".repeat(64),
                "5".repeat(64),
                input,
                enrichmentRun);
    }

    private ObjectNode input(
            long auctionId,
            String sourceSha,
            String description,
            String shortDescription) {
        return objectMapper.createObjectNode()
                .put("schemaVersion", "enrichment-location-input-v2")
                .put("sourceSnapshotSha256", sourceSha)
                .put("auctionId", auctionId)
                .put("placeName", "Долово")
                .put("municipality", "Панчево")
                .put("cadastral", "ДОЛОВО")
                .put("description", description)
                .put("shortDescription", shortDescription);
    }

    private String currentRows(long auctionId) {
        return jdbc.queryForObject("""
                SELECT jsonb_agg(to_jsonb(reference) ORDER BY member.reference_order)::text
                  FROM current_property_reference_extractions current_set
                  JOIN property_reference_extraction_memberships member
                    ON member.extraction_run_id = current_set.extraction_run_id
                  JOIN property_references reference ON reference.id = member.reference_id
                 WHERE current_set.auction_id = ?
                """, String.class, auctionId);
    }

    private void clean() {
        jdbc.execute("""
                TRUNCATE TABLE
                    property_reference_extraction_observations,
                    current_property_reference_extractions,
                    property_reference_extraction_memberships,
                    property_reference_extraction_runs,
                    enrichment_run_items, enrichment_state, enrichment_runs,
                    auction_enrichment_snapshot_observations,
                    auction_enrichment_input_snapshots,
                    sync_enrichment_queue, sync_run_listing_quarantines,
                    sync_run_detail_quarantines, sync_run_auction_observations,
                    auction_source_category_memberships, sync_run_errors,
                    sync_run_child_results, sync_run_root_results,
                    auctions, sync_runs, eaukcija_taxonomies
                RESTART IDENTITY CASCADE
                """);
    }
}
