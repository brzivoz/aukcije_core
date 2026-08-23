package rs.sud.eaukcija.map;

import java.math.BigDecimal;
import java.time.Instant;

import org.locationtech.jts.geom.Geometry;

import rs.sud.eaukcija.spatial.LocationPrecision;

/** One deduplicated, selected property location plus its safe auction fields. */
public record MapAuctionRow(
        String featureId,
        long auctionId,
        String auctionNumber,
        BigDecimal amount,
        Instant endTime,
        String sourceStatus,
        String propertyKind,
        LocationPrecision precision,
        Geometry geometry) {
}
