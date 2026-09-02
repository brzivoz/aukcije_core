package rs.sud.eaukcija.komatching;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rs.sud.eaukcija.addressregistry.SerbianNameNormalizer;

class ExtractedKoMatcherTest {

    @TempDir
    Path tempDirectory;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private KoDictionarySnapshot dictionary;
    private ExtractedKoMatcher matcher;

    @BeforeEach
    void setUp() throws Exception {
        Path root = KoDictionaryTestArtifact.create(tempDirectory.resolve("dictionary"), objectMapper);
        dictionary = new KoDictionarySnapshotLoader(objectMapper).load(root);
        matcher = new ExtractedKoMatcher(
                dictionary, StructuredKoMatcher.DEFAULT_FUZZY_CANDIDATE_LIMIT);
    }

    @Test
    void reviewedAcceptanceFixturesCoverScriptsContextAliasesInvalidityAndConflict() throws Exception {
        JsonNode fixture;
        try (InputStream input = getClass().getResourceAsStream(
                "/fixtures/ko-matching/extracted-ko-reviewed-v1.json")) {
            fixture = objectMapper.readTree(input);
        }
        assertThat(fixture.path("schemaVersion").asText())
                .isEqualTo("extracted-ko-reviewed-fixtures-v1");
        assertThat(fixture.path("review").path("reviewId").asText()).isNotBlank();
        assertThat(fixture.path("review").path("reviewer").asText()).isNotBlank();
        assertThat(fixture.path("review").path("reviewedAt").asText()).matches("\\d{4}-\\d{2}-\\d{2}");

        int ordinal = 0;
        for (JsonNode fixtureCase : fixture.path("cases")) {
            long auctionId = 33_000L + ++ordinal;
            UUID referenceId = UUID.nameUUIDFromBytes(
                    fixtureCase.path("id").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String place = nullableText(fixtureCase, "placeName");
            String municipality = nullableText(fixtureCase, "municipality");
            StructuredKoMatcher.Match structured = new StructuredKoMatcher(
                    dictionary, StructuredKoMatcher.DEFAULT_FUZZY_CANDIDATE_LIMIT).match(
                    new StructuredKoMatcher.Input(
                            auctionId,
                            nullableText(fixtureCase, "structuredKo"),
                            place,
                            municipality));
            ExtractedKoMatcher.Match result = matcher.match(
                    new ExtractedKoMatcher.Input(
                            referenceId,
                            auctionId,
                            nullableText(fixtureCase, "rawKo"),
                            nullableText(fixtureCase, "normalizedKo"),
                            place,
                            municipality,
                            ExtractedKoMatcher.KoProvenance.valueOf(
                                    fixtureCase.path("expectedKoProvenance").asText())),
                    evidence(structured, fixtureCase));

            assertThat(result.status().name()).as(fixtureCase.path("id").asText())
                    .isEqualTo(fixtureCase.path("expectedStatus").asText());
            assertThat(result.method().name()).as(fixtureCase.path("id").asText())
                    .isEqualTo(fixtureCase.path("expectedMethod").asText());
            assertThat(result.matchedKoCode()).as(fixtureCase.path("id").asText())
                    .isEqualTo(nullableText(fixtureCase, "expectedKoCode"));
            assertThat(result.reconciliation().name()).as(fixtureCase.path("id").asText())
                    .isEqualTo(fixtureCase.path("expectedReconciliation").asText());
            assertThat(result.koProvenance().name()).as(fixtureCase.path("id").asText())
                    .isEqualTo(fixtureCase.path("expectedKoProvenance").asText());
            if (fixtureCase.has("expectedRationalePrefix")) {
                assertThat(result.rationale()).as(fixtureCase.path("id").asText())
                        .startsWith(fixtureCase.path("expectedRationalePrefix").asText());
            }
        }
    }

    @Test
    void queryAndDictionarySidesCallTheSameNormalizerImplementation() {
        String raw = "  ЧàЈЕТИНА... ";
        String normalized = SerbianNameNormalizer.normalize(raw);
        StructuredKoMatcher.Match structured = structured(33_101L, "Čajetina", "Naselje A", "Opština A");

        ExtractedKoMatcher.Match result = matcher.match(
                new ExtractedKoMatcher.Input(
                        UUID.randomUUID(), 33_101L, raw, normalized, "Naselje A", "Opština A",
                        ExtractedKoMatcher.KoProvenance.TEXT_EXTRACTED),
                evidence(structured, raw, "Naselje A", "Opština A"));

        assertThat(StructuredKoMatcher.normalizeQuery(raw)).isEqualTo(normalized);
        assertThat(result.queryNormalizedKo()).isEqualTo(normalized).isEqualTo("CAJETINA");
        assertThat(dictionary.entriesByCode().get("100001").normalizedNames()).contains(normalized);
        assertThat(result.status()).isEqualTo(StructuredKoMatcher.Status.MATCHED);
    }

    @Test
    void fuzzyCandidatesRemainReviewOnlyAndGenuineConflictSelectsNeitherSide() {
        StructuredKoMatcher.Match structured = structured(33_102L, "Čajetina", "Naselje A", "Opština A");
        ExtractedKoMatcher.Match fuzzy = matcher.match(
                new ExtractedKoMatcher.Input(
                        UUID.randomUUID(), 33_102L, "Čajetinaa", "CAJETINAA", "Naselje A", "Opština A",
                        ExtractedKoMatcher.KoProvenance.TEXT_EXTRACTED),
                evidence(structured, "Čajetina", "Naselje A", "Opština A"));

        assertThat(fuzzy.status()).isEqualTo(StructuredKoMatcher.Status.NOT_FOUND);
        assertThat(fuzzy.method()).isEqualTo(ExtractedKoMatcher.Method.FUZZY_REVIEW);
        assertThat(fuzzy.matchedKoCode()).isNull();
        assertThat(fuzzy.candidates()).isNotEmpty();

        StructuredKoMatcher.Match sjenica = structured(33_103L, "Сјеница", "Урсуле", "Сјеница");
        ExtractedKoMatcher.Match conflict = matcher.match(
                new ExtractedKoMatcher.Input(
                        UUID.randomUUID(), 33_103L, "Урсуле", "URSULE", "Урсуле", "Сјеница",
                        ExtractedKoMatcher.KoProvenance.TEXT_EXTRACTED),
                evidence(sjenica, "Сјеница", "Урсуле", "Сјеница"));

        assertThat(conflict.textStatus()).isEqualTo(StructuredKoMatcher.Status.MATCHED);
        assertThat(conflict.textMatchedKoCode()).isEqualTo("500002");
        assertThat(conflict.structuredEvidence().matchedKoCode()).isEqualTo("500001");
        assertThat(conflict.status()).isEqualTo(StructuredKoMatcher.Status.AMBIGUOUS);
        assertThat(conflict.method()).isEqualTo(ExtractedKoMatcher.Method.STRUCTURED_CONFLICT);
        assertThat(conflict.matchedKoCode()).isNull();
        assertThat(conflict.rationale()).contains("neither candidate was selected");
    }

    @Test
    void everyReferenceAndVersionInputParticipatesInTheFingerprint() {
        UUID referenceId = UUID.randomUUID();
        ExtractedKoMatcher.Input input = new ExtractedKoMatcher.Input(
                referenceId, 33_104L, "Чајетина", "CAJETINA", "Насеље А", "Општина А",
                ExtractedKoMatcher.KoProvenance.TEXT_EXTRACTED);
        StructuredKoMatcher.Match structured = structured(33_104L, "Чајетина", "Насеље А", "Општина А");
        ExtractedKoMatcher.StructuredEvidence evidence = evidence(
                structured, "Чајетина", "Насеље А", "Општина А");
        String baseline = matcher.fingerprint(input, evidence);

        assertThat(matcher.fingerprint(input, evidence)).isEqualTo(baseline);
        assertThat(matcher.fingerprint(new ExtractedKoMatcher.Input(
                referenceId, 33_104L, "Чајетина", "DIFFERENT", "Насеље А", "Општина А",
                ExtractedKoMatcher.KoProvenance.TEXT_EXTRACTED), evidence))
                .isNotEqualTo(baseline);
        assertThat(matcher.fingerprint(new ExtractedKoMatcher.Input(
                UUID.randomUUID(), 33_104L, "Чајетина", "CAJETINA", "Насеље А", "Општина А",
                ExtractedKoMatcher.KoProvenance.TEXT_EXTRACTED), evidence))
                .isNotEqualTo(baseline);
        assertThat(matcher.fingerprint(new ExtractedKoMatcher.Input(
                referenceId, 33_104L, "Чајетина", "CAJETINA", "Насеље А", "Општина А",
                ExtractedKoMatcher.KoProvenance.STRUCTURED_FALLBACK), evidence))
                .isNotEqualTo(baseline);
        assertThat(matcher.fingerprint(input, new ExtractedKoMatcher.StructuredEvidence(
                "f".repeat(64), evidence.status(), evidence.method(), evidence.rationale(),
                evidence.matchedKoCode(), evidence.sourceCadastral(), evidence.sourcePlaceName(),
                evidence.sourceMunicipality(), evidence.dictionaryVersion(),
                evidence.dictionarySourceSha256(), evidence.normalizerVersion(),
                evidence.aliasDatasetVersion(), evidence.aliasSha256(),
                evidence.municipalityAliasDatasetVersion(), evidence.municipalityAliasSha256())))
                .isNotEqualTo(baseline);
    }

    private StructuredKoMatcher.Match structured(
            long auctionId, String ko, String place, String municipality) {
        return new StructuredKoMatcher(
                dictionary, StructuredKoMatcher.DEFAULT_FUZZY_CANDIDATE_LIMIT).match(
                new StructuredKoMatcher.Input(auctionId, ko, place, municipality));
    }

    private ExtractedKoMatcher.StructuredEvidence evidence(
            StructuredKoMatcher.Match match, JsonNode fixtureCase) {
        return evidence(
                match,
                nullableText(fixtureCase, "structuredKo"),
                nullableText(fixtureCase, "placeName"),
                nullableText(fixtureCase, "municipality"));
    }

    private ExtractedKoMatcher.StructuredEvidence evidence(
            StructuredKoMatcher.Match match,
            String cadastral,
            String place,
            String municipality) {
        return new ExtractedKoMatcher.StructuredEvidence(
                match.inputFingerprint(), match.status(), match.method(), match.rationale(),
                match.matchedKoCode(), cadastral, place, municipality,
                dictionary.version(), dictionary.sourceGpkgSha256(), dictionary.normalizerVersion(),
                dictionary.aliasDatasetVersion(), dictionary.aliasSha256(),
                dictionary.aliasDatasetVersion(), dictionary.municipalityAliasSha256());
    }

    private static String nullableText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
