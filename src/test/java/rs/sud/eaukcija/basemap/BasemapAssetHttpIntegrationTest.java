package rs.sud.eaukcija.basemap;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import rs.sud.eaukcija.testsupport.PostgisTestContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class BasemapAssetHttpIntegrationTest {

    private static final Path ASSET_ROOT = temporaryDirectory();
    private static final BasemapTestBundle.Bundle BUNDLE =
            BasemapTestBundle.synthetic(ASSET_ROOT, "http-v1", 1024 * 1024 + 37, (byte) 0x5a);

    static {
        BasemapTestBundle.activate(ASSET_ROOT, BUNDLE.version());
    }

    @ServiceConnection(name = "postgresql")
    static final PostgreSQLContainer<?> POSTGIS = PostgisTestContainer.shared();

    @DynamicPropertySource
    static void basemapProperties(DynamicPropertyRegistry registry) {
        registry.add("basemap.assets.directory", () -> ASSET_ROOT.toString());
        registry.add("basemap.assets.poll-interval", () -> "PT0.05S");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Test
    void fullArchiveHasStrongEtagRangeCapabilityAndCachePolicy() {
        ResponseEntity<byte[]> response = request(HttpMethod.GET, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsExactly(BUNDLE.archive());
        assertThat(response.getHeaders().getFirst(HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes");
        assertThat(response.getHeaders().getFirst(HttpHeaders.ETAG))
                .matches("\"sha256-[0-9a-f]{64}\"");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL))
                .isEqualTo("public, max-age=0, must-revalidate");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE))
                .isEqualTo("application/vnd.pmtiles");
        assertThat(response.getHeaders().getFirst("X-Basemap-Version")).isEqualTo("http-v1");
        assertThat(response.getHeaders().getContentLength()).isEqualTo(BUNDLE.archive().length);
    }

    @Test
    void prefixOpenEndedAndSuffixRangesReturnExactBytes() {
        assertRange("bytes=0-31", 0, 31);
        assertRange("bytes=1048570-", 1_048_570, BUNDLE.archive().length - 1);
        assertRange("bytes=-29", BUNDLE.archive().length - 29, BUNDLE.archive().length - 1);
        assertRange("bytes=1048560-9999999", 1_048_560, BUNDLE.archive().length - 1);
    }

    @Test
    void invalidAndUnsatisfiableRangesReturnStandardsShaped416() {
        for (String range : List.of(
                "bytes=9999999-",
                "bytes=9999999-,9999998-",
                "bytes=50-40",
                "bytes=-0",
                "bytes=abc-def")) {
            ResponseEntity<byte[]> response = request(HttpMethod.GET, headers(HttpHeaders.RANGE, range));
            assertThat(response.getStatusCode()).as(range)
                    .isEqualTo(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
            assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE))
                    .isEqualTo("bytes */" + BUNDLE.archive().length);
            assertThat(response.getBody()).as(range).isNullOrEmpty();
        }
    }

    @Test
    void unsupportedRangeFormsAreIgnoredWithAComplete200Response() {
        for (String range : List.of(
                "bytes=0-1,4-5",
                "bytes=0-1,9999999-",
                "items=0-1")) {
            ResponseEntity<byte[]> response = request(HttpMethod.GET, headers(HttpHeaders.RANGE, range));
            assertThat(response.getStatusCode()).as(range).isEqualTo(HttpStatus.OK);
            assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE)).isNull();
            assertThat(response.getHeaders().getContentLength()).isEqualTo(BUNDLE.archive().length);
            assertThat(response.getBody()).as(range).containsExactly(BUNDLE.archive());
        }
    }

    @Test
    void conditionalRequestsUseTheStableStrongEtag() {
        String etag = request(HttpMethod.HEAD, null).getHeaders().getETag();
        assertThat(etag).isNotBlank().doesNotStartWith("W/");

        ResponseEntity<byte[]> notModified = request(
                HttpMethod.GET, headers(HttpHeaders.IF_NONE_MATCH, "W/" + etag));
        assertThat(notModified.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(notModified.getBody()).isNullOrEmpty();

        HttpHeaders matchingIfRange = headers(HttpHeaders.RANGE, "bytes=8-15");
        matchingIfRange.set(HttpHeaders.IF_RANGE, etag);
        ResponseEntity<byte[]> partial = request(HttpMethod.GET, matchingIfRange);
        assertThat(partial.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
        assertThat(partial.getBody()).containsExactly(
                Arrays.copyOfRange(BUNDLE.archive(), 8, 16));

        HttpHeaders staleIfRange = headers(HttpHeaders.RANGE, "bytes=8-15");
        staleIfRange.set(HttpHeaders.IF_RANGE, "\"sha256-stale\"");
        ResponseEntity<byte[]> full = request(HttpMethod.GET, staleIfRange);
        assertThat(full.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(full.getBody()).containsExactly(BUNDLE.archive());
    }

    @Test
    void concurrentRangeReadsNeverCrossContaminateResponseWindows() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<ResponseEntity<byte[]>>> calls = new ArrayList<>();
            for (int index = 0; index < 32; index++) {
                int start = 8 + index * 4096;
                int end = start + 2047;
                calls.add(() -> request(
                        HttpMethod.GET,
                        headers(HttpHeaders.RANGE, "bytes=" + start + "-" + end)));
            }
            List<Future<ResponseEntity<byte[]>>> futures = executor.invokeAll(calls);
            for (int index = 0; index < futures.size(); index++) {
                int start = 8 + index * 4096;
                int end = start + 2047;
                ResponseEntity<byte[]> response = futures.get(index).get();
                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
                assertThat(response.getBody()).containsExactly(
                        Arrays.copyOfRange(BUNDLE.archive(), start, end + 1));
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void styleSpritesGlyphsAndHealthUseCorrectTypesAndVersion() {
        ResponseEntity<String> status = rest.getForEntity(uri("/api/basemap/status"), String.class);
        assertThat(status.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(status.getBody()).contains(
                "\"healthy\":true", "\"activeVersion\":\"http-v1\"");

        assertContentType("/basemap/style.json", "application/json");
        assertContentType("/basemap/sprites/light.json", "application/json");
        assertContentType("/basemap/sprites/light@2x.png", "image/png");
        assertContentType(
                "/basemap/glyphs/Noto%20Sans%20Regular/0-255.pbf",
                "application/x-protobuf");
        assertContentType("/basemap/THIRD_PARTY_NOTICES.md", "text/markdown;charset=UTF-8");
        assertContentType("/basemap/licenses/Noto-OFL-1.1.txt", "text/plain;charset=UTF-8");
        assertContentType("/basemap/licenses/Tangram-Icons-MIT.md", "text/markdown;charset=UTF-8");
        assertThat(rest.getForEntity(
                uri("/basemap/licenses/not-in-the-validated-bundle.txt"), byte[].class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private void assertRange(String header, int expectedStart, int expectedEnd) {
        ResponseEntity<byte[]> response = request(
                HttpMethod.GET, headers(HttpHeaders.RANGE, header));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE))
                .isEqualTo("bytes " + expectedStart + "-" + expectedEnd
                        + "/" + BUNDLE.archive().length);
        assertThat(response.getHeaders().getContentLength())
                .isEqualTo(expectedEnd - expectedStart + 1);
        assertThat(response.getBody()).containsExactly(
                Arrays.copyOfRange(BUNDLE.archive(), expectedStart, expectedEnd + 1));
    }

    private void assertContentType(String path, String expected) {
        ResponseEntity<byte[]> response = rest.getForEntity(uri(path), byte[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE)).isEqualTo(expected);
        assertThat(response.getHeaders().getFirst("X-Basemap-Version")).isEqualTo("http-v1");
    }

    private ResponseEntity<byte[]> request(HttpMethod method, HttpHeaders headers) {
        return rest.exchange(
                uri("/basemap/serbia.pmtiles"),
                method,
                new HttpEntity<>(headers == null ? new HttpHeaders() : headers),
                byte[].class);
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static HttpHeaders headers(String name, String value) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(name, value);
        return headers;
    }

    private static Path temporaryDirectory() {
        try {
            return Files.createTempDirectory("aukcije-basemap-http-");
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
