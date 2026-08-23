package rs.sud.eaukcija.controller;

import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import rs.sud.eaukcija.spatial.AuctionLocationRepository;
import rs.sud.eaukcija.spatial.AuctionLocationView;

/** Consumer-facing selected-location contract; precision is never inferred from coordinates. */
@RestController
@RequestMapping("/api/locations")
@Profile("!local-h2")
public class LocationController {

    private final AuctionLocationRepository locations;

    public LocationController(AuctionLocationRepository locations) {
        this.locations = locations;
    }

    @GetMapping("/{auctionId}")
    public ResponseEntity<AuctionLocationView> byAuction(@PathVariable long auctionId) {
        Map<Long, AuctionLocationView> selected = locations.findBestByAuctionIds(List.of(auctionId));
        AuctionLocationView location = selected.get(auctionId);
        return location == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(location);
    }
}
