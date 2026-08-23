package rs.sud.eaukcija.map;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;

import rs.sud.eaukcija.spatial.BoundingBox;
import rs.sud.eaukcija.spatial.LocationPrecision;

/** Parses the deliberately small, allowlisted public map query surface. */
@Component
@Profile("!local-h2")
public class MapAuctionRequestParser {

    public static final int DEFAULT_LIMIT = 1_000;
    public static final int MAX_LIMIT = 5_000;
    public static final double MAX_BBOX_AREA_SQUARE_KM = 1_000_000;
    public static final ZoneId DISPLAY_ZONE = ZoneId.of("Europe/Belgrade");

    private static final double EARTH_RADIUS_KM = 6_371.0088;
    private static final Set<String> ALLOWED_PARAMETERS =
            Set.of("bbox", "status", "kind", "precision", "from", "to", "limit");

    private final Clock clock;

    public MapAuctionRequestParser() {
        this(Clock.systemUTC());
    }

    MapAuctionRequestParser(Clock clock) {
        this.clock = clock;
    }

    public MapAuctionRequest parse(MultiValueMap<String, String> parameters) {
        rejectUnknownOrRepeatedParameters(parameters);
        BoundingBox box = parseBoundingBox(required(parameters, "bbox"));
        validateArea(box);

        String status = optional(parameters, "status");
        if (status != null) {
            String canonical = MapAuctionFilterOptions.canonicalStatus(status);
            if (canonical == null) {
                throw invalid("status", "status must be one of "
                        + MapAuctionFilterOptions.statuses().stream()
                                .map(MapAuctionFilterOptions.Option::value)
                                .toList());
            }
            status = canonical;
        }

        String kind = optional(parameters, "kind");
        if (kind != null && !MapAuctionFilterOptions.kindValues().contains(kind)) {
            throw invalid("kind", "kind is not in the supported eAukcija category allowlist");
        }

        LocationPrecision precision = parsePrecision(optional(parameters, "precision"));
        Instant from = parseFrom(parameters);
        Instant to = parseTo(parameters);
        if (to != null && !to.isAfter(from)) {
            throw invalid("to", "to must be the same as or later than from");
        }

        return new MapAuctionRequest(box, status, kind, precision, from, to, parseLimit(parameters));
    }

    private void rejectUnknownOrRepeatedParameters(MultiValueMap<String, String> parameters) {
        for (Map.Entry<String, java.util.List<String>> entry : parameters.entrySet()) {
            if (!ALLOWED_PARAMETERS.contains(entry.getKey())) {
                throw invalid(entry.getKey(), "unsupported query parameter");
            }
            if (entry.getValue().size() != 1) {
                throw invalid(entry.getKey(), "query parameter must occur exactly once");
            }
        }
    }

    private BoundingBox parseBoundingBox(String value) {
        String[] coordinates = value.split(",", -1);
        if (coordinates.length != 4) {
            throw invalid("bbox", "bbox must contain minLon,minLat,maxLon,maxLat");
        }
        double[] parsed = new double[4];
        for (int index = 0; index < coordinates.length; index++) {
            try {
                parsed[index] = Double.parseDouble(coordinates[index].trim());
            } catch (NumberFormatException e) {
                throw invalid("bbox", "bbox coordinates must be finite decimal numbers");
            }
        }
        try {
            return new BoundingBox(parsed[0], parsed[1], parsed[2], parsed[3]);
        } catch (IllegalArgumentException e) {
            throw invalid("bbox", e.getMessage());
        }
    }

    private void validateArea(BoundingBox box) {
        double longitudeRadians = Math.toRadians(box.maxLongitude() - box.minLongitude());
        double latitudeFactor = Math.abs(
                Math.sin(Math.toRadians(box.maxLatitude()))
                        - Math.sin(Math.toRadians(box.minLatitude())));
        double area = EARTH_RADIUS_KM * EARTH_RADIUS_KM * longitudeRadians * latitudeFactor;
        if (area > MAX_BBOX_AREA_SQUARE_KM) {
            throw invalid("bbox", "bbox area must not exceed 1000000 square kilometres");
        }
    }

    private LocationPrecision parsePrecision(String value) {
        if (value == null) {
            return null;
        }
        try {
            LocationPrecision precision = LocationPrecision.valueOf(value.toUpperCase(Locale.ROOT));
            if (!MapAuctionFilterOptions.precisionValues().contains(precision.name())) {
                throw new IllegalArgumentException();
            }
            return precision;
        } catch (IllegalArgumentException e) {
            throw invalid("precision", "precision must be one of "
                    + MapAuctionFilterOptions.precisions().stream()
                            .map(MapAuctionFilterOptions.Option::value)
                            .toList());
        }
    }

    private Instant parseFrom(MultiValueMap<String, String> parameters) {
        String value = optional(parameters, "from");
        if (value == null) {
            return clock.instant();
        }
        return parseDate("from", value).atStartOfDay(DISPLAY_ZONE).toInstant();
    }

    private Instant parseTo(MultiValueMap<String, String> parameters) {
        String value = optional(parameters, "to");
        if (value == null) {
            return null;
        }
        return parseDate("to", value).plusDays(1).atStartOfDay(DISPLAY_ZONE).toInstant();
    }

    private LocalDate parseDate(String field, String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeException e) {
            throw invalid(field, field + " must be an ISO date in YYYY-MM-DD form");
        }
    }

    private int parseLimit(MultiValueMap<String, String> parameters) {
        String value = optional(parameters, "limit");
        if (value == null) {
            return DEFAULT_LIMIT;
        }
        try {
            int limit = Integer.parseInt(value);
            if (limit < 1 || limit > MAX_LIMIT) {
                throw new NumberFormatException();
            }
            return limit;
        } catch (NumberFormatException e) {
            throw invalid("limit", "limit must be an integer between 1 and 5000");
        }
    }

    private static String required(MultiValueMap<String, String> parameters, String field) {
        String value = optional(parameters, field);
        if (value == null) {
            throw invalid(field, field + " is required");
        }
        return value;
    }

    private static String optional(MultiValueMap<String, String> parameters, String field) {
        String value = parameters.getFirst(field);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static InvalidMapRequestException invalid(String field, String message) {
        return new InvalidMapRequestException(field, message);
    }
}
