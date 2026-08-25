package rs.sud.eaukcija.map;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Internal map-readiness state, including workflow correlation evidence. */
public record MapDataStatus(
        boolean available,
        String state,
        String dataVersion,
        Instant lastSuccessfulSync,
        boolean stale,
        long populationCount,
        long mappedAuctionCount,
        Map<String, Long> precisionSummary,
        UUID successfulResolutionRunId,
        UUID refreshWorkflowId,
        String warning) {

    public MapDataStatus {
        precisionSummary = Map.copyOf(precisionSummary);
    }

    public MapDataStatus(
            boolean available,
            String state,
            String dataVersion,
            Instant lastSuccessfulSync,
            boolean stale,
            long populationCount,
            long mappedAuctionCount,
            String warning) {
        this(available, state, dataVersion, lastSuccessfulSync, stale,
                populationCount, mappedAuctionCount, Map.of(), null, null, warning);
    }
}
