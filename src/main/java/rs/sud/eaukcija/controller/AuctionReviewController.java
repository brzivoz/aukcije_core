package rs.sud.eaukcija.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import rs.sud.eaukcija.history.CatalogueChangesService;
import rs.sud.eaukcija.history.SourceHistoryService;
import rs.sud.eaukcija.map.InvalidMapRequestException;

/** POST is a bounded read, not a shared mutation. No reviewed IDs in URLs/logged query strings. */
@RestController
@Profile("!local-h2")
public class AuctionReviewController {
    public static final int MAX_BODY_BYTES = 96 * 1024;
    private final CatalogueChangesService changes;
    private final ObjectMapper json;
    public AuctionReviewController(CatalogueChangesService changes, ObjectMapper json) { this.changes = changes; this.json = json; }
    public record Request(SourceHistoryService.Frame frame, List<SourceHistoryService.Review> reviews, boolean liveBidding) { }
    @PostMapping(value = "/api/auctions/reviews", consumes = "application/json")
    public ResponseEntity<Map<String, Object>> compare(HttpServletRequest servlet) throws IOException {
        byte[] body = servlet.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) throw new InvalidMapRequestException("reviews", "Највише 96 KiB по захтеву.");
        Request request;
        try { request = json.readValue(body, Request.class); }
        catch (IOException e) { throw new InvalidMapRequestException("reviews", "Неисправан захтев за поређење."); }
        if (request == null) throw new InvalidMapRequestException("reviews", "Недостаје захтев.");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate()).body(Map.of(
                "frame", request.frame() == null ? Map.of() : request.frame(),
                "evidence", changes.reviewed(request.reviews(), request.frame(), request.liveBidding())));
    }
}
