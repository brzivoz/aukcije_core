package rs.sud.eaukcija.controller;

import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import rs.sud.eaukcija.map.MapDataStatusService;

/** Anonymous status endpoint for map version and visible freshness disclosure. */
@RestController
@RequestMapping("/api/map/status")
@Profile("!local-h2")
public class MapDataStatusController {

    private final MapDataStatusService service;

    public MapDataStatusController(MapDataStatusService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<MapDataStatusResponse> status() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(MapDataStatusResponse.from(service.status()));
    }
}
