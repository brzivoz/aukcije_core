package rs.sud.eaukcija.sync.persistence;

import java.time.Instant;

public record PersistedAuctionListingQuarantine(
        long auctionId,
        String sourceRowSha256,
        String errorCode,
        int rootCategoryId,
        Integer childCategoryId,
        int pageNumber,
        Instant occurredAt) {
}
