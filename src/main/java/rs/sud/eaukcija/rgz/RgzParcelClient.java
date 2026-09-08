package rs.sud.eaukcija.rgz;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.operation.valid.IsValidOp;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Bounded WFS client for issue #21 under the access decision recorded by issue #41. */
@Component
public class RgzParcelClient {

    private static final String OUTPUT_CRS = "EPSG:4326";
    private static final int MAX_FEATURES = 2;
    private static final int MAX_POSITIONS = 200_000;
    private static final double MIN_LONGITUDE = 18.0;
    private static final double MAX_LONGITUDE = 24.0;
    private static final double MIN_LATITUDE = 41.0;
    private static final double MAX_LATITUDE = 47.0;
    private static final List<Integer> RETRYABLE_STATUSES = List.of(429, 502, 503, 504);

    private final RgzParcelProperties properties;
    private final ObjectMapper objectMapper;
    private final OkHttpClient http;
    private final RgzTiming timing;
    private final RgzRateGate rateGate;

    @Autowired
    public RgzParcelClient(RgzParcelProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, RgzTiming.system(), false);
    }

    RgzParcelClient(
            RgzParcelProperties properties,
            ObjectMapper objectMapper,
            RgzTiming timing,
            boolean allowLoopbackHttp) {
        properties.validate(allowLoopbackHttp);
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.timing = timing;
        this.rateGate = new RgzRateGate(
                properties.getRequestsPerSecond(), properties.getMaxConcurrency(), timing);
        this.http = new OkHttpClient.Builder()
                .connectTimeout(properties.getConnectTimeout())
                .readTimeout(properties.getReadTimeout())
                .callTimeout(properties.getCallTimeout())
                .retryOnConnectionFailure(false)
                // Redirects would bypass the per-physical-request gate and could
                // reach a login/session endpoint outside the authorized contract.
                .followRedirects(false)
                .followSslRedirects(false)
                .addNetworkInterceptor(chain -> {
                    // OkHttp can internally follow up a 503 Retry-After: 0 even
                    // with retryOnConnectionFailure(false). Every wire request
                    // must instead pass our own attempt/rate/kill-switch gates.
                    if (!chain.request().tag(AtomicBoolean.class).compareAndSet(false, true)) {
                        throw new java.net.ProtocolException("Automatic HTTP follow-up prohibited");
                    }
                    return chain.proceed(chain.request());
                })
                .build();
    }

    public RgzParcelResult fetch(
            String koCode,
            String canonicalParcelNumber,
            BooleanSupplier networkAllowed) {
        RgzParcelResult result = fetchBounded(koCode, canonicalParcelNumber, networkAllowed);
        Map<String, Object> evidence = new LinkedHashMap<>(result.evidence());
        if (koCode != null && koCode.matches("[0-9]{1,16}")) {
            evidence.put("requestedKoCode", koCode);
        }
        if (canonicalParcelNumber != null
                && canonicalParcelNumber.matches("[0-9]{1,32}(?:/[0-9]{1,32})?")) {
            evidence.put("requestedParcelNumber", canonicalParcelNumber);
        }
        evidence.put("wfsVersion", "2.0.0");
        if (result.physicalAttempts() > 0) {
            evidence.put("retrievedAt", timing.instant().toString());
        }
        return new RgzParcelResult(result.status(), result.reason(), result.rawResponseSha256(),
                result.sourceFeatureId(), result.geometryType(), result.geometryJson(),
                result.areaSquareMetres(), result.sourceProjection(), result.scale(),
                result.physicalAttempts(), evidence);
    }

    private RgzParcelResult fetchBounded(
            String koCode, String canonicalParcelNumber, BooleanSupplier networkAllowed) {
        if (koCode == null || !koCode.matches("[0-9]{1,16}")) {
            return terminal(RgzParcelResult.Status.INVALID, "INVALID_KO_CODE", null, 0);
        }
        if (canonicalParcelNumber == null
                || !canonicalParcelNumber.matches("[0-9]{1,32}(?:/[0-9]{1,32})?")) {
            return terminal(RgzParcelResult.Status.INVALID, "INVALID_PARCEL_NUMBER", null, 0);
        }

        int physicalAttempts = 0;
        for (int attempt = 1; attempt <= properties.getMaxAttempts(); attempt++) {
            if (!networkAllowed.getAsBoolean()) {
                return terminal(RgzParcelResult.Status.ERROR, "KILL_SWITCH_ENGAGED", null, physicalAttempts);
            }
            try (RgzRateGate.Permit ignored = rateGate.acquire()) {
                if (!networkAllowed.getAsBoolean()) {
                    return terminal(
                            RgzParcelResult.Status.ERROR,
                            "KILL_SWITCH_ENGAGED", null, physicalAttempts);
                }
                physicalAttempts++;
                try (Response response = http.newCall(request(koCode, canonicalParcelNumber)).execute()) {
                    if (!response.isSuccessful()) {
                        int status = response.code();
                        if (RETRYABLE_STATUSES.contains(status)
                                && attempt < properties.getMaxAttempts()) {
                            if (!sleepBeforeRetry(response, attempt)) {
                                return terminal(
                                        RgzParcelResult.Status.ERROR,
                                        "INTERRUPTED", null, physicalAttempts);
                            }
                            continue;
                        }
                        return terminal(
                                RgzParcelResult.Status.ERROR,
                                "HTTP_" + status, null, physicalAttempts);
                    }
                    byte[] raw = boundedBody(response);
                    return parse(raw, response.body() == null ? null : response.body().contentType(),
                            koCode, canonicalParcelNumber, physicalAttempts);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return terminal(RgzParcelResult.Status.ERROR, "INTERRUPTED", null, physicalAttempts);
            } catch (BodyTooLargeException tooLarge) {
                return terminal(
                        RgzParcelResult.Status.ERROR, "RESPONSE_TOO_LARGE", null, physicalAttempts);
            } catch (IOException transportFailure) {
                if (attempt < properties.getMaxAttempts()) {
                    if (!sleep(properties.getRetryDelays().get(attempt - 1))) {
                        return terminal(
                                RgzParcelResult.Status.ERROR, "INTERRUPTED", null, physicalAttempts);
                    }
                    continue;
                }
                String reason = transportFailure instanceof SocketTimeoutException
                        ? "TRANSPORT_TIMEOUT" : "TRANSPORT_ERROR";
                return terminal(RgzParcelResult.Status.ERROR, reason, null, physicalAttempts);
            }
        }
        return terminal(RgzParcelResult.Status.ERROR, "ATTEMPTS_EXHAUSTED", null, physicalAttempts);
    }

    /** Metadata uses the same bounded transport, physical rate/concurrency and live kill gates. */
    public MetadataResponse fetchMetadata(boolean capabilities) {
        for (int attempt = 1; attempt <= properties.getMaxAttempts(); attempt++) {
            if (!properties.metadataNetworkAllowed()) {
                return new MetadataResponse(null, null, "RGZ_METADATA_STOPPED");
            }
            try (RgzRateGate.Permit ignored = rateGate.acquire()) {
                if (!properties.metadataNetworkAllowed()) {
                    return new MetadataResponse(null, null, "RGZ_METADATA_STOPPED");
                }
                HttpUrl.Builder url = HttpUrl.get(properties.getBaseUrl()).newBuilder()
                        .addQueryParameter("service", "WFS").addQueryParameter("version", "2.0.0")
                        .addQueryParameter("request", capabilities ? "GetCapabilities" : "DescribeFeatureType");
                if (!capabilities) url.addQueryParameter("typeNames", properties.getFeatureType());
                Request request = new Request.Builder().url(url.build())
                        .tag(AtomicBoolean.class, new AtomicBoolean())
                        .header("Accept", "application/xml, application/gml+xml, text/xml")
                        .header("User-Agent", properties.requestUserAgent()).build();
                try (Response response = http.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        if (RETRYABLE_STATUSES.contains(response.code()) && attempt < properties.getMaxAttempts()) {
                            if (!sleepBeforeRetry(response, attempt)) break;
                            continue;
                        }
                        return new MetadataResponse(null, null, "RGZ_METADATA_HTTP_ERROR");
                    }
                    MediaType type = response.body() == null ? null : response.body().contentType();
                    if (type == null || !("xml".equals(type.subtype()) || type.subtype().endsWith("+xml"))) {
                        return new MetadataResponse(null, null, "RGZ_METADATA_CONTENT_TYPE");
                    }
                    byte[] raw = boundedBody(response);
                    return new MetadataResponse(raw, sha256(raw), null);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            } catch (BodyTooLargeException tooLarge) {
                return new MetadataResponse(null, null, "RGZ_METADATA_TOO_LARGE");
            } catch (IOException failure) {
                if (attempt < properties.getMaxAttempts() && sleep(properties.getRetryDelays().get(attempt - 1))) {
                    continue;
                }
                return new MetadataResponse(null, null, "RGZ_METADATA_TRANSPORT_ERROR");
            }
        }
        return new MetadataResponse(null, null, "RGZ_METADATA_INTERRUPTED");
    }

    /** Raw XML stays transient and is never passed to persistence or operator status. */
    public record MetadataResponse(byte[] body, String sha256, String failureCode) { }

    private Request request(String koCode, String parcelNumber) {
        HttpUrl base = HttpUrl.get(properties.getBaseUrl());
        HttpUrl url = base.newBuilder()
                .addQueryParameter("service", "WFS")
                .addQueryParameter("version", "2.0.0")
                .addQueryParameter("request", "GetFeature")
                .addQueryParameter("typeNames", properties.getFeatureType())
                .addQueryParameter("outputFormat", "application/json")
                .addQueryParameter("srsName", OUTPUT_CRS)
                .addQueryParameter("count", Integer.toString(MAX_FEATURES))
                .addQueryParameter(
                        "cql_filter",
                        "cadmun_code=" + koCode + " AND parcel_num='" + parcelNumber + "'")
                .build();
        return new Request.Builder()
                .tag(AtomicBoolean.class, new AtomicBoolean())
                .url(url)
                .header("Accept", "application/json, application/geo+json")
                .header("User-Agent", properties.requestUserAgent())
                .get()
                .build();
    }

    private boolean sleepBeforeRetry(Response response, int attempt) {
        Duration configured = properties.getRetryDelays().get(attempt - 1);
        Duration retryAfter = retryAfter(response);
        Duration delay = retryAfter != null && retryAfter.compareTo(configured) > 0
                ? retryAfter : configured;
        if (delay.compareTo(properties.getMaxRetryAfter()) > 0) {
            delay = properties.getMaxRetryAfter();
        }
        return sleep(delay);
    }

    private boolean sleep(Duration delay) {
        try {
            timing.sleep(delay);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private Duration retryAfter(Response response) {
        String header = response.header("Retry-After");
        if (header == null) {
            return null;
        }
        String value = header.trim();
        try {
            if (value.matches("[0-9]{1,9}")) {
                return Duration.ofSeconds(Long.parseLong(value));
            }
            Instant retryAt = ZonedDateTime.parse(
                    value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            Duration remaining = Duration.between(timing.instant(), retryAt);
            return remaining.isNegative() ? Duration.ZERO : remaining;
        } catch (ArithmeticException | NumberFormatException | DateTimeParseException invalid) {
            return null;
        }
    }

    private byte[] boundedBody(Response response) throws IOException, BodyTooLargeException {
        ResponseBody body = response.body();
        if (body == null) {
            return new byte[0];
        }
        long declared = body.contentLength();
        if (declared > properties.getMaxResponseBytes()) {
            throw new BodyTooLargeException();
        }
        try (InputStream input = body.byteStream();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            long count = 0;
            while (true) {
                int read = input.read(buffer);
                if (read < 0) {
                    break;
                }
                count += read;
                if (count > properties.getMaxResponseBytes()) {
                    throw new BodyTooLargeException();
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private RgzParcelResult parse(
            byte[] raw,
            MediaType mediaType,
            String requestedKoCode,
            String requestedParcel,
            int physicalAttempts) {
        String rawSha256 = sha256(raw);
        if (mediaType == null || mediaType.type() == null
                || !"application".equalsIgnoreCase(mediaType.type())
                || !("json".equalsIgnoreCase(mediaType.subtype())
                || "geo+json".equalsIgnoreCase(mediaType.subtype()))) {
            return terminal(
                    RgzParcelResult.Status.ERROR,
                    "INVALID_CONTENT_TYPE", rawSha256, physicalAttempts);
        }
        JsonNode payload;
        try {
            payload = objectMapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                    .readTree(raw);
        } catch (JsonProcessingException invalidJson) {
            return terminal(RgzParcelResult.Status.ERROR, "INVALID_JSON", rawSha256, physicalAttempts);
        } catch (IOException invalidJson) {
            return terminal(RgzParcelResult.Status.ERROR, "INVALID_JSON", rawSha256, physicalAttempts);
        }
        if (payload == null || !payload.isObject()
                || !"FeatureCollection".equals(payload.path("type").asText())) {
            return terminal(
                    RgzParcelResult.Status.ERROR,
                    "INVALID_FEATURE_COLLECTION", rawSha256, physicalAttempts);
        }
        if (!validCrs(payload.path("crs"))) {
            return terminal(RgzParcelResult.Status.ERROR, "INVALID_CRS", rawSha256, physicalAttempts);
        }
        JsonNode features = payload.path("features");
        if (!features.isArray()) {
            return terminal(
                    RgzParcelResult.Status.ERROR,
                    "INVALID_FEATURES", rawSha256, physicalAttempts);
        }
        if (features.size() > 1) {
            return terminal(RgzParcelResult.Status.AMBIGUOUS,
                    "MULTIPLE_FEATURES", rawSha256, physicalAttempts);
        }
        // Observe ambiguity even when a broken/truncated server returns only
        // one of the advertised matches. Never choose or merge candidates.
        for (String field : List.of("numberMatched", "totalFeatures", "numberReturned")) {
            JsonNode count = payload.get(field);
            if (count == null || "unknown".equals(count.asText())) {
                continue;
            }
            if (!count.isIntegralNumber() || !count.canConvertToLong() || count.longValue() < 0) {
                return terminal(RgzParcelResult.Status.ERROR,
                        "INVALID_FEATURE_COUNT", rawSha256, physicalAttempts);
            }
            if (!"numberReturned".equals(field) && count.longValue() > 1) {
                return terminal(RgzParcelResult.Status.AMBIGUOUS,
                        "MULTIPLE_FEATURES", rawSha256, physicalAttempts);
            }
            if (count.longValue() != features.size()) {
                return terminal(RgzParcelResult.Status.ERROR,
                        "INCONSISTENT_FEATURE_COUNT", rawSha256, physicalAttempts);
            }
        }
        if (features.isEmpty()) {
            return terminal(
                    RgzParcelResult.Status.NOT_FOUND,
                    "AUTHORITATIVE_NOT_FOUND", rawSha256, physicalAttempts);
        }
        JsonNode feature = features.get(0);
        JsonNode values = feature.path("properties");
        if (!feature.isObject() || !"Feature".equals(feature.path("type").asText())
                || !values.isObject()) {
            return terminal(
                    RgzParcelResult.Status.ERROR,
                    "INVALID_FEATURE", rawSha256, physicalAttempts);
        }
        String returnedKoCode = textualNumber(values.get("cadmun_code"));
        String returnedParcel = textualNumber(values.get("parcel_num"));
        if (!requestedKoCode.equals(returnedKoCode)
                || !requestedParcel.equals(returnedParcel)) {
            return terminal(
                    RgzParcelResult.Status.INVALID,
                    "IDENTITY_MISMATCH", rawSha256, physicalAttempts);
        }
        BigDecimal area = decimal(values.get("area"));
        if (area == null || area.signum() <= 0) {
            return terminal(
                    RgzParcelResult.Status.INVALID,
                    "INVALID_AREA", rawSha256, physicalAttempts);
        }

        Geometry geometry;
        try {
            geometry = geometry(feature.path("geometry"));
        } catch (GeometryFailure invalidGeometry) {
            return terminal(
                    RgzParcelResult.Status.INVALID,
                    invalidGeometry.reason, rawSha256, physicalAttempts);
        }
        String geometryJson;
        try {
            JsonNode rawGeometry = feature.path("geometry");
            if (rawGeometry.has("crs") && !validCrs(rawGeometry.get("crs"))) {
                return terminal(RgzParcelResult.Status.ERROR, "INVALID_CRS", rawSha256, physicalAttempts);
            }
            // GeoJSON foreign members are not part of the persistence/export whitelist.
            geometryJson = objectMapper.writeValueAsString(objectMapper.createObjectNode()
                    .put("type", geometry.getGeometryType())
                    .set("coordinates", rawGeometry.path("coordinates")));
        } catch (JsonProcessingException impossible) {
            return terminal(
                    RgzParcelResult.Status.ERROR,
                    "INVALID_GEOMETRY_JSON", rawSha256, physicalAttempts);
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("schemaVersion", "rgz-parcel-evidence-v1");
        evidence.put("requestedKoCode", requestedKoCode);
        evidence.put("requestedParcelNumber", requestedParcel);
        evidence.put("returnedKoCode", returnedKoCode);
        evidence.put("returnedParcelNumber", returnedParcel);
        evidence.put("geometryType", geometry.getGeometryType());
        evidence.put("areaSquareMetres", area);
        String sourceProjection = safeScalar(values.get("source_projection"));
        String scale = safeScalar(values.get("scale"));
        if (sourceProjection != null) {
            evidence.put("sourceProjection", sourceProjection);
        }
        if (scale != null) {
            evidence.put("scale", scale);
        }
        evidence.put("rawResponseSha256", rawSha256);
        evidence.put("physicalAttempts", physicalAttempts);
        return new RgzParcelResult(
                RgzParcelResult.Status.RESOLVED,
                "EXACT_KO_PARCEL_MATCH",
                rawSha256,
                safeFeatureId(feature.get("id")),
                geometry.getGeometryType(),
                geometryJson,
                area,
                sourceProjection,
                scale,
                physicalAttempts,
                evidence);
    }

    private static boolean validCrs(JsonNode crs) {
        String name = crs.path("properties").path("name").asText();
        return crs.isObject() && "name".equals(crs.path("type").asText())
                && ("EPSG:4326".equals(name) || "urn:ogc:def:crs:EPSG::4326".equals(name));
    }

    private static String textualNumber(JsonNode value) {
        if (value == null || value.isNull() || (!value.isTextual() && !value.isIntegralNumber())) {
            return null;
        }
        return value.asText();
    }

    private static BigDecimal decimal(JsonNode value) {
        if (!finiteNumber(value)) {
            return null;
        }
        return value.decimalValue();
    }

    private static boolean finiteNumber(JsonNode value) {
        return value != null && value.isNumber() && Double.isFinite(value.doubleValue());
    }

    private static String safeScalar(JsonNode value) {
        if (value == null || value.isNull() || (!value.isTextual() && !value.isNumber())) {
            return null;
        }
        String text = value.asText();
        return text.length() <= 128 ? text : null;
    }

    private static String safeFeatureId(JsonNode value) {
        if (value == null || !value.isTextual()) {
            return null;
        }
        String text = value.textValue();
        return text.matches("[A-Za-z0-9_.:-]{1,256}") ? text : null;
    }

    private static Geometry geometry(JsonNode value) throws GeometryFailure {
        if (!value.isObject()) {
            throw new GeometryFailure("INVALID_GEOMETRY");
        }
        GeometryFactory factory = new GeometryFactory();
        PositionCounter counter = new PositionCounter();
        Geometry geometry = switch (value.path("type").asText()) {
            case "Polygon" -> polygon(factory, value.path("coordinates"), counter);
            case "MultiPolygon" -> multiPolygon(factory, value.path("coordinates"), counter);
            default -> throw new GeometryFailure("UNSUPPORTED_GEOMETRY_TYPE");
        };
        geometry.setSRID(4326);
        if (geometry.isEmpty() || geometry.getArea() <= 1e-15 || !new IsValidOp(geometry).isValid()) {
            throw new GeometryFailure("INVALID_GEOMETRY");
        }
        return geometry;
    }

    private static MultiPolygon multiPolygon(
            GeometryFactory factory,
            JsonNode coordinates,
            PositionCounter counter) throws GeometryFailure {
        if (!coordinates.isArray() || coordinates.isEmpty()) {
            throw new GeometryFailure("INVALID_MULTIPOLYGON");
        }
        List<Polygon> polygons = new ArrayList<>();
        for (JsonNode polygon : coordinates) {
            polygons.add(polygon(factory, polygon, counter));
        }
        return factory.createMultiPolygon(polygons.toArray(Polygon[]::new));
    }

    private static Polygon polygon(
            GeometryFactory factory,
            JsonNode coordinates,
            PositionCounter counter) throws GeometryFailure {
        if (!coordinates.isArray() || coordinates.isEmpty()) {
            throw new GeometryFailure("INVALID_POLYGON");
        }
        LinearRing shell = ring(factory, coordinates.get(0), counter);
        LinearRing[] holes = new LinearRing[Math.max(0, coordinates.size() - 1)];
        for (int index = 1; index < coordinates.size(); index++) {
            holes[index - 1] = ring(factory, coordinates.get(index), counter);
        }
        return factory.createPolygon(shell, holes);
    }

    private static LinearRing ring(
            GeometryFactory factory,
            JsonNode positions,
            PositionCounter counter) throws GeometryFailure {
        if (!positions.isArray() || positions.size() < 4) {
            throw new GeometryFailure("INVALID_RING");
        }
        Coordinate[] coordinates = new Coordinate[positions.size()];
        for (int index = 0; index < positions.size(); index++) {
            if (++counter.count > MAX_POSITIONS) {
                throw new GeometryFailure("GEOMETRY_TOO_COMPLEX");
            }
            JsonNode position = positions.get(index);
            if (!position.isArray() || position.size() != 2
                    || !finiteNumber(position.get(0)) || !finiteNumber(position.get(1))) {
                throw new GeometryFailure("INVALID_POSITION");
            }
            double longitude = position.get(0).doubleValue();
            double latitude = position.get(1).doubleValue();
            if (longitude < MIN_LONGITUDE || longitude > MAX_LONGITUDE
                    || latitude < MIN_LATITUDE || latitude > MAX_LATITUDE) {
                throw new GeometryFailure("OUTSIDE_SERBIA_BOUNDS");
            }
            coordinates[index] = new Coordinate(longitude, latitude);
        }
        if (!coordinates[0].equals2D(coordinates[coordinates.length - 1])) {
            throw new GeometryFailure("OPEN_RING");
        }
        try {
            return factory.createLinearRing(coordinates);
        } catch (IllegalArgumentException invalid) {
            throw new GeometryFailure("INVALID_RING");
        }
    }

    private static RgzParcelResult terminal(
            RgzParcelResult.Status status,
            String reason,
            String rawSha256,
            int attempts) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("schemaVersion", "rgz-parcel-evidence-v1");
        evidence.put("reason", reason);
        if (rawSha256 != null) {
            evidence.put("rawResponseSha256", rawSha256);
        }
        evidence.put("physicalAttempts", attempts);
        return new RgzParcelResult(
                status, reason, rawSha256, null, null, null, null, null, null, attempts, evidence);
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static final class PositionCounter {
        private int count;
    }

    private static final class GeometryFailure extends Exception {
        private static final long serialVersionUID = 1L;
        private final String reason;

        private GeometryFailure(String reason) {
            this.reason = reason;
        }
    }

    private static final class BodyTooLargeException extends Exception {
        private static final long serialVersionUID = 1L;
    }
}
