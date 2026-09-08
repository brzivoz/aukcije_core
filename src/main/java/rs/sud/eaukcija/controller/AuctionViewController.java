package rs.sud.eaukcija.controller;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import rs.sud.eaukcija.filter.AuctionResultsService;
import rs.sud.eaukcija.map.MapAuctionRequestParser;
import rs.sud.eaukcija.map.MapAuctionService;

/** Atomic refresh of both projections: one cutoff and one repeatable-read database snapshot. */
@RestController
@Profile("!local-h2")
public class AuctionViewController {
    private final MapAuctionRequestParser parser;
    private final MapAuctionService map;
    private final AuctionResultsService results;
    private final SpringTemplateEngine templates;
    private final rs.sud.eaukcija.filter.AuctionFilterParser filters;
    public AuctionViewController(MapAuctionRequestParser parser, MapAuctionService map,
                                 AuctionResultsService results, SpringTemplateEngine templates,
                                 rs.sud.eaukcija.filter.AuctionFilterParser filters) {
        this.parser = parser; this.map = map; this.results = results; this.templates = templates; this.filters = filters;
    }
    @GetMapping("/api/auctions/view")
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ResponseEntity<Map<String, Object>> view(@RequestParam MultiValueMap<String, String> parameters) {
        var request = parser.parse(parameters);
        var collection = map.findAuctions(request);
        Context context = new Context(Locale.forLanguageTag("sr"),
                results.model(request.filters(), collection.counts().filteredAuctionCount()));
        String html = templates.process("index", Set.of("results"), context);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate()).body(Map.of(
                "map", collection, "resultsHtml", html, "query", request.filters().query(), "options", filters.options(), "catalogue", results.catalogueStats()));
    }
}
