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
    private final rs.sud.eaukcija.history.CatalogueChangesService changes;
    private final com.fasterxml.jackson.databind.ObjectMapper json;
    public AuctionResultsService(AuctionSearchRepository repository, rs.sud.eaukcija.history.CatalogueChangesService changes,
                                 com.fasterxml.jackson.databind.ObjectMapper json) {
        this.repository = repository; this.changes = changes; this.json = json;
    }
    public Map<String, Long> catalogueStats() { return repository.catalogueStats(); }
    @org.springframework.transaction.annotation.Transactional(readOnly = true,
            isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String, Object> model(AuctionFilters filters, Long total) {
        filters = changes.prepare(filters);
        var frame = filters.changes().window().upper();
        var page = repository.page(filters, total == null ? repository.count(filters) : total);
        var evidence = changes.display(page.stream().map(Auction::getId).toList(), filters);
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("sourceFrame", frame);
        model.put("sourceFrameJson", serialize(frame));
        model.put("evidence", evidence);
        model.put("reviewJson", evidence.entrySet().stream().filter(e -> e.getValue().review() != null)
                .collect(Collectors.toMap(Map.Entry::getKey, e -> serialize(e.getValue().review()))));
        var comparison = changes.summary(filters);
        model.put("comparisonNotice", comparison == null ? "" : comparison.notice());
        model.put("newCount", comparison == null ? null : comparison.newCount());
        model.put("updatedCount", comparison == null ? null : comparison.updatedCount());
        model.put("filters", filters);
        model.put("auctions", page);
        model.put("currentPage", filters.page());
        model.put("precisionsByAuctionId", repository.precisions(page.stream().map(Auction::getId).toList(), filters));
        model.put("precisionLabels", Arrays.stream(LocationPrecision.values()).collect(Collectors.toMap(
                Enum::name, LocationPrecisionPresentation::labelSr)));
        model.put("displayZone", AuctionFilters.ZONE);
        return model;
    }
    private String serialize(Object value) {
        try { return json.writeValueAsString(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException(e); }
    }
}
