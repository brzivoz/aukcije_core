package rs.sud.eaukcija.rgz;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RgzParcelClientTest {

    private MockWebServer server;
    private RgzParcelProperties properties;
    private FakeTiming timing;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        properties = new RgzParcelProperties();
        properties.setBaseUrl(server.url("/regdkp/ows").uri());
        properties.setRequestsPerSecond(5.0);
        timing = new FakeTiming();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void fetchesOneExactPolygonWithoutCredentialsCookiesOrUserAction() throws Exception {
        server.enqueue(json(polygon("713848", "1572")));

        RgzParcelResult result = client().fetch("713848", "1572", () -> true);

        assertThat(result.status()).isEqualTo(RgzParcelResult.Status.RESOLVED);
        assertThat(result.geometryType()).isEqualTo("Polygon");
        assertThat(result.areaSquareMetres()).isEqualByComparingTo("406");
        assertThat(result.rawResponseSha256()).hasSize(64);
        assertThat(result.evidence()).containsKeys(
                "requestedKoCode", "requestedParcelNumber", "rawResponseSha256");
        assertThat(result.evidence())
                .containsEntry("returnedKoCode", "713848")
                .containsEntry("returnedParcelNumber", "1572");
        assertThat(result.evidence().toString())
                .doesNotContain("owner_name", "password", "cookie", "session", "FutureField");

        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.getMethod()).isEqualTo("GET");
        assertThat(request.getRequestUrl().queryParameter("service")).isEqualTo("WFS");
        assertThat(request.getRequestUrl().queryParameter("typeNames"))
                .isEqualTo("dkp:dkp_parcels_weekly_only_utm");
        assertThat(request.getRequestUrl().queryParameter("count")).isEqualTo("2");
        assertThat(request.getRequestUrl().queryParameter("cql_filter"))
                .isEqualTo("cadmun_code=713848 AND parcel_num='1572'");
        assertThat(request.getHeader("Authorization")).isNull();
        assertThat(request.getHeader("Cookie")).isNull();
        assertThat(request.getHeader("User-Agent"))
                .isEqualTo("aukcije-core/0.0.1 (+https://github.com/brzivoz/aukcije_core/issues/41)");
    }

    @Test
    void acceptsMultiPolygonAndIgnoresUnrecognizedOrPersonalProperties() {
        server.enqueue(json(multiPolygon("743968", "4577/337")));

        RgzParcelResult result = client().fetch("743968", "4577/337", () -> true);

        assertThat(result.status()).isEqualTo(RgzParcelResult.Status.RESOLVED);
        assertThat(result.geometryType()).isEqualTo("MultiPolygon");
        assertThat(result.geometryJson()).startsWith("{\"type\":\"MultiPolygon\"");
        assertThat(result.evidence().toString())
                .doesNotContain("owner_name", "Петар", "FutureField");
    }

    @Test
    void acceptsAValidParcelWhenOptionalProjectionAndScaleMetadataAreAbsent() {
        String withoutOptionalMetadata = polygon("713848", "1572")
                .replace(",\"source_projection\":\"EPSG:25834\",\"scale\":1000", "");
        server.enqueue(json(withoutOptionalMetadata));

        RgzParcelResult result = client().fetch("713848", "1572", () -> true);

        assertThat(result.status()).isEqualTo(RgzParcelResult.Status.RESOLVED);
        assertThat(result.sourceProjection()).isNull();
        assertThat(result.scale()).isNull();
        assertThat(result.evidence()).doesNotContainKeys("sourceProjection", "scale");
    }

    @Test
    void classifiesNotFoundAmbiguityIdentityCrsAndGeometryFailures() {
        server.enqueue(json(featureCollection("[]")));
        assertThat(client().fetch("713848", "1572", () -> true).status())
                .isEqualTo(RgzParcelResult.Status.NOT_FOUND);

        String feature = feature("713848", "1572", polygonGeometry());
        server.enqueue(json(featureCollection("[" + feature + "," + feature + "]")));
        assertThat(client().fetch("713848", "1572", () -> true).status())
                .isEqualTo(RgzParcelResult.Status.AMBIGUOUS);

        server.enqueue(json(polygon("999999", "1572")));
        RgzParcelResult identityMismatch = client().fetch("713848", "1572", () -> true);
        assertThat(identityMismatch.reason()).isEqualTo("IDENTITY_MISMATCH");
        assertThat(identityMismatch.status()).isEqualTo(RgzParcelResult.Status.INVALID);
        assertThat(identityMismatch.cacheable()).isTrue();

        server.enqueue(json(polygon("713848", "1572").replace("EPSG::4326", "EPSG::3857")));
        RgzParcelResult invalidCrs = client().fetch("713848", "1572", () -> true);
        assertThat(invalidCrs.reason()).isEqualTo("INVALID_CRS");
        assertThat(invalidCrs.status()).isEqualTo(RgzParcelResult.Status.ERROR);
        assertThat(invalidCrs.cacheable()).isFalse();

        server.enqueue(json(polygon("713848", "1572")
                .replace("[20.0,44.0]", "[30.0,44.0]")));
        RgzParcelResult outsideBounds = client().fetch("713848", "1572", () -> true);
        assertThat(outsideBounds.reason()).isEqualTo("OUTSIDE_SERBIA_BOUNDS");
        assertThat(outsideBounds.status()).isEqualTo(RgzParcelResult.Status.INVALID);
        assertThat(outsideBounds.cacheable()).isTrue();
    }

    @Test
    void treatsAnAbsentCrsAsANonCacheableProtocolError() {
        String withoutCrs = polygon("713848", "1572").replace(
                "\"crs\":{\"type\":\"name\",\"properties\":{"
                        + "\"name\":\"urn:ogc:def:crs:EPSG::4326\"}},",
                "");
        server.enqueue(json(withoutCrs));

        RgzParcelResult result = client().fetch("713848", "1572", () -> true);

        assertThat(result.reason()).isEqualTo("INVALID_CRS");
        assertThat(result.status()).isEqualTo(RgzParcelResult.Status.ERROR);
        assertThat(result.cacheable()).isFalse();
    }

    @Test
    void emptyCollectionsDoNotRequireCoordinateMetadataButMustHaveConsistentCounts() {
        for (String crs : List.of("", "\"crs\":null,", "\"crs\":{\"type\":\"name\",\"properties\":{\"name\":\"EPSG:3857\"}},")) {
            server.enqueue(json("{\"type\":\"FeatureCollection\"," + crs
                    + "\"numberMatched\":0,\"numberReturned\":0,\"features\":[]}"));
            var result = client().fetch("713848", "1572", () -> true);
            assertThat(result.reason()).isEqualTo("AUTHORITATIVE_NOT_FOUND");
            assertThat(result.cacheable()).isTrue();
        }
        server.enqueue(json("{\"type\":\"FeatureCollection\",\"numberMatched\":1,\"features\":[]}"));
        assertThat(client().fetch("713848", "1572", () -> true).reason()).isEqualTo("INCONSISTENT_FEATURE_COUNT");
        server.enqueue(json("{\"type\":\"FeatureCollection\",\"numberMatched\":2,\"features\":[]}"));
        assertThat(client().fetch("713848", "1572", () -> true).status()).isEqualTo(RgzParcelResult.Status.AMBIGUOUS);
    }

    @Test
    void enforcesContentTypeAndBodySizeBeforeParsing() {
        server.enqueue(new MockResponse().setHeader("Content-Type", "text/html").setBody("<html/>"));
        RgzParcelResult invalidContentType = client().fetch("713848", "1572", () -> true);
        assertThat(invalidContentType.reason()).isEqualTo("INVALID_CONTENT_TYPE");
        assertThat(invalidContentType.status()).isEqualTo(RgzParcelResult.Status.ERROR);
        assertThat(invalidContentType.cacheable()).isFalse();

        properties.setMaxResponseBytes(1024);
        server.enqueue(json(" ".repeat(1100)));
        RgzParcelResult tooLarge = client().fetch("713848", "1572", () -> true);
        assertThat(tooLarge.reason()).isEqualTo("RESPONSE_TOO_LARGE");
        assertThat(tooLarge.status()).isEqualTo(RgzParcelResult.Status.ERROR);
        assertThat(tooLarge.cacheable()).isFalse();

        properties.setMaxResponseBytes(5_000_000);
        server.enqueue(json("not-json"));
        RgzParcelResult invalidJson = client().fetch("713848", "1572", () -> true);
        assertThat(invalidJson.reason()).isEqualTo("INVALID_JSON");
        assertThat(invalidJson.status()).isEqualTo(RgzParcelResult.Status.ERROR);
        assertThat(invalidJson.cacheable()).isFalse();
    }

    @Test
    void retriesOnlyBoundedFailuresAndRechecksTheKillSwitchBeforeRetry() {
        server.enqueue(new MockResponse().setResponseCode(503).setHeader("Retry-After", "8"));
        server.enqueue(json(polygon("713848", "1572")));

        RgzParcelResult recovered = client().fetch("713848", "1572", () -> true);

        assertThat(recovered.status()).isEqualTo(RgzParcelResult.Status.RESOLVED);
        assertThat(recovered.physicalAttempts()).isEqualTo(2);
        assertThat(timing.sleeps()).contains(Duration.ofSeconds(8));

        server.enqueue(new MockResponse().setResponseCode(503)
                .setHeader("Retry-After", "Wed, 02 Sep 2026 20:00:20 GMT"));
        server.enqueue(json(polygon("713848", "1574")));
        assertThat(client().fetch("713848", "1574", () -> true).status())
                .isEqualTo(RgzParcelResult.Status.RESOLVED);
        assertThat(timing.sleeps()).contains(Duration.ofSeconds(12));

        server.enqueue(new MockResponse().setResponseCode(503));
        AtomicInteger checks = new AtomicInteger();
        RgzParcelResult killed = client().fetch(
                "713848", "1573", () -> checks.incrementAndGet() <= 2);

        assertThat(killed.status()).isEqualTo(RgzParcelResult.Status.ERROR);
        assertThat(killed.reason()).isEqualTo("KILL_SWITCH_ENGAGED");
        assertThat(killed.physicalAttempts()).isOne();
        assertThat(server.getRequestCount()).isEqualTo(5);
    }

    @Test
    void rejectsInvalidInputsWithoutMakingARequest() {
        assertThat(client().fetch("KO-1", "1572", () -> true).reason())
                .isEqualTo("INVALID_KO_CODE");
        assertThat(client().fetch("713848", "1 OR 1=1", () -> true).reason())
                .isEqualTo("INVALID_PARCEL_NUMBER");
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void refusesRedirectsRatherThanBypassingTheRateAndKillGates() {
        server.enqueue(new MockResponse().setResponseCode(302)
                .setHeader("Location", server.url("/login"))
                .setHeader("Set-Cookie", "session=must-not-reuse"));
        RgzParcelResult result = client().fetch("713848", "1572", () -> true);
        assertThat(result.reason()).isEqualTo("HTTP_302");
        assertThat(result.physicalAttempts()).isOne();
        assertThat(server.getRequestCount()).isOne();
        assertThat(result.evidence().toString()).doesNotContain("must-not-reuse", "login");
    }

    @Test
    void httpLibraryCannotRetry503OutsideThePhysicalRequestBudget() {
        properties.setMaxAttempts(1);
        properties.setRetryDelays(List.of());
        server.enqueue(new MockResponse().setResponseCode(503).setHeader("Retry-After", "0"));
        server.enqueue(json(polygon("713848", "1572")));
        RgzParcelResult result = client().fetch("713848", "1572", () -> true);
        assertThat(result.status()).isEqualTo(RgzParcelResult.Status.ERROR);
        assertThat(result.physicalAttempts()).isOne();
        assertThat(server.getRequestCount()).isOne();
    }

    @Test
    void cookiesFromAResponseAreNeverReplayedByTheSharedClient() throws Exception {
        RgzParcelClient shared = client();
        server.enqueue(json(polygon("713848", "1572")).setHeader("Set-Cookie", "session=secret"));
        server.enqueue(json(polygon("713848", "1573")));
        shared.fetch("713848", "1572", () -> true);
        shared.fetch("713848", "1573", () -> true);
        server.takeRequest(1, TimeUnit.SECONDS);
        RecordedRequest next = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(next.getHeader("Cookie")).isNull();
        assertThat(next.getHeader("Authorization")).isNull();
    }

    @Test
    void strictEnvelopeValidationNeverTurnsTruncationOrAmbiguityIntoSuccessOrNotFound() {
        String valid = polygon("713848", "1572");
        for (String body : List.of(valid + " {}", valid.replace("\"type\":\"FeatureCollection\"",
                "\"type\":\"FeatureCollection\",\"type\":\"FeatureCollection\""))) {
            server.enqueue(json(body));
            assertThat(client().fetch("713848", "1572", () -> true).reason()).isEqualTo("INVALID_JSON");
        }
        server.enqueue(json(valid.replace("\"features\":", "\"numberMatched\":2,\"features\":")));
        assertThat(client().fetch("713848", "1572", () -> true).status())
                .isEqualTo(RgzParcelResult.Status.AMBIGUOUS);
        server.enqueue(json(featureCollection("[]").replace("\"features\":", "\"numberMatched\":1,\"features\":")));
        assertThat(client().fetch("713848", "1572", () -> true).reason()).isEqualTo("INCONSISTENT_FEATURE_COUNT");
        server.enqueue(json(valid.replace("\"features\":", "\"numberMatched\":-1,\"features\":")));
        assertThat(client().fetch("713848", "1572", () -> true).reason()).isEqualTo("INVALID_FEATURE_COUNT");
    }

    @Test
    void nativeCrsRecoveryKeepsIdentityAndUsesTheSamePhysicalBudgetAndRateGate() throws Exception {
        server.enqueue(json(NativeCrsFixture.response(false, "701165", "1285")));
        server.enqueue(json(NativeCrsFixture.response(true, "701165", "1285")));
        var result = client().fetch("701165", "1285", () -> true);
        assertThat(result.status()).isEqualTo(RgzParcelResult.Status.RESOLVED);
        assertThat(result.geometrySrid()).isEqualTo(25834);
        assertThat(result.sourceProjection()).isEqualTo("GK7"); // not the payload CRS
        assertThat(result.physicalAttempts()).isEqualTo(2);
        assertThat(result.evidence().get("representationAttempts").toString()).contains("INVALID_GEOMETRY", "25834", "4326");
        var first = server.takeRequest(); var second = server.takeRequest();
        assertThat(first.getRequestUrl().queryParameter("srsName")).isEqualTo("EPSG:4326");
        assertThat(second.getRequestUrl().queryParameter("srsName")).isEqualTo("EPSG:25834");
        assertThat(first.getRequestUrl().queryParameter("cql_filter"))
                .isEqualTo(second.getRequestUrl().queryParameter("cql_filter"));
        assertThat(timing.sleeps()).contains(Duration.ofMillis(200));
    }

    @Test
    void nativeRecoveryNeverRelabelsCrsRepairsGeometryOrChangesIdentity() {
        for (String bad : List.of(
                NativeCrsFixture.response(true, "999999", "1285"),
                NativeCrsFixture.response(true, "701165", "1285").replace("EPSG:25834", "EPSG:4326"),
                NativeCrsFixture.response(true, "701165", "1285").replace("500040,4900003", "500040,4900001"))) {
            server.enqueue(json(NativeCrsFixture.response(false, "701165", "1285")));
            server.enqueue(json(bad));
            var result = client().fetch("701165", "1285", () -> true);
            assertThat(result.status()).isNotEqualTo(RgzParcelResult.Status.RESOLVED);
            assertThat(result.physicalAttempts()).isEqualTo(2);
            assertThat(result.geometryJson()).isNull();
        }
    }

    @Test
    void emptyNativeAlternativeIsNotAnAuthoritativeNegativeAndTransportRetriesStayBounded() throws Exception {
        server.enqueue(json(NativeCrsFixture.response(false, "701165", "1285")));
        server.enqueue(json("{\"type\":\"FeatureCollection\",\"features\":[],\"numberMatched\":0}"));
        var inconsistent = client().fetch("701165", "1285", () -> true);
        assertThat(inconsistent.reason()).isEqualTo("INCONSISTENT_REPRESENTATION");
        assertThat(inconsistent.cacheable()).isFalse();
        server.takeRequest(); server.takeRequest();
        server.enqueue(json(NativeCrsFixture.response(false, "701165", "1285")));
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(json(NativeCrsFixture.response(true, "701165", "1285")));
        var recovered = client().fetch("701165", "1285", () -> true);
        assertThat(recovered.physicalAttempts()).isEqualTo(3);
        assertThat(recovered.status()).isEqualTo(RgzParcelResult.Status.RESOLVED);
        assertThat(server.takeRequest().getRequestUrl().queryParameter("srsName")).isEqualTo("EPSG:4326");
        assertThat(server.takeRequest().getRequestUrl().queryParameter("srsName")).isEqualTo("EPSG:25834");
        assertThat(server.takeRequest().getRequestUrl().queryParameter("srsName")).isEqualTo("EPSG:25834");
    }

    @Test
    void nativeRecoveryHonorsKillSwitchAndOneAttemptCeiling() {
        server.enqueue(json(NativeCrsFixture.response(false, "701165", "1285")));
        AtomicInteger checks = new AtomicInteger();
        assertThat(client().fetch("701165", "1285", () -> checks.incrementAndGet() <= 2).reason())
                .isEqualTo("KILL_SWITCH_ENGAGED");
        properties.setMaxAttempts(1); properties.setRetryDelays(List.of());
        server.enqueue(json(NativeCrsFixture.response(false, "701165", "1285")));
        assertThat(client().fetch("701165", "1285", () -> true).reason()).isEqualTo("INVALID_GEOMETRY");
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    void geometryForeignMembersAreNotExportedAndInvalidRingsAreasAndCrsFailClosed() {
        properties.setMaxAttempts(1); properties.setRetryDelays(List.of());
        String valid = polygon("713848", "1572");
        server.enqueue(json(valid.replace("\"type\":\"Polygon\"",
                "\"type\":\"Polygon\",\"futurePersonalField\":\"secret\"")));
        assertThat(client().fetch("713848", "1572", () -> true).geometryJson())
                .doesNotContain("futurePersonalField", "secret");
        for (String invalid : List.of(
                valid.replace("\"area\":406", "\"area\":0"),
                valid.replace("[20.1,44.1]", "[20.2,44.0]"),
                valid.replace("[20.1,44.1],[20.0,44.0]", "[20.1,44.1],[20.0,44.1]"),
                valid.replace("\"type\":\"Polygon\"", "\"type\":\"Point\""))) {
            server.enqueue(json(invalid));
            assertThat(client().fetch("713848", "1572", () -> true).status())
                    .isEqualTo(RgzParcelResult.Status.INVALID);
        }
        server.enqueue(json(valid.replace("\"type\":\"name\"", "\"type\":\"link\"")));
        assertThat(client().fetch("713848", "1572", () -> true).reason()).isEqualTo("INVALID_CRS");
    }

    @Test
    void boundsPhysicalRetriesBackoffAndRetryAfterAndRetainsSafeNegativeProvenance() {
        for (int attempt = 0; attempt < 3; attempt++) server.enqueue(new MockResponse().setResponseCode(503));
        RgzParcelResult result = client().fetch("713848", "1572", () -> true);
        assertThat(result.physicalAttempts()).isEqualTo(3);
        assertThat(result.reason()).isEqualTo("HTTP_503");
        assertThat(timing.sleeps()).containsExactly(Duration.ofSeconds(5), Duration.ofSeconds(15));
        assertThat(result.evidence()).containsKeys("requestedKoCode", "requestedParcelNumber", "retrievedAt", "wfsVersion");
        for (int attempt = 0; attempt < 3; attempt++) {
            server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "999"));
        }
        assertThat(client().fetch("713848", "1573", () -> true).physicalAttempts()).isEqualTo(3);
        assertThat(timing.sleeps()).endsWith(Duration.ofSeconds(60), Duration.ofSeconds(60));
        assertThat(server.getRequestCount()).isEqualTo(6);
    }

    @Test
    void wholeCallTimeoutIsBoundedAndDoesNotCacheATransportFailure() {
        properties.setMaxAttempts(1);
        properties.setRetryDelays(List.of());
        properties.setConnectTimeout(Duration.ofMillis(100));
        properties.setReadTimeout(Duration.ofMillis(100));
        properties.setCallTimeout(Duration.ofMillis(150));
        server.enqueue(json(polygon("713848", "1572")).setBodyDelay(1, TimeUnit.SECONDS));
        long start = System.nanoTime();
        RgzParcelResult result = client().fetch("713848", "1572", () -> true);
        assertThat(result.status()).isEqualTo(RgzParcelResult.Status.ERROR);
        assertThat(result.cacheable()).isFalse();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
        assertThat(result.physicalAttempts()).isOne();
    }

    @Test
    void sharedClientEnforcesConcurrencyWhileResponsesAreStillBeingRead() throws Exception {
        RgzParcelClient shared = new RgzParcelClient(properties, new ObjectMapper(), RgzTiming.system(), true);
        server.enqueue(json(polygon("713848", "1572")).setBodyDelay(400, TimeUnit.MILLISECONDS));
        server.enqueue(json(polygon("713848", "1573")));
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> shared.fetch("713848", "1572", () -> true));
            assertThat(server.takeRequest(1, TimeUnit.SECONDS)).isNotNull();
            var second = executor.submit(() -> shared.fetch("713848", "1573", () -> true));
            assertThat(server.takeRequest(100, TimeUnit.MILLISECONDS)).isNull();
            assertThat(first.get(2, TimeUnit.SECONDS).status()).isEqualTo(RgzParcelResult.Status.RESOLVED);
            assertThat(second.get(2, TimeUnit.SECONDS).status()).isEqualTo(RgzParcelResult.Status.RESOLVED);
            assertThat(server.getRequestCount()).isEqualTo(2);
        } finally {
            executor.shutdownNow();
        }
    }

    private RgzParcelClient client() {
        return new RgzParcelClient(properties, new ObjectMapper(), timing, true);
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/geo+json").setBody(body);
    }

    private static String polygon(String koCode, String parcel) {
        return featureCollection("[" + feature(koCode, parcel, polygonGeometry()) + "]");
    }

    private static String multiPolygon(String koCode, String parcel) {
        return featureCollection("[" + feature(koCode, parcel,
                "{\"type\":\"MultiPolygon\",\"coordinates\":[[[[20.0,44.0],[20.1,44.0],"
                        + "[20.1,44.1],[20.0,44.0]]],[[[20.2,44.2],[20.3,44.2],"
                        + "[20.3,44.3],[20.2,44.2]]]]}") + "]");
    }

    private static String polygonGeometry() {
        return "{\"type\":\"Polygon\",\"coordinates\":[[[20.0,44.0],[20.1,44.0],"
                + "[20.1,44.1],[20.0,44.0]]]}";
    }

    private static String feature(String koCode, String parcel, String geometry) {
        return "{\"type\":\"Feature\",\"id\":\"parcel.1\",\"properties\":{"
                + "\"cadmun_code\":" + koCode + ",\"parcel_num\":\"" + parcel + "\","
                + "\"area\":406,\"source_projection\":\"EPSG:25834\",\"scale\":1000,"
                + "\"owner_name\":\"Петар\",\"FutureField\":\"ignored\"},"
                + "\"geometry\":" + geometry + "}";
    }

    private static String featureCollection(String features) {
        return "{\"type\":\"FeatureCollection\","
                + "\"crs\":{\"type\":\"name\",\"properties\":{"
                + "\"name\":\"urn:ogc:def:crs:EPSG::4326\"}},\"features\":" + features + "}";
    }

    private static final class FakeTiming implements RgzTiming {
        private long now;
        private final List<Duration> sleeps = new ArrayList<>();

        @Override
        public long nanoTime() {
            return now;
        }

        @Override
        public Instant instant() {
            return Instant.parse("2026-09-02T20:00:00Z").plusNanos(now);
        }

        @Override
        public void sleep(Duration duration) {
            sleeps.add(duration);
            now += duration.toNanos();
        }

        List<Duration> sleeps() {
            return List.copyOf(sleeps);
        }
    }
}
