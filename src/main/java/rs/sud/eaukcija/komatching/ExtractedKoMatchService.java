package rs.sud.eaukcija.komatching;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import rs.sud.eaukcija.enrichment.EnrichmentHashing;
import rs.sud.eaukcija.enrichment.EnrichmentWorkItem;

/** Population orchestration and per-auction persistence for issue #33. */
@Service
public class ExtractedKoMatchService {

    static final long POPULATION_LOCK_ID = 33_003_700_019L;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final KoDictionarySnapshotLoader dictionaryLoader;
    private final StructuredKoMatchProperties properties;
    private final StructuredKoMatchService structuredMatches;
    private final Clock clock;

    @Autowired
    public ExtractedKoMatchService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            KoDictionarySnapshotLoader dictionaryLoader,
            StructuredKoMatchProperties properties,
            StructuredKoMatchService structuredMatches) {
        this(jdbc, objectMapper, dictionaryLoader, properties, structuredMatches, Clock.systemUTC());
    }

    ExtractedKoMatchService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            KoDictionarySnapshotLoader dictionaryLoader,
            StructuredKoMatchProperties properties,
            StructuredKoMatchService structuredMatches,
            Clock clock) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.dictionaryLoader = dictionaryLoader;
        this.properties = properties;
        this.structuredMatches = structuredMatches;
        this.clock = clock;
    }

    /**
     * Refreshes #37 first, then matches one coherent current #19 population.
     * The outer transaction retains #37's advisory lock before acquiring #33's.
     */
    @Transactional
    public RunResult run() {
        Instant started = Instant.now(clock);
        StructuredKoMatchService.RunResult structuredRun = structuredMatches.run();
        properties.validate();
        KoDictionarySnapshot dictionary = dictionaryLoader.load(properties.getDictionaryDirectory());
        requireSameDictionary(structuredRun, dictionary);
        jdbc.execute("SELECT pg_advisory_xact_lock(" + POPULATION_LOCK_ID + ")");

        List<ReferenceInput> inputs = readInputs(null);
        ExtractedKoMatcher matcher = new ExtractedKoMatcher(
                dictionary, StructuredKoMatcher.DEFAULT_FUZZY_CANDIDATE_LIMIT);
        List<Outcome> outcomes = new ArrayList<>(inputs.size());
        for (ReferenceInput input : inputs) {
            requireSameDictionary(input.structured(), dictionary);
            outcomes.add(resolve(input, matcher, dictionary, null));
        }

        Instant finished = Instant.now(clock);
        UUID runId = UUID.randomUUID();
        Counters counters = Counters.from(outcomes);
        jdbc.update("""
                INSERT INTO extracted_ko_match_runs (
                    id, started_at, finished_at, matcher_version,
                    dictionary_version, dictionary_source_sha256, normalizer_version,
                    alias_dataset_version, alias_sha256,
                    municipality_alias_dataset_version, municipality_alias_sha256,
                    population_count, processed_count, unchanged_count,
                    matched_count, ambiguous_count, not_found_count, invalid_count,
                    conflict_count,
                    text_extracted_count, structured_fallback_count,
                    unresolved_ko_provenance_count,
                    text_extracted_matched_count, structured_fallback_matched_count,
                    method_counts, reconciliation_counts,
                    reconciliation_by_ko_provenance
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                          ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), CAST(? AS jsonb))
                """,
                runId, databaseTime(started), databaseTime(finished), ExtractedKoMatcher.MATCHER_VERSION,
                dictionary.version(), dictionary.sourceGpkgSha256(), dictionary.normalizerVersion(),
                dictionary.aliasDatasetVersion(), dictionary.aliasSha256(),
                dictionary.aliasDatasetVersion(), dictionary.municipalityAliasSha256(),
                outcomes.size(), counters.processed(), outcomes.size() - counters.processed(),
                counters.status(StructuredKoMatcher.Status.MATCHED),
                counters.status(StructuredKoMatcher.Status.AMBIGUOUS),
                counters.status(StructuredKoMatcher.Status.NOT_FOUND),
                counters.status(StructuredKoMatcher.Status.INVALID),
                counters.reconciliation(ExtractedKoMatcher.Reconciliation.CONFLICT),
                counters.provenance(ExtractedKoMatcher.KoProvenance.TEXT_EXTRACTED),
                counters.provenance(ExtractedKoMatcher.KoProvenance.STRUCTURED_FALLBACK),
                counters.provenance(ExtractedKoMatcher.KoProvenance.UNRESOLVED),
                counters.matched(ExtractedKoMatcher.KoProvenance.TEXT_EXTRACTED),
                counters.matched(ExtractedKoMatcher.KoProvenance.STRUCTURED_FALLBACK),
                json(counters.methodCounts()), json(counters.reconciliationCounts()),
                json(counters.reconciliationByKoProvenance()));
        for (int index = 0; index < outcomes.size(); index++) {
            Outcome outcome = outcomes.get(index);
            jdbc.update("""
                    INSERT INTO extracted_ko_match_run_results (
                        run_id, ordinal, reference_id, auction_id, input_fingerprint, processed
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    runId, index + 1, outcome.result().referenceId(), outcome.result().auctionId(),
                    outcome.result().inputFingerprint(), outcome.processed());
        }

        long matched = counters.status(StructuredKoMatcher.Status.MATCHED);
        BigDecimal matchRate = outcomes.isEmpty()
                ? BigDecimal.ZERO.setScale(2)
                : BigDecimal.valueOf(matched).multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(outcomes.size()), 2, RoundingMode.HALF_UP);
        return new RunResult(
                runId,
                structuredRun.runId(),
                started,
                finished,
                Duration.between(started, finished).toMillis(),
                ExtractedKoMatcher.MATCHER_VERSION,
                dictionary.version(),
                dictionary.sourceDate().toString(),
                dictionary.sourceGpkgSha256(),
                dictionary.normalizerVersion(),
                dictionary.aliasDatasetVersion(),
                dictionary.aliasSha256(),
                dictionary.aliasDatasetVersion(),
                dictionary.municipalityAliasSha256(),
                outcomes.size(),
                counters.processed(),
                outcomes.size() - counters.processed(),
                matched,
                counters.status(StructuredKoMatcher.Status.AMBIGUOUS),
                counters.status(StructuredKoMatcher.Status.NOT_FOUND),
                counters.status(StructuredKoMatcher.Status.INVALID),
                counters.reconciliation(ExtractedKoMatcher.Reconciliation.CONFLICT),
                matchRate,
                counters.provenance(ExtractedKoMatcher.KoProvenance.TEXT_EXTRACTED),
                counters.provenance(ExtractedKoMatcher.KoProvenance.STRUCTURED_FALLBACK),
                counters.provenance(ExtractedKoMatcher.KoProvenance.UNRESOLVED),
                counters.matched(ExtractedKoMatcher.KoProvenance.TEXT_EXTRACTED),
                counters.matched(ExtractedKoMatcher.KoProvenance.STRUCTURED_FALLBACK),
                percent(
                        counters.matched(ExtractedKoMatcher.KoProvenance.TEXT_EXTRACTED),
                        counters.provenance(ExtractedKoMatcher.KoProvenance.TEXT_EXTRACTED)),
                percent(
                        counters.matched(ExtractedKoMatcher.KoProvenance.STRUCTURED_FALLBACK),
                        counters.provenance(ExtractedKoMatcher.KoProvenance.STRUCTURED_FALLBACK)),
                counters.methodCounts(),
                counters.reconciliationCounts(),
                counters.reconciliationByKoProvenance());
    }

    /** Called by the fixed KO_MATCHING stage after its structured #37 result is current. */
    AuctionResult processAuction(EnrichmentWorkItem item, KoDictionarySnapshot dictionary) {
        List<ReferenceInput> inputs = readInputs(item.auctionId());
        ExtractedKoMatcher matcher = new ExtractedKoMatcher(
                dictionary, StructuredKoMatcher.DEFAULT_FUZZY_CANDIDATE_LIMIT);
        List<Outcome> outcomes = new ArrayList<>(inputs.size());
        for (ReferenceInput input : inputs) {
            requireSameDictionary(input.structured(), dictionary);
            outcomes.add(resolve(input, matcher, dictionary, item.enrichmentRunId()));
        }
        List<String> evidence = new ArrayList<>();
        evidence.add(ExtractedKoMatcher.MATCHER_VERSION);
        evidence.add(Long.toString(item.auctionId()));
        if (outcomes.isEmpty()) {
            evidence.add("NO_EXTRACTED_REFERENCES");
        } else {
            for (Outcome outcome : outcomes) {
                PersistedResult result = outcome.result();
                evidence.add(result.referenceId().toString());
                evidence.add(result.inputFingerprint());
                evidence.add(result.koProvenance().name());
                evidence.add(result.status().name());
                evidence.add(result.method().name());
                evidence.add(result.matchedKoCode());
                evidence.add(result.reconciliation().name());
            }
        }
        return new AuctionResult(outcomes.size(), EnrichmentHashing.sha256(evidence.toArray(String[]::new)));
    }

    private Outcome resolve(
            ReferenceInput input,
            ExtractedKoMatcher matcher,
            KoDictionarySnapshot dictionary,
            UUID enrichmentRunId) {
        String fingerprint = matcher.fingerprint(input.matchInput(), input.structured());
        PersistedResult existing = find(input.referenceId(), fingerprint);
        boolean processed = existing == null;
        PersistedResult result;
        if (existing == null) {
            ExtractedKoMatcher.Match match = matcher.match(input.matchInput(), input.structured());
            persist(input, match, dictionary);
            result = PersistedResult.from(match);
        } else {
            result = existing;
        }
        selectCurrent(result);
        applyToReference(result);
        observe(enrichmentRunId, result);
        return new Outcome(result, processed);
    }

    private List<ReferenceInput> readInputs(Long auctionId) {
        String filter = auctionId == null ? "" : " AND reference.auction_id = ?";
        String sql = """
                SELECT reference.id, reference.auction_id, reference.raw_ko, reference.normalized_ko,
                       auction.place_name, auction.municipality,
                       structured.input_fingerprint AS structured_input_fingerprint,
                       structured.status AS structured_status,
                       structured.method AS structured_method,
                       structured.rationale AS structured_rationale,
                       structured.matched_ko_code AS structured_matched_ko_code,
                       structured.source_cadastral, structured.source_place_name,
                       structured.source_municipality, structured.dictionary_version,
                       structured.dictionary_source_sha256, structured.normalizer_version,
                       structured.alias_dataset_version, structured.alias_sha256,
                       structured.municipality_alias_dataset_version,
                       structured.municipality_alias_sha256,
                       CASE
                           WHEN reference.reference_type = 'CADASTRAL_MUNICIPALITY'
                               THEN 'TEXT_EXTRACTED'
                           WHEN EXISTS (
                               SELECT 1
                                 FROM property_reference_extraction_memberships ko_membership
                                 JOIN property_references ko_reference
                                   ON ko_reference.id = ko_membership.reference_id
                                WHERE ko_membership.extraction_run_id = membership.extraction_run_id
                                  AND ko_membership.auction_id = membership.auction_id
                                  AND ko_reference.reference_type = 'CADASTRAL_MUNICIPALITY'
                                  AND ko_reference.raw_ko IS NOT DISTINCT FROM reference.raw_ko
                                  AND ko_reference.normalized_ko IS NOT DISTINCT FROM reference.normalized_ko
                           ) THEN 'TEXT_EXTRACTED'
                           WHEN reference.raw_ko IS NOT NULL OR reference.normalized_ko IS NOT NULL
                               THEN 'STRUCTURED_FALLBACK'
                           ELSE 'UNRESOLVED'
                       END AS ko_provenance
                  FROM current_property_reference_extractions current_extraction
                  JOIN property_reference_extraction_memberships membership
                    ON membership.extraction_run_id = current_extraction.extraction_run_id
                   AND membership.auction_id = current_extraction.auction_id
                  JOIN property_references reference ON reference.id = membership.reference_id
                  JOIN auctions auction ON auction.id = reference.auction_id
                  JOIN auction_structured_ko_matches structured
                    ON structured.auction_id = reference.auction_id
                 WHERE reference.reference_type <> 'STRUCTURED_LOCATION'
                """ + filter + " ORDER BY reference.auction_id, membership.reference_order, reference.id";
        Object[] arguments = auctionId == null ? new Object[0] : new Object[] {auctionId};
        return jdbc.query(sql, (result, row) -> {
            UUID referenceId = result.getObject("id", UUID.class);
            long resolvedAuctionId = result.getLong("auction_id");
            ExtractedKoMatcher.StructuredEvidence structured = new ExtractedKoMatcher.StructuredEvidence(
                    result.getString("structured_input_fingerprint").trim(),
                    StructuredKoMatcher.Status.valueOf(result.getString("structured_status")),
                    StructuredKoMatcher.Method.valueOf(result.getString("structured_method")),
                    result.getString("structured_rationale"),
                    result.getString("structured_matched_ko_code"),
                    result.getString("source_cadastral"),
                    result.getString("source_place_name"),
                    result.getString("source_municipality"),
                    result.getString("dictionary_version"),
                    result.getString("dictionary_source_sha256").trim(),
                    result.getString("normalizer_version"),
                    result.getString("alias_dataset_version"),
                    result.getString("alias_sha256").trim(),
                    result.getString("municipality_alias_dataset_version"),
                    trim(result.getString("municipality_alias_sha256")));
            return new ReferenceInput(
                    referenceId,
                    new ExtractedKoMatcher.Input(
                            referenceId,
                            resolvedAuctionId,
                            result.getString("raw_ko"),
                            result.getString("normalized_ko"),
                            result.getString("place_name"),
                            result.getString("municipality"),
                            ExtractedKoMatcher.KoProvenance.valueOf(
                                    result.getString("ko_provenance"))),
                    structured);
        }, arguments);
    }

    private PersistedResult find(UUID referenceId, String fingerprint) {
        List<PersistedResult> rows = jdbc.query("""
                SELECT reference_id, auction_id, input_fingerprint, status, method,
                       matched_ko_code, reconciliation_status, ko_provenance
                  FROM property_reference_ko_match_results
                 WHERE reference_id = ? AND input_fingerprint = ?
                """, (result, row) -> new PersistedResult(
                result.getObject("reference_id", UUID.class),
                result.getLong("auction_id"),
                result.getString("input_fingerprint").trim(),
                ExtractedKoMatcher.KoProvenance.valueOf(result.getString("ko_provenance")),
                StructuredKoMatcher.Status.valueOf(result.getString("status")),
                ExtractedKoMatcher.Method.valueOf(result.getString("method")),
                result.getString("matched_ko_code"),
                ExtractedKoMatcher.Reconciliation.valueOf(result.getString("reconciliation_status"))),
                referenceId, fingerprint);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void persist(
            ReferenceInput input,
            ExtractedKoMatcher.Match match,
            KoDictionarySnapshot dictionary) {
        ExtractedKoMatcher.StructuredEvidence structured = match.structuredEvidence();
        Map<String, Object> reconciliationEvidence = new LinkedHashMap<>();
        reconciliationEvidence.put("schemaVersion", "extracted-structured-ko-reconciliation-v1");
        reconciliationEvidence.put("decision", match.reconciliation().name());
        reconciliationEvidence.put("koProvenance", match.koProvenance().name());
        reconciliationEvidence.put("textStatus", match.textStatus().name());
        reconciliationEvidence.put("textMethod", match.textMethod().name());
        reconciliationEvidence.put("textMatchedKoCode", match.textMatchedKoCode());
        reconciliationEvidence.put("finalRationale", match.rationale());
        Map<String, Object> structuredJson = new LinkedHashMap<>();
        structuredJson.put("inputFingerprint", structured.inputFingerprint());
        structuredJson.put("status", structured.status().name());
        structuredJson.put("method", structured.method().name());
        structuredJson.put("rationale", structured.rationale());
        structuredJson.put("matchedKoCode", structured.matchedKoCode());
        structuredJson.put("sourceCadastral", structured.sourceCadastral());
        structuredJson.put("sourcePlaceName", structured.sourcePlaceName());
        structuredJson.put("sourceMunicipality", structured.sourceMunicipality());
        structuredJson.put("dictionaryVersion", structured.dictionaryVersion());
        structuredJson.put("dictionarySourceSha256", structured.dictionarySourceSha256());
        reconciliationEvidence.put("structuredMatch", structuredJson);
        int inserted = jdbc.update("""
                INSERT INTO property_reference_ko_match_results (
                    reference_id, auction_id, input_fingerprint,
                    source_raw_ko, source_normalized_ko, query_normalized_ko,
                    source_place_name, source_municipality, ko_provenance,
                    status, method, rationale, matched_ko_code,
                    text_status, text_method, text_matched_ko_code, reconciliation_status,
                    structured_match_input_fingerprint, structured_status,
                    structured_method, structured_matched_ko_code,
                    dictionary_version, dictionary_source_sha256, normalizer_version,
                    alias_dataset_version, alias_sha256,
                    municipality_alias_dataset_version, municipality_alias_sha256,
                    candidates, reconciliation_evidence, resolved_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                          ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?)
                ON CONFLICT (reference_id, input_fingerprint) DO NOTHING
                """,
                match.referenceId(), match.auctionId(), match.inputFingerprint(),
                input.matchInput().rawKo(), input.matchInput().normalizedKo(), match.queryNormalizedKo(),
                input.matchInput().placeName(), input.matchInput().municipality(), match.koProvenance().name(),
                match.status().name(), match.method().name(), match.rationale(), match.matchedKoCode(),
                match.textStatus().name(), match.textMethod().name(), match.textMatchedKoCode(),
                match.reconciliation().name(),
                structured.inputFingerprint(), structured.status().name(), structured.method().name(),
                structured.matchedKoCode(),
                dictionary.version(), dictionary.sourceGpkgSha256(), dictionary.normalizerVersion(),
                dictionary.aliasDatasetVersion(), dictionary.aliasSha256(),
                dictionary.aliasDatasetVersion(), dictionary.municipalityAliasSha256(),
                json(match.candidates()), json(reconciliationEvidence), databaseTime(Instant.now(clock)));
        if (inserted != 1 && find(match.referenceId(), match.inputFingerprint()) == null) {
            throw new KoExtractedMatchException(
                    "CONCURRENT_MATCH_STATE", "extracted KO result was not persisted exactly once");
        }
    }

    private void selectCurrent(PersistedResult result) {
        jdbc.update("""
                INSERT INTO current_property_reference_ko_matches (
                    reference_id, auction_id, input_fingerprint, selected_at
                ) VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (reference_id) DO UPDATE SET
                    auction_id = EXCLUDED.auction_id,
                    input_fingerprint = EXCLUDED.input_fingerprint,
                    selected_at = EXCLUDED.selected_at
                WHERE current_property_reference_ko_matches.input_fingerprint
                      IS DISTINCT FROM EXCLUDED.input_fingerprint
                """, result.referenceId(), result.auctionId(), result.inputFingerprint());
    }

    private void applyToReference(PersistedResult result) {
        jdbc.update("""
                UPDATE property_references
                   SET parcel_identity_id = CASE
                           WHEN ko_code IS DISTINCT FROM ? THEN NULL
                           ELSE parcel_identity_id
                       END,
                       ko_code = ?
                 WHERE id = ? AND NOT user_reviewed
                   AND ko_code IS DISTINCT FROM ?
                """,
                result.matchedKoCode(), result.matchedKoCode(),
                result.referenceId(), result.matchedKoCode());
    }

    private void observe(UUID enrichmentRunId, PersistedResult result) {
        if (enrichmentRunId == null) {
            return;
        }
        jdbc.update("""
                INSERT INTO property_reference_ko_match_observations (
                    enrichment_run_id, auction_id, reference_id, input_fingerprint
                ) VALUES (?, ?, ?, ?)
                ON CONFLICT (enrichment_run_id, reference_id) DO NOTHING
                """,
                enrichmentRunId, result.auctionId(), result.referenceId(), result.inputFingerprint());
    }

    private static void requireSameDictionary(
            StructuredKoMatchService.RunResult structured,
            KoDictionarySnapshot dictionary) {
        if (!dictionary.version().equals(structured.dictionaryVersion())
                || !dictionary.sourceGpkgSha256().equals(structured.sourceGpkgSha256())
                || !dictionary.normalizerVersion().equals(structured.normalizerVersion())
                || !dictionary.aliasDatasetVersion().equals(structured.aliasDatasetVersion())
                || !dictionary.aliasSha256().equals(structured.aliasSha256())
                || !dictionary.municipalityAliasSha256().equals(structured.municipalityAliasSha256())) {
            throw new KoExtractedMatchException(
                    "STRUCTURED_KO_DICTIONARY_CHANGED",
                    "active KO dictionary changed while refreshing structured matches");
        }
    }

    private static void requireSameDictionary(
            ExtractedKoMatcher.StructuredEvidence structured,
            KoDictionarySnapshot dictionary) {
        if (!dictionary.version().equals(structured.dictionaryVersion())
                || !dictionary.sourceGpkgSha256().equals(structured.dictionarySourceSha256())
                || !dictionary.normalizerVersion().equals(structured.normalizerVersion())
                || !dictionary.aliasDatasetVersion().equals(structured.aliasDatasetVersion())
                || !dictionary.aliasSha256().equals(structured.aliasSha256())
                || !dictionary.aliasDatasetVersion().equals(structured.municipalityAliasDatasetVersion())
                || !dictionary.municipalityAliasSha256().equals(structured.municipalityAliasSha256())) {
            throw new KoExtractedMatchException(
                    "STRUCTURED_KO_PROVENANCE_STALE",
                    "structured and extracted KO matches do not use one dictionary snapshot");
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new KoExtractedMatchException(
                    "EXTRACTED_KO_EVIDENCE_INVALID", "could not serialize extracted KO match evidence");
        }
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static OffsetDateTime databaseTime(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static BigDecimal percent(long numerator, long denominator) {
        return denominator == 0
                ? BigDecimal.ZERO.setScale(2)
                : BigDecimal.valueOf(numerator).multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);
    }

    private record ReferenceInput(
            UUID referenceId,
            ExtractedKoMatcher.Input matchInput,
            ExtractedKoMatcher.StructuredEvidence structured) {
    }

    private record PersistedResult(
            UUID referenceId,
            long auctionId,
            String inputFingerprint,
            ExtractedKoMatcher.KoProvenance koProvenance,
            StructuredKoMatcher.Status status,
            ExtractedKoMatcher.Method method,
            String matchedKoCode,
            ExtractedKoMatcher.Reconciliation reconciliation) {

        static PersistedResult from(ExtractedKoMatcher.Match match) {
            return new PersistedResult(
                    match.referenceId(), match.auctionId(), match.inputFingerprint(), match.koProvenance(),
                    match.status(), match.method(), match.matchedKoCode(), match.reconciliation());
        }
    }

    private record Outcome(PersistedResult result, boolean processed) {
    }

    private record Counters(
            long processed,
            EnumMap<StructuredKoMatcher.Status, Long> statuses,
            EnumMap<ExtractedKoMatcher.Method, Long> methods,
            EnumMap<ExtractedKoMatcher.Reconciliation, Long> reconciliations,
            EnumMap<ExtractedKoMatcher.KoProvenance, Long> provenances,
            EnumMap<ExtractedKoMatcher.KoProvenance, Long> matchedByProvenance,
            EnumMap<ExtractedKoMatcher.KoProvenance,
                    EnumMap<ExtractedKoMatcher.Reconciliation, Long>> reconciliationByProvenance) {

        static Counters from(List<Outcome> outcomes) {
            EnumMap<StructuredKoMatcher.Status, Long> statuses = initialized(StructuredKoMatcher.Status.class);
            EnumMap<ExtractedKoMatcher.Method, Long> methods = initialized(ExtractedKoMatcher.Method.class);
            EnumMap<ExtractedKoMatcher.Reconciliation, Long> reconciliations =
                    initialized(ExtractedKoMatcher.Reconciliation.class);
            EnumMap<ExtractedKoMatcher.KoProvenance, Long> provenances =
                    initialized(ExtractedKoMatcher.KoProvenance.class);
            EnumMap<ExtractedKoMatcher.KoProvenance, Long> matchedByProvenance =
                    initialized(ExtractedKoMatcher.KoProvenance.class);
            EnumMap<ExtractedKoMatcher.KoProvenance,
                    EnumMap<ExtractedKoMatcher.Reconciliation, Long>> reconciliationByProvenance =
                    new EnumMap<>(ExtractedKoMatcher.KoProvenance.class);
            for (ExtractedKoMatcher.KoProvenance provenance : ExtractedKoMatcher.KoProvenance.values()) {
                reconciliationByProvenance.put(
                        provenance, initialized(ExtractedKoMatcher.Reconciliation.class));
            }
            long processed = 0;
            for (Outcome outcome : outcomes) {
                processed += outcome.processed() ? 1 : 0;
                increment(statuses, outcome.result().status());
                increment(methods, outcome.result().method());
                increment(reconciliations, outcome.result().reconciliation());
                increment(provenances, outcome.result().koProvenance());
                increment(
                        reconciliationByProvenance.get(outcome.result().koProvenance()),
                        outcome.result().reconciliation());
                if (outcome.result().status() == StructuredKoMatcher.Status.MATCHED) {
                    increment(matchedByProvenance, outcome.result().koProvenance());
                }
            }
            return new Counters(
                    processed, statuses, methods, reconciliations,
                    provenances, matchedByProvenance, reconciliationByProvenance);
        }

        long status(StructuredKoMatcher.Status status) {
            return statuses.get(status);
        }

        long reconciliation(ExtractedKoMatcher.Reconciliation reconciliation) {
            return reconciliations.get(reconciliation);
        }

        long provenance(ExtractedKoMatcher.KoProvenance provenance) {
            return provenances.get(provenance);
        }

        long matched(ExtractedKoMatcher.KoProvenance provenance) {
            return matchedByProvenance.get(provenance);
        }

        Map<String, Long> methodCounts() {
            return named(methods);
        }

        Map<String, Long> reconciliationCounts() {
            return named(reconciliations);
        }

        Map<String, Map<String, Long>> reconciliationByKoProvenance() {
            Map<String, Map<String, Long>> named = new TreeMap<>();
            reconciliationByProvenance.forEach(
                    (provenance, counts) -> named.put(provenance.name(), named(counts)));
            return named;
        }

        private static <E extends Enum<E>> EnumMap<E, Long> initialized(Class<E> type) {
            EnumMap<E, Long> counts = new EnumMap<>(type);
            for (E value : type.getEnumConstants()) {
                counts.put(value, 0L);
            }
            return counts;
        }

        private static <E extends Enum<E>> void increment(EnumMap<E, Long> counts, E key) {
            counts.compute(key, (ignored, value) -> value + 1);
        }

        private static <E extends Enum<E>> Map<String, Long> named(EnumMap<E, Long> counts) {
            Map<String, Long> named = new TreeMap<>();
            counts.forEach((key, value) -> named.put(key.name(), value));
            return named;
        }
    }

    record AuctionResult(int referenceCount, String evidenceSha256) {
    }

    public record RunResult(
            UUID runId,
            UUID structuredRunId,
            Instant startedAt,
            Instant finishedAt,
            long durationMillis,
            String matcherVersion,
            String dictionaryVersion,
            String sourceDate,
            String sourceGpkgSha256,
            String normalizerVersion,
            String aliasDatasetVersion,
            String aliasSha256,
            String municipalityAliasDatasetVersion,
            String municipalityAliasSha256,
            long populationCount,
            long processedCount,
            long unchangedCount,
            long matchedCount,
            long ambiguousCount,
            long notFoundCount,
            long invalidCount,
            long conflictCount,
            BigDecimal overallMatchRatePercent,
            long textExtractedCount,
            long structuredFallbackCount,
            long unresolvedKoProvenanceCount,
            long textExtractedMatchedCount,
            long structuredFallbackMatchedCount,
            BigDecimal textExtractedMatchRatePercent,
            BigDecimal structuredFallbackMatchRatePercent,
            Map<String, Long> methodCounts,
            Map<String, Long> reconciliationCounts,
            Map<String, Map<String, Long>> reconciliationByKoProvenance) {
    }
}
