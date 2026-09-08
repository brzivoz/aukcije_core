package rs.sud.eaukcija.map;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** GeoJSON FeatureCollection with bounded-result observability extensions. */
public record MapGeoJsonResponse(
        String type,
        List<Feature> features,
        int numberReturned,
        int limit,
        boolean truncated,
        Instant asOf,
        String timeScope,
        MapAuctionRepository.Counts counts,
        long returnedAuctionCount,
        Selection selection) {

    public record Selection(long auctionId, String state) {}

    public record Feature(
            String type,
            String id,
            GeoJsonGeometry geometry,
            Properties properties) {
    }

    public record Properties(
            long auctionId,
            String title,
            BigDecimal amount,
            String currency,
            Instant endTime,
            String sourceStatus,
            String propertyKind,
            String precision,
            String detailUrl) {
        /** Unambiguous name; propertyKind remains a deprecated raw-category JSON alias. */
        @com.fasterxml.jackson.annotation.JsonProperty("category")
        public String category() { return propertyKind; }
    }
}
