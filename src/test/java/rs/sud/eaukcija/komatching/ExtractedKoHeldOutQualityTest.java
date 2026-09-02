package rs.sud.eaukcija.komatching;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import rs.sud.eaukcija.addressregistry.SerbianNameNormalizer;

/** Aggregate-only evaluation against the frozen issue-18 held-out labels. */
class ExtractedKoHeldOutQualityTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void exactMatchFalsePositivesAreZeroOnTheFrozenHeldOutCorpus() throws Exception {
        JsonNode authority = objectMapper.readTree(
                Path.of("corpus/property-references/v1/ko-authority.json").toFile());
        JsonNode heldOut = objectMapper.readTree(
                Path.of("corpus/property-references/v1/held-out.json").toFile());
        KoDictionarySnapshot dictionary = dictionary(authority);
        StructuredKoMatcher shared = new StructuredKoMatcher(dictionary, 0);
        ExtractedKoMatcher extracted = new ExtractedKoMatcher(dictionary, 0);

        int evaluated = 0;
        int exactMatches = 0;
        int falsePositives = 0;
        int unresolved = 0;
        for (JsonNode auction : heldOut.path("auctions")) {
            long auctionId = auction.path("auctionId").asLong();
            for (JsonNode expected : auction.path("expectedReferences")) {
                evaluated++;
                String koName = expected.path("koName").asText();
                String expectedCode = expected.path("koCode").asText();
                String annotationId = expected.path("annotationId").asText();
                StructuredKoMatcher.Match structured = shared.match(
                        new StructuredKoMatcher.Input(auctionId, koName, null, null));
                ExtractedKoMatcher.Match result = extracted.match(
                        new ExtractedKoMatcher.Input(
                                UUID.nameUUIDFromBytes(annotationId.getBytes(StandardCharsets.UTF_8)),
                                auctionId,
                                koName,
                                SerbianNameNormalizer.normalize(koName),
                                null,
                                null,
                                ExtractedKoMatcher.KoProvenance.TEXT_EXTRACTED),
                        evidence(structured, koName, dictionary));
                assertThat(result.method()).as(annotationId).isIn(
                        ExtractedKoMatcher.Method.EXACT_CODE,
                        ExtractedKoMatcher.Method.EXACT_NORMALIZED_NAME,
                        ExtractedKoMatcher.Method.REVIEWED_ALIAS,
                        ExtractedKoMatcher.Method.MUNICIPALITY_CONTEXT);
                if (result.status() == StructuredKoMatcher.Status.MATCHED) {
                    exactMatches++;
                    if (!expectedCode.equals(result.matchedKoCode())) {
                        falsePositives++;
                    }
                } else {
                    unresolved++;
                }
            }
        }

        assertThat(heldOut.path("split").asText()).isEqualTo("HELD_OUT");
        assertThat(evaluated).isEqualTo(37);
        assertThat(falsePositives).isZero();
        assertThat(unresolved).isZero();
        assertThat(exactMatches).isEqualTo(evaluated);
        System.out.printf(
                "ISSUE_33_HELD_OUT exact=%d false_positives=%d unresolved=%d%n",
                exactMatches, falsePositives, unresolved);
    }

    private static ExtractedKoMatcher.StructuredEvidence evidence(
            StructuredKoMatcher.Match match,
            String sourceKo,
            KoDictionarySnapshot dictionary) {
        return new ExtractedKoMatcher.StructuredEvidence(
                match.inputFingerprint(), match.status(), match.method(), match.rationale(),
                match.matchedKoCode(), sourceKo, null, null,
                dictionary.version(), dictionary.sourceGpkgSha256(), dictionary.normalizerVersion(),
                dictionary.aliasDatasetVersion(), dictionary.aliasSha256(),
                dictionary.aliasDatasetVersion(), dictionary.municipalityAliasSha256());
    }

    private static KoDictionarySnapshot dictionary(JsonNode authority) {
        Map<String, KoDictionarySnapshot.KoEntry> entries = new LinkedHashMap<>();
        Map<String, List<KoDictionarySnapshot.IndexCandidate>> index = new LinkedHashMap<>();
        for (JsonNode value : authority.path("entries")) {
            String code = value.path("koCode").asText();
            String cyrillic = value.path("officialNameCyrillic").asText();
            String latin = value.path("officialNameLatin").asText();
            List<String> names = java.util.stream.Stream.of(cyrillic, latin)
                    .map(SerbianNameNormalizer::normalize)
                    .distinct()
                    .sorted()
                    .toList();
            entries.put(code, new KoDictionarySnapshot.KoEntry(
                    code, cyrillic, latin, names, List.of(), List.of()));
            for (String name : names) {
                index.computeIfAbsent(name, ignored -> new ArrayList<>())
                        .add(new KoDictionarySnapshot.IndexCandidate(code, List.of(), true, List.of()));
            }
        }
        index.replaceAll((name, candidates) -> candidates.stream()
                .sorted(java.util.Comparator.comparing(KoDictionarySnapshot.IndexCandidate::koCode))
                .toList());
        String version = authority.path("dictionaryVersion").asText();
        String sourceHash = authority.path("sourceGpkgSha256").asText();
        String evidenceHash = authority.path("sourceDictionarySha256").asText();
        return new KoDictionarySnapshot(
                version,
                LocalDate.parse(version.substring(0, 10)),
                sourceHash,
                SerbianNameNormalizer.CONTRACT_VERSION,
                "held-out-authority-no-aliases-v1",
                evidenceHash,
                evidenceHash,
                Map.copyOf(entries),
                Map.copyOf(index),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of());
    }
}
