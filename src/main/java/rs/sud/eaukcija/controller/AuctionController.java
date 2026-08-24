package rs.sud.eaukcija.controller;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import rs.sud.eaukcija.map.MapAuctionFilterOptions;
import rs.sud.eaukcija.model.Auction;
import rs.sud.eaukcija.repository.AuctionRepository;
import rs.sud.eaukcija.repository.AuctionSpecifications;
import rs.sud.eaukcija.service.SyncService;
import rs.sud.eaukcija.spatial.AuctionLocationRepository;
import rs.sud.eaukcija.sync.persistence.SyncRunStatus;

import java.math.BigDecimal;
import java.util.Map;

@Controller
public class AuctionController {

    private final AuctionRepository repo;
    private final SyncService syncService;
    private final ObjectProvider<AuctionLocationRepository> locationRepository;
    private final boolean mapBrowserTestHooks;

    public AuctionController(
            AuctionRepository repo,
            SyncService syncService,
            ObjectProvider<AuctionLocationRepository> locationRepository,
            @Value("${map.browser-test-hooks:false}") boolean mapBrowserTestHooks) {
        this.repo = repo;
        this.syncService = syncService;
        this.locationRepository = locationRepository;
        this.mapBrowserTestHooks = mapBrowserTestHooks;
    }

    @GetMapping("/")
    public String index(
            @RequestParam(required = false) String municipality,
            @RequestParam(required = false) String placeName,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(required = false) Boolean firstSale,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "startingPrice") String sortBy,
            @RequestParam(defaultValue = "asc") String sortDir,
            Model model
    ) {
        Sort sort = sortDir.equalsIgnoreCase("desc")
                ? Sort.by(sortBy).descending()
                : Sort.by(sortBy).ascending();

        var spec = AuctionSpecifications.withFilters(
                municipality, placeName, category, status, minPrice, maxPrice, firstSale, search
        );

        Page<Auction> auctions = repo.findAll(spec, PageRequest.of(page, 25, sort));
        AuctionLocationRepository locations = locationRepository.getIfAvailable();

        model.addAttribute("auctions", auctions);
        model.addAttribute("locationsByAuctionId", locations == null
                ? Map.of()
                : locations.findBestByAuctionIds(auctions.getContent().stream().map(Auction::getId).toList()));
        model.addAttribute("municipalities", repo.findDistinctMunicipalities());
        model.addAttribute("places", repo.findDistinctPlaceNames());
        model.addAttribute("categories", repo.findDistinctCategories());
        model.addAttribute("statuses", repo.findDistinctStatuses());
        model.addAttribute("totalCount", repo.count());
        model.addAttribute("detailsCount", repo.countByDetailsFetched(true));
        model.addAttribute("mapStatusOptions", MapAuctionFilterOptions.statuses());
        model.addAttribute("mapKindOptions", MapAuctionFilterOptions.kinds());
        model.addAttribute("mapPrecisionOptions", MapAuctionFilterOptions.precisions());
        model.addAttribute("mapBrowserTestHooks", mapBrowserTestHooks);

        // Preserve filter params
        model.addAttribute("selectedMunicipality", municipality);
        model.addAttribute("selectedPlace", placeName);
        model.addAttribute("selectedCategory", category);
        model.addAttribute("selectedStatus", status);
        model.addAttribute("minPrice", minPrice);
        model.addAttribute("maxPrice", maxPrice);
        model.addAttribute("firstSale", firstSale);
        model.addAttribute("search", search);
        model.addAttribute("sortBy", sortBy);
        model.addAttribute("sortDir", sortDir);
        model.addAttribute("currentPage", page);

        // Durable sync status; local-h2 deliberately exposes no run ledger.
        var latestRun = syncService.findLatestRun();
        var activeRun = latestRun.filter(run -> run.status() == SyncRunStatus.RUNNING);
        model.addAttribute("syncEnabled", syncService.isEnabled());
        model.addAttribute("activeSyncRunId", activeRun.map(run -> run.runId().toString()).orElse(""));
        model.addAttribute("syncing", activeRun.isPresent());
        model.addAttribute("syncStatus", latestRun
                .map(run -> run.status() + " — " + run.stage())
                .orElse(syncService.isEnabled() ? "Није покренуто" : "Недоступно у local-h2 профилу"));
        model.addAttribute("syncProgress", latestRun
                .map(run -> run.pagesCompleted() + run.detailsSucceeded())
                .orElse(0L));
        model.addAttribute("syncTotal", latestRun
                .map(run -> run.pagesExpected() + run.detailsRequired())
                .orElse(0L));

        return "index";
    }
}
