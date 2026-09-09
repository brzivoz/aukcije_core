package rs.sud.eaukcija.map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;
import rs.sud.eaukcija.filter.AuctionFilterParser;
import rs.sud.eaukcija.repository.AuctionRepository;

class SharedAuctionFilterParserTest {
    private final AuctionRepository auctions = mock(AuctionRepository.class);
    private final MapAuctionRequestParser parser = new MapAuctionRequestParser(new AuctionFilterParser(
            auctions, Clock.fixed(Instant.parse("2026-08-30T12:00:00Z"), ZoneOffset.UTC)));

    @Test void canonicalAndHistoricalLegacyUrlsRoundTripWithoutFreezingTheCutoff() {
        var request = parse("category=Викендица&sortBy=startingPrice&sortDir=asc&municipality=&firstSale=&page=3&auction=179415");
        assertThat(request.filters().timeScope()).isEqualTo("not-ended");
        assertThat(request.filters().query()).contains("timeScope=not-ended", "page=3", "auction=179415").doesNotContain("asOf", "mapKind");
        assertThat(parse(request.filters().query()).filters()).isEqualTo(request.filters());
        var reserved = parse("search=x%2By%26z%25");
        assertThat(reserved.filters().search()).isEqualTo("x+y&z%");
        assertThat(parse(reserved.filters().query()).filters()).isEqualTo(reserved.filters());
        var historical = parse("category=Викендица&mapFrom=2026-08-28&mapTo=2026-08-28&mapPrecision=cadastral_municipality");
        assertThat(historical.filters().timeScope()).isEqualTo("all");
        assertThat(historical.filters().sortUrl("endDate")).contains("from=2026-08-28", "to=2026-08-28", "precision=CADASTRAL_MUNICIPALITY");
        assertThat(parse("mapFrom=2026-08-28&timeScope=not-ended").filters().timeScope()).isEqualTo("not-ended");
        assertThat(parse("to=2026-08-28").filters().timeScope()).isEqualTo("all");
    }
    @Test void aliasesMustAgreeAndRepeatedUnknownAndInvalidValuesAreFieldSpecific() {
        assertThat(parse("category=Викендица&mapKind=Викендица&kind=Викендица").propertyKind()).isEqualTo("Викендица");
        assertThat(parse("status=VERIFIED&mapStatus=Verified").sourceStatus()).isEqualTo("Verified");
        invalid("category=Кућа&mapKind=Викендица", "category");
        invalid("from=2026-08-28&mapFrom=2026-08-29", "from");
        invalid("precision=&mapPrecision=PARCEL", "precision");
        invalid("search=a&search=a", "search"); invalid("unknown=x", "unknown");
        invalid("minPrice=-1", "minPrice"); invalid("maxPrice=1.123", "maxPrice");
        invalid("minPrice=2&maxPrice=1", "maxPrice"); invalid("minPrice=1e3", "minPrice");
        invalid("firstSale=yes", "firstSale"); invalid("timeScope=open", "timeScope");
        invalid("sortBy=class", "sortBy"); invalid("sortDir=descending", "sortDir");
        invalid("page=-1", "page"); invalid("auction=9223372036854775808", "auction");
        invalid("from=2026-02-30", "from"); invalid("from=2026-08-29&to=2026-08-28", "to");
        invalid("search=" + "x".repeat(201), "search");
        invalid("search=" + "%20".repeat(201), "search");
        invalid("category=unretained", "category");
    }
    @Test void retainedLegacyAndNewSafeLabelsSupplyOptionsAndValidationTogether() {
        when(auctions.findDistinctCategories()).thenReturn(List.of("Викендица", "Нова категорија", "<script>"));
        when(auctions.findDistinctStatuses()).thenReturn(List.of("Closed", "NewWorkflow", "bad\u202e"));
        assertThat(parse("category=Нова категорија&status=newworkflow").sourceStatus()).isEqualTo("NewWorkflow");
        assertThat(parse("category=Викендица&status=closed").propertyKind()).isEqualTo("Викендица");
        invalid("status=bad\u202e", "status");
    }
    @Test void multipleMunicipalitiesAreCanonicalDistinctAndPreservedByEveryUrlHelper() {
        var filters = parse("municipality=ЧАЧАК&municipality=Ада&municipality=Чачак&municipality=&timeScope=ended&page=2&auction=179415").filters();
        assertThat(filters.municipalities()).containsExactly("Ада", "Чачак");
        assertThat(filters.parameters().get("municipality")).containsExactly("Ада", "Чачак");
        assertThat(filters.activeCriteria()).containsEntry("Општине", "Ада, Чачак");
        assertThat(parse(filters.query()).filters()).isEqualTo(filters);
        assertThat(parse(filters.pageUrl(3).substring(2)).filters().municipalities()).isEqualTo(filters.municipalities());
        assertThat(parse(filters.sortUrl("endDate").substring(2)).filters().municipalities()).isEqualTo(filters.municipalities());
        assertThat(parse(filters.resetUrl().substring(2)).filters().municipalities()).isEmpty();
        assertThat(parse("municipality=Ада").filters().municipalities()).containsExactly("Ада");
        invalid("municipality=not-a-municipality", "municipality");
        invalid("municipality=" + "x".repeat(256), "municipality");
        invalid(String.join("&", java.util.Collections.nCopies(257, "municipality=Ада")), "municipality");
    }

