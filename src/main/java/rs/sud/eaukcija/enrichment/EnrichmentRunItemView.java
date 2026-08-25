package rs.sud.eaukcija.enrichment;

import java.time.Instant;

public record EnrichmentRunItemView(
        int ordinal,
        long auctionId,
        String workKeySha256,
        int attemptNumber,
        EnrichmentStateStatus status,
        EnrichmentStageName lastStage,
        Instant startedAt,
        Instant finishedAt,
        String outputSha256,
        String errorClass,
        String errorMessage) {
}
