package rs.sud.eaukcija.map;

import java.time.Instant;
import java.util.Objects;

import rs.sud.eaukcija.spatial.BoundingBox;
import rs.sud.eaukcija.spatial.LocationPrecision;

/** Fully validated viewport filters ready for the bounded repository query. */
public record MapAuctionRequest(
        BoundingBox boundingBox,
        String sourceStatus,
        String propertyKind,
        LocationPrecision precision,
        Instant endsAtOrAfter,
        Instant endsBefore,
        int limit) {

    public MapAuctionRequest {
        Objects.requireNonNull(boundingBox, "boundingBox");
        Objects.requireNonNull(endsAtOrAfter, "endsAtOrAfter");
        if (endsBefore != null && !endsBefore.isAfter(endsAtOrAfter)) {
            throw new IllegalArgumentException("endsBefore must be after endsAtOrAfter");
        }
        if (limit < 1 || limit > MapAuctionRequestParser.MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and 5000");
        }
    }
}
