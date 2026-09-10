package rs.sud.eaukcija.controller;

import java.util.Optional;
import java.util.Set;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import rs.sud.eaukcija.filter.AuctionFilterParser;
import rs.sud.eaukcija.filter.AuctionResultsService;
import rs.sud.eaukcija.filter.ParcelSize;
import rs.sud.eaukcija.map.MapAuctionFilterOptions;
import rs.sud.eaukcija.repository.AuctionRepository;
import rs.sud.eaukcija.service.SyncService;
import rs.sud.eaukcija.sync.persistence.SyncRunStatus;
import rs.sud.eaukcija.sync.persistence.SyncRunView;

@Controller
@Profile("!local-h2")
public class AuctionController {

    private static final Logger log = LoggerFactory.getLogger(AuctionController.class);

    private final AuctionRepository repo;
    private final SyncService syncService;
    private final AuctionFilterParser filterParser;
    private final AuctionResultsService results;
    private final boolean mapBrowserTestHooks;
    private final boolean refreshEnabled;

    @Value("${map.auto-refresh-interval-ms:0}")
    private long mapAutoRefreshIntervalMs;
    @Value("${map.initial-longitude:20.46}") private double mapInitialLongitude;
    @Value("${map.initial-latitude:44.79}") private double mapInitialLatitude;
    @Value("${map.initial-zoom:14}") private double mapInitialZoom;
    @Value("${map.fit-parcels-on-select:false}") private boolean fitParcelsOnSelect;

    public AuctionController(
            AuctionRepository repo,
            SyncService syncService,
            AuctionFilterParser filterParser,
            AuctionResultsService results,
            @Value("${map.browser-test-hooks:false}") boolean mapBrowserTestHooks,
            @Value("${eaukcija.refresh.enabled:true}") boolean refreshEnabled) {
        this.repo = repo;
        this.syncService = syncService;
        this.filterParser = filterParser;
        this.results = results;
        this.mapBrowserTestHooks = mapBrowserTestHooks;
        this.refreshEnabled = refreshEnabled;
    }

    @GetMapping("/")
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public String index(@RequestParam MultiValueMap<String, String> parameters, Model model,
                        HttpServletResponse response) {
        var filters = filterParser.parse(parameters, Set.of());
        response.setHeader("Cache-Control", "no-store, private");
        // Native datetime-local is only an input adapter. Even without JS, the resulting bookmark is UTC.
        if (filters.changes().since().equals("date") && parameters.getFirst("sinceLocal") != null
                && !parameters.getFirst("sinceLocal").isBlank()) return "redirect:/?" + filters.query();
        model.addAllAttributes(results.model(filters, null));
        var options = filterParser.options();
        model.addAttribute("municipalities", options.get("municipality"));
        model.addAttribute("places", options.get("placeName"));
        model.addAttribute("categories", options.get("category"));
        model.addAttribute("statuses", options.get("status"));
        model.addAttribute("totalCount", repo.count());
        model.addAttribute("detailsCount", repo.countByDetailsFetched(true));
        model.addAttribute("mapPrecisionOptions", MapAuctionFilterOptions.precisions());
        model.addAttribute("parcelSizeOptions", ParcelSize.values());
        model.addAttribute("mapBrowserTestHooks", mapBrowserTestHooks);
        model.addAttribute("mapAutoRefreshIntervalMs", mapAutoRefreshIntervalMs);
        model.addAttribute("mapInitialLongitude", mapInitialLongitude);
        model.addAttribute("mapInitialLatitude", mapInitialLatitude);
        model.addAttribute("mapInitialZoom", mapInitialZoom);
        model.addAttribute("fitParcelsOnSelect", fitParcelsOnSelect);

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
        model.addAttribute("refreshEnabled", refreshEnabled && syncEnabled);
        model.addAttribute("syncStatusUnavailable", syncStatusUnavailable);
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
