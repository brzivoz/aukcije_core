package rs.sud.eaukcija.browser;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.microsoft.playwright.Page;
import rs.sud.eaukcija.basemap.BasemapTestBundle;

/** Real, offline PostGIS + browser parity and state transitions; no GeoJSON stubs. */
class SharedAuctionFiltersBrowserTest extends PostgisBrowserFixture {
    private static final Path BASEMAP = basemap();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("basemap.assets.directory", BASEMAP::toString);
        registry.add("map.browser-test-hooks", () -> "true");
        registry.add("map.auto-refresh-interval-ms", () -> "1000");
    }
    @RegisterExtension final BrowserHarnessExtension browser = new BrowserHarnessExtension();
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void legacyFixtures() {
        jdbc.update("""
                INSERT INTO auctions(id, auction_number, end_date, starting_price, status, category_name,
                    short_description, municipality, place_name, first_sale, details_fetched)
                SELECT n, 'Н' || n, '2026-08-28T11:00:00Z'::timestamptz, 125000, 'InPrediction', 'Викендица',
                    'Викенд кућа', 'Београд', 'Вождовац', true, false FROM generate_series(179385,179415) n
                """);
        UUID reference = UUID.randomUUID(), geometry = UUID.randomUUID(), attempt = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO property_references(id, auction_id, reference_order, reference_type, source_field,
                    parser_version, extraction_status, canonical_key)
                VALUES (?, 179415, 0, 'STRUCTURED_LOCATION', 'fixture', 'legacy', 'EXTRACTED', 'legacy-ko')
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
                VALUES (?, ?, 'fixture', 'legacy', repeat('a',64), 'fixture', 'v1', repeat('b',64),
                    'RESOLVED', 'CADASTRAL_MUNICIPALITY', ?, 'legacy fallback', '[]'::jsonb,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, attempt, reference, geometry);
        jdbc.update("""
                INSERT INTO current_location_resolutions(property_reference_id, resolution_attempt_id, selected_at, selection_reason)
                VALUES (?, ?, CURRENT_TIMESTAMP, 'retained fallback without source snapshots')
                """, reference, attempt);
    }
    @Test void historicalTableFilterReproductionSortPageSelectionCopyReloadBackForwardAndReset() {
        Page page = browser.page();
        page.navigate(applicationUri() + "?category=Викендица&mapFrom=2026-08-28&mapTo=2026-08-28&mapPrecision=CADASTRAL_MUNICIPALITY&auction=179415");
        ready(page);
        assertThat(page.locator("form").count()).isOne();
        assertThat(page.url()).contains("from=2026-08-28", "timeScope=all", "precision=CADASTRAL_MUNICIPALITY").doesNotContain("mapFrom", "mapPrecision");
        assertThat(page.locator("#shared-results tbody").textContent()).contains("Н179415", "28.08.2026. 13:00", "Центар катастарске општине");
        assertThat(page.locator(".map-popup").textContent()).contains("InPrediction", "Центар катастарске општине");
        page.selectOption("#time-scope-filter", "ended");
        page.fill("#search-filter", "vikend kuca");
        page.fill("#min-price-filter", "120000"); page.fill("#max-price-filter", "130000");
        page.selectOption("#first-sale-filter", "true");
        apply(page);
        assertThat(page.url()).contains("timeScope=ended", "search=vikend", "from=2026-08-28", "to=2026-08-28", "auction=179415");
        assertThat(page.locator("#shared-results tbody tr[data-auction-id]").count()).isOne();
        assertThat(page.locator("#map-result-count").textContent()).isEqualTo("1");
        page.selectOption("#map-precision-filter", "PARCEL"); apply(page);
        assertThat(page.locator("#shared-results tbody").textContent()).contains("Нема резултата");
        assertThat(page.locator("#map-result-count").textContent()).isEqualTo("0");
        assertThat(page.locator("#map-selection").textContent()).contains("Избор је сачуван");
        page.selectOption("#map-precision-filter", ""); apply(page);
        page.locator("#shared-results th a").filter(new com.microsoft.playwright.Locator.FilterOptions().setHasText("Почетна цена")).click();
        ready(page);
        assertThat(page.url()).contains("sortDir=desc", "from=2026-08-28", "to=2026-08-28", "search=vikend");
        page.locator(".pagination a").filter(new com.microsoft.playwright.Locator.FilterOptions().setHasText("Следећа")).click(); ready(page);
        assertThat(page.url()).contains("page=1", "auction=179415", "minPrice=120000");
        assertThat(page.locator("#shared-results tbody tr[data-auction-id]").count()).isEqualTo(6);
        String copied = page.url();
        page.reload(); ready(page); assertThat(page.url()).isEqualTo(copied);
        page.navigate(copied); ready(page); assertThat(page.locator("#map-from-filter").inputValue()).isEqualTo("2026-08-28");
        page.selectOption("#time-scope-filter", "not-ended"); apply(page);
        assertThat(page.locator("#shared-results tbody").textContent()).contains("Нема резултата");
        assertThat(page.url()).contains("page=0");
        page.goBack(); ready(page); assertThat(page.url()).isEqualTo(copied);
        page.goForward(); ready(page); assertThat(page.url()).contains("timeScope=not-ended");
        page.click("#shared-filter-reset"); ready(page);
        assertThat(page.url()).contains("timeScope=not-ended", "auction=179415", "sortDir=desc")
                .doesNotContain("from=", "to=", "precision=", "category=", "search=");
        assertThat(page.locator("#time-scope-filter").inputValue()).isEqualTo("not-ended");
        browser.network().assertOnlyLocalhostRequests();
    }
    @Test void originalUnprefixedTableUrlDefaultsBothViewsToNotEndedAndCanSwitchToHistory() {
        Page page = browser.page();
        page.navigate(applicationUri() + "?municipality=&placeName=&category=Викендица&status=&minPrice=&maxPrice=&firstSale=&search=&sortBy=startingPrice&sortDir=asc");
        ready(page);
        assertThat(page.locator("#time-scope-filter").inputValue()).isEqualTo("not-ended");
        assertThat(page.locator("#map-kind-filter").inputValue()).isEqualTo("Викендица");
        assertThat(page.locator("#shared-results tbody").textContent()).contains("Нема резултата");
        assertThat(page.locator("#map-result-count").textContent()).isEqualTo("0");
        page.selectOption("#time-scope-filter", "ended"); apply(page);
        assertThat(page.locator("#shared-results tbody tr[data-auction-id]").count()).isEqualTo(25);
        assertThat(page.locator("#map-result-count").textContent()).isEqualTo("1");
        assertThat(page.locator("#map-state").textContent()).contains("Филтрирано аукција: 31", "Без локације: 30");
        page.selectOption("#time-scope-filter", "all"); apply(page);
        assertThat(page.locator("#map-kind-filter").inputValue()).isEqualTo("Викендица");
        assertThat(page.locator("#map-result-list").textContent()).contains("Н179415", "Центар катастарске општине");
        browser.network().assertOnlyLocalhostRequests();
    }

    @Test void automaticAndSourceRefreshPreserveAppliedDatesPageSelectionAndUnsavedFormEdits() {
        Page page = browser.page();
        page.navigate(applicationUri() + "?category=Викендица&timeScope=ended&from=2026-08-28&to=2026-08-28&page=1&auction=179415");
        ready(page);
        String applied = page.url();
        String asOf = page.locator("#shared-results").getAttribute("data-as-of");
        page.fill("#search-filter", "несачувана измена");
        page.selectOption("#map-precision-filter", "PARCEL");
        page.waitForFunction("previous => document.querySelector('#shared-results').dataset.asOf !== previous", asOf);
        ready(page);
        assertThat(page.url()).isEqualTo(applied);
        assertThat(page.locator("#search-filter").inputValue()).isEqualTo("несачувана измена");
        assertThat(page.locator("#map-precision-filter").inputValue()).isEqualTo("PARCEL");
        assertThat(page.locator("#map-result-count").textContent()).isEqualTo("1");
        page.evaluate("window.dispatchEvent(new Event('eaukcija:refresh-complete'))"); ready(page);
        assertThat(page.url()).isEqualTo(applied);
        assertThat(page.locator("#shared-results tbody tr[data-auction-id]").count()).isEqualTo(6);
        assertThat(page.locator("#search-filter").inputValue()).isEqualTo("несачувана измена");
        // Invalid prices retain the previous usable map/table and draft, with a field-specific error.
        page.fill("#min-price-filter", "200000"); page.fill("#max-price-filter", "100000");
        page.click("#shared-filters button[type=submit]");
        page.waitForFunction("document.querySelector('#map-state').dataset.state === 'error'");
        assertThat(page.locator("#filter-state").textContent()).contains("maxPrice");
        assertThat(page.locator("#map-result-count").textContent()).isEqualTo("1");
        assertThat(page.locator("#shared-results tbody tr[data-auction-id]").count()).isEqualTo(6);
        browser.network().assertOnlyLocalhostRequests();
    }
    @Test void municipalityDropdownSupportsMultipleChoicesSearchHistoryAndDraftSafeRefresh() {
        jdbc.update("UPDATE auctions SET municipality='ЧАЧАК' WHERE id=179415");
        jdbc.update("UPDATE auctions SET municipality='Нови Сад' WHERE id=179414");
        Page page = browser.page();
        page.navigate(applicationUri() + "?timeScope=ended&from=2026-08-28&to=2026-08-28&page=1&auction=179415");
        ready(page);
        String before = page.url();
        page.locator("#municipality-filter summary").press("Enter");
        assertThat(page.locator("#municipality-options input").count()).isGreaterThanOrEqualTo(168);
        assertThat(page.locator("input[name=municipality][value='Апатин']").count()).isOne(); // No auctions required.
        page.fill("#municipality-search", "cacak");
        page.getByLabel("Чачак", new Page.GetByLabelOptions().setExact(true)).check();
        page.fill("#municipality-search", "novi sad");
        page.getByLabel("Нови Сад", new Page.GetByLabelOptions().setExact(true)).check();
        String asOf = page.locator("#shared-results").getAttribute("data-as-of");
        page.waitForFunction("previous => document.querySelector('#shared-results').dataset.asOf !== previous", asOf);
        ready(page);
        assertThat(page.url()).isEqualTo(before); // Editing options is not applying shared criteria.
        assertThat(page.locator("#municipality-filter").getAttribute("open")).isNotNull();
        assertThat(page.locator("#municipality-search").inputValue()).isEqualTo("novi sad");
        assertThat(page.locator("input[name=municipality]:checked").count()).isEqualTo(2);
        page.locator("#municipality-search").press("Escape");
        assertThat(page.locator("#municipality-filter").getAttribute("open")).isNull();
        assertThat(page.locator("#municipality-filter summary").evaluate("element => element === document.activeElement")).isEqualTo(true);
        apply(page);
        assertThat(page.evaluate("new URLSearchParams(location.search).getAll('municipality')"))
                .isEqualTo(java.util.List.of("Нови Сад", "Чачак"));
        assertThat(page.url()).contains("page=0", "from=2026-08-28", "to=2026-08-28", "auction=179415");
        assertThat(page.locator("#shared-results tbody tr[data-auction-id]").count()).isEqualTo(2);
        assertThat(page.locator("#map-result-count").textContent()).isEqualTo("1");
        assertThat(page.locator("#active-criteria").textContent()).contains("Општине: Нови Сад, Чачак");
        String filtered = page.url();
        page.locator("#shared-results th a").filter(new com.microsoft.playwright.Locator.FilterOptions().setHasText("Почетна цена")).click();
        ready(page);
        page.reload(); ready(page);
        assertThat(page.locator("input[name=municipality]:checked").count()).isEqualTo(2);
        page.goBack(); ready(page);
        assertThat(page.url()).isEqualTo(filtered);
        page.goForward(); ready(page);
        assertThat(page.locator("input[name=municipality]:checked").count()).isEqualTo(2);
        page.click("#municipality-filter summary");
        page.click("#municipality-clear");
        apply(page);
        assertThat(page.url()).doesNotContain("municipality=").contains("timeScope=ended", "from=2026-08-28");
        assertThat(page.locator("#municipality-summary").textContent()).isEqualTo("Све општине");
        assertThat(page.locator("#map-state").textContent()).contains("Филтрирано аукција: 31");
        page.setViewportSize(390, 844);
        page.click("#municipality-filter summary");
        assertThat(page.locator(".municipality-panel").evaluate("element => element.getBoundingClientRect().right <= innerWidth")).isEqualTo(true);
        assertThat(page.evaluate("document.documentElement.scrollWidth <= innerWidth")).isEqualTo(true);
        page.fill("#municipality-search", "no such municipality");
        assertThat(page.locator("#municipality-empty").isVisible()).isTrue();
        browser.network().assertOnlyLocalhostRequests();
    }

    @Test void rapidFilterEditsCannotBeOverwrittenByALateSuccessfulResponse() {
        Page page = browser.page();
        page.addInitScript("""
                const realFetch = window.fetch.bind(window);
                window.fetch = async (input, init) => {
                  const url = new URL(typeof input === 'string' ? input : input.url, location.href);
                  const response = await realFetch(input, init);
                  if (url.pathname === '/api/auctions/view' && url.searchParams.get('search') === 'vikend') {
                    window.__delayedView = true;
                    // Deliberately ignore cancellation after network completion: the sequence guard must still win.
                    await new Promise(resolve => setTimeout(resolve, 1500));
                  }
                  return response;
                };
                """);
        page.navigate(applicationUri() + "?timeScope=all&from=2026-08-28&to=2026-08-28"); ready(page);
        page.fill("#search-filter", "vikend"); page.click("#shared-filters button[type=submit]");
        page.waitForFunction("window.__delayedView === true");
        page.fill("#search-filter", "no-such-description"); apply(page);
        assertThat(page.locator("#map-result-count").textContent()).isEqualTo("0");
        page.waitForTimeout(1800);
        assertThat(page.url()).contains("search=no-such-description", "from=2026-08-28");
        assertThat(page.locator("#shared-results tbody").textContent()).contains("Нема резултата");
        assertThat(page.locator("#map-result-count").textContent()).isEqualTo("0");
        browser.network().assertOnlyLocalhostRequests();
    }

    private static void apply(Page page) { page.click("#shared-filters button[type=submit]"); ready(page); }
    private static void ready(Page page) {
        page.waitForFunction("window.__auctionMap?.ready && ['ready','empty'].includes(window.__auctionMap.getDiagnostics().lastState)");
    }
    private static Path basemap() {
        try {
            Path root = Files.createTempDirectory("shared-filters-browser-");
            Path fixture = Path.of(SharedAuctionFiltersBrowserTest.class.getResource("/fixtures/basemap-bundle").toURI());
            BasemapTestBundle.fromDirectory(root, "issue44", fixture); BasemapTestBundle.activate(root, "issue44"); return root;
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
}
