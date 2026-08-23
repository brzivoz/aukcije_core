package rs.sud.eaukcija.map;

import java.util.ArrayList;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Maps the database projection to the deliberately safe public GeoJSON contract. */
@Service
@Profile("!local-h2")
public class MapAuctionService {

    private static final String DETAIL_URL_PREFIX = "https://eaukcija.sud.rs/#/aukcije/";

    private final MapAuctionRepository repository;

    public MapAuctionService(MapAuctionRepository repository) {
        this.repository = repository;
    }

    public MapGeoJsonResponse findAuctions(MapAuctionRequest request) {
        List<MapAuctionRow> rows = repository.findWithin(request);
        boolean truncated = rows.size() > request.limit();
        int returned = Math.min(rows.size(), request.limit());
        List<MapGeoJsonResponse.Feature> features = new ArrayList<>(returned);
        for (int index = 0; index < returned; index++) {
            features.add(toFeature(rows.get(index)));
        }
        return new MapGeoJsonResponse("FeatureCollection", List.copyOf(features), returned, request.limit(), truncated);
    }

    private static MapGeoJsonResponse.Feature toFeature(MapAuctionRow row) {
        String title = safeText(row.auctionNumber(), "Е-аукција " + row.auctionId());
        String status = safeText(row.sourceStatus(), "Unknown");
        String kind = safeText(row.propertyKind(), "Непокретности");
        return new MapGeoJsonResponse.Feature(
                "Feature",
                row.featureId(),
                GeoJsonGeometry.from(row.geometry()),
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
