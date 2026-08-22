package rs.sud.eaukcija.komatching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.sud.eaukcija.addressregistry.SerbianNameNormalizer;

class StructuredKoMatcherTest {

    @TempDir
    Path tempDirectory;

    private ObjectMapper objectMapper;
    private KoDictionarySnapshot dictionary;
    private StructuredKoMatcher matcher;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        Path root = KoDictionaryTestArtifact.create(tempDirectory.resolve("dictionary"), objectMapper);
        dictionary = new KoDictionarySnapshotLoader(objectMapper).load(root);
        matcher = new StructuredKoMatcher(dictionary, 5);
    }

    @Test
    void queryAndDictionarySidesUseTheIdenticalNormalizerImplementation() {
        assertThat(dictionary.normalizerVersion()).isEqualTo(SerbianNameNormalizer.CONTRACT_VERSION);
        assertThat(StructuredKoMatcher.normalizeQuery("  Чајетина!!! "))
                .isEqualTo(SerbianNameNormalizer.normalize("  Чајетина!!! "))
                .isEqualTo("CAJETINA");
        assertThat(dictionary.normalizedIndex()).containsKey(StructuredKoMatcher.normalizeQuery("Čajetina"));
    }

    @Test
    void exactOfficialNamesMatchAcrossScriptsCasePunctuationAndDiacritics() {
        StructuredKoMatcher.Match cyrillic = matcher.match(
                new StructuredKoMatcher.Input(1, "  чАЈЕТИНА. ", "Насеље А", "Општина А"));
        StructuredKoMatcher.Match latin = matcher.match(
                new StructuredKoMatcher.Input(2, "Čajetina", "Naselje A", "M1"));

        assertThat(cyrillic.status()).isEqualTo(StructuredKoMatcher.Status.MATCHED);
        assertThat(cyrillic.method()).isEqualTo(StructuredKoMatcher.Method.EXACT_NORMALIZED_NAME);
        assertThat(cyrillic.matchedKoCode()).isEqualTo("100001");
        assertThat(latin.status()).isEqualTo(StructuredKoMatcher.Status.MATCHED);
        assertThat(latin.matchedKoCode()).isEqualTo("100001");
        assertThat(latin.candidates().get(0).municipalityContextMatch()).isTrue();
        assertThat(latin.candidates().get(0).placeContextMatch()).isTrue();
    }

    @Test
    void exactCodeWinsBeforeNamesOrContext() {
        StructuredKoMatcher.Match result = matcher.match(
                new StructuredKoMatcher.Input(3, "200001", "unrelated", "Општина Б"));

        assertThat(result.status()).isEqualTo(StructuredKoMatcher.Status.MATCHED);
        assertThat(result.method()).isEqualTo(StructuredKoMatcher.Method.EXACT_CODE);
        assertThat(result.matchedKoCode()).isEqualTo("200001");
    }

    @Test
    void duplicateNamesRemainAmbiguousWithoutAUniqueMunicipality() {
        StructuredKoMatcher.Match missing = matcher.match(
                new StructuredKoMatcher.Input(4, "Град", "Насеље А", null));
        StructuredKoMatcher.Match wrong = matcher.match(
                new StructuredKoMatcher.Input(5, "Grad", "Naselje A", "Unknown"));

        assertThat(missing.status()).isEqualTo(StructuredKoMatcher.Status.AMBIGUOUS);
        assertThat(missing.matchedKoCode()).isNull();
        assertThat(missing.candidates()).extracting(StructuredKoMatcher.Candidate::koCode)
                .containsExactly("300001", "300002");
        assertThat(wrong.status()).isEqualTo(StructuredKoMatcher.Status.AMBIGUOUS);
        assertThat(wrong.matchedKoCode()).isNull();
    }

    @Test
    void structuredMunicipalityDeterministicallyDisambiguatesAnExactName() {
        StructuredKoMatcher.Match result = matcher.match(
                new StructuredKoMatcher.Input(6, "ГРАД", "Насеље Б", "Opština B"));

        assertThat(result.status()).isEqualTo(StructuredKoMatcher.Status.MATCHED);
        assertThat(result.method()).isEqualTo(StructuredKoMatcher.Method.MUNICIPALITY_CONTEXT);
        assertThat(result.matchedKoCode()).isEqualTo("300002");
        assertThat(result.candidates()).hasSize(2);
        assertThat(result.candidates()).filteredOn(StructuredKoMatcher.Candidate::municipalityContextMatch)
                .extracting(StructuredKoMatcher.Candidate::koCode)
                .containsExactly("300002");
    }

    @Test
    void reviewedMunicipalityAliasDisambiguatesAcrossCyrillicAndLatinWithEvidence() {
        StructuredKoMatcher.Match cyrillic = matcher.match(
                new StructuredKoMatcher.Input(13, "ГРАД", "Насеље Б", "Општина Б-град"));
        StructuredKoMatcher.Match latin = matcher.match(
                new StructuredKoMatcher.Input(14, "Grad", "Naselje B", "Opština B-grad"));

        assertThat(cyrillic.status()).isEqualTo(StructuredKoMatcher.Status.MATCHED);
        assertThat(cyrillic.method()).isEqualTo(StructuredKoMatcher.Method.MUNICIPALITY_CONTEXT);
        assertThat(cyrillic.matchedKoCode()).isEqualTo("300002");
        assertThat(cyrillic.rationale()).contains("REVIEWED_ALIAS");
        assertThat(cyrillic.candidates()).filteredOn(StructuredKoMatcher.Candidate::municipalityContextMatch)
                .singleElement().satisfies(candidate ->
                        assertThat(candidate.municipalityAliasReviews()).singleElement().satisfies(alias -> {
                            assertThat(alias.id()).isEqualTo("opstina-b-grad");
                            assertThat(alias.municipalityCode()).isEqualTo("M2");
                            assertThat(alias.provenance()).isEqualTo("Reviewed municipality fixture");
                        }));
        assertThat(latin.status()).isEqualTo(StructuredKoMatcher.Status.MATCHED);
        assertThat(latin.matchedKoCode()).isEqualTo("300002");
    }

    @Test
    void collidingReviewedMunicipalityAliasCannotSelectEvenOneCandidate() {
        StructuredKoMatcher.Match result = matcher.match(
                new StructuredKoMatcher.Input(15, "ГРАД", "Насеље Б", "Заједничка-портал"));

        assertThat(result.status()).isEqualTo(StructuredKoMatcher.Status.AMBIGUOUS);
        assertThat(result.matchedKoCode()).isNull();
        assertThat(result.rationale()).contains("ALIAS_COLLISION");
        assertThat(result.candidates()).allMatch(StructuredKoMatcher.Candidate::municipalityIdentityCollision)
                .allMatch(candidate -> !candidate.municipalityContextMatch());
        assertThat(result.candidates()).flatExtracting(StructuredKoMatcher.Candidate::municipalityAliasReviews)
                .extracting(StructuredKoMatcher.MunicipalityAliasEvidence::id)
                .containsExactlyInAnyOrder("shared-portal-m1", "shared-portal-m2");
        assertThat(result.rationale()).contains("M1, M2");
        assertThat(result.candidates()).allSatisfy(candidate ->
                assertThat(candidate.collidingMunicipalityCodes()).containsExactly("M1", "M2"));
    }

    @Test
    void reviewedAliasCollidingWithAnOfficialMunicipalityNameRemainsAmbiguousWithEvidence() {
        StructuredKoMatcher.Match result = matcher.match(
                new StructuredKoMatcher.Input(16, "ГРАД", "Насеље А", "Општина А"));

        assertThat(result.status()).isEqualTo(StructuredKoMatcher.Status.AMBIGUOUS);
        assertThat(result.matchedKoCode()).isNull();
        assertThat(result.rationale()).contains("ALIAS_COLLISION");
        assertThat(result.candidates()).allMatch(StructuredKoMatcher.Candidate::municipalityIdentityCollision)
                .allMatch(candidate -> !candidate.municipalityContextMatch());
        assertThat(result.candidates()).filteredOn(candidate -> candidate.koCode().equals("300002"))
                .singleElement().satisfies(candidate ->
                        assertThat(candidate.municipalityAliasReviews())
                                .extracting(StructuredKoMatcher.MunicipalityAliasEvidence::id)
                                .containsExactly("official-name-collision-m2"));
        assertThat(result.rationale()).contains("M1, M2");
    }

    @Test
    void officialMunicipalityNamesThatCollideAreNotBlamedOnAReviewedAlias() {
        StructuredKoMatcher.Match result = matcher.match(
                new StructuredKoMatcher.Input(17, "Поље", "Насеље В", "Општина В"));

        assertThat(result.status()).isEqualTo(StructuredKoMatcher.Status.AMBIGUOUS);
        assertThat(result.matchedKoCode()).isNull();
        assertThat(result.rationale()).startsWith("AMBIGUOUS_MUNICIPALITY_IDENTITY_COLLISION:")
                .contains("M4, M5")
                .doesNotContain("alias");
        assertThat(result.candidates()).allMatch(StructuredKoMatcher.Candidate::municipalityIdentityCollision)
                .allMatch(candidate -> !candidate.municipalityContextMatch())
                .allMatch(candidate -> candidate.municipalityAliasReviews().isEmpty());
    }

    @Test
    void loaderPrecomputesOfficialMunicipalityNamesAndCodesForConstantTimeContextLookup() {
        assertThat(dictionary.municipalityCodesByNormalizedName())
                .containsEntry("OPSTINA A", java.util.List.of("M1"))
                .containsEntry("M1", java.util.List.of("M1"));
    }

    @Test
    void reviewedHistoricalAliasRetainsItsCompleteReviewEvidence() {
        StructuredKoMatcher.Match result = matcher.match(
                new StructuredKoMatcher.Input(7, "Цариброд", "Димитровград", "Димитровград"));

        assertThat(result.status()).isEqualTo(StructuredKoMatcher.Status.MATCHED);
        assertThat(result.method()).isEqualTo(StructuredKoMatcher.Method.REVIEWED_ALIAS);
        assertThat(result.matchedKoCode()).isEqualTo("200001");
        assertThat(result.candidates().get(0).aliasReviews()).singleElement().satisfies(alias -> {
            assertThat(alias.id()).isEqualTo("caribrod-1930");
            assertThat(alias.provenance()).isEqualTo("Reviewed historical gazette fixture");
            assertThat(alias.sourceReference()).isEqualTo("fixture://gazette/1930");
            assertThat(alias.reviewer()).isEqualTo("fixture-reviewer");
            assertThat(alias.reviewedAt()).isEqualTo("2026-08-20");
        });
    }

    @Test
    void fuzzyCandidatesAreRankedButNeverAutoSelected() {
        StructuredKoMatcher.Match result = matcher.match(
                new StructuredKoMatcher.Input(8, "Čajetinaa", "Naselje A", "Opština A"));

        assertThat(result.status()).isEqualTo(StructuredKoMatcher.Status.NOT_FOUND);
        assertThat(result.method()).isEqualTo(StructuredKoMatcher.Method.FUZZY_REVIEW);
        assertThat(result.matchedKoCode()).isNull();
        assertThat(result.candidates()).isNotEmpty();
        assertThat(result.candidates().get(0).koCode()).isEqualTo("100001");
        assertThat(result.candidates().get(0).editDistance()).isEqualTo(1);
        assertThat(result.candidates().get(0).similarityBasisPoints())
                .isGreaterThanOrEqualTo(StructuredKoMatcher.MIN_FUZZY_SIMILARITY_BASIS_POINTS);
        assertThat(result.rationale()).contains("not auto-selected");
    }

    @Test
    void implausibleFuzzyCandidatesBelowTheSimilarityFloorAreDropped() {
        StructuredKoMatcher.Match result = matcher.match(
                new StructuredKoMatcher.Input(12, "XYZXYZXYZ", "Naselje A", "Opština A"));

        assertThat(result.status()).isEqualTo(StructuredKoMatcher.Status.NOT_FOUND);
        assertThat(result.method()).isEqualTo(StructuredKoMatcher.Method.NONE);
        assertThat(result.matchedKoCode()).isNull();
        assertThat(result.candidates()).isEmpty();
        assertThat(result.rationale()).contains("70% review-similarity floor");
    }

    @Test
    void missingAndMalformedStructuredNamesAreInvalid() {
        assertThat(matcher.match(new StructuredKoMatcher.Input(9, null, "place", "municipality")).status())
                .isEqualTo(StructuredKoMatcher.Status.INVALID);
        assertThat(matcher.match(new StructuredKoMatcher.Input(10, " -- ", "place", "municipality")).status())
                .isEqualTo(StructuredKoMatcher.Status.INVALID);
    }

    @Test
    void sourceAndEveryVersionInputParticipateInTheIdempotencyFingerprint() {
        StructuredKoMatcher.Input first = new StructuredKoMatcher.Input(11, "Čajetina", "Naselje A", "Opština A");
        StructuredKoMatcher.Input changed = new StructuredKoMatcher.Input(11, "Čajetina", "Naselje B", "Opština A");
        String baseline = StructuredKoMatcher.fingerprint(first, dictionary);

        assertThat(baseline).isEqualTo(StructuredKoMatcher.fingerprint(first, dictionary));
        assertThat(baseline).isNotEqualTo(StructuredKoMatcher.fingerprint(changed, dictionary));
        assertThat(baseline).isNotEqualTo(StructuredKoMatcher.fingerprint(
                first, copyDictionary("different-dictionary", dictionary.normalizerVersion(),
                        dictionary.aliasDatasetVersion(), dictionary.aliasSha256(),
                        dictionary.municipalityAliasSha256())));
        assertThat(baseline).isNotEqualTo(StructuredKoMatcher.fingerprint(
                first, copyDictionary(dictionary.version(), "serbian-name-v2",
                        dictionary.aliasDatasetVersion(), dictionary.aliasSha256(),
                        dictionary.municipalityAliasSha256())));
        assertThat(baseline).isNotEqualTo(StructuredKoMatcher.fingerprint(
                first, copyDictionary(dictionary.version(), dictionary.normalizerVersion(),
                        "reviewed-aliases-v2", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                        dictionary.municipalityAliasSha256())));
        assertThat(baseline).isNotEqualTo(StructuredKoMatcher.fingerprint(
                first, copyDictionary(dictionary.version(), dictionary.normalizerVersion(),
                        dictionary.aliasDatasetVersion(), dictionary.aliasSha256(),
                        "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc")));
    }

    @Test
    void corruptDictionaryBytesFailBeforeAMatchCanRun() throws Exception {
        Path root = KoDictionaryTestArtifact.create(tempDirectory.resolve("corrupt"), objectMapper);
        String active = Files.readString(root.resolve("ACTIVE")).trim();
        Files.writeString(
                root.resolve("versions").resolve(active).resolve("normalized-index.ndjson"),
                "tampered\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);

        assertThatThrownBy(() -> new KoDictionarySnapshotLoader(objectMapper).load(root))
                .isInstanceOfSatisfying(KoStructuredMatchException.class,
                        error -> assertThat(error.code()).isEqualTo("DICTIONARY_FILE_CHECKSUM_MISMATCH"));
    }

    @Test
    void legacyManifestFormatIsRejectedAsAVersionMismatchBeforeFieldValidation() throws Exception {
        Path root = KoDictionaryTestArtifact.create(tempDirectory.resolve("legacy-manifest"), objectMapper);
        String active = Files.readString(root.resolve("ACTIVE")).trim();
        Path manifestFile = root.resolve("versions").resolve(active).resolve("manifest.json");
        com.fasterxml.jackson.databind.node.ObjectNode manifest =
                (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(manifestFile.toFile());
        manifest.put("formatVersion", 1);
        Files.writeString(manifestFile, objectMapper.writeValueAsString(manifest) + "\n", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> new KoDictionarySnapshotLoader(objectMapper).load(root))
                .isInstanceOfSatisfying(KoStructuredMatchException.class, error -> {
                    assertThat(error.code()).isEqualTo("DICTIONARY_FORMAT_VERSION_MISMATCH");
                    assertThat(error.getMessage()).contains("formatVersion 1", "expected 2");
                });
    }

    @Test
    void unexpectedLoaderRuntimeBugsAreNotMisreportedAsCorruptOperatorData() throws Exception {
        Path root = KoDictionaryTestArtifact.create(tempDirectory.resolve("loader-bug"), objectMapper);
        ObjectMapper brokenMapper = new ObjectMapper() {
            @Override
            public JsonNode readTree(File file) throws IOException {
                throw new NullPointerException("simulated loader bug");
            }
        };

        assertThatThrownBy(() -> new KoDictionarySnapshotLoader(brokenMapper).load(root))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("simulated loader bug");
    }

    private KoDictionarySnapshot copyDictionary(
            String version,
            String normalizer,
            String aliasVersion,
            String aliasSha256,
            String municipalityAliasSha256) {
        return new KoDictionarySnapshot(
                version,
                dictionary.sourceDate(),
                dictionary.sourceGpkgSha256(),
                normalizer,
                aliasVersion,
                aliasSha256,
                municipalityAliasSha256,
                dictionary.entriesByCode(),
                dictionary.normalizedIndex(),
                dictionary.municipalityCodesByNormalizedName(),
                dictionary.aliasesById(),
                dictionary.municipalityAliasesById(),
                dictionary.municipalityAliasesByNormalizedName());
    }
}
