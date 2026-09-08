package rs.sud.eaukcija.browser;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Route.FulfillOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import rs.sud.eaukcija.basemap.BasemapTestBundle;
import rs.sud.eaukcija.rgz.RgzWorkflowFixture;
import rs.sud.eaukcija.service.SyncService;
import rs.sud.eaukcija.sync.persistence.SyncRunStatus;
import rs.sud.eaukcija.testsupport.PostgisTestContainer;

/** Real automatic scheduler + metadata bootstrap + local WFS; no user refresh/parcel action. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AutoEnabledRgzBrowserTest {
    private static final RgzWorkflowFixture FIXTURE = new RgzWorkflowFixture();
    private static final String DATABASE = PostgisTestContainer.createEmptyDatabase();
    private static final Path BASEMAP = basemap();

    @DynamicPropertySource static void configure(DynamicPropertyRegistry registry) {
        FIXTURE.configure(registry);
        registry.add("spring.datasource.url", () -> DATABASE);
        registry.add("spring.datasource.username", PostgisTestContainer.shared()::getUsername);
        registry.add("spring.datasource.password", PostgisTestContainer.shared()::getPassword);
        registry.add("rgz.auto-configure", () -> "true");
        registry.add("rgz.warmup-enabled", () -> "true");
        registry.add("rgz.dataset-version", () -> "");
        registry.add("rgz.capabilities-sha256", () -> "");
        registry.add("rgz.schema-sha256", () -> "");
        registry.add("rgz.auto-poll-interval", () -> "PT0.1S");
        registry.add("map.auto-refresh-interval-ms", () -> "1000");
        registry.add("map.initial-longitude", () -> "20.8");
        registry.add("map.initial-latitude", () -> "44.0");
        registry.add("map.initial-zoom", () -> "6");
        registry.add("map.fit-parcels-on-select", () -> "true");
        registry.add("map.browser-test-hooks", () -> "true");
        registry.add("basemap.assets.directory", BASEMAP::toString);
    }
    @RegisterExtension final BrowserHarnessExtension browser = new BrowserHarnessExtension();
    @LocalServerPort int port;
    @Autowired SyncService source;

    @AfterAll static void close() throws Exception { FIXTURE.close(); }

    @Test void appDiscoversPinsAndDrawsNewShapesAutomaticallyThenZoomsToTheSelectedBoundary() throws Exception {
        // Populate source snapshots only. The production background scheduler
        // performs all extraction/matching/parcel resolution itself.
        UUID run = source.startManual(UUID.randomUUID()).runId();
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (source.findRun(run).orElseThrow().status() == SyncRunStatus.RUNNING) {
            if (System.nanoTime() > deadline) throw new AssertionError("source fixture timeout");
            Thread.sleep(25);
        }
        assertThat(source.findRun(run).orElseThrow().status()).isEqualTo(SyncRunStatus.SUCCEEDED);
        Page page = browser.page();
        AtomicBoolean initialEmptyView = new AtomicBoolean(true);
        page.route("**/api/map/auctions?**", route -> {
            if (initialEmptyView.get()) {
                route.fulfill(new FulfillOptions().setContentType("application/geo+json").setBody("""
                        {"type":"FeatureCollection","features":[],"numberReturned":0,"limit":1000,"truncated":false}
                        """));
            } else route.resume();
        });
        page.navigate("http://localhost:" + port + "/");
        page.waitForFunction("window.__auctionMap?.ready && window.__auctionMap.getDiagnostics().requestsCompleted > 0");
        assertThat(page.locator("#map-result-count").textContent()).isEqualTo("0");
        assertThat(((Number) page.evaluate("window.__auctionMap.map.getZoom()")).doubleValue()).isLessThan(10);
        initialEmptyView.set(false);
        // No click, reload, pan or Java-triggered enrichment: periodic local
        // map refresh observes the background worker's newly committed shapes.
        page.waitForFunction("window.__auctionMap.getDiagnostics().lastFeatureCount === 3");
        assertThat(page.locator("#map-result-list li[data-precision='PARCEL']").count()).isEqualTo(3);
        assertThat(FIXTURE.metadataRequests).containsExactly("GetCapabilities", "DescribeFeatureType");
        assertThat(FIXTURE.parcelRequests).hasSize(3);
        page.locator(".map-result-button").first().click();
        page.waitForFunction("window.__auctionMap.map.getZoom() > 10 && !window.__auctionMap.map.isMoving()");
        page.waitForFunction("window.__auctionMap.map.queryRenderedFeatures({layers: ['auction-area-parcel']}).length > 0");
        assertThat(page.locator(".map-popup").textContent()).contains("Парцела", "Проверена граница");
        browser.network().assertOnlyLocalhostRequests();
    }

    private static Path basemap() {
        try {
            Path root = Files.createTempDirectory("rgz-auto-browser-basemap-");
            Path fixture = Path.of(AutoEnabledRgzBrowserTest.class.getResource("/fixtures/basemap-bundle").toURI());
            BasemapTestBundle.fromDirectory(root, "rgz-auto-browser-v1", fixture);
            BasemapTestBundle.activate(root, "rgz-auto-browser-v1");
            return root;
        } catch (Exception failure) { throw new ExceptionInInitializerError(failure); }
    }
}
