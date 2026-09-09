package rs.sud.eaukcija.controller;

import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import rs.sud.eaukcija.spatial.LocationRefinementRepository;

@RestController
@Profile("!local-h2")
public class LocationRefinementController {
    private final LocationRefinementRepository refinements;
    public LocationRefinementController(LocationRefinementRepository refinements) { this.refinements = refinements; }

    @GetMapping("/api/locations/{auctionId}/refinement")
    public ResponseEntity<LocationRefinementRepository.Report> byAuction(@PathVariable long auctionId) {
        var report = refinements.find(auctionId);
        return report == null ? ResponseEntity.notFound().build()
                : ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(report);
    }
}
