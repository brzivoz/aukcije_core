package rs.sud.eaukcija.controller;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;
import rs.sud.eaukcija.filter.AuctionFilters;
import rs.sud.eaukcija.presentation.AuctionDetailsService;

@Controller
@Profile("!local-h2")
public class AuctionDetailsController {
    private final AuctionDetailsService details;
    private final rs.sud.eaukcija.filter.AuctionFilterParser filters;
    public AuctionDetailsController(AuctionDetailsService details, rs.sud.eaukcija.filter.AuctionFilterParser filters) {
        this.details = details; this.filters = filters;
    }

    @GetMapping("/api/auctions/{id}/details")
    @ResponseBody
    public AuctionDetailsService.Details details(@PathVariable String id, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, private");
        long parsed;
        try {
            if (!id.matches("[1-9][0-9]{0,18}")) throw new NumberFormatException();
            parsed = Long.parseLong(id);
        } catch (NumberFormatException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid auction ID");
        }
        return details.find(parsed).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Auction not found"));
    }

    /** Keyboard/touch and no-JavaScript fallback: the same allowlisted projection, not entity serialization. */
    @GetMapping("/auctions/{id}")
    public String page(@PathVariable String id, Model model, HttpServletResponse response,
                       @org.springframework.web.bind.annotation.RequestParam org.springframework.util.MultiValueMap<String, String> parameters) {
        model.addAttribute("detail", details(id, response));
        var navigation = filters.parse(parameters, java.util.Set.of());
        model.addAttribute("backUrl", navigation.pageUrl(navigation.page()));
        model.addAttribute("displayZone", AuctionFilters.ZONE);
        return "auction-details";
    }
}
