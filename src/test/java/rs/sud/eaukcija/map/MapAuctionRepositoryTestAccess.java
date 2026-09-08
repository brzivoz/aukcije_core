package rs.sud.eaukcija.map;

import java.util.List;

/** Cross-package access to the package-private production-plan probe. */
public final class MapAuctionRepositoryTestAccess {

    private MapAuctionRepositoryTestAccess() {
    }

    public static MapAuctionRequest request(rs.sud.eaukcija.spatial.BoundingBox box,
            String status, String category, rs.sud.eaukcija.spatial.LocationPrecision precision,
            java.time.Instant from, java.time.Instant to, int limit) {
        var zone = rs.sud.eaukcija.filter.AuctionFilters.ZONE;
        return new MapAuctionRequest(box, new rs.sud.eaukcija.filter.AuctionFilters(
                null, null, category, status, null, null, null, null, precision,
                null, to == null ? null : to.minusNanos(1).atZone(zone).toLocalDate(), "not-ended",
                "startingPrice", "asc", 0, null, from.minusMillis(1)), limit);
    }

    public static List<String> explain(MapAuctionRepository repository, MapAuctionRequest request) {
        return repository.explain(request);
    }
}
