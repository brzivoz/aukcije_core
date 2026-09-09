package rs.sud.eaukcija.propertyreference;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Known issue-55 regressions, NOT independent held-out quality evidence. */
class FullDescriptionParserTest {
    private final PropertyReferenceParser parser = new PropertyReferenceParser();

    @Test
    void propertyProseDoesNotSwallowRomanNumeralKoAndEachParcelKeepsItsExplicitKo() throws Exception {
        var input = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                rs.sud.eaukcija.testsupport.Fixtures.read("propertyreference/issue55/181104-current.json"));
        String description = input.path("description").asText();
        var result = parser.parse(input);
        assertThat(result.parserVersion()).isEqualTo("property-reference-v2");
        assertThat(ofType(result, PropertyReferenceType.PARCEL))
                .extracting(ParsedPropertyReference::canonicalParcelNumber).containsExactly("4411/2", "4411/20");
        assertThat(ofType(result, PropertyReferenceType.PARCEL))
                .extracting(ParsedPropertyReference::rawKo).containsExactly("Велика Плана I", "Велика Плаан 1");
        assertThat(ofType(result, PropertyReferenceType.CADASTRAL_MUNICIPALITY))
                .extracting(ParsedPropertyReference::rawKo)
                .containsExactly("Велика Плана I", "Велика Плаан 1", "Велика Плана 1");
        var address = ofType(result, PropertyReferenceType.ADDRESS).get(0);
        assertThat(address.addressStreet()).isEqualTo("Булевар Ослобођења");
        assertThat(address.addressHouseNumber()).isEqualTo("109");
        assertThat(address.rawKo()).isEqualTo("Велика Плана I");
        assertThat(result.references()).allSatisfy(reference -> {
            assertThat(reference.status()).isEqualTo(PropertyReferenceExtractionStatus.EXTRACTED);
            if (reference.sourceOffsetStart() != null) {
                assertThat(description.substring(reference.sourceOffsetStart(), reference.sourceOffsetEnd()))
                        .isEqualTo(reference.rawEvidence());
            }
        });
    }

    @Test
    void isolatedPropertyClausesDoNotPoisonEachOtherAndUnknownAssociationIsNotGuessed() {
        var result = parse(null, "КО Долово, кп 1, 2; кп 3 КО Јаково; "
                + "КО Лок и КО Долово, парцела 99", null);
        var parcels = ofType(result, PropertyReferenceType.PARCEL);
        assertThat(parcels).extracting(ParsedPropertyReference::canonicalParcelNumber)
                .containsExactly("1", "2", "3", "99");
        assertThat(parcels.subList(0, 3)).extracting(ParsedPropertyReference::rawKo)
                .containsExactly("Долово", "Долово", "Јаково");
        // A heading with two KOs is not evidence that parcel 99 belongs to the nearest one.
        assertThat(parcels.get(3).status()).isEqualTo(PropertyReferenceExtractionStatus.NEEDS_REVIEW);
    }

    @Test
    void parcelEnumerationBindsToItsPostfixKoWithoutBorrowingTheNextProperty() {
        var result = parse(null, "кп 11, 12 и 13 КО Долово, кп 14 КО Јаково", null);
        assertThat(ofType(result, PropertyReferenceType.PARCEL))
                .extracting(ParsedPropertyReference::rawKo)
                .containsExactly("Долово", "Долово", "Долово", "Јаково");
    }

    @Test
    void houseSuffixAndStreetDateAreKeptDistinctFromParcelAndOwnershipNumbers() {
        var result = parse("ДОЛОВО", "ул. 29. новембра бр. 10 А; ЛН 42; "
                + "број дела парцеле 1; удео 1/2; површина парцеле 500", null);
        var address = ofType(result, PropertyReferenceType.ADDRESS).get(0);
        assertThat(address.addressStreet()).isEqualTo("29. новембра");
        assertThat(address.addressHouseNumber()).isEqualTo("10 А");
        assertThat(ofType(result, PropertyReferenceType.PARCEL)).isEmpty();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            " и парцела 13", " која је предмет продаје.", " РГЗ СКН Општина.",
            "- грађевински, објекат.", " - градско грађевинско земљиште.",
            " са индустријским објектом.", " и породична стамбена зграда број 1.",
            " налази се дубоко у атару насеља.", " укупне површине 500.",
            " са свим непокретностима.", " а парцела 14", " и Кат. парцела 15",
            "Укупна површина 500.", " Број парцеле 16", " у приватној својини.",
            " ул. Тестна бр. 12"
    })
    void propertyProseIsNeverPartOfAnOfficialKoName(String prose) {
        var result = parse("ДОЛОВО", "парцела 12 КО Долово" + prose, null);
        assertThat(ofType(result, PropertyReferenceType.CADASTRAL_MUNICIPALITY))
                .extracting(ParsedPropertyReference::rawKo).containsExactly("Долово");
        assertThat(ofType(result, PropertyReferenceType.PARCEL).get(0).rawKo()).isEqualTo("Долово");
    }

    @Test
    void oldFrozenEvaluationRemainsAnExplicitLegacyParserNotV2Evidence() {
        var legacy = PropertyReferenceParser.legacyV1().parse(new PropertyReferenceParser.Input(
                1, "a".repeat(64), "СЈЕНИЦА", null, null, "КО Урсуле, парцела 10", null));
        assertThat(legacy.parserVersion()).isEqualTo("property-reference-v1");
        assertThat(legacy.references()).allSatisfy(reference ->
                assertThat(reference.status()).isEqualTo(PropertyReferenceExtractionStatus.NEEDS_REVIEW));
        var quality = new PropertyReferenceQualityProfile(new com.fasterxml.jackson.databind.ObjectMapper());
        assertThat(quality.profile(PropertyReferenceParser.VERSION).heldOutPrecision()).isNull();
        assertThat(quality.profile(PropertyReferenceParser.LEGACY_VERSION).heldOutPrecision()).isNotNull();
    }

    private PropertyReferenceParseResult parse(String ko, String description, String shortDescription) {
        return parser.parse(new PropertyReferenceParser.Input(55, "a".repeat(64), ko,
                "Велика Плана", "Велика Плана", description, shortDescription));
    }

    private static List<ParsedPropertyReference> ofType(PropertyReferenceParseResult result, PropertyReferenceType type) {
        return result.references().stream().filter(reference -> reference.type() == type).toList();
    }
}
