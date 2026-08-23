package rs.sud.eaukcija.coarselocation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import rs.sud.eaukcija.spatial.LocationPrecision;

class CoarseLocationResolverTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private CoarseLocationResolver resolver;

    @BeforeEach
    void createResolver() {
        CentroidSnapshot.Centroid ko = centroid(
                CentroidSnapshot.Level.KO, "K100", "КО ТЕСТ", "KO TEST", List.of("M100"));
        CentroidSnapshot.Centroid cajetina = centroid(
                CentroidSnapshot.Level.SETTLEMENT, "S100", "ЧАЈЕТИНА", "ČAJETINA", List.of("M100"));
        CentroidSnapshot.Centroid gradA = centroid(
                CentroidSnapshot.Level.SETTLEMENT, "S200", "ГРАД", "GRAD", List.of("M100"));
        CentroidSnapshot.Centroid gradB = centroid(
                CentroidSnapshot.Level.SETTLEMENT, "S300", "ГРАД", "GRAD", List.of("M200"));
        CentroidSnapshot.Centroid municipalityA = centroid(
                CentroidSnapshot.Level.MUNICIPALITY, "M100", "ОПШТИНА А", "OPŠTINA A", List.of());
        CentroidSnapshot.Centroid municipalityB = centroid(
                CentroidSnapshot.Level.MUNICIPALITY, "M200", "ОПШТИНА Б", "OPŠTINA B", List.of());
        CentroidSnapshot snapshot = new CentroidSnapshot(
                CentroidTestArtifact.VERSION,
                java.time.LocalDate.parse("2026-08-23"),
                CentroidTestArtifact.SOURCE_HASH,
                Map.of("K100", ko),
                Map.of("S100", cajetina, "S200", gradA, "S300", gradB),
                Map.of("M100", municipalityA, "M200", municipalityB),
                Map.of("CAJETINA", List.of(cajetina), "GRAD", List.of(gradA, gradB)),
                Map.of("OPSTINA A", List.of(municipalityA), "OPSTINA B", List.of(municipalityB)));
        resolver = new CoarseLocationResolver(snapshot, objectMapper);
    }

    @Test
    void coversEveryHonestTierAndNeverProducesAddressStreetOrParcel() throws Exception {
        List<CoarseLocationResolver.Resolution> resolutions = List.of(
                resolver.resolve(input(1, "КО ТЕСТ", null, "Општина А", "MATCHED", "K100", aliasCandidate())),
                resolver.resolve(input(2, "НЕПОЗНАТО", "Čajetina", "Opština A", "NOT_FOUND", null, array())),
                resolver.resolve(input(3, "?", null, "Општина Б", "INVALID", null, array())),
                resolver.resolve(input(4, "ГРАД", "ЧАЈЕТИНА", null, "AMBIGUOUS", null, ambiguousCandidates())),
                resolver.resolve(input(5, "НЕМА", "НЕМА", "НЕМА", "NOT_FOUND", null, array())),
                resolver.resolve(input(6, "ГРАД", "Grad", "Opština B-grad", "NOT_FOUND", null, aliasCandidate())));

        assertThat(resolutions).extracting(CoarseLocationResolver.Resolution::precision)
                .containsExactly(
                        LocationPrecision.CADASTRAL_MUNICIPALITY,
                        LocationPrecision.SETTLEMENT,
                        LocationPrecision.MUNICIPALITY,
                        LocationPrecision.SETTLEMENT,
                        LocationPrecision.NONE,
                        LocationPrecision.SETTLEMENT);
        assertThat(resolutions).extracting(CoarseLocationResolver.Resolution::precision)
                .doesNotContain(LocationPrecision.PARCEL, LocationPrecision.ADDRESS, LocationPrecision.STREET);
        assertThat(resolutions.get(0).usedMunicipalityAlias()).isTrue();
        assertThat(resolutions.get(0).centroid().memberPointCount()).isEqualTo(42);
        assertThat(resolutions.get(3).rationale()).startsWith("SETTLEMENT_EXACT_NAME:");
        assertThat(resolutions.get(5).rationale())
                .startsWith("SETTLEMENT_EXACT_NAME_WITH_MUNICIPALITY_CONTEXT:");
        assertThat(resolutions.get(5).centroid().officialCode()).isEqualTo("S300");
        JsonNode settlementEvidence = resolutions.get(5).candidateEvidence().path("settlementTier");
        assertThat(settlementEvidence.path("candidateCodes").toString())
                .isEqualTo("[\"S200\",\"S300\"]");
        assertThat(settlementEvidence.path("eligibleCandidateCodes").toString())
                .isEqualTo("[\"S300\"]");
        assertThat(settlementEvidence.path("municipalityContextNarrowedCandidates").asBoolean()).isTrue();
        assertThat(settlementEvidence.path("selectionBasis").asText())
                .isEqualTo("MUNICIPALITY_CONTEXT_NARROWED_TO_ONE");
        assertThat(resolutions.get(4).candidateEvidence().path("selected").path("precision").asText())
                .isEqualTo("NONE");
    }

    @Test
    void fingerprintChangesOnlyWithResolutionInputsOrVersions() {
        CoarseLocationResolver.Input first = input(
                1, "КО ТЕСТ", "Место", "Општина А", "MATCHED", "K100", aliasCandidate());
        CoarseLocationResolver.Input sameFieldsDifferentAuction = input(
                99, "КО ТЕСТ", "Место", "Општина А", "MATCHED", "K100", aliasCandidate());

        assertThat(CoarseLocationResolver.fingerprint(
                first, CentroidTestArtifact.VERSION, CoarseLocationResolver.RESOLVER_VERSION))
                .isEqualTo(CoarseLocationResolver.fingerprint(
                        sameFieldsDifferentAuction,
                        CentroidTestArtifact.VERSION,
                        CoarseLocationResolver.RESOLVER_VERSION));
        assertThat(CoarseLocationResolver.fingerprint(
                first, CentroidTestArtifact.VERSION, CoarseLocationResolver.RESOLVER_VERSION))
                .isNotEqualTo(CoarseLocationResolver.fingerprint(
                        input(1, "КО ТЕСТ", "Друго место", "Општина А", "MATCHED", "K100", array()),
                        CentroidTestArtifact.VERSION,
                        CoarseLocationResolver.RESOLVER_VERSION))
                .isNotEqualTo(CoarseLocationResolver.fingerprint(
                        first, "2026-08-24-" + "b".repeat(64), CoarseLocationResolver.RESOLVER_VERSION))
                .isNotEqualTo(CoarseLocationResolver.fingerprint(
                        first, CentroidTestArtifact.VERSION, "coarse-location-v3"));
        assertThat(CoarseLocationResolver.fingerprint(
                first, CentroidTestArtifact.VERSION, CoarseLocationResolver.RESOLVER_VERSION))
                .isNotEqualTo(CoarseLocationResolver.fingerprint(
                        withDictionaryVersion(first, "fixture-dictionary-republished"),
                        CentroidTestArtifact.VERSION,
                        CoarseLocationResolver.RESOLVER_VERSION));
        assertThat(CoarseLocationResolver.fingerprint(
                input(1, "ГРАД", "Grad", "Opština B-grad", "NOT_FOUND", null, array()),
                CentroidTestArtifact.VERSION,
                CoarseLocationResolver.RESOLVER_VERSION))
                .isNotEqualTo(CoarseLocationResolver.fingerprint(
                        input(1, "ГРАД", "Grad", "Opština B-grad", "NOT_FOUND", null, aliasCandidate()),
                        CentroidTestArtifact.VERSION,
                        CoarseLocationResolver.RESOLVER_VERSION));
    }

    @Test
    void settlementCannotOverrideAContradictoryStructuredMunicipality() {
        CoarseLocationResolver.Resolution resolution = resolver.resolve(input(
                7, "НЕПОЗНАТО", "Čajetina", "Opština B", "NOT_FOUND", null, array()));

        assertThat(resolution.precision()).isEqualTo(LocationPrecision.MUNICIPALITY);
        assertThat(resolution.centroid().officialCode()).isEqualTo("M200");
        assertThat(resolution.candidateEvidence().path("settlementTier").path("rejection").asText())
                .isEqualTo("NO_UNAMBIGUOUS_SETTLEMENT_IDENTITY");
    }

    @Test
    void structuralAliasEvidenceDoesNotDependOnRationaleWordingAndRationaleCodesAreSafe() {
        CoarseLocationResolver.Input input = input(
                8, "КО ТЕСТ", null, "Општина А", "MATCHED", "K100", aliasCandidate());

        assertThat(input.koRationale()).isEqualTo("RENAMED_UPSTREAM_REASON_WITHOUT_PREFIX");
        assertThat(input.usedReviewedMunicipalityAlias()).isTrue();
        assertThat(withKoMethod(input, "EXACT_NORMALIZED_NAME").usedReviewedMunicipalityAlias()).isFalse();
        assertThat(CoarseLocationResolver.rationaleCode("COLON_FREE_REASON"))
                .isEqualTo("COLON_FREE_REASON");
        assertThat(CoarseLocationResolver.rationaleCode("CODE: details")).isEqualTo("CODE");
    }

    private CoarseLocationResolver.Input input(
            long auctionId,
            String cadastral,
            String place,
            String municipality,
            String status,
            String matchedKoCode,
            JsonNode candidates) {
        String rationale = "MATCHED".equals(status)
                ? "RENAMED_UPSTREAM_REASON_WITHOUT_PREFIX"
                : status + ": fixture";
        return new CoarseLocationResolver.Input(
                auctionId,
                cadastral,
                place,
                municipality,
                status,
                "MATCHED".equals(status) ? "MUNICIPALITY_CONTEXT" : "NONE",
                rationale,
                matchedKoCode,
                "fixture-dictionary",
                CentroidTestArtifact.SOURCE_HASH,
                "serbian-name-v1",
                "fixture-review-v1",
                "c".repeat(64),
                "fixture-review-v1",
                "d".repeat(64),
                candidates);
    }

    private static CoarseLocationResolver.Input withDictionaryVersion(
            CoarseLocationResolver.Input input,
            String dictionaryVersion) {
        return new CoarseLocationResolver.Input(
                input.auctionId(), input.cadastral(), input.placeName(), input.municipality(),
                input.koStatus(), input.koMethod(), input.koRationale(), input.matchedKoCode(),
                dictionaryVersion, input.dictionarySourceSha256(), input.normalizerVersion(),
                input.aliasDatasetVersion(), input.aliasSha256(),
                input.municipalityAliasDatasetVersion(), input.municipalityAliasSha256(),
                input.koCandidates());
    }

    private static CoarseLocationResolver.Input withKoMethod(
            CoarseLocationResolver.Input input,
            String koMethod) {
        return new CoarseLocationResolver.Input(
                input.auctionId(), input.cadastral(), input.placeName(), input.municipality(),
                input.koStatus(), koMethod, input.koRationale(), input.matchedKoCode(),
                input.dictionaryVersion(), input.dictionarySourceSha256(), input.normalizerVersion(),
                input.aliasDatasetVersion(), input.aliasSha256(),
                input.municipalityAliasDatasetVersion(), input.municipalityAliasSha256(),
                input.koCandidates());
    }

    private JsonNode aliasCandidate() {
        try {
            return objectMapper.readTree("""
                    [{"koCode":"K100","municipalityContextMatch":true,
                      "municipalities":[{"code":"M200"}],
                      "municipalityAliasReviews":[{"id":"reviewed-city-alias"}]}]
                    """);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode ambiguousCandidates() {
        try {
            return objectMapper.readTree("""
                    [{"koCode":"K200","municipalityContextMatch":false,"municipalities":[{"code":"M100"}]},
                     {"koCode":"K300","municipalityContextMatch":false,"municipalities":[{"code":"M200"}]}]
                    """);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode array() {
        return objectMapper.createArrayNode();
    }

    private static CentroidSnapshot.Centroid centroid(
            CentroidSnapshot.Level level,
            String code,
            String cyrillic,
            String latin,
            List<String> municipalityCodes) {
        return new CentroidSnapshot.Centroid(
                level, code, cyrillic, latin, List.of(), municipalityCodes, 42, 20.5, 44.5);
    }
}
