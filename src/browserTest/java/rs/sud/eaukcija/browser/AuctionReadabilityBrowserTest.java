package rs.sud.eaukcija.browser;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.ReducedMotion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import rs.sud.eaukcija.basemap.BasemapTestBundle;

/** #51: real bounded details and shared views, including touch and localhost-only resource loading. */
class AuctionReadabilityBrowserTest extends PostgisBrowserFixture {
    private static final Path BASEMAP = basemap();
    private static final String DESCRIPTION = "<img src=https://evil.invalid/x onerror=window.__xss=true>\n"
            + "Љубиње Čačak опис непокретности. ".repeat(80) + "\n" + "ДугаРечLongWord".repeat(60) + " КРАЈ ОПИСА";
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("basemap.assets.directory", BASEMAP::toString);
        registry.add("map.browser-test-hooks", () -> "true");
        registry.add("map.auto-refresh-interval-ms", () -> "1000");
    }
    @RegisterExtension final BrowserHarnessExtension browser = new BrowserHarnessExtension(true, true);
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void seed() {
        jdbc.update("""
                UPDATE auctions SET end_date='2099-08-28T11:00:00Z', starting_price=123456.78,
                    category_name=?, place_name=?, municipality='Чачак', status='Verified',
                    description=?, short_description='Кратак опис', estimated_price=234567.89 WHERE id=34001
                """, "Кућа " + "ЉубињеČačak".repeat(15), "Место".repeat(40), DESCRIPTION);
        location(34001, 0, "PARCEL", "POLYGON((20.458 44.788,20.459 44.788,20.459 44.789,20.458 44.789,20.458 44.788))");
        location(34001, 1, "ADDRESS", "POINT(20.462 44.791)");
        for (int id = 34002; id <= 34030; id++) {
            jdbc.update("""
                    INSERT INTO auctions(id, auction_number, status, end_date, first_sale, details_fetched, description)
                    VALUES (?, ?, ?, NULL, false, true, 'Други опис')
                    """, id, "Н" + id, "Raw<svg/onload=window.__xss=true>");
            if (id < 34020) location(id, 0, "CADASTRAL_MUNICIPALITY", "POINT(20.461 44.792)");
        }
    }
    @Test void deliberateSafeFullDetailsAndKeyboardBackKeepPropertyScrollDraftsAndModes() {
        AtomicInteger details = new AtomicInteger();
        browser.page().onRequest(request -> { if (request.url().matches(".*/api/auctions/[0-9]+/details")) details.incrementAndGet(); });
        Page page = open();
        assertThat(details.get()).isZero();
        Locator card = page.locator(".map-result-button[data-auction-id='34001']").nth(1);
        assertThat(card.textContent()).contains("Кућа", "Место:", "Чачак", "Почетна цена аукције", "123.456,78", "2099", "Београд", "Број аукције:", "Прецизност локације:", "2 локација", "цена није по парцели");
        String property = card.getAttribute("data-feature-id");
        page.locator("#workspace-filter-toggle").click();
        page.locator("#search-filter").fill("unapplied Њива");
        page.locator("#filter-panel-close").click();
        card.focus();
        page.evaluate("window.__card = document.activeElement; window.__scroll = document.querySelector('.map-results').scrollTop");
        card.press("Enter"); loaded(page);
        Locator source = page.locator(".map-popup a[href^='https://eaukcija.sud.rs']");
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(source).isFocused();
        assertThat(page.locator(".auction-description").first().textContent()).isEqualTo(DESCRIPTION);
        assertThat(page.locator(".map-popup").textContent()).contains("Проверено на извору", "не временски опсег", "не на појединачну парцелу");
        assertThat(page.locator(".maplibregl-popup").count()).isZero();
        assertThat(page.locator(".map-popup img, .map-popup svg").count()).isZero();
        assertThat(source.getAttribute("rel")).isEqualTo("noopener noreferrer");
        assertThat(source.getAttribute("target")).isEqualTo("_blank");
        source.evaluate("el => el.addEventListener('click', event => event.preventDefault())");
        source.press("Enter");
        page.evaluate("window.__source = document.activeElement; window.__article = document.querySelector('.map-popup')");
        refresh(page);
        assertThat(page.evaluate("document.activeElement === window.__source && window.__card.isConnected")).isEqualTo(true);
        for (String mode : List.of("table", "map", "results")) {
            page.locator("#mode-" + mode).press("Enter"); ready(page);
            assertThat(page.evaluate("window.__auctionMap.getDiagnostics().selectedFeatureId")).isEqualTo(property);
            assertThat(page.evaluate("window.__auctionMap.getDiagnostics().detailsOpen")).isEqualTo(true);
            assertThat(page.evaluate("window.__article === document.querySelector('.map-popup')")).isEqualTo(true);
        }
        assertThat(page.locator("#search-filter").inputValue()).isEqualTo("unapplied Њива");
        page.locator(".rail-details-close").press("Enter");
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(card).isFocused();
        assertThat(page.evaluate("document.querySelector('.map-results').scrollTop === window.__scroll")).isEqualTo(true);
        refresh(page);
        assertThat(page.locator(".map-popup").count()).isZero();
        page.reload(); ready(page);
        assertThat(page.locator(".map-popup").count()).isZero();
        browser.network().assertOnlyLocalhostRequests();
    }
    @Test void unknownFieldsAndSourceStatusNeverPretendToBeParcelMetadataAndUnmappedTableDetailsWork() {
        Page page = open();
        Locator card = page.locator(".map-result-button[data-auction-id='34002']");
        assertThat(card.textContent()).contains("Категорија није наведена", "Место: није наведено", "Општина: није наведена", "Цена није наведена", "Завршетак: Није наведен", "Центар катастарске општине");
        card.tap(); loaded(page);
        assertThat(page.locator(".map-popup").textContent()).contains("Непознат изворни статус: Raw<svg", "Центар катастарске општине; ово није адреса ни парцела.");
        page.locator(".rail-details-close").tap();
        page.locator("#mode-table").tap();
        page.locator(".table-select[data-auction-id='34020']").tap(); loaded(page); ready(page);
        assertThat(page.locator(".map-popup").textContent()).contains("Други опис", "нема објављиву локацију", "Цена није наведена");
        assertThat(page.locator(".map-popup a[href^='https://www.google.com']").count()).isZero();
        assertThat(page.evaluate("window.__xss ?? null")).isNull();
        browser.network().assertOnlyLocalhostRequests();
    }
    @Test void tableDefaultsSecondaryColumnsStickySortDirectionAndRefreshPreserveNavigation() {
        Page page = open();
        page.locator("#mode-table").press("Enter");
        assertThat(page.locator("thead th:visible").count()).isEqualTo(5);
        assertThat(page.locator(".desc-cell").count()).isZero();
        page.locator("#table-secondary").press("Space");
        assertThat(page.locator("thead th:visible").count()).isEqualTo(8);
        Locator sort = page.locator("th a[data-sort-field=estimatedPrice]");
        sort.press("Enter"); ready(page);
        assertThat(page.url()).contains("sortBy=estimatedPrice", "sortDir=asc", "page=0");
        assertThat(page.locator("th[aria-sort=ascending]").textContent()).contains("↑", "Растуће");
        sort.press("Enter"); ready(page);
        assertThat(page.locator("th[aria-sort=descending]").textContent()).contains("↓", "Опадајуће");
        assertThat(page.locator(".secondary-column").allTextContents().toString()).contains("Проверено на извору", "Непознат изворни статус");
        page.locator(".table-scroll").evaluate("el => { el.scrollTop = 500; el.scrollLeft = 70; el.focus(); }");
        page.evaluate("window.__tableScroll = document.querySelector('.table-scroll').scrollTop");
        refresh(page);
        assertThat(page.evaluate("document.querySelector('.table-scroll').scrollTop === window.__tableScroll")).isEqualTo(true);
        assertThat(page.evaluate("() => { const scroll = document.querySelector('.table-scroll').getBoundingClientRect(), th = document.querySelector('th').getBoundingClientRect(); return Math.abs(scroll.top - th.top) < 3; }")).isEqualTo(true);
        assertThat(page.locator("#table-secondary").isChecked()).isTrue();
        page.locator("#mode-results").press("Enter"); refresh(page);
        page.locator("#mode-table").press("Enter"); ready(page);
        assertThat(page.evaluate("document.querySelector('.table-scroll').scrollTop === window.__tableScroll")).isEqualTo(true);
        page.locator(".pagination a").filter(new Locator.FilterOptions().setHasText("Следећа")).press("Enter"); ready(page);
        assertThat(page.url()).contains("page=1", "sortBy=estimatedPrice", "sortDir=desc");
        String url = page.url();
        page.locator(".table-select").first().press("Enter"); loaded(page);
        page.locator(".rail-details-close").press("Enter");
        page.locator("#mode-table").press("Enter"); ready(page);
        assertThat(page.url().split("&auction=")[0]).isEqualTo(url);
        browser.network().assertOnlyLocalhostRequests();
    }
    @Test void disappearingFocusedPropertyKeepsAuctionDescriptionButNeverSubstitutesASiblingGeometry() {
        Page page = open();
        Locator card = page.locator("li[data-precision=ADDRESS] .map-result-button[data-auction-id='34001']");
        String property = card.getAttribute("data-feature-id");
        card.press("Enter"); loaded(page);
        jdbc.update("DELETE FROM current_location_resolutions WHERE property_reference_id IN (SELECT id FROM property_references WHERE auction_id=34001 AND reference_order=1)");
        refresh(page);
        assertThat(page.evaluate("window.__auctionMap.getDiagnostics().selectedFeatureId")).isEqualTo(property);
        assertThat(page.locator(".map-popup").textContent()).contains("Изабрани објекат", "КРАЈ ОПИСА");
        assertThat(page.locator(".map-popup a[href^='https://www.google.com']").count()).isZero();
        assertThat(page.evaluate("async () => (await window.__auctionMap.map.getSource('auction-selection').getData()).features.length")).isEqualTo(0);
        assertThat(page.locator(".map-result-button[aria-current=true]").count()).isZero();
        page.locator(".rail-details-close").press("Enter"); refresh(page);
        assertThat(page.locator(".map-popup").count()).isZero();
        browser.network().assertOnlyLocalhostRequests();
    }

    @Test void lateFailedDetailsNeverResurrectDismissedOrDifferentSelection() {
        browser.page().addInitScript("""
                const realFetch = window.fetch.bind(window);
                window.fetch = async (input, options) => {
                    const response = await realFetch(input, options);
                    if (String(input).includes('/api/auctions/34001/details') && !window.__released) {
                        return new Promise(resolve => { window.__release = () => { window.__released = true; resolve(response); }; });
                    }
                    return response;
                };
                """);
        Page page = open();
        page.locator(".map-result-button[data-auction-id='34001']").first().press("Enter");
        page.waitForFunction("!!window.__release");
        page.keyboard().press("Escape");
        page.locator(".map-result-button[data-auction-id='34002']").press("Enter"); loaded(page);
        page.evaluate("window.__release()");
        assertThat(page.locator(".auction-description").first().textContent()).isEqualTo("Други опис");
        page.keyboard().press("Escape");
        page.route("**/api/auctions/34002/details", route -> route.fulfill(new com.microsoft.playwright.Route.FulfillOptions().setStatus(503)));
        page.locator(".map-result-button[data-auction-id='34002']").press("Enter");
        page.waitForSelector(".local-auction-details button:visible");
        assertThat(page.locator(".local-auction-details").textContent()).contains("Опис тренутно није доступан");
        page.locator(".rail-details-close").press("Enter");
        refresh(page);
        assertThat(page.locator(".map-popup").count()).isZero();
        browser.network().assertOnlyLocalhostRequests();
    }
    @Test void longBilingualTextWrapsAndReadableControlsStayInsideDesktopNarrowAndZoomedLayouts() throws Exception {
        Page page = open();
        Path evidence = Path.of("build/browser-test-results/evidence"); Files.createDirectories(evidence);
        for (int[] size : List.of(new int[]{1366, 900}, new int[]{683, 450}, new int[]{390, 844})) {
            page.setViewportSize(size[0], size[1]); ready(page);
            page.locator(".map-result-button[data-auction-id='34001']").first().tap(); loaded(page);
            assertThat(page.evaluate("document.documentElement.scrollWidth <= innerWidth")).isEqualTo(true);
            assertThat(page.locator(".map-popup").evaluate("el => el.scrollWidth <= el.clientWidth")).isEqualTo(true);
            assertThat(page.evaluate("() => {const rail=document.querySelector('#rail-details').getBoundingClientRect(), map=document.querySelector('#auction-map').getBoundingClientRect(); return rail.right <= map.left || rail.top >= map.bottom;}")).isEqualTo(true);
            assertThat(page.locator(".map-result-meta").first().evaluate("""
                    el => {
                      const rgb = getComputedStyle(el).color.match(/\\d+/g).slice(0,3).map(Number);
                      const linear = rgb.map(v => v/255 <= .04045 ? v/255/12.92 : ((v/255+.055)/1.055)**2.4);
                      const luminance = linear[0]*.2126 + linear[1]*.7152 + linear[2]*.0722;
                      return 1.05 / (luminance + .05) >= 4.5;
                    }
                    """)).isEqualTo(true);
            assertThat(page.locator(".auction-description").first().evaluate("el => parseFloat(getComputedStyle(el).fontSize) >= 15 && getComputedStyle(el).whiteSpace === 'pre-wrap'")).isEqualTo(true);
            assertThat(page.locator(".map-result-meta").first().evaluate("el => parseFloat(getComputedStyle(el).fontSize) >= 15")).isEqualTo(true);
            assertThat(page.locator(".rail-details-close").evaluate("el => {const b=el.getBoundingClientRect(); return b.height >= 44 && b.width >= 44;}")).isEqualTo(true);
            page.locator(".map-popup").evaluate("el => el.scrollTop = el.scrollHeight");
            page.locator(".rail-details-close").scrollIntoViewIfNeeded();
            page.screenshot(new Page.ScreenshotOptions().setPath(evidence.resolve("issue-51-details-" + size[0] + ".png")));
            page.locator(".rail-details-close").tap();
            page.locator("#mode-table").tap(); ready(page);
            assertThat(page.evaluate("document.documentElement.scrollWidth <= innerWidth")).isEqualTo(true);
            page.locator("#mode-results").tap();
        }
        browser.network().assertOnlyLocalhostRequests();
    }
    private Page open() {
        Page page = browser.page(); page.setViewportSize(1366, 900);
        page.emulateMedia(new Page.EmulateMediaOptions().setReducedMotion(ReducedMotion.REDUCE));
        page.navigate(applicationUri() + "?timeScope=all&sortBy=id&sortDir=asc"); ready(page); return page;
    }
    private static void ready(Page page) {
        page.waitForFunction("window.__auctionMap?.ready && ['ready','empty'].includes(window.__auctionMap.getDiagnostics().lastState) && !window.__auctionMap.getDiagnostics().requestInFlight && !window.__auctionMap.getDiagnostics().pendingRefresh && !window.__auctionMap.map.isMoving()");
    }
    private static void loaded(Page page) { page.waitForFunction("document.querySelector('.local-auction-details .auction-description')?.textContent.length > 0"); }
    private static void refresh(Page page) { page.evaluate("window.__auctionMap.refreshNow()"); ready(page); }
    private void location(long auction, int order, String precision, String wkt) {
        UUID reference = UUID.randomUUID(), geometry = UUID.randomUUID(), attempt = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO property_references(id, auction_id, reference_order, reference_type, source_field, parser_version, extraction_status, canonical_key)
                VALUES (?, ?, ?, 'OTHER', 'fixture', 'issue51', 'EXTRACTED', ?)
                """, reference, auction, order, "property-" + order);
        jdbc.update("""
                INSERT INTO spatial_resolution_geometries(id, source_geometry, source_crs_authority, source_crs_code, original_geometry_valid, make_valid_applied)
                VALUES (?, ST_GeomFromText(?,4326), 'EPSG',4326,true,false)
                """, geometry, wkt);
        jdbc.update("""
                INSERT INTO location_resolution_attempts(id, property_reference_id, resolver, resolver_version, input_fingerprint,
                    source_dataset, source_dataset_version, source_dataset_sha256, resolution_status, location_precision, geometry_id,
                    confidence_reason, candidate_evidence, attempted_at, completed_at, resolved_at)
                VALUES (?, ?, 'fixture', 'issue51', repeat('a',64), 'fixture', 'v1', repeat('b',64), 'RESOLVED', ?, ?, 'fixture', '[]', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, attempt, reference, precision, geometry);
        jdbc.update("INSERT INTO current_location_resolutions(property_reference_id,resolution_attempt_id,selected_at,selection_reason) VALUES (?,?,CURRENT_TIMESTAMP,'fixture')", reference, attempt);
    }
    private static Path basemap() {
        try {
            Path root = Files.createTempDirectory("readability-browser-");
            BasemapTestBundle.fromDirectory(root, "issue51", Path.of(AuctionReadabilityBrowserTest.class.getResource("/fixtures/basemap-bundle").toURI()));
            BasemapTestBundle.activate(root, "issue51"); return root;
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
}
