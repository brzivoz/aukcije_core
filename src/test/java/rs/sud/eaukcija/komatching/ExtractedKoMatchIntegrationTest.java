package rs.sud.eaukcija.komatching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import rs.sud.eaukcija.enrichment.EnrichmentHashing;
import rs.sud.eaukcija.enrichment.EnrichmentInputSnapshot;
import rs.sud.eaukcija.enrichment.EnrichmentWorkItem;
import rs.sud.eaukcija.propertyreference.PropertyReferenceExtractionRepository;
import rs.sud.eaukcija.propertyreference.PropertyReferenceParser;
import rs.sud.eaukcija.testsupport.PostgisTestContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class ExtractedKoMatchIntegrationTest {

    @ServiceConnection(name = "postgresql")
    static final PostgreSQLContainer<?> POSTGIS = PostgisTestContainer.shared();

    private static final Path DICTIONARY = createDictionary();

    @DynamicPropertySource
    static void matcherProperties(DynamicPropertyRegistry registry) {
        registry.add("ko.structured-match.dictionary-directory", DICTIONARY::toString);
    }

    @Autowired
    private ExtractedKoMatchService service;

    @Autowired
    private PropertyReferenceParser parser;

    @Autowired
    private PropertyReferenceExtractionRepository references;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID sourceRunId;

    @BeforeEach
    void setUp() {
        clean();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        sourceRunId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO sync_runs (
                    id, idempotency_key_sha256, trigger_kind, status, stage,
                    started_at, heartbeat_at, configured_roots, page_size
                ) VALUES (?, ?, 'MANUAL', 'RUNNING', 'PROMOTING', ?, ?, '[7]'::jsonb, 3000)
                """, sourceRunId, EnrichmentHashing.sha256("issue-33", sourceRunId.toString()), now, now);
        seed(33_001L, "Чајетина", "Насеље А", "Општина А", "КО Чајетина; парцела број 1");
        seed(33_002L, "Сјеница", "Урсуле", "Сјеница", "КО Урсуле; парцела број 2");
        seed(33_003L, "Димитровград", "Димитровград", "Димитровград",
                "КО Цариброд; парцела број 3");
        seed(33_004L, "Чајетина", "Насеље А", "Општина А", "КО Чајетинаа; парцела број 4");
        seed(33_005L, "Чајетина", "Насеље А", "Општина А", "парцела број 5");
        seed(33_006L, "Чајетина", "Насеље А", "Општина А",
                "КО Чајетина; КО Урсуле; парцела број 6");

        UUID reviewed = reference(33_003L, "PARCEL");
        jdbc.update("""
                UPDATE property_references
                   SET ko_code = 'REVIEWED-KO', user_reviewed = TRUE,
                       extraction_status = 'USER_CONFIRMED'
                 WHERE id = ?
                """, reviewed);
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    @Test
    void persistsImmutablePerReferenceEvidenceReconcilesConflictsAndReplaysIdempotently() {
        UUID exactReference = reference(33_001L, "CADASTRAL_MUNICIPALITY");
        UUID conflictReference = reference(33_002L, "CADASTRAL_MUNICIPALITY");
        UUID aliasReference = reference(33_003L, "CADASTRAL_MUNICIPALITY");
        UUID reviewedReference = reference(33_003L, "PARCEL");
        UUID fuzzyReference = reference(33_004L, "CADASTRAL_MUNICIPALITY");
        UUID fallbackReference = reference(33_005L, "PARCEL");
        UUID unresolvedReference = reference(33_006L, "PARCEL");

        ExtractedKoMatchService.RunResult first = service.run();

        assertThat(first.populationCount()).isEqualTo(12);
        assertThat(first.processedCount()).isEqualTo(12);
        assertThat(first.unchangedCount()).isZero();
        assertThat(first.matchedCount()).isEqualTo(6);
        assertThat(first.ambiguousCount()).isEqualTo(3);
        assertThat(first.notFoundCount()).isEqualTo(2);
        assertThat(first.invalidCount()).isOne();
        assertThat(first.conflictCount()).isEqualTo(3);
        assertThat(first.overallMatchRatePercent()).isEqualByComparingTo("50.00");
        assertThat(first.textExtractedCount()).isEqualTo(10);
        assertThat(first.structuredFallbackCount()).isOne();
        assertThat(first.unresolvedKoProvenanceCount()).isOne();
        assertThat(first.textExtractedMatchedCount()).isEqualTo(5);
        assertThat(first.structuredFallbackMatchedCount()).isOne();
        assertThat(first.textExtractedMatchRatePercent()).isEqualByComparingTo("50.00");
        assertThat(first.structuredFallbackMatchRatePercent()).isEqualByComparingTo("100.00");
        assertThat(first.reconciliationByKoProvenance().get("TEXT_EXTRACTED"))
                .containsEntry("AGREES", 5L)
                .containsEntry("CONFLICT", 3L)
                .containsEntry("STRUCTURED_ONLY", 2L);
        assertThat(first.reconciliationByKoProvenance().get("STRUCTURED_FALLBACK"))
                .containsEntry("AGREES", 1L);
        assertThat(first.reconciliationByKoProvenance().get("UNRESOLVED"))
                .containsEntry("STRUCTURED_ONLY", 1L);
        assertThat(first.normalizerVersion()).isEqualTo("serbian-name-v1");

        assertThat(row(exactReference)).containsEntry("status", "MATCHED")
                .containsEntry("method", "EXACT_NORMALIZED_NAME")
                .containsEntry("matched_ko_code", "100001")
                .containsEntry("reconciliation_status", "AGREES")
                .containsEntry("ko_provenance", "TEXT_EXTRACTED")
                .containsEntry("query_normalized_ko", "CAJETINA")
                .containsEntry("dictionary_source_sha256", first.sourceGpkgSha256());
        assertThat(jdbc.queryForObject(
                "SELECT ko_code FROM property_references WHERE id = ?",
                String.class, exactReference)).isEqualTo("100001");

        Map<String, Object> conflict = row(conflictReference);
        assertThat(conflict).containsEntry("status", "AMBIGUOUS")
                .containsEntry("method", "STRUCTURED_CONFLICT")
                .containsEntry("text_matched_ko_code", "500002")
                .containsEntry("structured_matched_ko_code", "500001")
                .containsEntry("reconciliation_status", "CONFLICT");
        assertThat(conflict.get("matched_ko_code")).isNull();
        assertThat(conflict.get("reconciliation_evidence").toString())
                .contains("neither", "500001", "500002", "structuredMatch");
        assertThat(jdbc.queryForObject(
                "SELECT ko_code FROM property_references WHERE id = ?",
                String.class, conflictReference)).isNull();

        assertThat(row(aliasReference)).containsEntry("status", "MATCHED")
                .containsEntry("method", "REVIEWED_ALIAS")
                .containsEntry("matched_ko_code", "200001");
        assertThat(row(aliasReference).get("candidates").toString())
                .contains("caribrod-1930", "fixture-reviewer", "fixture://gazette/1930");
        assertThat(jdbc.queryForObject(
                "SELECT ko_code FROM property_references WHERE id = ?",
                String.class, reviewedReference)).isEqualTo("REVIEWED-KO");

        assertThat(row(fuzzyReference)).containsEntry("status", "NOT_FOUND")
                .containsEntry("method", "FUZZY_REVIEW")
                .containsEntry("reconciliation_status", "STRUCTURED_ONLY");
        assertThat(row(fuzzyReference).get("matched_ko_code")).isNull();
        assertThat(row(fuzzyReference).get("candidates").toString())
                .contains("editDistance", "similarityBasisPoints");

        assertThat(row(fallbackReference)).containsEntry("status", "MATCHED")
                .containsEntry("matched_ko_code", "100001")
                .containsEntry("ko_provenance", "STRUCTURED_FALLBACK")
                .containsEntry("reconciliation_status", "AGREES");

        // Two distinct text KOs leave #19 unable to attribute one, so the parcel
        // reference carries no KO at all and neither side may supply one.
        Map<String, Object> unresolved = row(unresolvedReference);
        assertThat(unresolved).containsEntry("status", "INVALID")
                .containsEntry("method", "NONE")
                .containsEntry("ko_provenance", "UNRESOLVED")
                .containsEntry("structured_matched_ko_code", "100001")
                .containsEntry("reconciliation_status", "STRUCTURED_ONLY")
                .containsEntry("candidates", "[]");
        assertThat(unresolved.get("matched_ko_code")).isNull();
        assertThat(unresolved.get("text_matched_ko_code")).isNull();
        assertThat(unresolved.get("query_normalized_ko")).isNull();
        assertThat(unresolved.get("rationale").toString()).startsWith("MISSING_KO_NAME:");
        assertThat(jdbc.queryForObject(
                "SELECT ko_code FROM property_references WHERE id = ?",
                String.class, unresolvedReference)).isNull();

        Map<String, Object> runEvidence = jdbc.queryForMap("""
                SELECT text_extracted_count, structured_fallback_count,
                       unresolved_ko_provenance_count, text_extracted_matched_count,
                       structured_fallback_matched_count,
                       reconciliation_by_ko_provenance::text AS split_reconciliation
                  FROM extracted_ko_match_runs WHERE id = ?
                """, first.runId());
        assertThat(runEvidence)
                .containsEntry("text_extracted_count", 10L)
                .containsEntry("structured_fallback_count", 1L)
                .containsEntry("unresolved_ko_provenance_count", 1L)
                .containsEntry("text_extracted_matched_count", 5L)
                .containsEntry("structured_fallback_matched_count", 1L);
        assertThat(runEvidence.get("split_reconciliation").toString())
                .contains("TEXT_EXTRACTED", "STRUCTURED_FALLBACK", "UNRESOLVED",
                        "AGREES", "CONFLICT");

        OffsetDateTime selectedAt = jdbc.queryForObject("""
                SELECT selected_at FROM current_property_reference_ko_matches
                 WHERE reference_id = ?
                """, OffsetDateTime.class, exactReference);
        OffsetDateTime resolvedAt = jdbc.queryForObject("""
                SELECT result.resolved_at
                  FROM current_property_reference_ko_matches current_match
                  JOIN property_reference_ko_match_results result
                    ON result.reference_id = current_match.reference_id
                   AND result.input_fingerprint = current_match.input_fingerprint
                 WHERE current_match.reference_id = ?
                """, OffsetDateTime.class, exactReference);

        ExtractedKoMatchService.RunResult replay = service.run();

        assertThat(replay.processedCount()).isZero();
        assertThat(replay.unchangedCount()).isEqualTo(12);
        assertThat(jdbc.queryForObject("""
                SELECT selected_at FROM current_property_reference_ko_matches
                 WHERE reference_id = ?
                """, OffsetDateTime.class, exactReference)).isEqualTo(selectedAt);
        assertThat(jdbc.queryForObject("""
                SELECT result.resolved_at
                  FROM current_property_reference_ko_matches current_match
                  JOIN property_reference_ko_match_results result
                    ON result.reference_id = current_match.reference_id
                   AND result.input_fingerprint = current_match.input_fingerprint
                 WHERE current_match.reference_id = ?
                """, OffsetDateTime.class, exactReference)).isEqualTo(resolvedAt);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM extracted_ko_match_runs", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM property_reference_ko_match_results", Long.class)).isEqualTo(12);

        jdbc.update("""
                UPDATE property_references SET raw_ko = '200001', normalized_ko = '200001'
                 WHERE id = ?
                """, aliasReference);
        ExtractedKoMatchService.RunResult changed = service.run();
        assertThat(changed.processedCount()).isEqualTo(2);
        assertThat(changed.unchangedCount()).isEqualTo(10);
        assertThat(row(aliasReference)).containsEntry("method", "EXACT_CODE")
                .containsEntry("matched_ko_code", "200001");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM property_reference_ko_match_results WHERE reference_id = ?
                """, Long.class, aliasReference)).isEqualTo(2);

        assertThatThrownBy(() -> jdbc.update("""
                UPDATE property_reference_ko_match_results
                   SET rationale = 'tampered' WHERE reference_id = ?
                """, exactReference))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("extracted KO match evidence is immutable");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM extracted_ko_match_runs WHERE id = ?", first.runId()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("extracted KO match evidence is immutable");
    }

    private void seed(
            long auctionId,
            String cadastral,
            String placeName,
            String municipality,
            String description) {
        String sourceSha = EnrichmentHashing.sha256("source", Long.toString(auctionId));
        String inputSha = EnrichmentHashing.sha256("input", Long.toString(auctionId));
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO auctions (
                    id, auction_number, cadastral, place_name, municipality,
                    first_sale, details_fetched, last_successful_sync_run_id
                ) VALUES (?, ?, ?, ?, ?, FALSE, TRUE, ?)
                """, auctionId, "ISSUE-33-" + auctionId,
                cadastral, placeName, municipality, sourceRunId);

        ObjectNode source = objectMapper.createObjectNode();
        source.putObject("listing").put("Id", auctionId);
        ObjectNode detail = source.putObject("detail");
        detail.put("Id", auctionId);
        detail.put("Description", description);
        detail.putNull("ShortDescription");
        ObjectNode sourcePlace = detail.putObject("Place");
        sourcePlace.put("Cadastral", cadastral);
        sourcePlace.put("Name", placeName);
        sourcePlace.put("Municipality", municipality);
        jdbc.update("""
                INSERT INTO auction_source_snapshots (
                    auction_id, content_sha256, schema_version, minimization_policy_version,
                    listing_endpoint, detail_endpoint, canonical_payload,
                    fetched_at, listing_fetched_at, detail_fetched_at,
                    source_start_at, source_end_at, ingest_run_id
                ) VALUES (?, ?, 'test-source-v1', 'test-policy-v1', 'listing', 'detail',
                          CAST(? AS jsonb), ?, ?, ?, ?, ?, ?)
                """, auctionId, sourceSha, source.toString(), now, now, now,
                now.minusDays(1), now.plusDays(1), sourceRunId);
        ObjectNode input = objectMapper.createObjectNode()
                .put("schemaVersion", EnrichmentInputSnapshot.SCHEMA_VERSION)
                .put("sourceSnapshotSha256", sourceSha)
                .put("auctionId", auctionId)
                .put("cadastral", cadastral)
                .put("placeName", placeName)
                .put("municipality", municipality)
                .put("description", description)
                .putNull("shortDescription");
        jdbc.update("""
                INSERT INTO auction_enrichment_input_snapshots (
                    auction_id, snapshot_sha256, canonical_input
                ) VALUES (?, ?, CAST(? AS jsonb))
                """, auctionId, inputSha, input.toString());
        jdbc.update("""
                UPDATE auctions SET current_source_snapshot_sha256 = ?,
                                    current_enrichment_snapshot_sha256 = ?
                 WHERE id = ?
                """, sourceSha, inputSha, auctionId);
        EnrichmentWorkItem item = new EnrichmentWorkItem(
                auctionId,
                sourceRunId,
                inputSha,
                EnrichmentHashing.sha256("dependency", Long.toString(auctionId)),
                EnrichmentHashing.sha256("work", Long.toString(auctionId)),
                input);
        references.replace(item, parser.parse(input));
    }

    private UUID reference(long auctionId, String type) {
        return jdbc.queryForObject("""
                SELECT reference.id
                  FROM current_property_reference_extractions current_extraction
                  JOIN property_reference_extraction_memberships membership
                    ON membership.extraction_run_id = current_extraction.extraction_run_id
                   AND membership.auction_id = current_extraction.auction_id
                  JOIN property_references reference ON reference.id = membership.reference_id
                 WHERE reference.auction_id = ? AND reference.reference_type = ?
                 ORDER BY membership.reference_order LIMIT 1
                """, UUID.class, auctionId, type);
    }

    private Map<String, Object> row(UUID referenceId) {
        return jdbc.queryForMap("""
                SELECT result.status, result.method, result.matched_ko_code,
                       result.text_matched_ko_code, result.structured_matched_ko_code,
                       result.reconciliation_status, result.ko_provenance,
                       result.query_normalized_ko, result.rationale,
                       btrim(result.dictionary_source_sha256) AS dictionary_source_sha256,
                       result.candidates::text AS candidates,
                       result.reconciliation_evidence::text AS reconciliation_evidence
                  FROM current_property_reference_ko_matches current_match
                  JOIN property_reference_ko_match_results result
                    ON result.reference_id = current_match.reference_id
                   AND result.input_fingerprint = current_match.input_fingerprint
                 WHERE current_match.reference_id = ?
                """, referenceId);
    }

    private void clean() {
        jdbc.execute("""
                TRUNCATE TABLE
                    property_reference_ko_match_observations,
                    extracted_ko_match_run_results,
                    extracted_ko_match_runs,
                    current_property_reference_ko_matches,
                    property_reference_ko_match_results,
                    structured_ko_match_runs,
                    auction_structured_ko_matches,
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

    private static Path createDictionary() {
        try {
            Path root = Files.createTempDirectory("extracted-ko-it-");
            return KoDictionaryTestArtifact.create(root, new ObjectMapper().findAndRegisterModules());
        } catch (Exception failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }
}
