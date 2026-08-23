package rs.sud.eaukcija.komatching;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Database orchestration, idempotency, and population reporting for issue #37. */
@Service
public class StructuredKoMatchService {

    /**
     * Shared population lock used by downstream consumers that need one coherent #37 snapshot.
     * Consumers needing another advisory lock must acquire this lock first, then their own lock.
     */
    public static final long POPULATION_LOCK_ID = 37_003_700_014L;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final KoDictionarySnapshotLoader dictionaryLoader;
    private final StructuredKoMatchProperties properties;
    private final Clock clock;

    @Autowired
    public StructuredKoMatchService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            KoDictionarySnapshotLoader dictionaryLoader,
            StructuredKoMatchProperties properties) {
        this(jdbc, objectMapper, dictionaryLoader, properties, Clock.systemUTC());
    }

    StructuredKoMatchService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            KoDictionarySnapshotLoader dictionaryLoader,
            StructuredKoMatchProperties properties,
            Clock clock) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.dictionaryLoader = dictionaryLoader;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public RunResult run() {
        properties.validate();
        Instant started = Instant.now(clock);
        KoDictionarySnapshot dictionary = dictionaryLoader.load(properties.getDictionaryDirectory());
        StructuredKoMatcher matcher = new StructuredKoMatcher(
                dictionary, StructuredKoMatcher.DEFAULT_FUZZY_CANDIDATE_LIMIT);

        // One process owns a database-wide match transaction. This makes the
        // unchanged test and the conditional upsert one atomic decision.
        jdbc.execute("SELECT pg_advisory_xact_lock(" + POPULATION_LOCK_ID + ")");

        List<StructuredKoMatcher.Input> inputs = jdbc.query("""
                SELECT id, cadastral, place_name, municipality
                FROM auctions
                ORDER BY id
                """, (resultSet, rowNumber) -> new StructuredKoMatcher.Input(
                resultSet.getLong("id"),
                resultSet.getString("cadastral"),
                resultSet.getString("place_name"),
                resultSet.getString("municipality")));
        Map<Long, ExistingMatch> existing = jdbc.query("""
                SELECT auction_id, input_fingerprint, status, method
                FROM auction_structured_ko_matches
                """, resultSet -> {
            Map<Long, ExistingMatch> rows = new LinkedHashMap<>();
            while (resultSet.next()) {
                ExistingMatch match = new ExistingMatch(
                        resultSet.getString("input_fingerprint"),
                        StructuredKoMatcher.Status.valueOf(resultSet.getString("status")),
                        StructuredKoMatcher.Method.valueOf(resultSet.getString("method")));
                rows.put(resultSet.getLong("auction_id"), match);
            }
            return rows;
        });

        EnumMap<StructuredKoMatcher.Status, Long> statusCounts = initializedStatusCounts();
        EnumMap<StructuredKoMatcher.Method, Long> methodCounts = initializedMethodCounts();
        long processed = 0;
        long unchanged = 0;
        Instant resolvedAt = Instant.now(clock);
        for (StructuredKoMatcher.Input input : inputs) {
            String fingerprint = StructuredKoMatcher.fingerprint(input, dictionary);
            ExistingMatch prior = existing.get(input.auctionId());
            if (prior != null && prior.inputFingerprint().equals(fingerprint)) {
                unchanged++;
                increment(statusCounts, prior.status());
                increment(methodCounts, prior.method());
                continue;
            }
            StructuredKoMatcher.Match match = matcher.match(input);
            persist(input, match, dictionary, resolvedAt);
            processed++;
            increment(statusCounts, match.status());
            increment(methodCounts, match.method());
        }

        Instant finished = Instant.now(clock);
        UUID runId = UUID.randomUUID();
        String methodCountsJson = json(new TreeMap<>(methodCounts.entrySet().stream().collect(
                Collectors.toMap(entry -> entry.getKey().name(), Map.Entry::getValue))));
        jdbc.update("""
                INSERT INTO structured_ko_match_runs (
                    id, started_at, finished_at, dictionary_version, dictionary_source_sha256,
                    normalizer_version, alias_dataset_version, alias_sha256,
                    municipality_alias_dataset_version, municipality_alias_sha256,
                    population_count, processed_count, unchanged_count,
                    matched_count, ambiguous_count, not_found_count, invalid_count, method_counts
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                """,
                runId, databaseTime(started), databaseTime(finished),
                dictionary.version(), dictionary.sourceGpkgSha256(),
                dictionary.normalizerVersion(), dictionary.aliasDatasetVersion(), dictionary.aliasSha256(),
                // The loader requires one shared review-dataset version; only the municipality subset hash differs.
                // Keep the dedicated version column for evidence-schema symmetry with that independent subset hash.
                dictionary.aliasDatasetVersion(), dictionary.municipalityAliasSha256(),
                inputs.size(), processed, unchanged,
                statusCounts.get(StructuredKoMatcher.Status.MATCHED),
                statusCounts.get(StructuredKoMatcher.Status.AMBIGUOUS),
                statusCounts.get(StructuredKoMatcher.Status.NOT_FOUND),
                statusCounts.get(StructuredKoMatcher.Status.INVALID),
                methodCountsJson);

        long matched = statusCounts.get(StructuredKoMatcher.Status.MATCHED);
        BigDecimal matchRate = inputs.isEmpty()
                ? BigDecimal.ZERO.setScale(2)
                : BigDecimal.valueOf(matched)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(inputs.size()), 2, RoundingMode.HALF_UP);
        return new RunResult(
                runId,
                started,
                finished,
                Duration.between(started, finished).toMillis(),
                dictionary.version(),
                dictionary.sourceDate().toString(),
                dictionary.sourceGpkgSha256(),
                dictionary.normalizerVersion(),
                dictionary.aliasDatasetVersion(),
                dictionary.aliasSha256(),
                dictionary.aliasDatasetVersion(),
                dictionary.municipalityAliasSha256(),
                inputs.size(),
                processed,
                unchanged,
                matched,
                statusCounts.get(StructuredKoMatcher.Status.AMBIGUOUS),
                statusCounts.get(StructuredKoMatcher.Status.NOT_FOUND),
                statusCounts.get(StructuredKoMatcher.Status.INVALID),
                matchRate,
                methodCounts.entrySet().stream().collect(Collectors.toMap(
                        entry -> entry.getKey().name(), Map.Entry::getValue,
                        (left, right) -> left, TreeMap::new)));
    }

    private void persist(
            StructuredKoMatcher.Input input,
            StructuredKoMatcher.Match match,
            KoDictionarySnapshot dictionary,
            Instant resolvedAt) {
        int changed = jdbc.update("""
                INSERT INTO auction_structured_ko_matches (
                    auction_id, source_cadastral, source_place_name, source_municipality,
                    input_fingerprint, status, method, rationale, matched_ko_code,
                    dictionary_version, dictionary_source_sha256, normalizer_version,
                    alias_dataset_version, alias_sha256,
                    municipality_alias_dataset_version, municipality_alias_sha256,
                    candidates, resolved_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
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
                // Kept as a separate column for evidence-query symmetry with the independent subset hash.
                dictionary.aliasDatasetVersion(), dictionary.municipalityAliasSha256(),
                json(match.candidates()), databaseTime(resolvedAt));
        if (changed != 1) {
            throw new KoStructuredMatchException(
                    "CONCURRENT_MATCH_STATE", "auction " + input.auctionId() + " was not persisted exactly once");
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new KoStructuredMatchException(
                    "MATCH_SERIALIZATION_FAILED", "could not serialize KO match evidence", e);
        }
    }

    private static EnumMap<StructuredKoMatcher.Status, Long> initializedStatusCounts() {
        EnumMap<StructuredKoMatcher.Status, Long> counts = new EnumMap<>(StructuredKoMatcher.Status.class);
        for (StructuredKoMatcher.Status status : StructuredKoMatcher.Status.values()) {
            counts.put(status, 0L);
        }
        return counts;
    }

    private static EnumMap<StructuredKoMatcher.Method, Long> initializedMethodCounts() {
        EnumMap<StructuredKoMatcher.Method, Long> counts = new EnumMap<>(StructuredKoMatcher.Method.class);
        for (StructuredKoMatcher.Method method : StructuredKoMatcher.Method.values()) {
            counts.put(method, 0L);
        }
        return counts;
    }

    private static <E extends Enum<E>> void increment(EnumMap<E, Long> counts, E key) {
        counts.compute(key, (ignored, value) -> value + 1);
    }

    private static OffsetDateTime databaseTime(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private record ExistingMatch(
            String inputFingerprint,
            StructuredKoMatcher.Status status,
            StructuredKoMatcher.Method method) {
    }

    public record RunResult(
            UUID runId,
            Instant startedAt,
            Instant finishedAt,
            long durationMillis,
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
            BigDecimal matchRatePercent,
            Map<String, Long> methodCounts) {
    }
}
