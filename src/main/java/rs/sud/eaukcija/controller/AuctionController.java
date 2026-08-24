package rs.sud.eaukcija.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import rs.sud.eaukcija.sync.persistence.SyncRunView;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

@Controller
public class AuctionController {

    private static final Logger log = LoggerFactory.getLogger(AuctionController.class);

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

        // Durable sync status is auxiliary page chrome. A ledger outage must
        // not hide the already-persisted auction catalogue from operators.
        boolean syncEnabled = syncService.isEnabled();
        Optional<SyncRunView> latestRun = Optional.empty();
        boolean syncStatusUnavailable = false;
        if (syncEnabled) {
            try {
                latestRun = syncService.findLatestRun();
            } catch (RuntimeException ledgerFailure) {
                syncStatusUnavailable = true;
                log.error("eAukcija page sync status unavailable code=SYNC_LEDGER_UNAVAILABLE");
            }
        }
        var activeRun = latestRun.filter(run -> run.status() == SyncRunStatus.RUNNING);
        model.addAttribute("syncEnabled", syncEnabled);
        model.addAttribute("activeSyncRunId", activeRun.map(run -> run.runId().toString()).orElse(""));
        model.addAttribute("syncing", activeRun.isPresent());
        model.addAttribute("syncStatus", latestRun
                .map(run -> {
                    String statusText = run.status() + " — " + run.stage();
                    long listingQuarantines = run.listingRowsQuarantined();
                    long detailQuarantines = run.detailsQuarantined();
                    if (listingQuarantines == 0 && detailQuarantines == 0) {
                        return statusText;
                    }
                    return statusText + " — издвојено: огласи " + listingQuarantines
                            + ", детаљи " + detailQuarantines;
                })
                .orElse(syncStatusUnavailable
                        ? "Статус синхронизације тренутно није доступан"
                        : syncEnabled ? "Није покренуто" : "Недоступно у local-h2 профилу"));
        model.addAttribute("syncProgress", latestRun
                .map(run -> run.pagesCompleted()
                        + run.detailsSucceeded()
                        + run.detailsQuarantined())
                .orElse(0L));
        model.addAttribute("syncTotal", latestRun
                .map(run -> run.pagesExpected() + run.detailsRequired())
                .orElse(0L));

        return "index";
    }
}