    @Test void municipalityOptionsIncludeTheWholePinnedRegistryEvenWithoutAnyAuctions() {
        var filters = new AuctionFilterParser(auctions);
        assertThat(rs.sud.eaukcija.filter.SerbiaMunicipalities.names()).hasSize(168).doesNotHaveDuplicates();
        assertThat(filters.municipalities()).hasSize(168)
                .contains("Ада", "Нови Сад", "Чачак", "Севојно", "Палилула (Београд)", "Палилула (Ниш)");
        when(auctions.findDistinctMunicipalities()).thenReturn(List.of("ЧАЧАК", "Београд", "Сремска Митровица-град", "<unsafe>"));
        assertThat(filters.municipalities()).hasSize(170).contains("Београд", "Сремска Митровица-град")
                .doesNotContain("ЧАЧАК", "<unsafe>");
        assertThat(parse("municipality=Београд&municipality=чачак").filters().municipalities()).containsExactly("Београд", "Чачак");
    }

    @Test void parcelSizesAreCanonicalOptionalScalarCriteriaAcrossNavigation() {
        assertThat(parse("").filters().parcelSize()).isNull();
        assertThat(parse("parcelSize=%20").filters().query()).doesNotContain("parcelSize");
        for (var size : rs.sud.eaukcija.filter.ParcelSize.values()) {
            var filters = parse("parcelSize=" + size.value() + "&category=Кућа&page=2&auction=179415&sortDir=desc").filters();
            assertThat(filters.parcelSize()).isEqualTo(size);
            assertThat(filters.activeCriteria()).containsEntry("Површина парцеле", size.label());
            assertThat(parse(filters.query()).filters()).isEqualTo(filters);
            assertThat(parse(filters.pageUrl(3).substring(2)).filters().parcelSize()).isEqualTo(size);
            assertThat(parse(filters.sortUrl("endDate").substring(2)).filters().parcelSize()).isEqualTo(size);
            var reset = parse(filters.resetUrl().substring(2)).filters();
            assertThat(reset.parcelSize()).isNull();
            assertThat(reset.page()).isZero();
            assertThat(reset.auction()).isEqualTo(179415);
            assertThat(reset.sortDir()).isEqualTo("desc");
        }
        for (String value : List.of("all", "unknown", "UNDER-8", "8", "8ar", "8–15", "under-8&parcelSize=under-8",
                "&parcelSize=", "under-8&parcelSize=over-15")) invalid("parcelSize=" + value, "parcelSize");
        // Search remains literal, not a second numeric syntax.
        assertThat(parse("search=%3C%208ar").filters().parcelSize()).isNull();
        assertThat(parse("search=%3C%208ar").filters().search()).isEqualTo("< 8ar");
    }

    @Test void bothBelgradeDstDaysAndSameDayRangesHaveTheCorrectExclusiveUtcBoundary() {
        var spring = parse("timeScope=all&from=2026-03-29&to=2026-03-29");
        assertThat(spring.endsAtOrAfter()).isEqualTo("2026-03-28T23:00:00Z");
        assertThat(spring.endsBefore()).isEqualTo("2026-03-29T22:00:00Z");
        assertThat(Duration.between(spring.endsAtOrAfter(), spring.endsBefore()).toHours()).isEqualTo(23);
        var autumn = parse("timeScope=ended&from=2026-10-25&to=2026-10-25");
        assertThat(autumn.endsAtOrAfter()).isEqualTo("2026-10-24T22:00:00Z");
        assertThat(autumn.endsBefore()).isEqualTo("2026-10-25T23:00:00Z");
        assertThat(Duration.between(autumn.endsAtOrAfter(), autumn.endsBefore()).toHours()).isEqualTo(25);
        assertThat(autumn.filters().timeScope()).isEqualTo("ended"); // contradictory with asOf, never overridden
    }
    private MapAuctionRequest parse(String query) {
        var values = new LinkedMultiValueMap<>(UriComponentsBuilder.fromUriString("/?" + query).build().getQueryParams());
        // Decode serialized canonical URLs as a servlet would.
        values.replaceAll((key, list) -> list.stream().map(value -> java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8)).toList());
        values.add("bbox", "20.2,44.6,20.8,44.9");
        return parser.parse(values);
    }
    private void invalid(String query, String field) {
        var failure = catchThrowableOfType(InvalidMapRequestException.class, () -> parse(query));
        assertThat(failure).isNotNull(); assertThat(failure.field()).isEqualTo(field);
    }
}
