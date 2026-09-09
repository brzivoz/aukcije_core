package rs.sud.eaukcija.browser;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

/** #46: real viewport/periodic refreshes over PostGIS, not synthetic map refresh events. */
class AuctionMapDetailsBrowserTest extends PostgisBrowserFixture {
    private static final Path BASEMAP = basemap();
    private static final String CLOSE = ".rail-details-close, .maplibregl-popup-close-button";
    private static final String REOPEN = ".map-selection-reopen";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("basemap.assets.directory", BASEMAP::toString);
        registry.add("map.browser-test-hooks", () -> "true");
        registry.add("map.auto-refresh-interval-ms", () -> "1000");
    }

    @RegisterExtension final BrowserHarnessExtension browser = new BrowserHarnessExtension();
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seedProperties() {
        browser.page().addInitScript("localStorage.setItem('eaukcija.workspace.v1.filters', 'true'); localStorage.setItem('eaukcija.workspace.v1.advanced-filters', 'true');");
        jdbc.update("""
                INSERT INTO auctions(id, auction_number, end_date, starting_price, status, category_name,
                    first_sale, details_fetched)
                VALUES (34002, 'Н46-002 <img src=x onerror=window.__popupXss=true>',
                    '2099-08-30T08:00:00Z', 200000, 'Verified', 'Парцела', false, true)
                """);
        location(34001, "ADDRESS", "POINT(20.4585 44.7890)");
        location(34001, "STREET", "POINT(20.4650 44.7905)");
        location(34002, "PARCEL",
                "POLYGON((20.4558 44.7860,20.4570 44.7860,20.4570 44.7872,20.4558 44.7872,20.4558 44.7860))");
    }

    @Test
    void pointerMapListAndTableOpenWithoutImmediateDismissalAndInsideLinksStayOpen() {
        Page page = open();
        clickMap(page, 20.4564, 44.7866);
        assertOpen(page, "PARCEL");
        String selectedUrl = page.url();
        page.locator(".map-popup h3").click();
        assertOpen(page, "PARCEL");
        Locator source = page.locator(".map-popup a[href^='https://eaukcija.sud.rs']");
        assertThat(source.getAttribute("href")).isEqualTo("https://eaukcija.sud.rs/#/aukcije/34002");
        assertThat(source.getAttribute("rel")).isEqualTo("noopener noreferrer");
        assertThat(source.getAttribute("target")).isEqualTo("_blank");
        Locator maps = page.locator(".map-popup a[href^='https://www.google.com/maps/search/']");
        assertThat(maps.getAttribute("href"))
                .isEqualTo("https://www.google.com/maps/search/?api=1&query=44.786600%2C20.456400");
        assertThat(maps.getAttribute("rel")).isEqualTo("noopener noreferrer");
        assertThat(maps.getAttribute("target")).isEqualTo("_blank");
        // Exercise the actual click without navigating to the public portal in this offline suite.
        source.evaluate("el => el.addEventListener('click', event => event.preventDefault())");
        source.click();
        assertOpen(page, "PARCEL");
        assertThat(page.locator(".map-popup img").count()).isZero();
        assertThat(page.evaluate("window.__popupXss ?? null")).isNull();
        // Closing the old summary must not shift the map before the new hit test.
        clickMap(page, 20.4650, 44.7905);
        assertOpen(page, "STREET");
        clickMap(page, 20.4564, 44.7866);
        assertOpen(page, "PARCEL");

        page.locator("#search-filter").click();
        assertDismissed(page, selectedUrl);
        assertFocused(page.locator("#search-filter"));
        result(page, "PARCEL").click();
        assertOpen(page, "PARCEL");
        clickBlankMap(page);
        assertDismissed(page, selectedUrl);
        clickMap(page, 20.4564, 44.7866); // Explicitly activate the same polygon again.
        assertOpen(page, "PARCEL");

        result(page, "ADDRESS").click();
        assertOpen(page, "ADDRESS");
        result(page, "STREET").click(); // Same auction, a different property.
        assertOpen(page, "STREET");
        page.locator(CLOSE).click();
        assertFocused(result(page, "STREET"));
        result(page, "STREET").click();
        assertOpen(page, "STREET");

        page.locator("#mode-table").click();
        page.locator(".table-select[data-auction-id='34002']").click();
        assertOpen(page, "PARCEL");
        assertThat(page.locator("#mode-results").getAttribute("aria-pressed")).isEqualTo("true");
        browser.network().assertOnlyLocalhostRequests();
        assertThat(browser.network().contactedHosts()).containsExactly("localhost");
    }

    @Test
    void keyboardEscapeAndCloseRestoreConnectedTriggersAndBackgroundUpdatesDoNotMoveDetailsFocus() {
        Page page = open();
        result(page, "ADDRESS").press("Enter");
        assertOpen(page, "ADDRESS");
        assertFocused(page.locator(".map-popup a[href^='https://eaukcija.sud.rs/']"));
        page.evaluate("window.__focusedDetails = document.activeElement; window.__popup = document.querySelector('#auction-popup-details')");
        jdbc.update("UPDATE auctions SET starting_price=987654 WHERE id=34001");
        awaitPeriodicUpdate(page);
        assertThat(page.locator(".map-popup").textContent()).contains("987.654");
        assertThat(page.evaluate("document.activeElement === window.__focusedDetails && window.__focusedDetails.isConnected && document.querySelector('#auction-popup-details') === window.__popup")).isEqualTo(true);
        page.keyboard().press("Escape");
        assertDismissed(page, page.url());
        assertFocused(result(page, "ADDRESS")); // The original result node was replaced by refresh.

        result(page, "STREET").press("Space");
        assertOpen(page, "STREET");
        page.locator(".map-popup a").last().press("Tab");
        Locator close = page.locator(CLOSE);
        assertFocused(close);
        assertThat(close.getAttribute("aria-label")).contains("Затвори детаље аукције");
        assertThat(close.evaluate("el => { const b = el.getBoundingClientRect(); return b.width >= 44 && b.height >= 44 && el.matches(':focus-visible'); }")).isEqualTo(true);
        page.evaluate("window.__focusedClose = document.activeElement");
        awaitPeriodicUpdate(page);
        assertThat(page.evaluate("document.activeElement === window.__focusedClose && window.__focusedClose.isConnected")).isEqualTo(true);
        close.press("Enter");
        assertDismissed(page, page.url());
        assertFocused(result(page, "STREET"));

        result(page, "STREET").press("Enter");
        page.locator("#selection-toggle").press("Enter");
        assertOpen(page, "STREET");
        page.keyboard().press("Escape");
        assertDismissed(page, page.url());
        assertFocused(page.locator("#selection-toggle")); // The compact reopen control remains available.
        result(page, "STREET").press("Enter");
        page.locator("#selection-toggle").focus();
        page.evaluate("window.__reopen = document.activeElement");
        awaitPeriodicUpdate(page);
        assertThat(page.evaluate("document.activeElement === window.__reopen && window.__reopen.isConnected")).isEqualTo(true);
        page.locator("#selection-toggle").press("Space");
        page.locator(CLOSE).click();
        assertDismissed(page, page.url());
        assertFocused(page.locator("#selection-toggle"));

        // The table trigger becomes hidden when selection reveals the map; use the matching result instead.
        page.locator("#mode-table").press("Enter");
        page.locator(".table-select[data-auction-id='34002']").press("Enter");
        assertOpen(page, "PARCEL");
        page.keyboard().press("Escape");
        assertFocused(result(page, "PARCEL"));
        clickMap(page, 20.4564, 44.7866);
        page.locator(CLOSE).click();
        assertFocused(page.locator("#auction-map canvas"));
        browser.network().assertOnlyLocalhostRequests();
    }

    @Test
    void everyDismissalSurvivesRealPanZoomPeriodicSourceCompletionAndLayerRedraw() {
        Page page = open();
        for (String dismissal : List.of("outside", "blank", "escape", "close")) {
            result(page, "STREET").press("Enter");
            assertOpen(page, "STREET");
            String url = page.url();
            String property = page.locator(".map-popup").getAttribute("data-feature-id");
            switch (dismissal) {
                case "outside" -> page.locator("#search-filter").click();
                case "blank" -> clickBlankMap(page);
                case "escape" -> page.keyboard().press("Escape");
                default -> page.locator(CLOSE).click();
            }
            assertDismissed(page, url);
            int completed = completed(page);
            page.evaluate("() => { const map = window.__auctionMap.map; map.jumpTo({center: [20.461, 44.7895], zoom: 14.1}); }");
            awaitRequest(page, completed);
            assertDismissed(page, url);
            awaitPeriodicUpdate(page);
            assertDismissed(page, url);
            // Production completion listener must fetch a new shared view, not reopen transient details.
            completed = completed(page);
            page.evaluate("window.dispatchEvent(new Event('eaukcija:refresh-complete'))");
            awaitRequest(page, completed);
            assertDismissed(page, url);
            assertThat(page.evaluate("window.__auctionMap.getDiagnostics().selectedFeatureId")).isEqualTo(property);
            assertThat(result(page, "STREET").getAttribute("aria-current")).isEqualTo("true");
            assertThat(page.locator("#shared-results tr[data-auction-id='34001']").getAttribute("aria-selected")).isEqualTo("true");
            assertThat(page.evaluate("async () => (await window.__auctionMap.map.getSource('auction-selection').getData()).features.map(f => f.id)").toString()).contains(property);
            if (dismissal.equals("outside")) assertFocused(page.locator("#search-filter"));
            page.evaluate("() => { const map = window.__auctionMap.map; map.setPaintProperty('auction-selected-area', 'line-width', 7); map.triggerRepaint(); }");
            page.waitForFunction("window.__auctionMap.map.isStyleLoaded() && window.__auctionMap.map.areTilesLoaded()");
            assertDismissed(page, url);
            result(page, "STREET").press("Enter");
            assertOpen(page, "STREET");
            assertThat(page.locator(".map-popup").getAttribute("data-feature-id")).isEqualTo(property);
        }
        browser.network().assertOnlyLocalhostRequests();
    }

    @Test
    void missingSelectedPropertyDoesNotSubstituteAnotherPropertyOrReopenWhenItReturns() {
        Page page = open();
        result(page, "STREET").press("Enter");
        assertOpen(page, "STREET");
        String property = page.locator(".map-popup").getAttribute("data-feature-id");
        String url = page.url();
        page.keyboard().press("Escape");
        page.selectOption("#map-precision-filter", "ADDRESS");
        page.locator("#shared-filters button[type=submit]").click();
        ready(page);
        assertThat(page.locator("#map-selection").textContent()).contains("Изабрани објекат", "Избор је сачуван");
        assertThat(page.locator(".map-popup").count()).isZero();
        assertThat(page.evaluate("window.__auctionMap.getDiagnostics().selectedFeatureId")).isEqualTo(property);
        page.selectOption("#map-precision-filter", "");
        page.locator("#shared-filters button[type=submit]").click();
        ready(page);
        assertDismissed(page, url);
        int before = completed(page);
        page.evaluate("() => { window.__auctionMap.map.jumpTo({center: [21.2, 44.2], zoom: 14}); }");
        awaitRequest(page, before);
        assertThat(page.locator("#map-selection").textContent()).contains("ван видљивог дела", "Избор је сачуван");
        assertDismissed(page, url);
        before = completed(page);
        page.evaluate("() => { window.__auctionMap.map.jumpTo({center: [20.46, 44.79], zoom: 14}); }");
        awaitRequest(page, before);
        assertDismissed(page, url);
        result(page, "STREET").press("Enter");
        assertOpen(page, "STREET");
        assertThat(page.locator(".map-popup").getAttribute("data-feature-id")).isEqualTo(property);
        browser.network().assertOnlyLocalhostRequests();
    }

    @Test
    void reloadCopiedUrlAndHistoryRestoreAuctionButNeverSerializeOrRestoreTransientOpenState() {
        Page page = open();
        result(page, "STREET").click();
        assertOpen(page, "STREET");
        String first = page.url();
        page.locator(CLOSE).click();
        assertDismissed(page, first);
        page.reload(); // Reload after dismissal, as well as reload while open below.
        ready(page);
        assertRestoredSelection(page, first);
        result(page, "PARCEL").click();
        assertOpen(page, "PARCEL");
        String second = page.url();
        page.goBack();
        ready(page);
        assertRestoredSelection(page, first);
        assertThat(page.evaluate("window.__auctionMap.getDiagnostics().selectedAuctionId")).isEqualTo("34001");
        page.goForward();
        ready(page);
        assertRestoredSelection(page, second);
        page.locator(REOPEN).press("Enter");
        assertOpen(page, "PARCEL");
        page.reload();
        ready(page);
        assertRestoredSelection(page, second);
        assertThat(page.locator("#map-selection").textContent()).contains("Изабрана аукција");
        Page copied = browser.newPage();
        copied.navigate(second);
        ready(copied);
        assertRestoredSelection(copied, second);
        assertThat(copied.locator("#map-selection").textContent()).contains("Изабрана аукција");
        copied.keyboard().press("Escape");
        assertDismissed(copied, second); // A restored summary also dismisses without a popup.
        awaitPeriodicUpdate(copied);
        assertDismissed(copied, second);
        assertThat(page.evaluate("[...new URLSearchParams(location.search).keys()]")).isEqualTo(List.of("timeScope", "sortBy", "sortDir", "page", "auction"));
        browser.network().assertOnlyLocalhostRequests();
    }

    @Test
    void popupLinksHaveSeparateLinesOnDesktopAndPhoneAndDismissalHidesTheSummary() {
        Page page = open();
        for (int width : List.of(1366, 390)) {
            result(page, "PARCEL").press("Enter");
            page.setViewportSize(width, 900);
            assertOpen(page, "PARCEL");
            assertThat(page.evaluate("""
                    () => {
                      const source = document.querySelector('.map-popup a[href^="https://eaukcija.sud.rs/"]').getBoundingClientRect();
                      const maps = document.querySelector('.map-popup a[href^="https://www.google.com/maps/search/"]').getBoundingClientRect();
                      return maps.top >= source.bottom + 5;
                    }
                    """)).isEqualTo(true);
            page.locator(CLOSE).click();
            assertDismissed(page, page.url());
            awaitPeriodicUpdate(page);
            assertDismissed(page, page.url());
        }
        browser.network().assertOnlyLocalhostRequests();
    }

    @Test
    void unsafeLinksStayAbsentDuringOpenRefreshAndKeyboardFallsBackToTheLabelledArticle() {
        browser.page().addInitScript("""
                const realFetch = window.fetch.bind(window);
                window.fetch = async (input, init) => {
                  const response = await realFetch(input, init);
                  const url = new URL(typeof input === 'string' ? input : input.url, location.href);
                  if (url.pathname !== '/api/auctions/view' || !response.ok || !window.__sourceUrl) return response;
                  const view = await response.json();
                  for (const feature of view.map.features) feature.properties.detailUrl = window.__sourceUrl;
                  return new Response(JSON.stringify(view), {status: response.status, headers: response.headers});
                };
                """);
        Page page = open();
        for (String unsafe : List.of("javascript:window.__popupXss=true", "https://example.invalid/#/aukcije/34002",
                "https://eaukcija.sud.rs.evil.invalid/#/aukcije/34002", "http://eaukcija.sud.rs/#/aukcije/34002",
                "https://eaukcija.sud.rs/path#/aukcije/34002", "https://eaukcija.sud.rs/?redirect=bad#/aukcije/34002",
                "https://eaukcija.sud.rs/#/aukcije/99999")) {
            page.evaluate("value => { window.__sourceUrl = value; }", unsafe);
            page.evaluate("window.__auctionMap.refreshNow()");
            ready(page);
            result(page, "PARCEL").press("Enter");
            assertOpen(page, "PARCEL");
            assertThat(page.locator(".map-popup a:has-text('Отвори на порталу еАукција'), #map-selection a").count()).isZero();
            assertThat(page.locator(".map-popup").getAttribute("aria-label")).startsWith("Детаљи аукције");
            assertFocused(page.locator(".map-popup"));
            assertThat(page.locator(".map-popup img").count()).isZero();
            assertThat(page.evaluate("window.__popupXss ?? null")).isNull();
        }
        page.keyboard().press("Escape");
        assertFocused(result(page, "PARCEL"));
        browser.network().assertOnlyLocalhostRequests();
        assertThat(browser.network().blockedHosts()).isEmpty();
    }

    @Test
    void municipalityDisclosureKeepsItsOwnEscapeAndOutsideClickBehavior() {
        Page page = open();
        result(page, "ADDRESS").press("Enter");
        page.locator("#municipality-filter summary").press("Enter");
        assertDismissed(page, page.url());
        page.locator("#municipality-search").fill("cacak");
        page.getByLabel("Чачак", new Page.GetByLabelOptions().setExact(true)).check();
        awaitPeriodicUpdate(page);
        assertThat(page.locator("#municipality-filter").getAttribute("open")).isNotNull();
        page.locator("#municipality-search").press("Escape");
        assertThat(page.locator("#municipality-filter").getAttribute("open")).isNull();
        assertFocused(page.locator("#municipality-filter summary"));
        page.locator("#municipality-filter summary").click();
        page.locator("#search-filter").click();
        assertThat(page.locator("#municipality-filter").getAttribute("open")).isNull();
        assertFocused(page.locator("#search-filter"));
        assertThat(page.locator("input[name=municipality][value='Чачак']").isChecked()).isTrue();
        browser.network().assertOnlyLocalhostRequests();
    }

    private Page open() {
        Page page = browser.page();
        page.setViewportSize(1366, 900);
        page.emulateMedia(new Page.EmulateMediaOptions().setReducedMotion(ReducedMotion.REDUCE));
        page.navigate(applicationUri().toString());
        ready(page);
        return page;
    }

    private static Locator result(Page page, String precision) {
        return page.locator("#map-result-list li[data-precision='" + precision + "'] button");
    }

    private static void assertOpen(Page page, String precision) {
        page.waitForSelector(".map-popup");
        ready(page);
        assertThat(page.locator(".map-popup").getAttribute("data-feature-id")).isEqualTo(result(page, precision).getAttribute("data-feature-id"));
        assertThat(page.locator("#map-selection").isHidden()).isTrue(); // Do not duplicate the open rail article.
        assertThat(page.locator("#rail-details").isVisible()).isTrue();
        assertThat(page.locator(REOPEN).getAttribute("aria-expanded")).isEqualTo("true");
        assertThat(page.evaluate("window.__auctionMap.getDiagnostics().detailsOpen")).isEqualTo(true);
    }

    private static void assertDismissed(Page page, String url) {
        assertPopupClosed(page, url);
        assertThat(page.locator("#map-selection").isHidden()).isTrue();
    }

    private static void assertRestoredSelection(Page page, String url) {
        assertPopupClosed(page, url);
        assertThat(page.locator("#map-selection").isVisible()).isTrue();
    }

    private static void assertPopupClosed(Page page, String url) {
        assertThat(page.locator("#auction-popup-details").count()).isZero();
        assertThat(page.evaluate("window.__auctionMap.getDiagnostics().detailsOpen")).isEqualTo(false);
        assertThat(page.url()).isEqualTo(url);
    }

    private static void assertFocused(Locator locator) {
        // A closing summary resizes/refetches the map. Re-resolve replaced result
        // buttons atomically instead of evaluating a detached locator handle.
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(locator).isFocused();
    }

    private static void ready(Page page) {
        page.waitForFunction("""
                window.__auctionMap?.ready && ['ready', 'empty'].includes(window.__auctionMap.getDiagnostics().lastState)
                  && !window.__auctionMap.getDiagnostics().pendingRefresh && !window.__auctionMap.getDiagnostics().requestInFlight
                  && !window.__auctionMap.map.isMoving() && window.__auctionMap.map.areTilesLoaded()
                """);
    }

    private static int completed(Page page) {
        return ((Number) page.evaluate("window.__auctionMap.getDiagnostics().requestsCompleted")).intValue();
    }

    private static void awaitRequest(Page page, int before) {
        page.waitForFunction("before => window.__auctionMap.getDiagnostics().requestsCompleted > before", before);
        ready(page);
    }

    private static void awaitPeriodicUpdate(Page page) {
        String asOf = page.locator("#shared-results").getAttribute("data-as-of");
        page.waitForFunction("before => document.querySelector('#shared-results').dataset.asOf !== before", asOf);
        ready(page);
    }

    private static void clickMap(Page page, double longitude, double latitude) {
        page.locator("#auction-map").scrollIntoViewIfNeeded();
        @SuppressWarnings("unchecked")
        Map<String, Number> point = (Map<String, Number>) page.evaluate("""
                coordinates => {
                  const map = window.__auctionMap.map, p = map.project(coordinates), b = map.getContainer().getBoundingClientRect();
                  return {x: b.left + p.x, y: b.top + p.y};
                }
                """, List.of(longitude, latitude));
        page.mouse().click(point.get("x").doubleValue(), point.get("y").doubleValue());
    }

    private static void clickBlankMap(Page page) {
        Locator canvas = page.locator("#auction-map canvas");
        double height = ((Number) canvas.evaluate("el => el.clientHeight")).doubleValue();
        // Top-left now contains the deliberate selection reopen control, not blank map.
        canvas.click(new Locator.ClickOptions().setPosition(15, height - 35));
    }

    private void location(long auction, String precision, String wkt) {
        UUID reference = UUID.randomUUID(), geometry = UUID.randomUUID(), attempt = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO property_references(id, auction_id, reference_order, reference_type, source_field,
                    parser_version, extraction_status, canonical_key)
                VALUES (?, ?, ?, 'STRUCTURED_LOCATION', 'fixture', 'issue46', 'EXTRACTED', ?)
                """, reference, auction, precision.equals("STREET") ? 1 : 0, precision);
        jdbc.update("""
                INSERT INTO spatial_resolution_geometries(id, source_geometry, source_crs_authority, source_crs_code,
                    original_geometry_valid, make_valid_applied)
                VALUES (?, ST_GeomFromText(?, 4326), 'EPSG', 4326, true, false)
                """, geometry, wkt);
        jdbc.update("""
                INSERT INTO location_resolution_attempts(id, property_reference_id, resolver, resolver_version,
                    input_fingerprint, source_dataset, source_dataset_version, source_dataset_sha256,
                    resolution_status, location_precision, geometry_id, confidence_reason, candidate_evidence,
                    attempted_at, completed_at, resolved_at)
                VALUES (?, ?, 'fixture', 'issue46', repeat('a',64), 'fixture', 'v1', repeat('b',64),
                    'RESOLVED', ?, ?, 'details fixture', '[]'::jsonb, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, attempt, reference, precision, geometry);
        jdbc.update("""
                INSERT INTO current_location_resolutions(property_reference_id, resolution_attempt_id, selected_at, selection_reason)
                VALUES (?, ?, CURRENT_TIMESTAMP, 'details fixture')
                """, reference, attempt);
    }

    private static Path basemap() {
        try {
            Path root = Files.createTempDirectory("map-details-browser-");
            Path fixture = Path.of(AuctionMapDetailsBrowserTest.class.getResource("/fixtures/basemap-bundle").toURI());
            BasemapTestBundle.fromDirectory(root, "issue46", fixture);
            BasemapTestBundle.activate(root, "issue46");
            return root;
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
