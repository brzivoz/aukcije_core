package rs.sud.eaukcija.browser;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.WaitUntilState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import rs.sud.eaukcija.basemap.BasemapTestBundle;

class LocalBasemapBrowserTest extends PostgisBrowserFixture {

    private static final String VERSION = "browser-belgrade-v1";
    private static final Path ASSET_ROOT = createAssetRoot();

    static {
        URL fixture = LocalBasemapBrowserTest.class.getResource("/fixtures/basemap-bundle");
        if (fixture == null) {
            throw new IllegalStateException("compact PMTiles browser fixture is missing");
        }
        try {
            BasemapTestBundle.fromDirectory(ASSET_ROOT, VERSION, Path.of(fixture.toURI()));
            BasemapTestBundle.activate(ASSET_ROOT, VERSION);
        } catch (Exception exception) {
            throw new IllegalStateException("could not stage compact PMTiles browser fixture", exception);
        }
    }

    @DynamicPropertySource
    static void basemapProperties(DynamicPropertyRegistry registry) {
        registry.add("basemap.assets.directory", () -> ASSET_ROOT.toString());
        registry.add("basemap.assets.poll-interval", () -> "PT0.05S");
    }

    @RegisterExtension
    final BrowserHarnessExtension browser = new BrowserHarnessExtension();

    @Test
    void localMapLoadsZoomsAndPansWithoutAnyExternalAssetHost() throws Exception {
        Page page = browser.page();
        List<ResponseEvidence> pmtilesResponses = new ArrayList<>();
        page.onResponse(response -> {
            if ("/basemap/serbia.pmtiles".equals(URI.create(response.url()).getPath())) {
                synchronized (pmtilesResponses) {
                    pmtilesResponses.add(new ResponseEvidence(
                            response.status(),
                            response.headerValue("accept-ranges"),
                            response.headerValue("content-range"),
                            response.headerValue("etag")));
                }
            }
        });

        page.navigate(applicationUri().resolve("/basemap-smoke.html").toString(),
                new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        page.waitForFunction("window.__basemapSmoke?.ready === true", null,
                new Page.WaitForFunctionOptions().setTimeout(30_000));

        assertThat(page.locator("#status").textContent()).contains(VERSION);
        assertThat(page.locator("#map canvas").isVisible()).isTrue();
        List<Map<String, Object>> renderStates = new ArrayList<>();
        renderStates.add(assertRenderedAt(page, 5, 20.460, 44.790));
        renderStates.add(assertRenderedAt(page, 9, 20.460, 44.790));
        renderStates.add(assertRenderedAt(page, 14, 20.460, 44.790));
        // Stay inside the compact fixture while crossing the high-zoom tile
        // viewport so the smoke exercises a real post-load pan as well.
        renderStates.add(assertRenderedAt(page, 14, 20.464, 44.791));

        assertThat((List<?>) page.evaluate("window.__basemapSmoke.errors")).isEmpty();
        assertThat(page.locator(".maplibregl-ctrl-attrib").isVisible()).isTrue();
        assertThat(page.locator(
                ".maplibregl-ctrl-attrib a[href='https://www.openstreetmap.org/copyright']")
                .isVisible()).isTrue();
        assertThat(page.locator(".maplibregl-ctrl-attrib").textContent())
                .contains("OpenStreetMap contributors");

        synchronized (pmtilesResponses) {
            assertThat(pmtilesResponses).isNotEmpty();
            assertThat(pmtilesResponses).allSatisfy(evidence -> {
                assertThat(evidence.status()).isEqualTo(206);
                assertThat(evidence.acceptRanges()).isEqualTo("bytes");
                assertThat(evidence.contentRange()).startsWith("bytes ").contains("/");
                assertThat(evidence.etag()).matches("\"sha256-[0-9a-f]{64}\"");
            });
        }

        browser.network().assertOnlyLocalhostRequests();
        assertThat(browser.network().contactedHosts()).containsExactly("localhost");
        assertThat(browser.network().blockedHosts()).isEmpty();

        Path evidence = Path.of(System.getProperty(
                "browser.artifact.dir", "build/browser-test-results/artifacts"))
                .resolveSibling("evidence")
                .resolve("issue-25-local-basemap.png");
        Files.createDirectories(evidence.getParent());
        page.screenshot(new Page.ScreenshotOptions().setFullPage(true).setPath(evidence));
        assertThat(Files.size(evidence)).isGreaterThan(10_000);
        List<ResponseEvidence> retainedResponses;
        synchronized (pmtilesResponses) {
            retainedResponses = List.copyOf(pmtilesResponses);
        }
        Map<String, Object> retained = Map.of(
                "activeVersion", VERSION,
                "renderStates", renderStates,
                "pmtilesResponses", retainedResponses,
                "contactedHosts", browser.network().contactedHosts(),
                "blockedHosts", browser.network().blockedHosts(),
                "attributionUrl", "https://www.openstreetmap.org/copyright",
                "screenshot", Map.of(
                        "filename", evidence.getFileName().toString(),
                        "sizeBytes", Files.size(evidence),
                        "sha256", sha256(evidence)));
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(
                evidence.resolveSibling("issue-25-local-basemap.json").toFile(), retained);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> assertRenderedAt(
            Page page, int zoom, double longitude, double latitude) {
        Map<String, Object> result = (Map<String, Object>) page.evaluate("""
                async target => {
                  const map = window.__basemapSmoke.map;
                  await new Promise((resolve, reject) => {
                    const timeout = window.setTimeout(
                      () => reject(new Error(`idle timeout at zoom ${target.zoom}`)), 20000);
                    map.once('idle', () => {
                      window.clearTimeout(timeout);
                      resolve();
                    });
                    map.jumpTo({center: target.center, zoom: target.zoom});
                  });
                  return {
                    zoom: map.getZoom(),
                    center: [map.getCenter().lng, map.getCenter().lat],
                    tilesLoaded: map.areTilesLoaded(),
                    features: map.queryRenderedFeatures().length
                  };
                }
                """, Map.of("zoom", zoom, "center", List.of(longitude, latitude)));
        assertThat(((Number) result.get("zoom")).doubleValue()).isEqualTo(zoom);
        assertThat((Boolean) result.get("tilesLoaded")).isTrue();
        assertThat(((Number) result.get("features")).intValue()).isPositive();
        List<Number> center = (List<Number>) result.get("center");
        assertThat(center.get(0).doubleValue()).isCloseTo(longitude,
                org.assertj.core.data.Offset.offset(0.0001));
        assertThat(center.get(1).doubleValue()).isCloseTo(latitude,
                org.assertj.core.data.Offset.offset(0.0001));
        return result;
    }

    private static Path createAssetRoot() {
        try {
            return Files.createTempDirectory("aukcije-basemap-browser-");
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    private record ResponseEvidence(
            int status, String acceptRanges, String contentRange, String etag) {
    }
}
