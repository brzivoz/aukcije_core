package rs.sud.eaukcija.komatching;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import rs.sud.eaukcija.enrichment.EnrichmentHashing;
import rs.sud.eaukcija.enrichment.EnrichmentStage;
import rs.sud.eaukcija.enrichment.EnrichmentStageException;
import rs.sud.eaukcija.enrichment.EnrichmentStageName;
import rs.sud.eaukcija.enrichment.EnrichmentStageResult;
import rs.sud.eaukcija.enrichment.EnrichmentWorkItem;

/** Per-auction adapter for the deterministic #37 matcher. */
@Component
public class StructuredKoEnrichmentStage implements EnrichmentStage {

    private static final String IMPLEMENTATION_VERSION = StructuredKoMatcher.MATCHER_VERSION;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final KoDictionarySnapshotLoader dictionaryLoader;
    private final StructuredKoMatchProperties properties;
    private volatile CachedDictionary cached;

    StructuredKoEnrichmentStage(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            KoDictionarySnapshotLoader dictionaryLoader,
            StructuredKoMatchProperties properties) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.dictionaryLoader = dictionaryLoader;
        this.properties = properties;
    }

    @Override
    public EnrichmentStageName name() {
        return EnrichmentStageName.KO_MATCHING;
    }

    @Override
    public String implementationVersion() {
        return IMPLEMENTATION_VERSION;
    }

    @Override
    public String activeDatasetVersion() {
        KoDictionarySnapshot dictionary = dictionary();
        return String.join(":",
                dictionary.version(),
                dictionary.sourceGpkgSha256(),
                dictionary.normalizerVersion(),
                dictionary.aliasDatasetVersion(),
                dictionary.aliasSha256(),
                dictionary.municipalityAliasSha256());
    }

    @Override
    public EnrichmentStageResult process(EnrichmentWorkItem item) {
        try {
            KoDictionarySnapshot dictionary = dictionary();
            JsonNode input = item.canonicalInput();
            StructuredKoMatcher.Input matchInput = new StructuredKoMatcher.Input(
                    item.auctionId(),
                    text(input, "cadastral"),
                    text(input, "placeName"),
                    text(input, "municipality"));
            String fingerprint = StructuredKoMatcher.fingerprint(matchInput, dictionary);
            Existing existing = findExisting(item.auctionId(), fingerprint);
            if (existing != null) {
                return result(fingerprint, existing.status(), existing.method(), existing.matchedKoCode());
            }
            StructuredKoMatcher.Match match = new StructuredKoMatcher(
                    dictionary, StructuredKoMatcher.DEFAULT_FUZZY_CANDIDATE_LIMIT).match(matchInput);
            persist(matchInput, match, dictionary);
            return result(
                    match.inputFingerprint(),
                    match.status().name(),
                    match.method().name(),
                    match.matchedKoCode());
        } catch (KoStructuredMatchException artifactFailure) {
            throw EnrichmentStageException.permanent(safeCode(artifactFailure.code()), artifactFailure);
        } catch (DataAccessException persistenceFailure) {
            throw EnrichmentStageException.retryable("KO_MATCH_PERSISTENCE_FAILED", persistenceFailure);
        }
    }

    private EnrichmentStageResult result(
            String fingerprint,
            String status,
            String method,
            String matchedKoCode) {
        return EnrichmentStageResult.continuing(EnrichmentHashing.sha256(
                IMPLEMENTATION_VERSION, fingerprint, status, method, matchedKoCode));
    }

    private Existing findExisting(long auctionId, String fingerprint) {
        List<Existing> rows = jdbc.query("""
                SELECT status, method, matched_ko_code
                  FROM auction_structured_ko_matches
                 WHERE auction_id = ? AND input_fingerprint = ?
                """, (result, row) -> new Existing(
                result.getString("status"),
                result.getString("method"),
                result.getString("matched_ko_code")), auctionId, fingerprint);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void persist(
            StructuredKoMatcher.Input input,
            StructuredKoMatcher.Match match,
            KoDictionarySnapshot dictionary) {
        try {
            jdbc.update("""
                    INSERT INTO auction_structured_ko_matches (
                        auction_id, source_cadastral, source_place_name, source_municipality,
                        input_fingerprint, status, method, rationale, matched_ko_code,
                        dictionary_version, dictionary_source_sha256, normalizer_version,
                        alias_dataset_version, alias_sha256,
                        municipality_alias_dataset_version, municipality_alias_sha256,
                        candidates, resolved_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CURRENT_TIMESTAMP)
                    ON CONFLICT (auction_id) DO UPDATE SET
                        source_cadastral = EXCLUDED.source_cadastral,
                        source_place_name = EXCLUDED.source_place_name,
                        source_municipality = EXCLUDED.source_municipality,
                        input_fingerprint = EXCLUDED.input_fingerprint,
                        status = EXCLUDED.status,
                        method = EXCLUDED.method,
                        rationale = EXCLUDED.rationale,
                        matched_ko_code = EXCLUDED.matched_ko_code,
                        dictionary_version = EXCLUDED.dictionary_version,
                        dictionary_source_sha256 = EXCLUDED.dictionary_source_sha256,
                        normalizer_version = EXCLUDED.normalizer_version,
                        alias_dataset_version = EXCLUDED.alias_dataset_version,
                        alias_sha256 = EXCLUDED.alias_sha256,
                        municipality_alias_dataset_version = EXCLUDED.municipality_alias_dataset_version,
                        municipality_alias_sha256 = EXCLUDED.municipality_alias_sha256,
                        candidates = EXCLUDED.candidates,
                        resolved_at = EXCLUDED.resolved_at
                    WHERE auction_structured_ko_matches.input_fingerprint
                          IS DISTINCT FROM EXCLUDED.input_fingerprint
                    """,
                    input.auctionId(), input.cadastral(), input.placeName(), input.municipality(),
                    match.inputFingerprint(), match.status().name(), match.method().name(), match.rationale(),
                    match.matchedKoCode(), dictionary.version(), dictionary.sourceGpkgSha256(),
                    dictionary.normalizerVersion(), dictionary.aliasDatasetVersion(), dictionary.aliasSha256(),
                    dictionary.aliasDatasetVersion(), dictionary.municipalityAliasSha256(),
                    objectMapper.writeValueAsString(match.candidates()));
        } catch (JsonProcessingException serializationFailure) {
            throw EnrichmentStageException.permanent("KO_MATCH_EVIDENCE_INVALID", serializationFailure);
        }
    }

    private KoDictionarySnapshot dictionary() {
        properties.validate();
        String pointer = activePointer(properties.getDictionaryDirectory());
        CachedDictionary existing = cached;
        if (existing != null && existing.pointer().equals(pointer)) {
            return existing.snapshot();
        }
        synchronized (this) {
            existing = cached;
            if (existing != null && existing.pointer().equals(pointer)) {
                return existing.snapshot();
            }
            KoDictionarySnapshot loaded = dictionaryLoader.load(properties.getDictionaryDirectory());
            cached = new CachedDictionary(pointer, loaded);
            return loaded;
        }
    }

    private static String activePointer(Path directory) {
        try {
            return Files.readString(
                    directory.toAbsolutePath().normalize().resolve("ACTIVE"),
                    StandardCharsets.UTF_8).trim();
        } catch (IOException failure) {
            throw new KoStructuredMatchException(
                    "ACTIVE_VERSION_UNAVAILABLE", "active dictionary pointer is unavailable", failure);
        }
    }

    private static String safeCode(String code) {
        return code != null && code.matches("[A-Z0-9_]{1,64}") ? code : "KO_MATCH_FAILED";
    }

    private static String text(JsonNode input, String field) {
        JsonNode value = input.path(field);
        return value.isTextual() ? value.asText() : null;
    }

    private record Existing(String status, String method, String matchedKoCode) {
    }

    private record CachedDictionary(String pointer, KoDictionarySnapshot snapshot) {
    }
}
