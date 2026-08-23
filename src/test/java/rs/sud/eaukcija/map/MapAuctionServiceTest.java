package rs.sud.eaukcija.map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.io.WKTReader;

import rs.sud.eaukcija.spatial.BoundingBox;
import rs.sud.eaukcija.spatial.LocationPrecision;

class MapAuctionServiceTest {

    private final MapAuctionRepository repository = mock(MapAuctionRepository.class);
    private final MapAuctionService service = new MapAuctionService(repository);

    @Test
    void returnsSafeGeoJsonFieldsWithoutDescriptionsOrSourcePayloads() throws Exception {
        MapAuctionRequest request = request(5);
        when(repository.findWithin(request)).thenReturn(List.of(new MapAuctionRow(
                "42:feature",
                42,
                "<script>alert('x')</script>\n Н42",
                new BigDecimal("123456.70"),
                Instant.parse("2026-08-24T10:00:00Z"),
                "<img src=x onerror=alert(1)>\u202e",
                "Парцела & кућа",
                LocationPrecision.PARCEL,
                new WKTReader().read("POLYGON((20 44,21 44,21 45,20 45,20 44))"))));

        MapGeoJsonResponse response = service.findAuctions(request);

        assertThat(response.type()).isEqualTo("FeatureCollection");
        assertThat(response.numberReturned()).isOne();
        assertThat(response.truncated()).isFalse();
        assertThat(response.features()).singleElement().satisfies(feature -> {
            assertThat(feature.type()).isEqualTo("Feature");
            assertThat(feature.id()).isEqualTo("42:feature");
            assertThat(feature.geometry().type()).isEqualTo("Polygon");
            assertThat(feature.properties().auctionId()).isEqualTo(42);
            assertThat(feature.properties().title())
                    .isEqualTo("<script>alert('x')</script> Н42");
            assertThat(feature.properties().sourceStatus())
                    .isEqualTo("<img src=x onerror=alert(1)>");
            assertThat(feature.properties().propertyKind()).isEqualTo("Парцела & кућа");
            assertThat(feature.properties().amount()).isEqualByComparingTo("123456.70");
            assertThat(feature.properties().currency()).isEqualTo("RSD");
            assertThat(feature.properties().endTime()).isEqualTo("2026-08-24T10:00:00Z");
            assertThat(feature.properties().precision()).isEqualTo("PARCEL");
            assertThat(feature.properties().detailUrl())
                    .isEqualTo("https://eaukcija.sud.rs/#/aukcije/42");
        });
    }

    @Test
    void sentinelRowMakesTruncationObservableWithoutReturningIt() throws Exception {
        MapAuctionRequest request = request(1);
        when(repository.findWithin(request)).thenReturn(List.of(row(1), row(2)));

        MapGeoJsonResponse response = service.findAuctions(request);

        assertThat(response.numberReturned()).isOne();
        assertThat(response.limit()).isOne();
        assertThat(response.truncated()).isTrue();
        assertThat(response.features()).extracting(feature -> feature.properties().auctionId())
                .containsExactly(1L);
    }

    @Test
    void serializesPointAndMultiPolygonCoordinatesInGeoJsonLongitudeLatitudeOrder() throws Exception {
        GeoJsonGeometry point = GeoJsonGeometry.from(new WKTReader().read("POINT(20.5 44.75)"));
        GeoJsonGeometry multiPolygon = GeoJsonGeometry.from(new WKTReader().read("""
                MULTIPOLYGON(((20 44,21 44,21 45,20 45,20 44)),
                             ((22 43,23 43,23 44,22 44,22 43)))
                """));

        assertThat(point.type()).isEqualTo("Point");
        assertThat(point.coordinates()).isEqualTo(List.of(20.5, 44.75));
        assertThat(multiPolygon.type()).isEqualTo("MultiPolygon");
        assertThat((List<?>) multiPolygon.coordinates()).hasSize(2);
    }

    private static MapAuctionRequest request(int limit) {
        return new MapAuctionRequest(
                new BoundingBox(18, 41, 24, 47), null, null, null,
                Instant.parse("2026-08-23T00:00:00Z"), null, limit);
    }

    private static MapAuctionRow row(long id) throws Exception {
        return new MapAuctionRow(
                id + ":feature", id, "Н" + id, BigDecimal.TEN,
                Instant.parse("2026-08-24T10:00:00Z"), "Verified", "Парцела",
                LocationPrecision.MUNICIPALITY, new WKTReader().read("POINT(20 44)"));
    }
}
