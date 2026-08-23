package rs.sud.eaukcija.controller;

import java.time.Duration;

import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import rs.sud.eaukcija.map.MapAuctionRequest;
import rs.sud.eaukcija.map.MapAuctionRequestParser;
import rs.sud.eaukcija.map.MapAuctionService;
import rs.sud.eaukcija.map.MapGeoJsonResponse;

/** Public, bounded GeoJSON viewport API. */
@RestController
@RequestMapping("/api/map/auctions")
@Profile("!local-h2")
public class MapAuctionController {

    private static final CacheControl VIEWPORT_CACHE =
            CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic();

    private final MapAuctionRequestParser parser;
    private final MapAuctionService service;

    public MapAuctionController(MapAuctionRequestParser parser, MapAuctionService service) {
        this.parser = parser;
        this.service = service;
    }

    @GetMapping(produces = {"application/geo+json", MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<MapGeoJsonResponse> auctions(
            @RequestParam MultiValueMap<String, String> parameters) {
        MapAuctionRequest request = parser.parse(parameters);
        MapGeoJsonResponse response = service.findAuctions(request);
        return ResponseEntity.ok()
                .header("X-Map-Feature-Count", Integer.toString(response.numberReturned()))
                .header("X-Map-Feature-Limit", Integer.toString(response.limit()))
                .header("X-Map-Truncated", Boolean.toString(response.truncated()))
                .cacheControl(VIEWPORT_CACHE)
                .varyBy("Accept")
                .body(response);
    }
}
