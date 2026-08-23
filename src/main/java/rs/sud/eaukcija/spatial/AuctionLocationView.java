package rs.sud.eaukcija.spatial;

import java.time.Instant;
import java.util.UUID;

/** Best currently selected location for one auction, including explicit NONE. */
public record AuctionLocationView(
        long auctionId,
        UUID propertyReferenceId,
        UUID resolutionAttemptId,
        LocationPrecision precision,
        String precisionLabelSr,
        boolean coarse,
        Double longitude,
        Double latitude,
        Long memberPointCount,
        String confidenceReason,
        String resolver,
        String resolverVersion,
        String sourceDatasetVersion,
        Instant resolvedAt) {
}
