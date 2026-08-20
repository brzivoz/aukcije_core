package rs.sud.eaukcija.controller;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import rs.sud.eaukcija.model.Auction;
import rs.sud.eaukcija.repository.AuctionRepository;
import rs.sud.eaukcija.repository.AuctionSpecifications;
import rs.sud.eaukcija.service.SyncService;

import java.math.BigDecimal;

@Controller
public class AuctionController {

    private final AuctionRepository repo;
    private final SyncService syncService;

    public AuctionController(AuctionRepository repo, SyncService syncService) {
        this.repo = repo;
        this.syncService = syncService;
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

        model.addAttribute("auctions", auctions);
        model.addAttribute("municipalities", repo.findDistinctMunicipalities());
        model.addAttribute("places", repo.findDistinctPlaceNames());
        model.addAttribute("categories", repo.findDistinctCategories());
        model.addAttribute("statuses", repo.findDistinctStatuses());
        model.addAttribute("totalCount", repo.count());
        model.addAttribute("detailsCount", repo.countByDetailsFetched(true));

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

        // Sync status
        model.addAttribute("syncing", syncService.isSyncing());
        model.addAttribute("syncStatus", syncService.getSyncStatus());
        model.addAttribute("syncProgress", syncService.getProgress());
        model.addAttribute("syncTotal", syncService.getTotalPages());

        return "index";
    }
}
