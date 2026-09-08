package rs.sud.eaukcija.map;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import rs.sud.eaukcija.spatial.BoundingBox;
import rs.sud.eaukcija.spatial.AuctionLocationRepository;
import rs.sud.eaukcija.spatial.LocationPrecision;
import rs.sud.eaukcija.testsupport.PostgisTestContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class MapAuctionRepositoryIntegrationTest {

    private static final String DATASET_HASH = "a".repeat(64);
    private static final Instant FROM = Instant.parse("2026-08-23T00:00:00Z");

    static final PostgreSQLContainer<?> POSTGIS = PostgisTestContainer.shared();
    private static final String JDBC_URL = PostgisTestContainer.createEmptyDatabase();

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> JDBC_URL);
        registry.add("spring.datasource.username", POSTGIS::getUsername);
        registry.add("spring.datasource.password", POSTGIS::getPassword);
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MapAuctionRepository repository;

    @Autowired
    private AuctionLocationRepository auctionLocations;

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private ObjectMapper json;

    @BeforeEach
    @AfterEach
    void clearPopulation() {
        jdbc.execute("TRUNCATE TABLE auctions CASCADE");
        jdbc.execute("TRUNCATE TABLE parcel_identities RESTART IDENTITY CASCADE");
    }

    @Test
    void returnsStableFilteredRowsAndPreservesDistinctPropertiesWhileCollapsingDuplicates() {
        insertAuction(101, "<script>Н101</script>", "125000.50", "2026-08-24T10:00:00Z", "Verified", "Парцела");
        long parcelIdentity = insertParcelIdentity("702013", "1572");
        UUID duplicateAddress = insertReference(101, 0, "PARCEL", "parcel:1572:v1", parcelIdentity);
        UUID duplicateParcel = insertReference(101, 1, "PARCEL", "parcel:1572:v2", parcelIdentity);
        UUID otherProperty = insertReference(101, 2, "OTHER", "property:other", null);
        select(duplicateAddress, "POINT(20.45 44.75)", "ADDRESS", "2026-08-23T08:00:00Z");
        select(duplicateParcel,
                "POLYGON((20.40 44.70,20.50 44.70,20.50 44.80,20.40 44.80,20.40 44.70))",
                "PARCEL", "2026-08-23T09:00:00Z");
        select(otherProperty, "POINT(20.55 44.76)", "MUNICIPALITY", "2026-08-23T09:00:00Z");

        insertAuction(102, "Н102", "200000", "2026-08-25T10:00:00Z", "Published", "Објекат");
        UUID edge = insertReference(102, 0, "OTHER", "property:edge", null);
        select(edge, "POINT(20.20 44.60)", "ADDRESS", "2026-08-23T09:00:00Z");

        insertAuction(103, "Н103", "300000", "2026-08-25T10:00:00Z", "Verified", "Парцела");
        UUID outside = insertReference(103, 0, "OTHER", "property:outside", null);
        select(outside, "POINT(21.50 44.75)", "ADDRESS", "2026-08-23T09:00:00Z");

        insertAuction(104, "Н104", "400000", "2026-08-22T23:59:59Z", "Verified", "Парцела");
        UUID ended = insertReference(104, 0, "OTHER", "property:ended", null);
        select(ended, "POINT(20.60 44.80)", "ADDRESS", "2026-08-23T09:00:00Z");

        MapAuctionRequest all = request(null, null, null, FROM, null, 100);
        List<MapAuctionRow> first = repository.findWithin(all);
        List<MapAuctionRow> replay = repository.findWithin(all);

        assertThat(first).hasSize(3);
        assertThat(first).extracting(MapAuctionRow::auctionId).containsExactly(101L, 101L, 102L);
        assertThat(replay).extracting(MapAuctionRow::featureId)
                .containsExactlyElementsOf(first.stream().map(MapAuctionRow::featureId).toList());
        MapAuctionRow parcel = first.stream().filter(row -> row.precision() == LocationPrecision.PARCEL)
                .findFirst().orElseThrow();
        MapAuctionRow other = first.stream()
                .filter(row -> row.auctionId() == 101 && row.precision() == LocationPrecision.MUNICIPALITY)
                .findFirst().orElseThrow();
        assertThat(parcel).satisfies(row -> {
            assertThat(row.auctionNumber()).isEqualTo("<script>Н101</script>");
            assertThat(row.amount()).isEqualByComparingTo(new BigDecimal("125000.50"));
            assertThat(row.endTime()).isEqualTo("2026-08-24T10:00:00Z");
            assertThat(row.sourceStatus()).isEqualTo("Verified");
            assertThat(row.propertyKind()).isEqualTo("Парцела");
            assertThat(row.precision()).isEqualTo(LocationPrecision.PARCEL);
            assertThat(row.geometry().getGeometryType()).isEqualTo("Polygon");
        });
        assertThat(other.precision()).isEqualTo(LocationPrecision.MUNICIPALITY);
        assertThat(first.get(2).geometry().getCoordinate().x).isEqualTo(20.20);

        assertThat(repository.findWithin(request("Verified", null, null, FROM, null, 100)))
                .extracting(MapAuctionRow::auctionId).containsExactly(101L, 101L);
        assertThat(repository.findWithin(request(null, "Објекат", null, FROM, null, 100)))
                .extracting(MapAuctionRow::auctionId).containsExactly(102L);
        assertThat(repository.findWithin(request(null, null, LocationPrecision.PARCEL, FROM, null, 100)))
                .extracting(MapAuctionRow::auctionId).containsExactly(101L);
        assertThat(repository.findWithin(request(null, null, LocationPrecision.ADDRESS, FROM, null, 100)))
                .extracting(MapAuctionRow::auctionId).containsExactly(102L);
    }

    @Test
    void mapAndLocationSelectorsShareTheSameTieBreakForOneCanonicalProperty() {
        insertAuction(150, "Н150", "100000", "2026-08-24T10:00:00Z", "Verified", "Парцела");
        long identity = insertParcelIdentity("702013", "1572");
        UUID firstReference = insertReference(150, 0, "PARCEL", "parcel:1572:v1", identity);
        UUID laterReference = insertReference(150, 1, "PARCEL", "parcel:1572:v2", identity);
        UUID firstAttempt = select(
                firstReference, "POINT(20.40 44.70)", "ADDRESS", "2026-08-23T08:00:00Z");
        select(laterReference, "POINT(20.60 44.80)", "ADDRESS", "2026-08-23T09:00:00Z");

        assertThat(repository.findWithin(request(null, null, null, FROM, null, 100)))
                .singleElement()
                .satisfies(row -> assertThat(row.geometry().getCoordinate().x).isEqualTo(20.40));
        assertThat(auctionLocations.findBestByAuctionIds(List.of(150L)).get(150L)).satisfies(location -> {
            assertThat(location.propertyReferenceId()).isEqualTo(firstReference);
            assertThat(location.resolutionAttemptId()).isEqualTo(firstAttempt);
            assertThat(location.longitude()).isEqualTo(20.40);
        });
    }

    @Test
    void mapFiltersUnpublishableStatesWhileDetailKeepsReviewEvidence() {
        insertAuction(160, "Н160", "100000", "2026-08-24T10:00:00Z", "Verified", "Парцела");
        UUID extracted = insertReference(160, 0, "OTHER", "property:extracted", null, "EXTRACTED");
        UUID confirmed = insertReference(160, 1, "OTHER", "property:confirmed", null, "USER_CONFIRMED");
        UUID review = insertReference(160, 2, "OTHER", "property:review", null, "NEEDS_REVIEW");
        UUID invalid = insertReference(160, 3, "OTHER", "property:invalid", null, "INVALID");
        select(extracted, "POINT(20.40 44.70)", "MUNICIPALITY", "2026-08-23T08:00:00Z");
        select(confirmed, "POINT(20.45 44.72)", "ADDRESS", "2026-08-23T08:00:00Z");
        select(review, "POINT(20.50 44.74)", "PARCEL", "2026-08-23T08:00:00Z");
        select(invalid, "POINT(20.55 44.76)", "PARCEL", "2026-08-23T08:00:00Z");

        assertThat(repository.findWithin(request(null, null, null, FROM, null, 100)))
                .extracting(MapAuctionRow::precision)
                .containsExactlyInAnyOrder(LocationPrecision.MUNICIPALITY, LocationPrecision.ADDRESS);
        assertThat(auctionLocations.findBestByAuctionIds(List.of(160L)).get(160L)).satisfies(location -> {
            assertThat(location.propertyReferenceId()).isEqualTo(review);
            assertThat(location.extractionStatus()).isEqualTo("NEEDS_REVIEW");
            assertThat(location.publishable()).isFalse();
        });
    }

    @Test
    void dateRangeUsesInclusiveFromAndExclusiveDayAfterTo() {
        insertAuction(201, "Н201", "100", "2026-08-22T22:00:00Z", "Verified", "Парцела");
        insertAuction(202, "Н202", "100", "2026-08-23T21:59:59Z", "Verified", "Парцела");
        insertAuction(203, "Н203", "100", "2026-08-23T22:00:00Z", "Verified", "Парцела");
        for (long id = 201; id <= 203; id++) {
            UUID reference = insertReference(id, 0, "OTHER", "property:" + id, null);
            select(reference, "POINT(20.5 44.75)", "ADDRESS", "2026-08-23T09:00:00Z");
        }

        // 2026-08-23 in Belgrade is [2026-08-22T22:00Z, 2026-08-23T22:00Z).
        assertThat(repository.findWithin(request(
                null, null, null,
                Instant.parse("2026-08-22T22:00:00Z"),
                Instant.parse("2026-08-23T22:00:00Z"), 100)))
                .extracting(MapAuctionRow::auctionId)
                .containsExactly(201L, 202L);
    }

    @Test
    void realHttpEndpointReadsPostgisAndReturnsOnlyTheSafeContract() throws Exception {
        insertAuction(301, "<script>Н301</script>", "98765.43", "2026-08-24T10:00:00Z", "Verified", "Парцела");
        UUID reference = insertReference(301, 0, "OTHER", "property:http", null);
        select(reference, "POINT(20.5 44.75)", "ADDRESS", "2026-08-23T09:00:00Z");

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.ALL));
        var response = http.exchange(
                "/api/map/auctions?bbox=20.2,44.6,20.8,44.9&from=2026-08-23&limit=10",
                HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("application/geo+json");
        assertThat(response.getHeaders().getFirst("X-Map-Feature-Count")).isEqualTo("1");
        assertThat(response.getHeaders().getFirst("Cache-Control"))
                .contains("max-age=60", "private").doesNotContain("public");
        assertThat(response.getHeaders().getFirst("Vary")).contains("Accept");
        JsonNode body = json.readTree(response.getBody());
        assertThat(body.path("type").asText()).isEqualTo("FeatureCollection");
        assertThat(body.path("features")).hasSize(1);
        assertThat(body.at("/features/0/properties/title").asText())
                .isEqualTo("<script>Н301</script>");
        assertThat(body.at("/features/0/properties/amount").decimalValue())
                .isEqualByComparingTo("98765.43");
        assertThat(body.at("/features/0/properties/currency").asText()).isEqualTo("RSD");
        assertThat(body.at("/features/0/properties/detailUrl").asText())
                .isEqualTo("https://eaukcija.sud.rs/#/aukcije/301");
        assertThat(response.getBody()).doesNotContain("must never be selected", "sourcePayload", "description");

        HttpHeaders jsonHeaders = new HttpHeaders();
        jsonHeaders.setAccept(List.of(MediaType.APPLICATION_JSON));
        var jsonResponse = http.exchange(
                "/api/map/auctions?bbox=20.2,44.6,20.8,44.9&from=2026-08-23&limit=10",
                HttpMethod.GET, new HttpEntity<>(jsonHeaders), String.class);
        assertThat(jsonResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jsonResponse.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(jsonResponse.getHeaders().getFirst("Vary")).contains("Accept");
    }

    @Autowired private rs.sud.eaukcija.filter.AuctionSearchRepository searchRepository;
    @Autowired private rs.sud.eaukcija.repository.AuctionRepository auctions;
    @Autowired private MapAuctionService service;

    @Test
    void sharedTemporalScopesHandleLegacy179415BoundaryAndUnknownEndWithoutSourceSnapshots() {
        insertAuction(179415, "Н179415", "150000", "2026-08-28T11:00:00Z", "InPrediction", "Викендица");
        select(insertReference(179415, 0, "STRUCTURED_LOCATION", "legacy-ko", null),
                "POINT(20.5 44.75)", "CADASTRAL_MUNICIPALITY", "2026-08-23T09:00:00Z");
        for (long id = 1; id <= 4; id++) {
            insertAuction(id, "Н" + id, "100", id == 1 ? "2026-09-01T00:00:00Z" :
                    id == 2 ? "2026-08-30T12:00:00Z" : "2026-08-28T11:00:00Z", "InPrediction", "Викендица");
            if (id != 4) select(insertReference(id, 0, "OTHER", "property:" + id, null),
                    "POINT(20.5 44.75)", "ADDRESS", "2026-08-23T09:00:00Z");
        }
        jdbc.update("UPDATE auctions SET end_date = NULL WHERE id = 3");
        membership("", List.of(1L), List.of(1L));
        membership("timeScope=ended", List.of(2L, 4L, 179415L), List.of(2L, 179415L));
        membership("timeScope=all", List.of(1L, 2L, 3L, 4L, 179415L), List.of(1L, 2L, 3L, 179415L));
        membership("timeScope=ended&category=Викендица&status=InPrediction&precision=CADASTRAL_MUNICIPALITY",
                List.of(179415L), List.of(179415L));
        membership("timeScope=ended&precision=PARCEL", List.of(), List.of());
        membership("timeScope=all&precision=NONE", List.of(4L), List.of());
        membership("timeScope=all&from=2026-08-28&to=2026-08-28", List.of(4L, 179415L), List.of(179415L));
        membership("timeScope=not-ended&from=2026-08-28&to=2026-08-28", List.of(), List.of());
        assertThat(repository.selectionState(shared("auction=179415"))).isEqualTo("OUTSIDE_FILTERS");
        assertThat(repository.selectionState(shared("auction=3"))).isEqualTo("OUTSIDE_FILTERS");
        assertThat(repository.selectionState(shared("timeScope=all&auction=4"))).isEqualTo("UNMAPPED");
        assertThat(repository.selectionState(shared("timeScope=all&auction=179415"))).isEqualTo("VISIBLE");
        assertThat(repository.selectionState(shared("auction=999999"))).isEqualTo("NOT_FOUND");
        assertThat(service.findAuctions(shared("timeScope=all")).features()).anySatisfy(feature -> {
            assertThat(feature.properties().auctionId()).isEqualTo(3L);
            assertThat(feature.properties().endTime()).isNull();
        });
        assertThat(service.findAuctions(shared("timeScope=ended&precision=CADASTRAL_MUNICIPALITY")).features())
                .singleElement().satisfies(feature -> {
                    assertThat(feature.properties().endTime()).isEqualTo("2026-08-28T11:00:00Z");
                    assertThat(feature.properties().precision()).isEqualTo("CADASTRAL_MUNICIPALITY");
                });
    }

    @Test
    void everySharedScalarFilterAndSerbianSearchAgreeIncludingLiteralWildcardsAndRsd() {
        insertAuction(501, "Н501", "12345.67", "2026-09-01T00:00:00Z", "Closed", "Викендица");
        select(insertReference(501, 0, "OTHER", "one", null), "POINT(20.5 44.75)", "ADDRESS", "2026-08-23T09:00:00Z");
        jdbc.update("UPDATE auctions SET municipality='Београд', place_name='Вождовац', first_sale=true, short_description='Њива Љубиње Чачак', description='Ђорђе 50%_попуст' WHERE id=501");
        insertAuction(502, "Н502", "20000", "2026-09-01T00:00:00Z", "Verified", "Кућа");
        select(insertReference(502, 0, "OTHER", "two", null), "POINT(20.5 44.75)", "PARCEL", "2026-08-23T09:00:00Z");
        for (String query : List.of("municipality=Београд", "placeName=Вождовац", "category=Викендица", "status=Closed",
                "minPrice=12345.67&maxPrice=12345.67", "maxPrice=15000", "firstSale=true", "precision=ADDRESS",
                "search=њИВА", "search=NJIVA", "search=Ljubinje", "search=Čačak", "search=Djordje", "search=50%25_", "search=Н501",
                "municipality=Београд&placeName=Вождовац&category=Викендица&status=Closed&minPrice=12000&maxPrice=13000&firstSale=true&search=njiva&precision=ADDRESS")) {
            membership(query, List.of(501L), List.of(501L));
        }
        membership("firstSale=false&minPrice=15000", List.of(502L), List.of(502L));
        assertThat(service.findAuctions(shared("search=njiva")).features()).singleElement();
        jdbc.update("UPDATE auctions SET status='closed' WHERE id=502");
        membership("status=CLOSED", List.of(501L, 502L), List.of(501L, 502L));
    }

    @Test
    void multipleMunicipalitiesUseOrWithinTheSharedFiltersWithoutDependingOnCurrentOptions() throws Exception {
        for (long id = 551; id <= 553; id++) {
            insertAuction(id, "Н" + id, "100", "2026-09-01T00:00:00Z", "Verified", "Викендица");
            select(insertReference(id, 0, "OTHER", "municipality:" + id, null), "POINT(20.5 44.75)", "ADDRESS", "2026-08-23T09:00:00Z");
        }
        jdbc.update("UPDATE auctions SET municipality = CASE id WHEN 551 THEN 'ЧАЧАК' WHEN 552 THEN 'Ада' ELSE 'Београд' END");
        membership("municipality=Чачак&municipality=Ада", List.of(551L, 552L), List.of(551L, 552L));
        membership("municipality=Чачак&municipality=Ада&category=Кућа", List.of(), List.of());
        membership("municipality=Чачак&municipality=ЧАЧАК&municipality=Апатин", List.of(551L), List.of(551L));
        membership("municipality=Апатин", List.of(), List.of()); // Known municipality, currently no auctions.
        membership("municipality=", List.of(551L, 552L, 553L), List.of(551L, 552L, 553L));
        var response = http.getForEntity("/api/auctions/view?bbox=20.2,44.6,20.8,44.9&timeScope=all&municipality=Чачак&municipality=Ада", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = json.readTree(response.getBody());
        assertThat(body.at("/map/counts/filteredAuctionCount").asInt()).isEqualTo(2);
        assertThat(body.path("resultsHtml").asText()).contains("Н551", "Н552").doesNotContain("Н553");
        assertThat(body.at("/map/features")).hasSize(2);
    }

    @Test
    void outsideViewportWinningParcelCannotResurrectAnInsideDuplicateAndCountsExplainTheSubset() {
        insertAuction(601, "Н601", "1", "2026-09-01T00:00:00Z", "Verified", "Парцела");
        long identity = insertParcelIdentity("702013", "1572");
        select(insertReference(601, 0, "PARCEL", "old", identity), "POINT(20.5 44.75)", "ADDRESS", "2026-08-23T09:00:00Z");
        select(insertReference(601, 1, "PARCEL", "winner", identity), "POINT(21.5 44.75)", "PARCEL", "2026-08-23T09:00:00Z");
        membership("", List.of(601L), List.of());
        membership("precision=ADDRESS", List.of(), List.of());
        membership("precision=PARCEL", List.of(601L), List.of());
        assertThat(repository.counts(shared(""))).isEqualTo(new MapAuctionRepository.Counts(1, 0, 0, 0));
        assertThat(repository.selectionState(shared("auction=601"))).isEqualTo("OUTSIDE_VIEWPORT");
        // A genuine second property is not an obsolete duplicate.
        select(insertReference(601, 2, "OTHER", "second", null), "POINT(20.5 44.75)", "MUNICIPALITY", "2026-08-23T09:00:00Z");
        select(insertReference(601, 3, "OTHER", "third", null), "POINT(20.6 44.75)", "ADDRESS", "2026-08-23T09:00:00Z");
        insertAuction(602, "Н602", "1", "2026-09-01T00:00:00Z", "Verified", "Парцела");
        var response = service.findAuctions(shared("limit=1"));
        assertThat(response.counts()).isEqualTo(new MapAuctionRepository.Counts(2, 1, 1, 2));
        assertThat(response.truncated()).isTrue();
        assertThat(response.numberReturned()).isOne();
        assertThat(response.returnedAuctionCount()).isOne();
        membership("page=1", List.of(), List.of(601L)); // table page never restricts the map
        insertAuction(603, "Н603", "1", "2026-09-01T00:00:00Z", "Verified", "Парцела");
        select(insertReference(603, 0, "OTHER", "limited", null), "POINT(20.5 44.75)", "ADDRESS", "2026-08-23T09:00:00Z");
        assertThat(service.findAuctions(shared("limit=1&auction=603")).selection().state()).isEqualTo("LIMIT");
    }

    @Test
    void realPostgisDatePredicatesIncludeExactlyTheLocalDayAtBothDstTransitions() {
        String[][] days = {{"2026-03-29", "2026-03-28T23:00:00Z", "2026-03-29T22:00:00Z"},
                {"2026-10-25", "2026-10-24T22:00:00Z", "2026-10-25T23:00:00Z"}};
        long base = 800;
        for (String[] day : days) {
            var start = Instant.parse(day[1]); var end = Instant.parse(day[2]);
            var times = List.of(start.minusMillis(1), start, end.minusMillis(1), end);
            for (int i = 0; i < 4; i++) {
                long id = base + i;
                insertAuction(id, "Н" + id, "1", times.get(i).toString(), "InPrediction", "Викендица");
                select(insertReference(id, 0, "OTHER", "dst" + id, null), "POINT(20.5 44.75)", "ADDRESS", "2026-08-23T09:00:00Z");
            }
            membership("timeScope=all&from=" + day[0] + "&to=" + day[0], List.of(base + 1, base + 2), List.of(base + 1, base + 2));
            base += 100;
        }
    }

    @Test
    void realCombinedViewUsesOneCutoffAndNullableEndPresentationAndLegacyHttpErrors() throws Exception {
        insertAuction(701, "Н701", "1", "2026-08-28T11:00:00Z", "InPrediction", "Викендица");
        jdbc.update("UPDATE auctions SET end_date = NULL WHERE id=701");
        select(insertReference(701, 0, "OTHER", "unknown", null), "POINT(20.5 44.75)", "CADASTRAL_MUNICIPALITY", "2026-08-23T09:00:00Z");
        var response = http.getForEntity("/api/auctions/view?bbox=20.2,44.6,20.8,44.9&timeScope=all", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = json.readTree(response.getBody());
        assertThat(body.at("/map/features/0/properties/endTime").isNull()).isTrue();
        assertThat(body.path("resultsHtml").asText()).contains("Непознат завршетак", body.at("/map/asOf").asText());
        for (String path : List.of("/", "/api/auctions/view", "/api/map/auctions")) {
            var invalid = http.getForEntity(path + "?category=Кућа&mapKind=Викендица", String.class);
            assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(json.readTree(invalid.getBody()).path("field").asText()).isEqualTo("category");
        }
    }

    private MapAuctionRequest shared(String query) {
        var values = new org.springframework.util.LinkedMultiValueMap<String, String>();
        if (!query.isBlank()) for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            values.add(parts[0], java.net.URLDecoder.decode(parts[1], java.nio.charset.StandardCharsets.UTF_8));
        }
        values.add("bbox", "20.2,44.6,20.8,44.9");
        return new MapAuctionRequestParser(new rs.sud.eaukcija.filter.AuctionFilterParser(auctions,
                java.time.Clock.fixed(Instant.parse("2026-08-30T12:00:00Z"), java.time.ZoneOffset.UTC))).parse(values);
    }
    private void membership(String query, List<Long> table, List<Long> map) {
        var request = shared(query);
        assertThat(searchRepository.page(request.filters(), searchRepository.count(request.filters())).stream()
                .map(rs.sud.eaukcija.model.Auction::getId).sorted().toList()).as("table: %s", query).isEqualTo(table);
        assertThat(repository.findWithin(request).stream().map(MapAuctionRow::auctionId).distinct().sorted().toList())
                .as("map: %s", query).isEqualTo(map);
    }

    private MapAuctionRequest request(
            String status, String kind, LocationPrecision precision,
            Instant from, Instant to, int limit) {
        return MapAuctionRepositoryTestAccess.request(
                new BoundingBox(20.20, 44.60, 20.80, 44.90),
                status, kind, precision, from, to, limit);
    }

    private void insertAuction(
            long id, String number, String amount, String endTime, String status, String kind) {
        jdbc.update("""
                INSERT INTO auctions (
                    id, auction_number, starting_price, end_date, status,
                    category_name, short_description, description,
                    first_sale, details_fetched
                ) VALUES (?, ?, ?, ?, ?, ?, 'must never be selected',
                          '<source-payload>must never be selected</source-payload>', false, true)
                """, id, number, new BigDecimal(amount), timestamp(endTime), status, kind);
    }

    private long insertParcelIdentity(String koCode, String parcel) {
        return jdbc.queryForObject("""
                INSERT INTO parcel_identities (ko_code, canonical_parcel_number)
                VALUES (?, ?) RETURNING id
                """, Long.class, koCode, parcel);
    }

    private UUID insertReference(
            long auctionId, int order, String type, String canonicalKey, Long parcelIdentity) {
        return insertReference(auctionId, order, type, canonicalKey, parcelIdentity, "EXTRACTED");
    }

    private UUID insertReference(
            long auctionId, int order, String type, String canonicalKey,
            Long parcelIdentity, String extractionStatus) {
        UUID id = UUID.randomUUID();
        if (parcelIdentity == null) {
            jdbc.update("""
                    INSERT INTO property_references (
                        id, auction_id, reference_order, reference_type,
                        source_field, parser_version, extraction_status, canonical_key
                    ) VALUES (?, ?, ?, ?, 'fixture', 'map-api-v1', ?, ?)
                    """, id, auctionId, order, type, extractionStatus, canonicalKey);
        } else {
            jdbc.update("""
                    INSERT INTO property_references (
                        id, auction_id, reference_order, reference_type,
                        ko_code, canonical_parcel_number, parcel_identity_id,
                        source_field, parser_version, extraction_status, canonical_key
                    ) VALUES (?, ?, ?, ?, '702013', '1572', ?,
                              'fixture', 'map-api-v1', ?, ?)
                    """, id, auctionId, order, type, parcelIdentity, extractionStatus, canonicalKey);
        }
        return id;
    }

    private UUID select(UUID referenceId, String wkt, String precision, String resolvedAt) {
        UUID geometryId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        Timestamp resolved = timestamp(resolvedAt);
        jdbc.update("""
                INSERT INTO spatial_resolution_geometries (
                    id, source_geometry, source_crs_authority, source_crs_code,
                    original_geometry_valid, make_valid_applied
                ) VALUES (?, ST_SetSRID(ST_GeomFromText(?), 4326), 'EPSG', 4326, true, false)
                """, geometryId, wkt);
        jdbc.update("""
                INSERT INTO location_resolution_attempts (
                    id, property_reference_id,
                    resolver, resolver_version, input_fingerprint,
                    source_dataset, source_dataset_version, source_dataset_sha256,
                    resolution_status, location_precision, geometry_id,
                    confidence_reason, candidate_evidence,
                    attempted_at, completed_at, resolved_at
                ) VALUES (?, ?, 'map-fixture', 'v1', ?,
                          'fixture', 'v1', ?, 'RESOLVED', ?, ?,
                          'fixture selection', '[]'::jsonb, ?, ?, ?)
                """, attemptId, referenceId, fingerprint(attemptId), DATASET_HASH,
                precision, geometryId, resolved, resolved, resolved);
        jdbc.update("""
                INSERT INTO current_location_resolutions (
                    property_reference_id, resolution_attempt_id, selected_at, selection_reason
                ) VALUES (?, ?, ?, 'fixture current selection')
                """, referenceId, attemptId, resolved);
        return attemptId;
    }

    private static String fingerprint(UUID id) {
        return id.toString().replace("-", "") + id.toString().replace("-", "");
    }

    private static Timestamp timestamp(String value) {
        return Timestamp.from(Instant.parse(value));
    }
}
