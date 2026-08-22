package rs.sud.eaukcija.spatial;

import java.time.Instant;
import java.util.UUID;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;

/** One currently selected property-reference resolution intersecting a viewport. */
public record ViewportLocation(
        long auctionId,
        UUID propertyReferenceId,
        UUID resolutionAttemptId,
        LocationPrecision precision,
        Geometry geometry,
        Point centroid,
        Point representativePoint,
        GeometryBounds bounds,
        String resolver,
        String resolverVersion,
        String sourceDataset,
        String sourceDatasetVersion,
        String sourceDatasetSha256,
        Instant resolvedAt,
        String confidenceReason,
        Long memberPointCount) {
}
