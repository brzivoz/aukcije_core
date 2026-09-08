package rs.sud.eaukcija.filter;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import rs.sud.eaukcija.model.Auction;
import rs.sud.eaukcija.spatial.LocationPrecision;
import rs.sud.eaukcija.spatial.LocationPrecisionPresentation;

@Service
@Profile("!local-h2")
public class AuctionResultsService {
    private final AuctionSearchRepository repository;
    public AuctionResultsService(AuctionSearchRepository repository) { this.repository = repository; }
    public Map<String, Long> catalogueStats() { return repository.catalogueStats(); }
    public Map<String, Object> model(AuctionFilters filters, Long total) {
        var page = repository.page(filters, total == null ? repository.count(filters) : total);
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("filters", filters);
        model.put("auctions", page);
        model.put("currentPage", filters.page());
        model.put("precisionsByAuctionId", repository.precisions(page.stream().map(Auction::getId).toList(), filters));
        model.put("precisionLabels", Arrays.stream(LocationPrecision.values()).collect(Collectors.toMap(
                Enum::name, LocationPrecisionPresentation::labelSr)));
        model.put("displayZone", AuctionFilters.ZONE);
        return model;
    }
}
