package rs.sud.eaukcija.map;

import java.time.Instant;
import java.util.Objects;
import rs.sud.eaukcija.filter.AuctionFilters;
import rs.sud.eaukcija.spatial.BoundingBox;
import rs.sud.eaukcija.spatial.LocationPrecision;

/** A shared query plus map-only transport bounds; pagination never restricts the map. */
public record MapAuctionRequest(BoundingBox boundingBox, AuctionFilters filters, int limit) {
    public MapAuctionRequest {
        Objects.requireNonNull(boundingBox, "boundingBox");
        Objects.requireNonNull(filters, "filters");
        if (limit < 1 || limit > MapAuctionRequestParser.MAX_LIMIT)
            throw new IllegalArgumentException("limit must be between 1 and 5000");
    }
    public String sourceStatus() { return filters.status(); }
    /** Legacy name: this is the raw category, NOT normalized property kind. */
    public String propertyKind() { return filters.category(); }
    public LocationPrecision precision() { return filters.precision(); }
    public Instant endsAtOrAfter() { return filters.endsAtOrAfter(); }
    public Instant endsBefore() { return filters.endsBefore(); }
}
