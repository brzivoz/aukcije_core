package rs.sud.eaukcija.map;

import java.time.Clock;
import java.time.ZoneId;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import rs.sud.eaukcija.filter.AuctionFilterParser;
import rs.sud.eaukcija.filter.AuctionFilters;
import rs.sud.eaukcija.spatial.BoundingBox;

/** Spatial transport validation only; auction criteria belong to AuctionFilterParser. */
@Component
@Profile("!local-h2")
public class MapAuctionRequestParser {
    public static final int DEFAULT_LIMIT = 1_000;
    public static final int MAX_LIMIT = 5_000;
    public static final double MAX_BBOX_AREA_SQUARE_KM = 1_000_000;
    public static final ZoneId DISPLAY_ZONE = AuctionFilters.ZONE;
    private static final double EARTH_RADIUS_KM = 6_371.0088;
    private final AuctionFilterParser filters;

    @Autowired
    public MapAuctionRequestParser(AuctionFilterParser filters) { this.filters = filters; }
    public MapAuctionRequestParser() { this(Clock.systemUTC()); }
    MapAuctionRequestParser(Clock clock) { this(new AuctionFilterParser(null, clock)); }

    public MapAuctionRequest parse(MultiValueMap<String, String> parameters) {
        AuctionFilters shared = filters.parse(parameters, Set.of("bbox", "limit"));
        String value = optional(parameters, "bbox");
        if (value == null) throw invalid("bbox", "bbox is required");
        BoundingBox box = parseBoundingBox(value);
        validateArea(box);
        return new MapAuctionRequest(box, shared, parseLimit(parameters));
    }

    private BoundingBox parseBoundingBox(String value) {
        String[] coordinates = value.split(",", -1);
        if (coordinates.length != 4) throw invalid("bbox", "bbox must contain minLon,minLat,maxLon,maxLat");
        double[] parsed = new double[4];
        for (int index = 0; index < coordinates.length; index++) {
            try { parsed[index] = Double.parseDouble(coordinates[index].trim()); }
            catch (NumberFormatException e) { throw invalid("bbox", "bbox coordinates must be finite decimal numbers"); }
        }
        try { return new BoundingBox(parsed[0], parsed[1], parsed[2], parsed[3]); }
        catch (IllegalArgumentException e) { throw invalid("bbox", e.getMessage()); }
    }

    private void validateArea(BoundingBox box) {
        double longitudeRadians = Math.toRadians(box.maxLongitude() - box.minLongitude());
        double latitudeFactor = Math.abs(Math.sin(Math.toRadians(box.maxLatitude()))
                - Math.sin(Math.toRadians(box.minLatitude())));
        if (EARTH_RADIUS_KM * EARTH_RADIUS_KM * longitudeRadians * latitudeFactor > MAX_BBOX_AREA_SQUARE_KM)
            throw invalid("bbox", "bbox area must not exceed 1000000 square kilometres");
    }
    private int parseLimit(MultiValueMap<String, String> parameters) {
        String value = optional(parameters, "limit");
        if (value == null) return DEFAULT_LIMIT;
        try {
            int limit = Integer.parseInt(value);
            if (limit < 1 || limit > MAX_LIMIT) throw new NumberFormatException();
            return limit;
        } catch (NumberFormatException e) { throw invalid("limit", "limit must be an integer between 1 and 5000"); }
    }
    private static String optional(MultiValueMap<String, String> parameters, String field) {
        String value = parameters.getFirst(field);
        return value == null || value.isBlank() ? null : value.trim();
    }
    private static InvalidMapRequestException invalid(String field, String message) {
        return new InvalidMapRequestException(field, message);
    }
}
