package rs.sud.eaukcija.browser;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitUntilState;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import rs.sud.eaukcija.basemap.BasemapTestBundle;
import rs.sud.eaukcija.rgz.RgzWorkflowFixture;

/** Local source + WFS -> normal refresh -> real PostGIS -> MapLibre polygon rendering. */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AutomaticParcelBrowserTest extends PostgisBrowserFixture {
    private static final RgzWorkflowFixture FIXTURE = new RgzWorkflowFixture();
    private static final Path BASEMAP = basemap();

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        FIXTURE.configure(registry);
        registry.add("basemap.assets.directory", BASEMAP::toString);
        registry.add("basemap.assets.poll-interval", () -> "PT0.05S");
        registry.add("map.browser-test-hooks", () -> "true");
    }

    @RegisterExtension final BrowserHarnessExtension browser = new BrowserHarnessExtension();
    @Autowired JdbcTemplate jdbc;

    @BeforeEach @AfterEach void resetPopulation() throws Exception {
        jdbc.execute("""
                TRUNCATE refresh_runs, enrichment_runs, sync_runs, auctions, eaukcija_taxonomies,
                    location_resolution_cache_records, spatial_resolution_geometries, parcel_identities
                RESTART IDENTITY CASCADE
                """);
        jdbc.update("UPDATE enrichment_control SET paused = FALSE WHERE singleton");
        FIXTURE.parcelRequests.clear();
        Files.deleteIfExists(FIXTURE.killSwitch);
    }

    @AfterAll static void close() throws Exception { FIXTURE.close(); }

    @Test void ordinaryRefreshAutomaticallyDrawsVerifiedParcelShapesWithoutAnyParcelAction() {
        Page page = browser.page();
        page.navigate(applicationUri().toString(), new Page.NavigateOptions()
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        page.waitForFunction("document.querySelector('#refresh-status')?.textContent === 'Освежавање није покренуто.'");
        page.locator("#refresh-start").click();
        page.waitForFunction("document.querySelector('#refresh-status')?.textContent.includes('Карта је спремна')");
        page.waitForFunction("window.__auctionMap?.ready === true && window.__auctionMap.map !== null");
        page.evaluate("() => { window.__auctionMap.map.jumpTo({center: [20.495,44.775], zoom: 14}); }");
        page.waitForFunction("window.__auctionMap.getDiagnostics().lastFeatureCount === 3");
        page.waitForFunction("window.__auctionMap.map.queryRenderedFeatures({layers: ['auction-area-parcel']}).length > 0");

        assertThat(page.locator("#map-result-list li[data-precision='PARCEL']").count()).isEqualTo(3);
        assertThat(page.locator("#map-result-list li[data-precision='CADASTRAL_MUNICIPALITY']").count()).isZero();
        assertThat((Boolean) page.evaluate("""
                window.__auctionMap.map.queryRenderedFeatures({layers: ['auction-area-parcel']})
                  .every(feature => feature.properties.precision === 'PARCEL'
                    && ['Polygon', 'MultiPolygon'].includes(feature.geometry.type))
                """)).isTrue();
        page.locator(".map-result-button").first().click();
        page.waitForSelector(".maplibregl-popup .map-popup");
        assertThat(page.locator(".map-popup").textContent()).contains("Парцела", "Проверена граница");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM rgz_parcel_cache_keys", Long.class)).isEqualTo(3);
        assertThat(FIXTURE.parcelRequests).containsExactlyInAnyOrderElementsOf(
                RgzWorkflowFixture.SUCCESSES.stream().map(RgzWorkflowFixture.Example::filter).toList());
        browser.network().assertOnlyLocalhostRequests();
    }

    @Test void liveKillSwitchIsVisibleOnOperatorSurfaceWithoutRestartOrLeakingConfiguration() throws Exception {
        Page page = browser.page();
        page.navigate(applicationUri().resolve("operator/status").toString());
        page.waitForFunction("document.querySelector('#rgz').textContent.includes('ENABLED')");
        assertThat(page.locator("#rgz").textContent()).contains("Network allowedtrue");
        Files.createFile(FIXTURE.killSwitch);
        page.locator("#refresh").click();
        page.waitForFunction("document.querySelector('#rgz').textContent.includes('KILL_SWITCH_ENGAGED')");
        assertThat(page.locator("#rgz").textContent()).contains("Kill switch engagedtrue", "Network allowedfalse");
        assertThat(page.locator("#signals").textContent()).contains("RGZ_KILL_SWITCH_ENGAGED_CACHE_AND_FALLBACK_ONLY");
        assertThat(page.locator("#evidence").textContent()).doesNotContain(FIXTURE.killSwitch.toString(),
                "Authorization", "Cookie", RgzWorkflowFixture.PRIVATE_SENTINEL);
        Files.delete(FIXTURE.killSwitch);
        page.locator("#refresh").click();
        page.waitForFunction("document.querySelector('#rgz').textContent.includes('ENABLED')");
        assertThat(FIXTURE.parcelRequests).isEmpty();
        browser.network().assertOnlyLocalhostRequests();
    }

    private static Path basemap() {
        try {
            Path root = Files.createTempDirectory("issue-21-browser-basemap-");
            Path fixture = Path.of(AutomaticParcelBrowserTest.class.getResource("/fixtures/basemap-bundle").toURI());
            BasemapTestBundle.fromDirectory(root, "browser-issue-21-v1", fixture);
            BasemapTestBundle.activate(root, "browser-issue-21-v1");
            return root;
        } catch (Exception failure) { throw new ExceptionInInitializerError(failure); }
    }
}
