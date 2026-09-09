package rs.sud.eaukcija.map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;
import rs.sud.eaukcija.client.EAukcijaClient;
import rs.sud.eaukcija.filter.AuctionFilterParser;
import rs.sud.eaukcija.filter.AuctionSearchRepository;
import rs.sud.eaukcija.model.Auction;
import rs.sud.eaukcija.repository.AuctionRepository;
import rs.sud.eaukcija.rgz.RgzParcelClient;
import rs.sud.eaukcija.testsupport.ParcelAreaFixture;
import rs.sud.eaukcija.testsupport.PostgisTestContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class ParcelSizeFilterIntegrationTest {
    private static final String JDBC_URL = PostgisTestContainer.createEmptyDatabase();
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        var db = PostgisTestContainer.shared();
        registry.add("spring.datasource.url", () -> JDBC_URL);
        registry.add("spring.datasource.username", db::getUsername);
        registry.add("spring.datasource.password", db::getPassword);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired AuctionRepository auctions;
    @Autowired AuctionSearchRepository search;
    @Autowired MapAuctionRepository map;
    @Autowired MapAuctionService service;
    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper json;
    @MockitoBean RgzParcelClient rgz;
    @MockitoBean EAukcijaClient source;
    ParcelAreaFixture fixture;

    @BeforeEach void setup() { clear(); fixture = new ParcelAreaFixture(jdbc); }
    @AfterEach void cleanup() { verifyNoInteractions(rgz, source); clear(); }
    private void clear() {
        jdbc.execute("TRUNCATE auctions, sync_runs, parcel_identities, spatial_resolution_geometries, location_resolution_cache_records CASCADE");
    }

    @Test void exactNumericBandsCoverBothBoundariesFractionsAndEveryUnknownWithoutRoundingOrCastingErrors() {
        String[] areas = {"0.01", "799.999999999999999999", "800", "800.000000000000000001",
                "1499.999999999999999999", "1500", "1500.000000000000000001", "1e50", "1e-50",
                "null", "0", "-1", "-0.0001", "\"799\"", "\"bad\"", "\"NaN\"", "\"Infinity\"",
                "true", "{}", "[]", "\"1e999999999\""};
        for (int i = 0; i < areas.length; i++) {
            fixture.auction(i + 1);
            var ref = fixture.parcel(i + 1, 0, Integer.toString(i + 1));
            fixture.publish(i + 1); fixture.rgz(ref, areas[i]);
        }
        fixture.auction(22);
        var missing = fixture.parcel(22, 0, "22"); fixture.publish(22);
        fixture.attempt(missing, "RGZ_WFS_PARCEL", "PARCEL", "{}", ParcelAreaFixture.POINT, true);
        fixture.auction(23);
        var array = fixture.parcel(23, 0, "23"); fixture.publish(23);
        fixture.attempt(array, "RGZ_WFS_PARCEL", "PARCEL", "[{\"areaSquareMetres\":700}]", ParcelAreaFixture.POINT, true);
        fixture.auction(24); // No location is not a zero-sized parcel either.
        matches("", java.util.stream.LongStream.rangeClosed(1, 24).boxed().toList(),
                java.util.stream.LongStream.rangeClosed(1, 23).boxed().toList());
        matches("parcelSize=", java.util.stream.LongStream.rangeClosed(1, 24).boxed().toList(),
                java.util.stream.LongStream.rangeClosed(1, 23).boxed().toList());
        matches("parcelSize=under-8", List.of(1L, 2L, 9L), List.of(1L, 2L, 9L));
        matches("parcelSize=8-15", List.of(3L, 4L, 5L, 6L), List.of(3L, 4L, 5L, 6L));
        matches("parcelSize=over-15", List.of(7L, 8L), List.of(7L, 8L));
        matches("precision=NONE", List.of(24L), List.of());
        for (String band : List.of("under-8", "8-15", "over-15")) matches("parcelSize=" + band + "&precision=NONE", List.of(), List.of());
    }

    @Test void anyIndividualParcelMatchesOnceButOnlyMatchingCanonicalPropertiesAreReturnedWithSamePropertyPrecision() {
        fixture.auction(101);
        var small = fixture.parcel(101, 0, "1");
        var duplicate = fixture.parcel(101, 1, "1");
        var sibling = fixture.parcel(101, 2, "2");
        var middle = fixture.parcel(101, 3, "3");
        var large = fixture.parcel(101, 4, "4");
        var address = fixture.reference(101, 5, "ADDRESS", null);
        fixture.publish(101);
        fixture.rgz(small, "600"); fixture.rgz(duplicate, "600"); fixture.rgz(sibling, "600");
        fixture.rgz(middle, "800"); fixture.rgz(large, "1600");
        // Area-looking evidence from another resolver must never be interpreted as RGZ parcel area.
        fixture.attempt(address, "OFFICIAL_ADDRESS_REGISTRY", "ADDRESS", "{\"areaSquareMetres\":600}", ParcelAreaFixture.POINT, true);
        fixture.auction(102);
        var sameParcelOtherAuction = fixture.parcel(102, 0, "1"); fixture.publish(102); fixture.rgz(sameParcelOtherAuction, "600");
        matches("parcelSize=under-8", List.of(101L, 102L), List.of(101L, 101L, 102L));
        matches("parcelSize=8-15", List.of(101L), List.of(101L));
        matches("parcelSize=over-15", List.of(101L), List.of(101L)); // Not divided by the 1/2 ownership share.
        matches("precision=ADDRESS", List.of(101L), List.of(101L));
        matches("parcelSize=under-8&precision=ADDRESS", List.of(), List.of());
        matches("parcelSize=under-8&precision=PARCEL", List.of(101L, 102L), List.of(101L, 101L, 102L));
        var allIds = map.findWithin(request("")).stream().map(MapAuctionRow::featureId).toList();
        assertThat(map.findWithin(request("parcelSize=under-8"))).extracting(MapAuctionRow::featureId)
                .doesNotHaveDuplicates().isSubsetOf(allIds);
        assertThat(search.precisions(List.of(101L), request("parcelSize=under-8").filters()).get(101L)).containsExactly("PARCEL");
        String combined = "parcelSize=under-8&precision=PARCEL&category=Кућа&timeScope=not-ended&status=Verified"
                + "&municipality=Београд&placeName=Вождовац&firstSale=true&minPrice=125000&maxPrice=125000"
                + "&search=njiva&from=2099-08-30&to=2099-08-30";
        matches(combined, List.of(101L, 102L), List.of(101L, 101L, 102L));
        for (String mismatch : List.of("category=Парцела", "timeScope=ended", "minPrice=125001", "search=unknown"))
            matches("parcelSize=under-8&" + mismatch, List.of(), List.of());
        matches("search=%3C%208ar", List.of(), List.of());
        // Two individual 600 m² parcels are not a summed middle-band lot.
        fixture.auction(103);
        var a = fixture.parcel(103, 0, "10"); var b = fixture.parcel(103, 1, "11"); fixture.publish(103);
        fixture.rgz(a, "600"); fixture.rgz(b, "600");
        matches("parcelSize=8-15", List.of(101L), List.of(101L));
    }

    @Test void winnersPrecedeAreaPrecisionAndViewportAndNeverReviveHistoryOrHiddenFallbacks() {
        fixture.auction(201);
        var winner = fixture.parcel(201, 0, "1"); var losing = fixture.parcel(201, 1, "1");
        var coarse = fixture.reference(201, 2, "STRUCTURED_LOCATION", null); fixture.publish(201);
        fixture.rgz(winner, "700"); // Historical selected attempt, replaced below.
        fixture.attempt(winner, "RGZ_WFS_PARCEL", "PARCEL", "{\"areaSquareMetres\":1600}", "POINT(21.5 44.79)", true);
        fixture.rgz(losing, "700");
        fixture.attempt(coarse, "fixture", "MUNICIPALITY", "{\"areaSquareMetres\":700}", ParcelAreaFixture.POINT, true);
        matches("parcelSize=under-8", List.of(), List.of());
        matches("precision=MUNICIPALITY", List.of(), List.of());
        matches("parcelSize=over-15", List.of(201L), List.of());
        assertThat(service.findAuctions(request("parcelSize=under-8&auction=201")).selection().state()).isEqualTo("OUTSIDE_FILTERS");
        assertThat(service.findAuctions(request("parcelSize=over-15&auction=201")).selection().state()).isEqualTo("OUTSIDE_VIEWPORT");
        assertThat(map.counts(request("parcelSize=over-15")).outsideViewportAuctionCount()).isOne();
        fixture.rgz(winner, "null"); // Unknown current evidence cannot borrow area from either losing attempt.
        matches("parcelSize=over-15", List.of(), List.of());
        matches("parcelSize=under-8", List.of(), List.of());
        matches("", List.of(201L), List.of(201L));
    }

    @Test void staleKoExtractionAndUnpublishableReferencesCannotUseRetainedAttemptOrCacheArea() {
        for (long id = 301; id <= 304; id++) {
            fixture.auction(id); var ref = fixture.parcel(id, 0, Long.toString(id)); fixture.publish(id); fixture.rgz(ref, "700");
            if (id == 301) fixture.koMatch(ref, ParcelAreaFixture.hash(UUID.randomUUID())); // Trigger revokes selection.
            if (id == 302) jdbc.update("DELETE FROM current_property_reference_extractions WHERE auction_id=?", id);
            if (id == 303) jdbc.update("DELETE FROM current_property_reference_ko_matches WHERE reference_id=?", ref);
            if (id == 304) jdbc.update("UPDATE property_references SET extraction_status='NEEDS_REVIEW' WHERE id=?", ref);
        }
        // An unrelated historical cache success is not evidence of an auction's current properties.
        jdbc.update("""
                INSERT INTO location_resolution_cache_records(id, resolver, resolver_version, input_fingerprint,
                    source_dataset, source_dataset_version, source_dataset_sha256, resolution_status, location_precision,
                    geometry_id, confidence_reason, candidate_evidence, resolved_at)
                SELECT ?, 'RGZ_WFS_PARCEL', 'old', repeat('a',64), 'fixture', 'old', repeat('b',64), 'RESOLVED', 'PARCEL',
                    geometry_id, 'historical cache', '{"areaSquareMetres":700}'::jsonb, now()
                FROM location_resolution_attempts LIMIT 1
                """, UUID.randomUUID());
        matches("parcelSize=under-8", List.of(), List.of());
        matches("", List.of(301L, 302L, 303L, 304L), List.of());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM location_resolution_attempts", Integer.class)).isEqualTo(4);
        for (long id = 301; id <= 304; id++)
            assertThat(service.findAuctions(request("parcelSize=under-8&auction=" + id)).selection().state()).isEqualTo("OUTSIDE_FILTERS");
    }

    @Test void allThreeHttpRoutesShareBandsCountsValidationLimitsAndSelectionWithoutExternalCalls() throws Exception {
        String[] areas = {"799.99", "800", "1500", "1500.01"};
        for (int i = 0; i < areas.length; i++) {
            fixture.auction(401 + i); var ref = fixture.parcel(401 + i, 0, "4" + i); fixture.publish(401 + i); fixture.rgz(ref, areas[i]);
        }
        fixture.auction(405);
        for (String band : List.of("", "under-8", "8-15", "over-15")) {
            var expected = request("parcelSize=" + band);
            long count = search.count(expected.filters());
            for (String path : List.of("/", "/api/map/auctions", "/api/auctions/view")) {
                String url = path + "?parcelSize=" + band + (path.equals("/") ? "" : "&bbox=20.2,44.6,20.8,44.9");
                var response = http.getForEntity(url, String.class);
                assertThat(response.getStatusCode()).as(url).isEqualTo(HttpStatus.OK);
                String html;
                if (path.equals("/")) html = response.getBody();
                else {
                    var body = json.readTree(response.getBody()); var geo = path.equals("/api/auctions/view") ? body.path("map") : body;
                    assertThat(geo.at("/counts/filteredAuctionCount").asLong()).isEqualTo(count);
                    assertThat(geo.path("features")).hasSize(map.findWithin(expected).size());
                    assertThat(geo.toString()).doesNotContain("areaSquareMetres", "candidate_evidence", "Продаје се");
                    html = path.equals("/api/auctions/view") ? body.path("resultsHtml").asText() : null;
                }
                if (html != null) for (long id = 401; id <= 405; id++) {
                    if (search.page(expected.filters(), count).stream().map(Auction::getId).toList().contains(id)) assertThat(html).contains("Н57-" + id);
                    else assertThat(html).doesNotContain("Н57-" + id);
                }
            }
        }
        for (String path : List.of("/", "/api/map/auctions", "/api/auctions/view")) {
            for (String invalid : List.of("no-such-band", "under-8&parcelSize=under-8", "&parcelSize=")) {
                var response = http.getForEntity(path + "?parcelSize=" + invalid + (path.equals("/") ? "" : "&bbox=20.2,44.6,20.8,44.9"), String.class);
                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(json.readTree(response.getBody()).path("field").asText()).isEqualTo("parcelSize");
            }
        }
        var limited = service.findAuctions(request("parcelSize=8-15&limit=1&auction=403"));
        assertThat(limited.counts()).isEqualTo(new MapAuctionRepository.Counts(2, 0, 2, 2));
        assertThat(limited.truncated()).isTrue(); assertThat(limited.numberReturned()).isOne();
        assertThat(limited.selection().state()).isEqualTo("LIMIT");
        assertThat(service.findAuctions(request("parcelSize=8-15&auction=402")).selection().state()).isEqualTo("VISIBLE");
        assertThat(service.findAuctions(request("parcelSize=8-15&auction=405")).selection().state()).isEqualTo("OUTSIDE_FILTERS");
        assertThat(service.findAuctions(request("parcelSize=8-15&auction=999")).selection().state()).isEqualTo("NOT_FOUND");
        matches("parcelSize=8-15&page=1", List.of(), List.of(402L, 403L), 2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM location_resolution_attempts", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM location_resolution_cache_records", Integer.class)).isZero();
    }

    private MapAuctionRequest request(String query) {
        var values = new LinkedMultiValueMap<>(UriComponentsBuilder.fromUriString("/?" + query).build().getQueryParams());
        values.replaceAll((key, entries) -> entries.stream().map(value -> java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8)).toList());
        values.set("bbox", "20.2,44.6,20.8,44.9");
        return new MapAuctionRequestParser(new AuctionFilterParser(auctions,
                Clock.fixed(Instant.parse("2026-09-09T00:00:00Z"), ZoneOffset.UTC))).parse(values);
    }
    private void matches(String query, List<Long> table, List<Long> features) { matches(query, table, features, table.size()); }
    private void matches(String query, List<Long> table, List<Long> features, long count) {
        var r = request(query);
        assertThat(search.count(r.filters())).as("count: %s", query).isEqualTo(count);
        assertThat(search.page(r.filters(), count).stream().map(Auction::getId).sorted().toList()).as("table: %s", query).isEqualTo(table);
        assertThat(map.findWithin(r).stream().map(MapAuctionRow::auctionId).sorted().toList()).as("map: %s", query).isEqualTo(features);
        assertThat(map.counts(r).filteredAuctionCount()).isEqualTo(count);
        assertThat(map.counts(r).featureCountInViewport()).isEqualTo(features.size());
        assertThat(map.counts(r).mappedAuctionCountInViewport()).isEqualTo(features.stream().distinct().count());
    }
}
