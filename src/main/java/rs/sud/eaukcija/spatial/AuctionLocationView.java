package rs.sud.eaukcija.spatial;

import java.time.Instant;
import java.util.UUID;

/** Best current location evidence, including explicit NONE and unpublishable review states. */
public record AuctionLocationView(
        long auctionId,
        UUID propertyReferenceId,
        UUID resolutionAttemptId,
        LocationPrecision precision,
        String precisionLabelSr,
        boolean coarse,
        String extractionStatus,
        boolean publishable,
        Double longitude,
        Double latitude,
        Long memberPointCount,
        String confidenceReason,
        String resolver,
        String resolverVersion,
        String sourceDatasetVersion,
        Instant resolvedAt) {
}
