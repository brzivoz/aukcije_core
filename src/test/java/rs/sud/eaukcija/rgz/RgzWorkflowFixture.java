package rs.sud.eaukcija.rgz;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.springframework.test.context.DynamicPropertyRegistry;

import rs.sud.eaukcija.addressregistry.KoDictionaryPublisherTestBridge;
import rs.sud.eaukcija.testsupport.Fixtures;

/** Entirely synthetic shapes/auctions with the three reviewed exact identities.
 * No captured RGZ geometry or personal records are redistributed by these fixtures. */
public final class RgzWorkflowFixture implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    public static final Example DIMITROVGRAD = new Example(21001, "Димитровград", "713848", "1572", "success");
    public static final Example CAJETINA = new Example(21002, "Чајетина", "743968", "4577/337", "success");
    public static final Example VOZDOVAC = new Example(21003, "Вождовац", "703621", "7300/1", "success");
    public static final List<Example> SUCCESSES = List.of(DIMITROVGRAD, CAJETINA, VOZDOVAC);
    public static final String PRIVATE_SENTINEL = "DO_NOT_RETAIN_PRIVATE_SENTINEL";

    public final MockWebServer server = new MockWebServer();
    public final Path root;
    public final Path centroids;
    public final Path dictionary;
    public final Path killSwitch;
    public final List<String> parcelRequests = new CopyOnWriteArrayList<>();
    public final List<String> metadataRequests = new CopyOnWriteArrayList<>();
    public volatile String metadataFailure;
    public volatile List<Example> population = SUCCESSES;
    public volatile String overrideScenario;

    public RgzWorkflowFixture() {
        try {
            root = Files.createTempDirectory("issue-21-workflow-");
            killSwitch = root.resolve("rgz.disabled");
            centroids = createCentroids(root.resolve("centroids"));
            dictionary = KoDictionaryPublisherTestBridge.publishFromCentroids(root, centroids, JSON);
            server.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) {
                    try {
                        String path = request.getRequestUrl().encodedPath();
                        if (path.equals("/regdkp/ows")) {
                            String operation = request.getRequestUrl().queryParameter("request");
                            if (!"GetFeature".equals(operation)) {
                                metadataRequests.add(operation);
                                if (metadataFailure != null) return json(metadataFailure);
                                return new MockResponse().setHeader("Content-Type", "application/xml")
                                        .setBody("GetCapabilities".equals(operation)
                                                ? RgzMetadataFixture.CAPABILITIES : RgzMetadataFixture.SCHEMA);
                            }
                            String filter = request.getRequestUrl().queryParameter("cql_filter");
                            parcelRequests.add(filter);
                            Example example = population.stream().filter(e -> e.filter().equals(filter))
                                    .findFirst().orElseThrow();
                            return wfs(example, overrideScenario == null ? example.scenario() : overrideScenario);
                        }
                        if (path.endsWith("/GetCategories")) {
                            return json(Fixtures.read("eaukcija/categories.json"));
                        }
                        JsonNode body = JSON.readTree(request.getBody().readUtf8());
                        if (path.endsWith("/GetAuctionsByCategoryId")) {
                            boolean populated = body.path("CategoryId").asInt() == 47
                                    || body.path("CategoryId").asInt() == 7;
                            ObjectNode data = JSON.createObjectNode().put("TotalCount", populated ? population.size() : 0);
                            var auctions = data.putArray("Auctions");
                            if (populated) {
                                for (Example example : population) auctions.add(detailData(example));
                            }
                            return json(envelope(data).toString());
                        }
                        if (path.endsWith("/GetImmovablePropertyDetails")) {
                            long id = body.path("AuctionId").asLong();
                            Example example = population.stream().filter(e -> e.id() == id).findFirst().orElseThrow();
                            return json(envelope(detailData(example)).toString());
                        }
                        return new MockResponse().setResponseCode(404);
                    } catch (Exception invalidFixtureRequest) {
                        return new MockResponse().setResponseCode(400);
                    }
                }
            });
            server.start();
        } catch (Exception failure) {
            throw new IllegalStateException("could not create local RGZ workflow fixture", failure);
        }
    }

    public void configure(DynamicPropertyRegistry registry) {
        registry.add("eaukcija.api.base-url", () -> server.url("/WebApi.Proxy/api/EAukcija").toString());
        registry.add("eaukcija.api.allow-http-loopback-test", () -> "true");
        registry.add("eaukcija.api.requests-per-second", () -> "10");
        registry.add("eaukcija.refresh.schedule-cron", () -> "-");
        registry.add("eaukcija.refresh.poll-interval", () -> "PT0.05S");
        registry.add("eaukcija.enrichment.schedule-cron", () -> "-");
        registry.add("coarse.location.centroid-directory", centroids::toString);
        registry.add("ko.structured-match.dictionary-directory", dictionary::toString);
        registry.add("rgz.enabled", () -> "true");
        registry.add("rgz.allow-http-loopback-test", () -> "true");
        registry.add("rgz.base-url", () -> server.url("/regdkp/ows").toString());
        registry.add("rgz.dataset-version", () -> "synthetic-parcels-v1");
        registry.add("rgz.capabilities-sha256", () -> "a".repeat(64));
        registry.add("rgz.schema-sha256", () -> "b".repeat(64));
        registry.add("rgz.requests-per-second", () -> "5");
        registry.add("rgz.max-attempts", () -> "1");
        registry.add("rgz.retry-delays", () -> "");
        registry.add("rgz.max-response-bytes", () -> "4096");
        registry.add("rgz.kill-switch-path", killSwitch::toString);
    }

    public record Example(long id, String name, String koCode, String parcel, String scenario) {
        public String filter() { return "cadmun_code=" + koCode + " AND parcel_num='" + parcel + "'"; }
        public Example scenario(String value) { return new Example(id, name, koCode, parcel, value); }
    }

    private static ObjectNode envelope(ObjectNode data) {
        return JSON.createObjectNode().put("ResultCode", "0").put("ResultMessage", "OK").set("Data", data);
    }

    private static ObjectNode detailData(Example example) throws Exception {
        ObjectNode data = (ObjectNode) JSON.readTree(Fixtures.read("eaukcija/immovable-property-detail.json")).get("Data");
        data.put("Id", example.id()).put("AuctionNumber", "Н21-" + example.id());
        data.put("StartDate", "2026-01-01T08:00:00Z").put("EndDate", "2099-09-10T12:00:00Z");
        String ko = "structured-only".equals(example.scenario()) ? "Непостојећа" : example.name();
        String description = "invalid-input".equals(example.scenario()) ? "Без података о парцели."
                : "КО " + ko + "; катастарска парцела број " + example.parcel() + ".";
        data.put("Description", description).put("ShortDescription", description);
        data.remove(List.of("ExecutorName", "Images"));
        ObjectNode place = (ObjectNode) data.get("Place");
        place.put("Name", example.name()).put("Municipality", example.name()).put("Cadastral", example.name());
        return data;
    }

    public static MockResponse wfs(Example example, String scenario) throws Exception {
        if ("http-error".equals(scenario)) return new MockResponse().setResponseCode(503);
        if ("schema-error".equals(scenario)) return json("""
                {"type":"FeatureCollection","crs":{"type":"name","properties":{"name":"EPSG:4326"}},
                 "features":{}}
                """);
        if ("response-error".equals(scenario)) return json("not-json");
        if ("oversize".equals(scenario)) return json(" ".repeat(5000)).setChunkedBody(" ".repeat(5000), 100);
        ObjectNode payload = JSON.createObjectNode().put("type", "FeatureCollection");
        payload.putObject("crs").put("type", "name").putObject("properties")
                .put("name", "wrong-crs".equals(scenario) ? "EPSG:3857" : "EPSG:4326");
        var features = payload.putArray("features");
        if (!"not-found".equals(scenario)) {
            ObjectNode feature = features.addObject().put("type", "Feature").put("id", "parcel." + example.id());
            ObjectNode values = feature.putObject("properties");
            values.put("cadmun_code", "identity-mismatch".equals(scenario) ? "999999" : example.koCode());
            values.put("parcel_num", example.parcel()).put("area", 406)
                    .put("source_projection", "EPSG:25834").put("scale", 1000);
            for (String name : List.of("owner_name", "cookie", "credential", "session_token", "FutureField")) {
                values.put(name, PRIVATE_SENTINEL);
                feature.put(name, PRIVATE_SENTINEL);
                payload.put(name, PRIVATE_SENTINEL);
            }
            String ring = "[[20.490,44.770],[20.500,44.770],[20.500,44.780],[20.490,44.780],[20.490,44.770]]";
            boolean multi = example.koCode().equals(CAJETINA.koCode());
            ObjectNode geometry = feature.putObject("geometry").put("type", multi ? "MultiPolygon" : "Polygon");
            String secondRing = "[[20.503,44.781],[20.507,44.781],[20.507,44.784],[20.503,44.781]]";
            geometry.set("coordinates", JSON.readTree(multi
                    ? "[[" + ring + "],[" + secondRing + "]]" : "[" + ring + "]"));
            geometry.put("FutureGeometryProperty", PRIVATE_SENTINEL);
            if ("invalid-geometry".equals(scenario)) geometry.putArray("coordinates");
            if ("ambiguous".equals(scenario)) features.add(feature.deepCopy());
        }
        payload.put("numberMatched", features.size()).put("numberReturned", features.size());
        return json(payload.toString()).setHeader("Set-Cookie", "session=" + PRIVATE_SENTINEL)
                .setHeader("Authorization", "Bearer " + PRIVATE_SENTINEL);
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    private static Path createCentroids(Path root) throws Exception {
        String sha = "c".repeat(64);
        String version = "2026-09-08-" + sha;
        Path directory = root.resolve("versions").resolve(version);
        Files.createDirectories(directory);
        Files.writeString(root.resolve("ACTIVE"), version + "\n");
        Files.writeString(root.resolve(".publish.lock"), "");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Example example : SUCCESSES) {
            for (String level : List.of("KO", "SETTLEMENT", "MUNICIPALITY")) {
                rows.add(Map.ofEntries(
                        Map.entry("extractVersion", version), Map.entry("sourceDate", "2026-09-08"),
                        Map.entry("sourceGpkgSha256", sha), Map.entry("level", level),
                        Map.entry("officialCode", (level.equals("KO") ? "" : level.substring(0, 1)) + example.koCode()),
                        Map.entry("nameCyrillic", example.name()), Map.entry("nameLatin", example.name()),
                        Map.entry("settlementCodes", level.equals("KO") ? List.of("S" + example.koCode()) : List.of()),
                        Map.entry("municipalityCodes", level.equals("MUNICIPALITY") ? List.of() : List.of("M" + example.koCode())),
                        Map.entry("memberPointCount", 1), Map.entry("longitude", 20.495), Map.entry("latitude", 44.775)));
            }
        }
        StringBuilder ndjson = new StringBuilder();
        for (var row : rows) ndjson.append(JSON.writeValueAsString(row)).append('\n');
        Files.writeString(directory.resolve("centroids.ndjson"), ndjson);
        Files.writeString(directory.resolve("report.json"), JSON.writeValueAsString(Map.of(
                "extractVersion", version, "sourceGpkgSha256", sha,
                "sourceRows", Map.of("total", 3, "active", 3, "rejected", 0, "rejectedByReason", Map.of()))));
        Files.writeString(directory.resolve("ATTRIBUTION.md"), "Synthetic issue-21 fixture; not official boundaries.\n");
        List<Map<String, Object>> evidence = new ArrayList<>();
        for (String name : List.of("centroids.ndjson", "report.json", "ATTRIBUTION.md")) {
            Path file = directory.resolve(name);
            evidence.add(Map.of("name", name, "bytes", Files.size(file), "sha256", java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)))));
        }
        Files.writeString(directory.resolve("manifest.json"), JSON.writeValueAsString(Map.of(
                "formatVersion", 1, "extractVersion", version,
                "source", Map.of("datasetDate", "2026-09-08", "gpkgSha256", sha, "targetCrs", 4326,
                        "rowCount", 3, "canonicalUrl", "https://fixture.invalid/synthetic", "sourceSha256", sha, "schemaSha256", sha),
                "content", Map.of("activeSourceRows", 3, "rejectedSourceRows", 0,
                        "centroidCounts", Map.of("KO", 3, "SETTLEMENT", 3, "MUNICIPALITY", 3)),
                "files", evidence)));
        return root;
    }

    @Override public void close() throws Exception { server.shutdown(); }
}
