package rs.sud.eaukcija.operations;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import rs.sud.eaukcija.spatial.LocationRefinementRepository;

@RestController
@Profile("!local-h2")
public class LocationRefinementStatusController {
    private final LocationRefinementRepository repository;
    public LocationRefinementStatusController(LocationRefinementRepository repository) { this.repository = repository; }

    @GetMapping("/api/operator/location-refinement")
    public ResponseEntity<?> status(HttpServletRequest request) {
        if (!LoopbackRequest.isLoopback(request)) return ResponseEntity.status(403).cacheControl(CacheControl.noStore()).build();
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(repository.statistics());
        } catch (RuntimeException unavailable) {
            return ResponseEntity.status(503).cacheControl(CacheControl.noStore())
                    .body(java.util.Map.of("code", "REFINEMENT_EVIDENCE_UNAVAILABLE"));
        }
    }
}
