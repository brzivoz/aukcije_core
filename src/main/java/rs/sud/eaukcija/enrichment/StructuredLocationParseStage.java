package rs.sud.eaukcija.enrichment;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import rs.sud.eaukcija.addressregistry.SerbianNameNormalizer;

/** Persists the structured Place reference already available in accepted sync input. */
@Component
public class StructuredLocationParseStage implements EnrichmentStage {

    public static final String PARSER_VERSION = "coarse-structured-place-v1";
    public static final String CANONICAL_KEY = "structured-place";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public StructuredLocationParseStage(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public EnrichmentStageName name() {
        return EnrichmentStageName.PARSE;
    }

    @Override
    public String implementationVersion() {
        return PARSER_VERSION;
    }

    @Override
    public String activeDatasetVersion() {
        return "NONE";
    }

    @Override
    public EnrichmentStageResult process(EnrichmentWorkItem item) {
        JsonNode input = item.canonicalInput();
        String cadastral = text(input, "cadastral");
        String placeName = text(input, "placeName");
        String municipality = text(input, "municipality");
        String normalizedKo = SerbianNameNormalizer.normalize(cadastral);
        String extractionStatus = cadastral == null && placeName == null && municipality == null
                ? "NO_STRUCTURED_REFERENCE" : "EXTRACTED";
        UUID id = referenceId(item.auctionId());
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("snapshotSha256", item.snapshotSha256());
        evidence.put("cadastral", cadastral);
        evidence.put("placeName", placeName);
        evidence.put("municipality", municipality);
        try {
            jdbc.update("""
                    INSERT INTO property_references (
                        id, auction_id, reference_order, reference_type,
                        raw_ko, normalized_ko,
                        address_municipality, address_settlement,
                        source_field, raw_evidence, parser_version,
                        extraction_status, canonical_key
                    ) VALUES (?, ?, 0, 'STRUCTURED_LOCATION', ?, ?, ?, ?,
                              'Place.Cadastral|Place.Name|Place.Municipality',
                              CAST(? AS jsonb), ?, ?, ?)
                    ON CONFLICT (auction_id, parser_version, canonical_key) DO UPDATE SET
                        raw_ko = EXCLUDED.raw_ko,
                        normalized_ko = EXCLUDED.normalized_ko,
                        address_municipality = EXCLUDED.address_municipality,
                        address_settlement = EXCLUDED.address_settlement,
                        raw_evidence = EXCLUDED.raw_evidence,
                        extraction_status = EXCLUDED.extraction_status
                    WHERE NOT property_references.user_reviewed
                    """,
                    id,
                    item.auctionId(),
                    cadastral,
                    normalizedKo,
                    municipality,
                    placeName,
                    objectMapper.writeValueAsString(evidence),
                    PARSER_VERSION,
                    extractionStatus,
                    CANONICAL_KEY);
        } catch (JsonProcessingException serializationFailure) {
            throw EnrichmentStageException.permanent("PARSE_EVIDENCE_INVALID", serializationFailure);
        } catch (DataAccessException persistenceFailure) {
            throw EnrichmentStageException.retryable("PARSE_PERSISTENCE_FAILED", persistenceFailure);
        }
        return EnrichmentStageResult.continuing(EnrichmentHashing.sha256(
                PARSER_VERSION,
                item.snapshotSha256(),
                cadastral,
                normalizedKo,
                placeName,
                municipality,
                extractionStatus));
    }

    private static String text(JsonNode input, String field) {
        JsonNode value = input.path(field);
        return value.isTextual() ? value.asText() : null;
    }

    public static UUID referenceId(long auctionId) {
        if (auctionId <= 0) {
            throw new IllegalArgumentException("auctionId must be positive");
        }
        return UUID.nameUUIDFromBytes(("coarse-structured-place:"
                + PARSER_VERSION + ":" + auctionId).getBytes(StandardCharsets.UTF_8));
    }
}
