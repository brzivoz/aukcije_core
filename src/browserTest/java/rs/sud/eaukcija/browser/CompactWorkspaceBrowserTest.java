package rs.sud.eaukcija.browser;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.options.ReducedMotion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import rs.sud.eaukcija.basemap.BasemapTestBundle;

/** #54: the compact shell against the real shared-view API and retained local map. */
class CompactWorkspaceBrowserTest extends PostgisBrowserFixture {
    private static final Path BASEMAP = basemap();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("basemap.assets.directory", BASEMAP::toString);
        registry.add("map.browser-test-hooks", () -> "true");
        registry.add("map.auto-refresh-interval-ms", () -> "1000");
    }
    @RegisterExtension final BrowserHarnessExtension browser = new BrowserHarnessExtension();
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void seed() {
        jdbc.update("UPDATE auctions SET end_date='2099-01-01', starting_price=125000, status='Verified' WHERE id=34001");
        UUID reference = UUID.randomUUID(), geometry = UUID.randomUUID(), attempt = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO property_references(id, auction_id, reference_order, reference_type, source_field,
                    parser_version, extraction_status, canonical_key)
                VALUES (?, 34001, 0, 'STRUCTURED_LOCATION', 'fixture', 'issue54', 'EXTRACTED', 'address')
                """, reference);
        jdbc.update("""
                INSERT INTO spatial_resolution_geometries(id, source_geometry, source_crs_authority, source_crs_code,
                    original_geometry_valid, make_valid_applied)
                VALUES (?, ST_GeomFromText('POINT(20.46 44.79)', 4326), 'EPSG', 4326, true, false)
                """, geometry);
        jdbc.update("""
                INSERT INTO location_resolution_attempts(id, property_reference_id, resolver, resolver_version,
                    input_fingerprint, source_dataset, source_dataset_version, source_dataset_sha256,
                    resolution_status, location_precision, geometry_id, confidence_reason, candidate_evidence,
                    attempted_at, completed_at, resolved_at)
                VALUES (?, ?, 'fixture', 'issue54', repeat('a',64), 'fixture', 'v1', repeat('b',64),
                    'RESOLVED', 'ADDRESS', ?, 'fixture', '[]'::jsonb, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, attempt, reference, geometry);
        jdbc.update("""
                INSERT INTO current_location_resolutions(property_reference_id, resolution_attempt_id, selected_at, selection_reason)
                VALUES (?, ?, CURRENT_TIMESTAMP, 'fixture')
                """, reference, attempt);
        // Isolate healthy shell dimensions from unrelated absence of operator-run fixtures.
        browser.page().route("**/api/map/status", route -> route.fulfill(json("""
                {"available":true,"stale":false,"dataVersion":"issue54","lastSuccessfulSync":"2026-09-09T01:05:00Z"}
                """)));
        browser.page().route("**/api/operator/refresh", route -> route.fulfill(json("""
                {"enabled":true,"workflowId":null,"status":"SUCCEEDED","stage":"COMPLETED",
                 "listingsProcessed":1,"listingsTotal":1,"detailsProcessed":1,"detailsTotal":1,
                 "locationsProcessed":1,"locationsTotal":1,"mappedCount":1,"populationCount":1,
                 "lastSuccessfulCompleteRefresh":"2026-09-09T01:05:00Z","precisionSummary":{"ADDRESS":1}}
                """)));
    }

    @Test void defaultChromeMeetsDesktopBudgetAndPanelsAndSelectionDoNotMoveTheMap() throws Exception {
        Page page = open(1366, 768, "");
        var evidence = new LinkedHashMap<String, Object>();
        Path root = Path.of("build/browser-test-results/evidence");
        Files.createDirectories(root);
        for (int[] size : List.of(new int[]{1366, 768}, new int[]{1920, 1080})) {
            page.setViewportSize(size[0], size[1]);
            page.navigate(applicationUri().toString());
            ready(page);
            Map<String, Number> bounds = bounds(page);
            assertThat(bounds.get("top").doubleValue()).isBetween(120.0, 160.0);
            assertThat(bounds.get("height").doubleValue()).isGreaterThanOrEqualTo(size[1] * .75);
            assertThat(bounds.get("bottom").doubleValue()).isLessThanOrEqualTo(size[1]);
            assertThat(page.locator("#shared-filters").isHidden()).isTrue();
            assertThat(page.locator("#refresh-panel").evaluate("el => !!el.closest('header')")).isEqualTo(true);
            assertThat(page.locator("#catalogue-count").isHidden()).isTrue();
            assertThat(page.locator("#workspace-applied").textContent()).contains("Нису завршене");
            assertThat(page.locator("#map-count-summary").textContent()).contains("1 аукција", "1 објеката");
            assertThat(page.locator("#auction-map-description").isVisible()).isTrue();
            page.screenshot(new Page.ScreenshotOptions().setPath(root.resolve("issue-54-workspace-" + size[0] + "x" + size[1] + ".png")));
            evidence.put(size[0] + "x" + size[1], bounds);
            Object camera = camera(page);
            page.evaluate("window.__map = window.__auctionMap.map; window.__form = document.querySelector('#shared-filters')");
            page.locator("#workspace-filter-toggle").press("Enter");
            ready(page);
            assertTop(page, bounds);
            assertThat(bounds(page).get("width").doubleValue()).isLessThan(bounds.get("width").doubleValue());
            page.screenshot(new Page.ScreenshotOptions().setPath(root.resolve("issue-54-filters-" + size[0] + "x" + size[1] + ".png")));
            page.locator("#search-filter").fill("draft");
            assertThat(page.locator("#filter-draft-state").textContent()).contains("Непримењене");
            page.locator("#search-filter").press("Escape");
            assertThat(page.locator("#workspace-filter-toggle").evaluate("el => el === document.activeElement")).isEqualTo(true);
            ready(page);
            assertTop(page, bounds);
            page.locator(".map-result-button").press("Enter");
            ready(page);
            assertTop(page, bounds);
            assertThat(page.locator("#rail-details .map-popup").isVisible()).isTrue();
            assertThat(page.locator("#selection-toggle").getAttribute("aria-controls")).isEqualTo("auction-popup-details");
            assertThat(page.locator(".maplibregl-popup").count()).isZero();
            assertThat(page.locator("#map-selection").evaluate("el => !!el.closest('#map-sidebar')")).isEqualTo(true);
            page.screenshot(new Page.ScreenshotOptions().setPath(root.resolve("issue-54-details-" + size[0] + "x" + size[1] + ".png")));
            page.evaluate("window.__focused = document.activeElement");
            periodic(page);
            assertTop(page, bounds);
            assertThat(page.evaluate("window.__focused === document.activeElement && window.__focused.isConnected")).isEqualTo(true);
            page.keyboard().press("Escape");
            assertThat(page.locator("#rail-details").isHidden()).isTrue();
            page.locator("#mode-map").press("Enter");
            ready(page);
            page.locator("#selection-toggle").press("Enter");
            assertThat(page.locator(".maplibregl-popup .map-popup").isVisible()).isTrue();
            assertTop(page, bounds);
            page.locator(".maplibregl-popup-close-button").press("Enter");
            periodic(page);
            assertThat(page.locator(".map-popup").count()).isZero();
            assertThat(page.locator("#selection-toggle").isVisible()).isTrue();
            assertThat(page.locator("#selection-toggle").getAttribute("aria-controls")).isEqualTo("map-selection");
            page.locator("#mode-results").press("Enter");
            assertThat(camera(page)).isEqualTo(camera);
            assertThat(page.evaluate("window.__map === window.__auctionMap.map && window.__form === document.querySelector('#shared-filters')")).isEqualTo(true);
            page.locator("#workspace-filter-toggle").click();
            assertThat(page.locator("#search-filter").inputValue()).isEqualTo("draft");
            page.locator("#search-filter").fill("");
            page.locator("#filter-panel-close").click();
            ready(page);
        }
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(root.resolve("issue-54-workspace-bounds.json").toFile(), evidence);
        browser.network().assertOnlyLocalhostRequests();
    }

    @Test void preferencesChipsAndDraftsSurviveModesRefreshReloadAndHistory() {
        Page page = open(1366, 900, "?status=Verified&minPrice=100000&auction=34001&sortBy=startingPrice&sortDir=desc");
        assertThat(page.locator("#shared-filters").isHidden()).isTrue();
        String initial = page.url();
        page.locator("#workspace-filter-toggle").press("Enter");
        page.locator("#advanced-filters > summary").press("Enter");
        assertThat(page.locator("#advanced-filter-count").textContent()).contains("1 примењено");
        page.locator("#search-filter").fill("unapplied <img>");
        page.locator("#map-precision-filter").selectOption("PARCEL");
        page.locator("#filter-panel-close").click();
        periodic(page);
        assertThat(page.url()).isEqualTo(initial);
        assertThat(page.locator("#filter-dirty-indicator").isVisible()).isTrue();
        assertThat(page.locator("#applied-filter-chips").textContent()).doesNotContain("unapplied", "Парцела");
        page.locator(".filter-chip[data-field=status]").press("Enter");
        ready(page);
        assertThat(page.url()).doesNotContain("status=").contains("minPrice=100000", "page=0", "auction=34001", "sortDir=desc");
        assertThat(page.locator("#search-filter").inputValue()).isEqualTo("unapplied <img>");
        assertThat(page.locator("#map-precision-filter").inputValue()).isEqualTo("PARCEL");
        assertThat(page.locator("#map-status-filter").inputValue()).isEmpty();
        String removed = page.url();
        for (String mode : List.of("map", "table", "results")) {
            page.locator("#mode-" + mode).press("Enter");
            ready(page);
            assertThat(page.url()).isEqualTo(removed);
            assertThat(page.locator("#filter-dirty-indicator").isVisible()).isTrue();
        }
        page.locator("#workspace-filter-toggle").click();
        assertThat(page.locator("#advanced-filters").getAttribute("open")).isNotNull();
        page.reload(); ready(page);
        assertThat(page.locator("#shared-filters").isVisible()).isTrue();
        assertThat(page.locator("#advanced-filters").getAttribute("open")).isNotNull();
        assertThat(page.locator("#search-filter").inputValue()).isEmpty(); // Drafts are deliberately not persisted.
        page.goBack(); ready(page);
        assertThat(page.locator(".filter-chip[data-field=status]").textContent()).contains("Verified");
        page.goForward(); ready(page);
        assertThat(page.locator(".filter-chip[data-field=status]").count()).isZero();
        page.locator("#applied-filter-reset").click(); ready(page);
        assertThat(page.locator(".filter-chip").count()).isOne();
        assertThat(page.url()).contains("auction=34001", "sortDir=desc", "timeScope=not-ended").doesNotContain("minPrice=");
        browser.network().assertOnlyLocalhostRequests();
    }

    @Test void chipsKeepExactDecimalPricesAndDoNotLoseUnrelatedMunicipalityDrafts() {
        Page page = open(1366, 768, "?minPrice=90071992547409911.01&municipality=Београд");
        assertThat(page.locator(".filter-chip[data-field=minPrice]").textContent()).contains("90071992547409911.01");
        assertThat(page.locator("#filter-dirty-indicator").isHidden()).isTrue();
        page.locator("#workspace-filter-toggle").click();
        page.locator("#municipality-filter summary").click();
        page.locator("#municipality-search").fill("novi sad");
        page.getByLabel("Нови Сад", new Page.GetByLabelOptions().setExact(true)).check();
        page.locator("#municipality-search").press("Escape");
        page.locator("#filter-panel-close").click();
        page.locator(".filter-chip[data-field=municipality]").click(); ready(page);
        assertThat(page.locator("input[name=municipality][value='Нови Сад']").isChecked()).isTrue();
        assertThat(page.locator("input[name=municipality][value='Београд']").isChecked()).isFalse();
        assertThat(page.locator("#filter-dirty-indicator").isVisible()).isTrue();
        assertThat(page.url()).doesNotContain("municipality=").contains("minPrice=90071992547409911.01");
        periodic(page);
        assertThat(page.locator("input[name=municipality][value='Нови Сад']").isChecked()).isTrue();
        browser.network().assertOnlyLocalhostRequests();
    }

    @Test void invalidHiddenFieldsAreRevealedWithoutDiscardingTheLastUsableView() {
        Page page = open(1366, 768, "");
        page.locator("#workspace-filter-toggle").click();
        page.locator("#min-price-filter").fill("200000");
        page.locator("#max-price-filter").fill("100000");
        page.locator("#filter-panel-close").click();
        page.evaluate("document.querySelector('#shared-filters').requestSubmit()");
        page.waitForFunction("document.querySelector('#map-state').dataset.state === 'error'");
        assertThat(page.locator("#shared-filters").isVisible()).isTrue();
        assertThat(page.locator("#max-price-filter").evaluate("el => el === document.activeElement")).isEqualTo(true);
        assertThat(page.locator("#map-result-count").textContent()).isEqualTo("1");
        assertThat(page.locator("#workspace-applied").textContent()).doesNotContain("200000");
        assertThat(page.locator("#map-retry").isHidden()).isTrue();
        page.locator("#applied-filter-reset").click(); ready(page);
        page.evaluate("document.querySelector('#map-from-filter').value='2026-09-10'; document.querySelector('#map-to-filter').value='2026-09-09'");
        page.locator("#filter-panel-close").click();
        page.evaluate("document.querySelector('#shared-filters').requestSubmit()");
        assertThat(page.locator("#shared-filters").isVisible()).isTrue();
        assertThat(page.locator("#advanced-filters").getAttribute("open")).isNotNull();
        assertThat(page.locator("#map-to-filter").evaluate("el => el === document.activeElement")).isEqualTo(true);
        browser.network().assertOnlyLocalhostRequests();
    }

    @Test void countsWarningsRetryAndExceptionalSelectionsRemainDiscoverableInMapOnly() {
        Page page = open(1366, 768, "?auction=34001");
        page.locator("#mode-map").click();
        page.locator("#map-count-summary").press("Enter");
        assertThat(page.locator("#map-count-breakdown").isVisible()).isTrue();
        assertThat(page.locator("#map-count-breakdown").textContent()).contains("Без локације", "Ван приказа", "Објеката на карти");
        page.locator("#map-count-summary").press("Escape");
        page.evaluate("() => { window.__auctionMap.map.jumpTo({center:[21.2,44.2],zoom:14}); }");
        page.waitForFunction("document.querySelector('#selection-toggle').textContent.includes('ван приказа')");
        assertThat(page.locator("#selection-toggle").isVisible()).isTrue();
        page.locator("#selection-toggle").press("Enter");
        assertThat(page.locator("#map-selection").isVisible()).isTrue();
        assertThat(page.locator("#map-selection").textContent()).contains("ван видљивог дела");
        page.locator("#mode-map").click();
        page.route("**/api/auctions/view?*", route -> route.fulfill(new Route.FulfillOptions().setStatus(503)));
        page.evaluate("window.__auctionMap.refreshNow()");
        page.waitForFunction("document.querySelector('#map-state').dataset.state === 'error'");
        assertThat(page.locator("#map-state").isVisible()).isTrue();
        assertThat(page.locator("#map-retry").isVisible()).isTrue();
        page.unroute("**/api/auctions/view?*");
        page.locator("#map-retry").click(); ready(page);
        assertThat(page.locator("#map-retry").isHidden()).isTrue();
        browser.network().assertOnlyLocalhostRequests();
    }

    @Test void narrowAndZoomedWindowsReflowAndStorageDenialDoesNotDisableTheShell() {
        Page page = browser.page();
        page.addInitScript("""
                for (const method of ['getItem','setItem']) {
                  const original = Storage.prototype[method];
                  Storage.prototype[method] = function(key, ...args) {
                    if (key.startsWith('eaukcija.workspace.')) throw new DOMException('denied', 'SecurityError');
                    return original.call(this, key, ...args);
                  };
                }
                """);
        open(683, 384, "");
        for (int[] size : List.of(new int[]{683,384}, new int[]{390,844})) {
            page.setViewportSize(size[0], size[1]);
            if (page.locator("#shared-filters").isHidden()) page.locator("#workspace-filter-toggle").press("Enter");
            page.locator("#advanced-filters > summary").evaluate("el => { el.parentElement.open = true; }");
            page.locator("#search-filter").fill("zoom draft");
            for (String mode : List.of("map", "table", "results")) {
                page.locator("#mode-" + mode).press("Enter");
                ready(page);
                assertThat(page.evaluate("document.documentElement.scrollWidth <= innerWidth")).isEqualTo(true);
                if (mode.equals("table")) assertThat(page.locator(".table-scroll").evaluate("el => el.scrollWidth > el.clientWidth")).isEqualTo(true);
            }
            page.locator("#filter-panel-close").click();
            assertThat(page.locator("#workspace-filter-toggle").getAttribute("aria-expanded")).isEqualTo("false");
        }
        browser.network().assertOnlyLocalhostRequests();
    }

    private Page open(int width, int height, String query) {
        Page page = browser.page();
        page.setViewportSize(width, height);
        page.emulateMedia(new Page.EmulateMediaOptions().setReducedMotion(ReducedMotion.REDUCE));
        page.navigate(applicationUri() + query); ready(page); return page;
    }
    private static void ready(Page page) {
        page.waitForFunction("""
                window.__auctionMap?.ready && ['ready','empty'].includes(window.__auctionMap.getDiagnostics().lastState)
                  && !window.__auctionMap.getDiagnostics().pendingRefresh && !window.__auctionMap.getDiagnostics().requestInFlight
                  && !window.__auctionMap.map.isMoving()
                  && window.__auctionMap.map.getCanvas().clientWidth === window.__auctionMap.map.getContainer().clientWidth
                """);
    }
    private static void periodic(Page page) {
        String before = page.locator("#shared-results").getAttribute("data-as-of");
        page.waitForFunction("before => document.querySelector('#shared-results').dataset.asOf !== before", before);
        ready(page);
    }
    private static Object camera(Page page) { return page.evaluate("[...window.__auctionMap.map.getCenter().toArray(),window.__auctionMap.map.getZoom()].map(n => n.toFixed(8))"); }
    @SuppressWarnings("unchecked") private static Map<String, Number> bounds(Page page) {
        return (Map<String, Number>) page.locator("#auction-map").evaluate("el => { const r=el.getBoundingClientRect(); return {top:r.top,bottom:r.bottom,width:r.width,height:r.height}; }");
    }
    private static void assertTop(Page page, Map<String, Number> expected) {
        assertThat(bounds(page).get("top").doubleValue()).isEqualTo(expected.get("top").doubleValue());
    }
    private static Route.FulfillOptions json(String body) { return new Route.FulfillOptions().setContentType("application/json").setBody(body); }
    private static Path basemap() {
        try {
            Path root = Files.createTempDirectory("compact-workspace-browser-");
            BasemapTestBundle.fromDirectory(root, "issue54", Path.of(CompactWorkspaceBrowserTest.class.getResource("/fixtures/basemap-bundle").toURI()));
            BasemapTestBundle.activate(root, "issue54"); return root;
        } catch (Exception exception) { throw new ExceptionInInitializerError(exception); }
    }
}
