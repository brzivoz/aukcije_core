package rs.sud.eaukcija.map;

import java.time.Instant;

/** Public freshness/version state for the locally rendered auction dataset. */
public record MapDataStatus(
        boolean available,
        String state,
        String dataVersion,
        Instant lastSuccessfulSync,
        boolean stale,
        long populationCount,
        long mappedAuctionCount,
        String warning) {
}
