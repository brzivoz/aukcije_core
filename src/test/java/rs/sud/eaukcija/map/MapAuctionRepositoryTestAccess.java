package rs.sud.eaukcija.map;

import java.util.List;

/** Cross-package access to the package-private production-plan probe. */
public final class MapAuctionRepositoryTestAccess {

    private MapAuctionRepositoryTestAccess() {
    }

    public static List<String> explain(MapAuctionRepository repository, MapAuctionRequest request) {
        return repository.explain(request);
    }
}
