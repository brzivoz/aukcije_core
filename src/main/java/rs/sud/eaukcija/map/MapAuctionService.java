package rs.sud.eaukcija.map;

import java.util.ArrayList;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import rs.sud.eaukcija.history.SourceHistoryService;

/** Maps the database projection to the deliberately safe public GeoJSON contract. */
@Service
@Profile("!local-h2")
public class MapAuctionService {

    private static final String DETAIL_URL_PREFIX = "https://eaukcija.sud.rs/#/aukcije/";

    private final MapAuctionRepository repository;
    private final SourceHistoryService history;
    private final rs.sud.eaukcija.history.CatalogueChangesService changes;

    @org.springframework.beans.factory.annotation.Autowired
    public MapAuctionService(MapAuctionRepository repository, SourceHistoryService history,
                             rs.sud.eaukcija.history.CatalogueChangesService changes) {
        this.repository = repository; this.history = history; this.changes = changes;
    }
    public MapAuctionService(MapAuctionRepository repository, SourceHistoryService history) {
        this(repository, history, null);
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true,
            isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public MapGeoJsonResponse findAuctions(MapAuctionRequest request) {
        if (changes != null) request = new MapAuctionRequest(request.boundingBox(), changes.prepare(request.filters()), request.limit());
        var sourceFrame = changes == null ? history.capture(request.filters().asOf()) : request.filters().changes().window().upper();
        List<MapAuctionRow> rows = repository.findWithin(request);
        boolean truncated = rows.size() > request.limit();
        int returned = Math.min(rows.size(), request.limit());
        List<MapGeoJsonResponse.Feature> features = new ArrayList<>(returned);
        for (int index = 0; index < returned; index++) {
            features.add(toFeature(rows.get(index), request));
        }
        MapGeoJsonResponse.Selection selection = null;
        if (request.filters().auction() != null) {
            String selectedState = repository.selectionState(request);
            long selectedId = request.filters().auction();
            if ("VISIBLE".equals(selectedState) && features.stream().noneMatch(
                    f -> f.properties().auctionId() == selectedId)) selectedState = "LIMIT";
            selection = new MapGeoJsonResponse.Selection(request.filters().auction(), selectedState);
        }
        return new MapGeoJsonResponse("FeatureCollection", List.copyOf(features), returned, request.limit(), truncated,
                request.filters().asOf(), request.filters().timeScope(), repository.counts(request),
                features.stream().map(f -> f.properties().auctionId()).distinct().count(), selection, sourceFrame,
                changes == null ? java.util.Map.of() : changes.display(features.stream().map(f -> f.properties().auctionId()).toList(), request.filters()),
                changes == null ? null : changes.summary(request.filters()));
    }

    private static MapGeoJsonResponse.Feature toFeature(MapAuctionRow row, MapAuctionRequest request) {
        String title = safeText(row.auctionNumber(), "Е-аукција " + row.auctionId());
        String status = safeText(row.sourceStatus(), "Unknown");
        String kind = safeText(row.propertyKind(), null); // Unknown raw category is not inferred taxonomy.
        return new MapGeoJsonResponse.Feature(
                "Feature",
                row.featureId(),
                GeoJsonGeometry.from(row.geometry()),
                GeoJsonGeometry.markerFor(row.geometry(), request.boundingBox()),
                new MapGeoJsonResponse.Properties(
                        row.auctionId(),
                        title,
                        row.amount(),
                        "RSD",
                        row.endTime(),
                        status,
                        kind,
                        row.precision().name(),
                        detailUrl(row.auctionId())));
    }

    private static String detailUrl(long auctionId) {
        return DETAIL_URL_PREFIX + auctionId;
    }

    private static String safeText(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        StringBuilder cleaned = new StringBuilder(Math.min(value.length(), 256));
        boolean previousWhitespace = false;
        for (int offset = 0; offset < value.length() && cleaned.length() < 256;) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isISOControl(codePoint) || Character.getType(codePoint) == Character.FORMAT) {
                continue;
            }
            if (Character.isWhitespace(codePoint)) {
                if (!previousWhitespace && !cleaned.isEmpty()) {
                    cleaned.append(' ');
                }
                previousWhitespace = true;
            } else {
                cleaned.appendCodePoint(codePoint);
                previousWhitespace = false;
            }
        }
        String normalized = cleaned.toString().trim();
        if (normalized.isEmpty()) {
            return fallback;
        }
        return normalized;
    }
}
