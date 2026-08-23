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
                .contains("max-age=60", "public");
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

    private MapAuctionRequest request(
            String status, String kind, LocationPrecision precision,
            Instant from, Instant to, int limit) {
        return new MapAuctionRequest(
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
