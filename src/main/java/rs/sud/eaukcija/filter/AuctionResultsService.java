package rs.sud.eaukcija.filter;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import rs.sud.eaukcija.history.SourceHistoryService;
import rs.sud.eaukcija.model.Auction;
import rs.sud.eaukcija.spatial.LocationPrecision;
import rs.sud.eaukcija.spatial.LocationPrecisionPresentation;

@Service
@Profile("!local-h2")
public class AuctionResultsService {
    private final AuctionSearchRepository repository;
    private final SourceHistoryService history;
    public AuctionResultsService(AuctionSearchRepository repository, SourceHistoryService history) {
        this.repository = repository;
        this.history = history;
    }
    public Map<String, Long> catalogueStats() { return repository.catalogueStats(); }
    @org.springframework.transaction.annotation.Transactional(readOnly = true,
            isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String, Object> model(AuctionFilters filters, Long total) {
        var frame = history.capture(filters.asOf());
        var page = repository.page(filters, total == null ? repository.count(filters) : total);
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("sourceFrame", frame);
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
