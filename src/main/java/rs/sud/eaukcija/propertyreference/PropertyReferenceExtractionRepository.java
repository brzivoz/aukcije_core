package rs.sud.eaukcija.propertyreference;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import rs.sud.eaukcija.enrichment.EnrichmentWorkItem;
import rs.sud.eaukcija.snapshot.AuctionSourceCanonicalJson;

/** Atomic current-set replacement with immutable snapshot/run evidence. */
@Repository
public class PropertyReferenceExtractionRepository {

    private static final long LOCK_NAMESPACE = 19_000_000_000L;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final PropertyReferenceQualityProfile quality;

    public PropertyReferenceExtractionRepository(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            PropertyReferenceQualityProfile quality) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.quality = quality;
    }

    @Transactional
    public PersistedExtraction replace(
            EnrichmentWorkItem item,
            PropertyReferenceParseResult result) {
        String sourceSnapshotSha256 = sourceSnapshotSha256(item.canonicalInput());
        jdbc.execute("SELECT pg_advisory_xact_lock("
                + Math.addExact(LOCK_NAMESPACE, item.auctionId()) + ")");

        PersistedExtraction existing = find(
                item.auctionId(), item.snapshotSha256(), result.parserVersion());
        if (existing != null) {
            if (!existing.resultSha256().equals(result.outputSha256())) {
                throw new IllegalStateException(
                        "same parser input/version produced a different property-reference hash");
            }
            selectCurrent(item.auctionId(), existing.extractionRunId());
            observe(item, existing);
            return existing;
        }

        List<UUID> selected = new ArrayList<>();
        Set<UUID> selectedSet = new LinkedHashSet<>();
        LinkedHashMap<String, UUID> reviewedByKey = new LinkedHashMap<>();
        jdbc.query("""
                SELECT canonical_key, id FROM property_references
                 WHERE auction_id = ? AND user_reviewed
                 ORDER BY created_at, id
                """, resultSet -> {
            while (resultSet.next()) {
                reviewedByKey.putIfAbsent(
                        resultSet.getString("canonical_key"),
                        resultSet.getObject("id", UUID.class));
            }
            return null;
        }, item.auctionId());
        for (ParsedPropertyReference reference : result.references()) {
            UUID id = reviewedByKey.get(reference.canonicalKey());
            if (id == null) {
                id = upsertReference(item, sourceSnapshotSha256, result.parserVersion(), reference);
            }
            if (selectedSet.add(id)) {
                selected.add(id);
            }
        }
        for (UUID reviewed : reviewedByKey.values()) {
            if (selectedSet.add(reviewed)) {
                selected.add(reviewed);
            }
        }

        UUID extractionRunId = extractionRunId(
                item.auctionId(), item.snapshotSha256(), result.parserVersion());
        ObjectNode evidence = objectMapper.createObjectNode();
        evidence.put("parserVersion", result.parserVersion());
        evidence.put("sourceSnapshotSha256", sourceSnapshotSha256);
        evidence.put("inputSnapshotSha256", item.snapshotSha256());
        evidence.put("resultSha256", result.outputSha256());
        evidence.set("references", objectMapper.valueToTree(result.references()));
        var selectedEvidence = objectMapper.createArrayNode();
        for (UUID referenceId : selected) {
            String storedReference = jdbc.queryForObject("""
                    SELECT (to_jsonb(reference) - 'created_at')::text
                      FROM property_references reference WHERE id = ?
                    """, String.class, referenceId);
            try {
                selectedEvidence.add(AuctionSourceCanonicalJson.readTree(storedReference));
            } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
                throw new IllegalStateException("stored property reference is not JSON", invalid);
            }
        }
        evidence.set("selectedReferences", selectedEvidence);
        PropertyReferenceQualityProfile.Profile profile = quality.profile();
        jdbc.update("""
                INSERT INTO property_reference_extraction_runs (
                    id, auction_id, source_sync_run_id, source_snapshot_sha256,
                    input_snapshot_sha256, parser_version, result_sha256, result_json,
                    generated_reference_count, selected_reference_count,
                    text_reference_count, no_structured_count, ko_conflict_count,
                    quality_corpus_version, quality_metrics_sha256,
                    held_out_precision, held_out_recall, held_out_negative_fp
                ) VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                extractionRunId,
                item.auctionId(),
                item.sourceSyncRunId(),
                sourceSnapshotSha256,
                item.snapshotSha256(),
                result.parserVersion(),
                result.outputSha256(),
                AuctionSourceCanonicalJson.write(evidence),
                result.references().size(),
                selected.size(),
                result.textReferenceCount(),
                result.noStructuredReferenceCount(),
                result.koConflictCount(),
                profile.corpusVersion(),
                profile.metricsSha256(),
                profile.heldOutPrecision(),
                profile.heldOutRecall(),
                profile.heldOutNegativeFalsePositives());
        for (int order = 0; order < selected.size(); order++) {
            jdbc.update("""
                    INSERT INTO property_reference_extraction_memberships (
                        extraction_run_id, auction_id, reference_id, reference_order
                    ) VALUES (?, ?, ?, ?)
                    """, extractionRunId, item.auctionId(), selected.get(order), order);
        }
        PersistedExtraction persisted = new PersistedExtraction(
                extractionRunId,
                result.outputSha256(),
                selected.size(),
                result.textReferenceCount(),
                result.noStructuredReferenceCount(),
                result.koConflictCount());
        selectCurrent(item.auctionId(), extractionRunId);
        observe(item, persisted);
        return persisted;
    }

    private UUID upsertReference(
            EnrichmentWorkItem item,
            String sourceSnapshotSha256,
            String parserVersion,
            ParsedPropertyReference reference) {
        UUID proposedId = referenceId(item.auctionId(), parserVersion, reference.canonicalKey());
        jdbc.update("""
                INSERT INTO property_references (
                    id, auction_id, reference_order, reference_type,
                    raw_ko, normalized_ko, ko_code,
                    raw_parcel_number, canonical_parcel_number, land_register_number,
                    address_municipality, address_settlement, address_street, address_house_number,
                    source_field, source_offset_start, source_offset_end, raw_evidence,
                    parser_version, extraction_status, canonical_key,
                    source_snapshot_sha256, input_snapshot_sha256
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (auction_id, parser_version, canonical_key) DO UPDATE SET
                    reference_type = EXCLUDED.reference_type,
                    raw_ko = EXCLUDED.raw_ko,
                    normalized_ko = EXCLUDED.normalized_ko,
                    ko_code = CASE
                        WHEN property_references.normalized_ko
                                 IS NOT DISTINCT FROM EXCLUDED.normalized_ko
                             AND EXCLUDED.extraction_status = 'EXTRACTED'
                            THEN COALESCE(EXCLUDED.ko_code, property_references.ko_code)
                        ELSE EXCLUDED.ko_code
                    END,
                    raw_parcel_number = EXCLUDED.raw_parcel_number,
                    canonical_parcel_number = EXCLUDED.canonical_parcel_number,
                    land_register_number = EXCLUDED.land_register_number,
                    address_municipality = EXCLUDED.address_municipality,
                    address_settlement = EXCLUDED.address_settlement,
                    address_street = EXCLUDED.address_street,
                    address_house_number = EXCLUDED.address_house_number,
                    source_field = EXCLUDED.source_field,
                    source_offset_start = EXCLUDED.source_offset_start,
                    source_offset_end = EXCLUDED.source_offset_end,
                    raw_evidence = EXCLUDED.raw_evidence,
                    extraction_status = EXCLUDED.extraction_status,
                    source_snapshot_sha256 = EXCLUDED.source_snapshot_sha256,
                    input_snapshot_sha256 = EXCLUDED.input_snapshot_sha256
                WHERE NOT property_references.user_reviewed
                """,
                proposedId,
                item.auctionId(),
                reference.referenceOrder(),
                reference.type().name(),
                reference.rawKo(),
                reference.normalizedKo(),
                reference.koCode(),
                reference.rawParcelNumber(),
                reference.canonicalParcelNumber(),
                reference.landRegisterNumber(),
                reference.addressMunicipality(),
                reference.addressSettlement(),
                reference.addressStreet(),
                reference.addressHouseNumber(),
                reference.sourceField(),
                reference.sourceOffsetStart(),
                reference.sourceOffsetEnd(),
                reference.rawEvidence(),
                parserVersion,
                reference.status().name(),
                reference.canonicalKey(),
                sourceSnapshotSha256,
                item.snapshotSha256());
        return jdbc.queryForObject("""
                SELECT id FROM property_references
                 WHERE auction_id = ? AND parser_version = ? AND canonical_key = ?
                """, UUID.class, item.auctionId(), parserVersion, reference.canonicalKey());
    }

    private PersistedExtraction find(long auctionId, String inputSha, String parserVersion) {
        return jdbc.query("""
                SELECT id, result_sha256, selected_reference_count,
                       text_reference_count, no_structured_count, ko_conflict_count
                  FROM property_reference_extraction_runs
                 WHERE auction_id = ? AND input_snapshot_sha256 = ? AND parser_version = ?
                """, result -> result.next() ? new PersistedExtraction(
                        result.getObject("id", UUID.class),
                        result.getString("result_sha256").trim(),
                        result.getInt("selected_reference_count"),
                        result.getInt("text_reference_count"),
                        result.getInt("no_structured_count"),
                        result.getInt("ko_conflict_count")) : null,
                auctionId, inputSha, parserVersion);
    }

    private void selectCurrent(long auctionId, UUID extractionRunId) {
        jdbc.update("""
                INSERT INTO current_property_reference_extractions (
                    auction_id, extraction_run_id, selected_at
                ) VALUES (?, ?, ?)
                ON CONFLICT (auction_id) DO UPDATE SET
                    extraction_run_id = EXCLUDED.extraction_run_id,
                    selected_at = EXCLUDED.selected_at
                WHERE current_property_reference_extractions.extraction_run_id
                      IS DISTINCT FROM EXCLUDED.extraction_run_id
                """, auctionId, extractionRunId, OffsetDateTime.now(ZoneOffset.UTC));
    }

    private void observe(EnrichmentWorkItem item, PersistedExtraction extraction) {
        if (item.enrichmentRunId() == null) {
            return;
        }
        int inserted = jdbc.update("""
                INSERT INTO property_reference_extraction_observations (
                    enrichment_run_id, auction_id, extraction_run_id
                ) VALUES (?, ?, ?)
                ON CONFLICT (enrichment_run_id, auction_id) DO NOTHING
                """, item.enrichmentRunId(), item.auctionId(), extraction.extractionRunId());
        if (inserted == 1) {
            PropertyReferenceQualityProfile.Profile profile = quality.profile();
            int updated = jdbc.update("""
                    UPDATE enrichment_runs
                       SET property_reference_extraction_success_count =
                               property_reference_extraction_success_count + 1,
                           property_reference_count = property_reference_count + ?,
                           text_reference_count = text_reference_count + ?,
                           no_structured_reference_count = no_structured_reference_count + ?,
                           ko_conflict_count = ko_conflict_count + ?,
                           property_reference_quality_corpus_version = ?,
                           property_reference_quality_metrics_sha256 = ?
                     WHERE id = ? AND status = 'RUNNING'
                    """,
                    extraction.selectedReferenceCount(),
                    extraction.textReferenceCount(),
                    extraction.noStructuredReferenceCount(),
                    extraction.koConflictCount(),
                    profile.corpusVersion(),
                    profile.metricsSha256(),
                    item.enrichmentRunId());
            if (updated != 1) {
                throw new IllegalStateException("enrichment run is not RUNNING");
            }
        }
    }

    private static String sourceSnapshotSha256(JsonNode canonicalInput) {
        JsonNode value = canonicalInput.get("sourceSnapshotSha256");
        if (value == null || !value.isTextual()
                || !value.textValue().matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("parser input has no source snapshot lineage");
        }
        return value.textValue();
    }

    public static UUID referenceId(long auctionId, String parserVersion, String canonicalKey) {
        if (auctionId <= 0 || parserVersion == null || parserVersion.isBlank()
                || canonicalKey == null || canonicalKey.isBlank()) {
            throw new IllegalArgumentException("complete reference identity is required");
        }
        return UUID.nameUUIDFromBytes(("property-reference:" + auctionId + ":"
                + parserVersion + ":" + canonicalKey).getBytes(StandardCharsets.UTF_8));
    }

    public static UUID extractionRunId(long auctionId, String inputSha, String parserVersion) {
        return UUID.nameUUIDFromBytes(("property-reference-extraction:" + auctionId + ":"
                + inputSha + ":" + parserVersion).getBytes(StandardCharsets.UTF_8));
    }

    public record PersistedExtraction(
            UUID extractionRunId,
            String resultSha256,
            int selectedReferenceCount,
            int textReferenceCount,
            int noStructuredReferenceCount,
            int koConflictCount) {
    }
}
