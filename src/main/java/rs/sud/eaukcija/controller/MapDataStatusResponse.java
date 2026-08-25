package rs.sud.eaukcija.controller;

import java.time.Instant;
import java.util.Map;

import rs.sud.eaukcija.map.MapDataStatus;

/** Deliberately narrow anonymous map freshness contract. */
public record MapDataStatusResponse(
        boolean available,
        String state,
        String dataVersion,
        Instant lastSuccessfulSync,
        boolean stale,
        long populationCount,
        long mappedAuctionCount,
        Map<String, Long> precisionSummary,
        String warning) {

    static MapDataStatusResponse from(MapDataStatus status) {
        return new MapDataStatusResponse(
                status.available(), status.state(), status.dataVersion(),
                status.lastSuccessfulSync(), status.stale(), status.populationCount(),
                status.mappedAuctionCount(), status.precisionSummary(), status.warning());
    }

    public MapDataStatusResponse {
        precisionSummary = Map.copyOf(precisionSummary);
    }
}
