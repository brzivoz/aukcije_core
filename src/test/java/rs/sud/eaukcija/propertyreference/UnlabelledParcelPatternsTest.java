package rs.sud.eaukcija.propertyreference;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UnlabelledParcelPatternsTest {
    @ParameterizedTest
    @ValueSource(strings = {"81/2 ЊИВА ДРУГЕ КЛАСЕ", "81 ∕ 2 njiva druge klase", "81 ⁄ 2 NJIVA", "81 /\n2 њива"})
    void aParcelTitleAndLandUseCanDisambiguateAnImproperFraction(String description) {
        var parcels = parcels(description, "ПАРЦЕЛА");
        assertThat(parcels).hasSize(1);
        assertThat(parcels.get(0).canonicalParcelNumber()).isEqualTo("81/2");
        assertThat(parcels.get(0).status()).isEqualTo(PropertyReferenceExtractionStatus.EXTRACTED);
        assertThat(parcels.get(0).rawEvidence()).containsIgnoringCase(description.contains("њ") || description.contains("Њ") ? "њива" : "njiva");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1/2 ЊИВА", "2/14 ЊИВА", "1 /\n2 њива", "652 ЊИВА", "81/2", "81/2 СТАН", "81/2 m2", "81/0 њива",
            "Површина њиве 81/2 КО Долово", "Пољопривредно земљиште 1 /\n2 КО Долово", "Пољопривредно земљиште 652 м2 КО Долово",
            "Број дела парцеле: 1", "Стан бр. 16, 64м2", "ИИ-30/26 од 24.08.2026", "Шума 5. класе"})
    void sharesAreasUnitsDatesAndUnlabelledNumbersAreNotAutomaticParcelIdentities(String description) {
        assertThat(parcels(description, "ПАРЦЕЛА")).isEmpty();
    }

    @Test void leadingLandUseNeedsTheCompanionParcelTitle() {
        assertThat(parcels("81/2 ЊИВА", "непокретности")).isEmpty();
    }

    @Test void contextualLandClausePreservesUtf16OffsetsAndExplicitKo() {
        String description = "🏡 Пољопривредно земљиште 81 / 2 КО Долово";
        var parcel = parcels(description, "ПАРЦЕЛА").get(0);
        assertThat(parcel.sourceOffsetStart()).isEqualTo(3);
        assertThat(description.substring(parcel.sourceOffsetStart(), parcel.sourceOffsetEnd())).isEqualTo(parcel.rawEvidence());
        assertThat(parcel.canonicalParcelNumber()).isEqualTo("81/2");
        assertThat(parcel.rawKo()).isEqualTo("Долово");
    }

    @ParameterizedTest
    @ValueSource(strings = {"81 Долово", "81/2 DOLOVO", "81 Долово и 2 објекта"})
    void exactPlaceTitlesAreReviewCandidatesNotLookupAuthorization(String title) {
        var parcel = parcels("652м2", title).get(0);
        assertThat(parcel.status()).isEqualTo(PropertyReferenceExtractionStatus.NEEDS_REVIEW);
        assertThat(parcel.koConflict()).isFalse(); // Numeric-role ambiguity is not an asserted KO conflict.
    }

    @ParameterizedTest
    @ValueSource(strings = {"1/2 Долово", "2/14 Долово", "81 Јаково", "стан 81 Долово", "81м2 Долово", "81 Долово 652м2"})
    void titlesCannotDiscardConflictingNamesOrUnitsOrTreatSharesAsParcels(String title) {
        assertThat(parcels("652м2", title)).isEmpty();
    }

    @Test void aBareUnitTitleAndPersonNameAreNotParcelAndPostalAddressEvidence() {
        assertThat(parcels("СТАН, ПОСЕБАН ДЕО 81", "81 Долово")).isEmpty();
        var parsed = parse("Јокаи Мора", "кућа Хоргош");
        assertThat(parsed.references()).noneMatch(r -> r.type() == PropertyReferenceType.ADDRESS);
    }

    @Test void aLabeledParcelMayStillHaveAProperFractionAndIsNotSuppressedByShareHeuristics() {
        assertThat(parcels("парцела 1/2 КО Долово", null)).extracting(ParsedPropertyReference::canonicalParcelNumber).containsExactly("1/2");
    }

    @Test void latinLettersAreNotClauseSeparatorsAndRealNewlinesDoSeparateProperties() {
        var parsed = parse("KO Dolovo, na parceli 11\nKO Jakovo, na parceli 12", null);
        var parcels = parsed.references().stream().filter(r -> r.type() == PropertyReferenceType.PARCEL).toList();
        assertThat(parcels).extracting(ParsedPropertyReference::rawKo).containsExactly("Dolovo", "Jakovo");
        assertThat(parcels).allSatisfy(r -> assertThat(r.status()).isEqualTo(PropertyReferenceExtractionStatus.EXTRACTED));
    }

    @Test void aFolioAcrossANewlineDoesNotBorrowKoWhenTheTextHasMultipleIdentities() {
        var parsed = parse("KO Dolovo, parcela 11\nЛН 42", "KO Jakovo, parcela 12");
        assertThat(parsed.references().stream().filter(r -> r.type() == PropertyReferenceType.LAND_REGISTER))
                .singleElement().satisfies(r -> {
                    assertThat(r.status()).isEqualTo(PropertyReferenceExtractionStatus.NEEDS_REVIEW);
                    assertThat(r.rawKo()).isNull();
                });
    }

    @Test void unbrokenLandLikeTokensDoNotCauseQuadraticSuffixBacktracking() {
        org.junit.jupiter.api.Assertions.assertTimeout(java.time.Duration.ofSeconds(3), () ->
                assertThat(parcels("њива".repeat(7000), "ПАРЦЕЛА")).isEmpty());
    }

    private List<ParsedPropertyReference> parcels(String description, String title) {
        return parse(description, title).references().stream().filter(r -> r.type() == PropertyReferenceType.PARCEL).toList();
    }
    private PropertyReferenceParseResult parse(String description, String title) {
        return new PropertyReferenceParser().parse(new PropertyReferenceParser.Input(55, "a".repeat(64),
                "ДОЛОВО", "Долово", "Панчево", description, title));
    }
}
