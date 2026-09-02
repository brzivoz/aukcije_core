package rs.sud.eaukcija.propertyreference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PropertyReferenceParserTest {

    private static final String SOURCE_SHA = "a".repeat(64);

    private final PropertyReferenceParser parser = new PropertyReferenceParser();

    @Test
    void parsesStructuredFieldsFirstThenEveryDescriptionAndShortDescriptionReference() {
        String description = "КО Долово; катастарска парцела број 870 / 2, 871⁄2; ЛН бр. 51; "
                + "ул. Липовачка 28";
        String shortDescription = "КП.бр. 999/1 и КП.бр. 999/1";

        PropertyReferenceParseResult result = parser.parse(input(
                "ДОЛОВО", "Долово", "Панчево", description, shortDescription));

        assertThat(result.references().get(0).type())
                .isEqualTo(PropertyReferenceType.STRUCTURED_LOCATION);
        assertThat(result.references()).extracting(ParsedPropertyReference::type)
                .containsExactly(
                        PropertyReferenceType.STRUCTURED_LOCATION,
                        PropertyReferenceType.CADASTRAL_MUNICIPALITY,
                        PropertyReferenceType.PARCEL,
                        PropertyReferenceType.PARCEL,
                        PropertyReferenceType.LAND_REGISTER,
                        PropertyReferenceType.ADDRESS,
                        PropertyReferenceType.PARCEL);
        assertThat(result.references().stream()
                .filter(value -> value.type() == PropertyReferenceType.PARCEL)
                .map(ParsedPropertyReference::canonicalParcelNumber))
                .containsExactly("870/2", "871/2", "999/1");
        assertThat(result.references().stream()
                .filter(value -> value.type() == PropertyReferenceType.PARCEL)
                .findFirst().orElseThrow().rawParcelNumber()).isEqualTo("870 / 2");
        assertThat(result.references()).extracting(ParsedPropertyReference::sourceField)
                .contains("detail.Description", "detail.ShortDescription");
        assertThat(result.textReferenceCount()).isEqualTo(6);
    }

    @Test
    void normalizesLatinCyrillicWhitespacePunctuationSuffixesAndOffsetsWithoutLosingEvidence() {
        String description = "Почетак — K.O.  Čajetina ; k.p. br. 4577∕337.";

        PropertyReferenceParseResult result = parser.parse(input(
                "ЧАЈЕТИНА", null, null, description, null));

        ParsedPropertyReference ko = reference(result, PropertyReferenceType.CADASTRAL_MUNICIPALITY);
        ParsedPropertyReference parcel = reference(result, PropertyReferenceType.PARCEL);
        assertThat(ko.rawKo()).isEqualTo("Čajetina");
        assertThat(ko.normalizedKo()).isEqualTo("CAJETINA");
        assertThat(parcel.rawParcelNumber()).isEqualTo("4577∕337");
        assertThat(parcel.canonicalParcelNumber()).isEqualTo("4577/337");
        assertThat(description.substring(parcel.sourceOffsetStart(), parcel.sourceOffsetEnd()))
                .isEqualTo(parcel.rawEvidence());
    }

    @Test
    void retainsStructuredDefaultButFlagsARealTextKoConflictForReview() {
        PropertyReferenceParseResult result = parser.parse(input(
                "СЈЕНИЦА", null, "Сјеница",
                "КО Урсуле, парцела број 1553/7", null));

        assertThat(result.koConflictCount()).isOne();
        assertThat(result.references()).allSatisfy(reference -> {
            assertThat(reference.koConflict()).isTrue();
            assertThat(reference.status()).isEqualTo(PropertyReferenceExtractionStatus.NEEDS_REVIEW);
            assertThat(reference.koCode()).isNull();
        });
        ParsedPropertyReference parcel = reference(result, PropertyReferenceType.PARCEL);
        assertThat(parcel.rawKo()).isEqualTo("Урсуле");
        assertThat(parcel.normalizedKo()).isEqualTo("URSULE");
    }

    @Test
    void missingStructureHasItsOwnStatusWhileTextExtractionStillSucceeds() {
        PropertyReferenceParseResult result = parser.parse(input(
                null, null, null, "КП број 1577", null));

        assertThat(result.noStructuredReferenceCount()).isOne();
        assertThat(result.references().get(0).status())
                .isEqualTo(PropertyReferenceExtractionStatus.NO_STRUCTURED_REFERENCE);
        assertThat(reference(result, PropertyReferenceType.PARCEL).status())
                .isEqualTo(PropertyReferenceExtractionStatus.EXTRACTED);
    }

    @Test
    void rejectsFolioAreaObjectPartAndSubparcelNumbersAsParcelIdentities() {
        PropertyReferenceParseResult result = parser.parse(input(
                "ЈАКОВО", null, null,
                "ЛН 4020; површине парцеле 786 квм; број дела парцеле 1; "
                        + "подброј парцеле 60; парцела је у уделу 1/1", null));

        assertThat(result.references()).extracting(ParsedPropertyReference::type)
                .containsExactly(
                        PropertyReferenceType.STRUCTURED_LOCATION,
                        PropertyReferenceType.LAND_REGISTER);
    }

    @Test
    void stableInputProducesIdenticalRowsKeysAndHashes() {
        PropertyReferenceParser.Input input = input(
                "ЛОК", "Лок", "Тител",
                "К.О. Лок; парцели број 529, 530 и 531; лист непокретности 52",
                "парцели број 529");

        PropertyReferenceParseResult first = parser.parse(input);
        PropertyReferenceParseResult second = parser.parse(input);

        assertThat(second).isEqualTo(first);
        assertThat(first.references()).extracting(ParsedPropertyReference::canonicalKey)
                .doesNotHaveDuplicates();
    }

    @Test
    void boundsOversizedControlCharacterAndReferenceFloodInputs() {
        assertThatThrownBy(() -> parser.parse(input(
                "КО", null, null, "x".repeat(PropertyReferenceParser.MAX_FIELD_CHARACTERS + 1), null)))
                .isInstanceOf(PropertyReferenceParser.PropertyReferenceParseException.class)
                .hasMessage("PARSER_INPUT_TOO_LARGE");
        assertThatThrownBy(() -> parser.parse(input(
                "КО", null, null, "парцела 1\u0000<script>alert(1)</script>", null)))
                .isInstanceOf(PropertyReferenceParser.PropertyReferenceParseException.class)
                .hasMessage("PARSER_INPUT_CONTROL_CHARACTER");
        String maximum = java.util.stream.IntStream.rangeClosed(
                        1, PropertyReferenceParser.MAX_REFERENCES - 1)
                .mapToObj(number -> "парцела број " + number)
                .reduce((left, right) -> left + "; " + right).orElseThrow();
        assertThat(parser.parse(input("КО", null, null, maximum, null)).references())
                .hasSize(PropertyReferenceParser.MAX_REFERENCES);
        String flood = maximum + "; парцела број " + PropertyReferenceParser.MAX_REFERENCES;
        assertThatThrownBy(() -> parser.parse(input("КО", null, null, flood, null)))
                .isInstanceOf(PropertyReferenceParser.PropertyReferenceParseException.class)
                .hasMessage("PARSER_REFERENCE_LIMIT_EXCEEDED");
    }

    @Test
    void harmlessMarkupIsOnlyTextAndNeverChangesTheEvidenceContract() {
        String description = "<b>КП.бр. 12/3</b><script>not executable</script>";
        PropertyReferenceParseResult result = parser.parse(input(
                "ГРАД", null, null, description, null));

        ParsedPropertyReference parcel = reference(result, PropertyReferenceType.PARCEL);
        assertThat(parcel.canonicalParcelNumber()).isEqualTo("12/3");
        assertThat(parcel.rawEvidence()).isEqualTo("КП.бр. 12/3");
        assertThat(result.references()).noneSatisfy(reference ->
                assertThat(reference.rawEvidence()).contains("not executable"));
    }

    private static ParsedPropertyReference reference(
            PropertyReferenceParseResult result,
            PropertyReferenceType type) {
        return result.references().stream().filter(value -> value.type() == type)
                .findFirst().orElseThrow();
    }

    private static PropertyReferenceParser.Input input(
            String cadastral,
            String place,
            String municipality,
            String description,
            String shortDescription) {
        return new PropertyReferenceParser.Input(
                19, SOURCE_SHA, cadastral, place, municipality, description, shortDescription);
    }
}
