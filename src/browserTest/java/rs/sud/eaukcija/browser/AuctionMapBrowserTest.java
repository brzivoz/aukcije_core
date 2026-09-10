package rs.sud.eaukcija.browser;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.ViewportSize;
import com.microsoft.playwright.options.ReducedMotion;
import com.microsoft.playwright.options.WaitUntilState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import rs.sud.eaukcija.basemap.BasemapTestBundle;
import rs.sud.eaukcija.map.MapAuctionFilterOptions;

class AuctionMapBrowserTest extends PostgisBrowserFixture {

    private static final String BASEMAP_VERSION = "browser-issue-27-v1";
    private static final Path ASSET_ROOT = createAssetRoot();
    private static final List<String> PRECISIONS = List.of(
            "PARCEL", "ADDRESS", "STREET", "CADASTRAL_MUNICIPALITY", "SETTLEMENT", "MUNICIPALITY");

    static {
        URL fixture = AuctionMapBrowserTest.class.getResource("/fixtures/basemap-bundle");
        if (fixture == null) {
            throw new IllegalStateException("compact PMTiles browser fixture is missing");
        }
        try {
            BasemapTestBundle.fromDirectory(ASSET_ROOT, BASEMAP_VERSION, Path.of(fixture.toURI()));
            BasemapTestBundle.activate(ASSET_ROOT, BASEMAP_VERSION);
        } catch (Exception exception) {
            throw new IllegalStateException("could not stage issue #27 basemap fixture", exception);
        }
    }

    @DynamicPropertySource
    static void mapProperties(DynamicPropertyRegistry registry) {
        registry.add("basemap.assets.directory", () -> ASSET_ROOT.toString());
        registry.add("basemap.assets.poll-interval", () -> "PT0.05S");
        registry.add("map.data.stale-after", () -> "PT24H");
        registry.add("map.browser-test-hooks", () -> "true");
    }

    @RegisterExtension
    final BrowserHarnessExtension browser = new BrowserHarnessExtension();

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seedPrecisionMap() {
        Instant end = Instant.now().plus(Duration.ofDays(30));
        jdbc.update("""
                UPDATE auctions
                   SET auction_number = ?, end_date = ?, starting_price = 100000.00,
                       status = 'Verified', category_name = 'Парцела'
                 WHERE id = ?
                """,
                "Н27-001 <img src=x onerror=window.__popupXss=true>",
                Timestamp.from(end),
                SEEDED_AUCTION_ID);

        for (int index = 1; index < PRECISIONS.size(); index++) {
            long auctionId = SEEDED_AUCTION_ID + index;
            jdbc.update("""
                    INSERT INTO auctions (
                        id, auction_number, end_date, starting_price, status,
                        category_name, first_sale, details_fetched
                    ) VALUES (?, ?, ?, ?, 'Verified', ?, false, true)
                    """,
                    auctionId,
                    "Н27-00" + (index + 1),
                    Timestamp.from(end.plus(Duration.ofHours(index))),
                    100000 + index * 10000,
                    index == 1 ? "Кућа" : "Непокретности");
        }

        seedLocation(0, SEEDED_AUCTION_ID, "PARCEL",
                "POLYGON((20.4558 44.7860,20.4570 44.7860,20.4570 44.7872,20.4558 44.7872,20.4558 44.7860))");
        seedLocation(1, SEEDED_AUCTION_ID + 1, "ADDRESS", "POINT(20.4585 44.7890)");
        seedLocation(2, SEEDED_AUCTION_ID + 2, "STREET", "POINT(20.4600 44.7878)");
        seedLocation(3, SEEDED_AUCTION_ID + 3, "CADASTRAL_MUNICIPALITY", "POINT(20.4625 44.7905)");
        seedLocation(4, SEEDED_AUCTION_ID + 4, "SETTLEMENT", "POINT(20.4625 44.7905)");
        seedLocation(5, SEEDED_AUCTION_ID + 5, "MUNICIPALITY", "POINT(20.4625 44.7905)");

        jdbc.update("""
                INSERT INTO coarse_location_resolution_runs (
                    id, started_at, finished_at, resolver_version, extract_version,
                    extract_source_sha256, population_count, processed_count,
                    unchanged_count, cadastral_municipality_count, settlement_count,
                    municipality_count, none_count, municipality_alias_ko_count,
                    structured_ko_status_counts, rationale_counts
                ) VALUES (
                    ?::uuid, CURRENT_TIMESTAMP - INTERVAL '2 minutes',
                    CURRENT_TIMESTAMP - INTERVAL '1 minute',
                    'browser-resolver-v1', 'browser-centroids-v1', repeat('a', 64),
                    6, 6, 0, 4, 1, 1, 0, 0, '{}'::jsonb, '{}'::jsonb
                )
                """, "27000000-0000-0000-0000-000000000099");
    }

    @Test
    void completeMapFlowIsAccessibleSafeClusteredUrlBackedAndResponsive() throws Exception {
        Page page = browser.page();
        page.navigate(applicationUri().toString(),
                new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        waitForReadyMap(page);

        assertThat(page.locator("#auction-map canvas").isVisible()).isTrue();
        assertThat(page.locator("#map-state").getAttribute("data-state")).isEqualTo("ready");
        assertThat(page.locator("#map-result-count").textContent()).isEqualTo("6");
        assertThat(page.locator(".map-legend li").count()).isEqualTo(6);
        assertThat(page.locator("#basemap-version").textContent()).isEqualTo(BASEMAP_VERSION);
        assertThat(page.locator("#map-data-version").textContent())
                .startsWith("browser-resolver-v1/browser-centroids-v1/");
        assertThat(page.locator("#map-last-sync").textContent()).doesNotContain("Није");
        assertThat(page.locator("#map-freshness-warning").isHidden()).isTrue();
        assertThat(page.locator("#map-default-time-note").textContent())
                .contains("Подразумевано", "нису завршене", "познат завршетак");

        assertThat(optionValues(page, "#map-status-filter"))
                .containsExactlyInAnyOrderElementsOf(values(MapAuctionFilterOptions.statuses()));
        assertThat(optionValues(page, "#map-kind-filter"))
                .containsExactlyInAnyOrderElementsOf(values(MapAuctionFilterOptions.kinds()));
        assertThat(optionValues(page, "#map-precision-filter"))
                .containsExactlyElementsOf(values(MapAuctionFilterOptions.precisions()));

        for (String precision : PRECISIONS) {
            assertThat(page.locator(".map-legend li[data-precision='" + precision + "']").count())
                    .isEqualTo(1);
            assertThat(page.locator("#map-result-list li[data-precision='" + precision + "']").count())
                    .isEqualTo(1);
        }
        assertThat(((Number) page.evaluate("""
                window.__auctionMap.map.getStyle().layers
                  .filter(layer => layer.id.startsWith('auction-point-')).length
                """)).intValue()).isEqualTo(6);
        assertThat((Boolean) page.evaluate("""
                () => {
                  const ids = window.__auctionMap.map.getStyle().layers.map(layer => layer.id);
                  const selected = ids.indexOf('auction-selected-area');
                  const precisionLayers = ids
                    .map((id, index) => ({id, index}))
                    .filter(layer => layer.id.startsWith('auction-area-')
                      && layer.id !== 'auction-selected-area');
                  return selected > Math.max(...precisionLayers.map(layer => layer.index));
                }
                """)).isTrue();

        page.waitForFunction("window.__auctionMap.renderedClusterCount() > 0");
        assertThat(((Number) page.evaluate("window.__auctionMap.renderedClusterCount()")).intValue())
                .isPositive();

        Locator unsafeTitle = page.locator(".map-result-button")
                .filter(new Locator.FilterOptions().setHasText("<img src=x onerror=window.__popupXss=true>"));
        int requestsBeforeKeyboardSelection = ((Number) page.evaluate(
                "window.__auctionMap.getDiagnostics().requestsStarted")).intValue();
        unsafeTitle.focus();
        assertThat((Boolean) unsafeTitle.evaluate("element => element.matches(':focus-visible')")).isTrue();
        unsafeTitle.press("Enter");
        page.waitForSelector(".map-popup");

        assertThat(page.url()).contains("auction=34001").doesNotContain("%3Cimg", "onerror");
        assertThat(page.locator(".map-popup").textContent())
                .contains("<img src=x onerror=window.__popupXss=true>")
                .contains("RSD", "Парцела", "Проверена граница", "Verified");
        assertThat(page.locator(".map-popup img").count()).isZero();
        assertThat(page.evaluate("window.__popupXss ?? null")).isNull();
        Locator source = page.locator(".map-popup a[href^='https://eaukcija.sud.rs']");
        assertThat(source.getAttribute("href"))
                .isEqualTo("https://eaukcija.sud.rs/#/aukcije/34001");
        assertThat(source.getAttribute("rel")).isEqualTo("noopener noreferrer");
        Locator maps = page.locator(".map-popup a[href^='https://www.google.com/maps/search/']");
        assertThat(maps.getAttribute("href"))
                .isEqualTo("https://www.google.com/maps/search/?api=1&query=44.786600%2C20.456400");
        assertThat(maps.getAttribute("rel")).isEqualTo("noopener noreferrer");
        assertThat(maps.getAttribute("target")).isEqualTo("_blank");
        Locator selectedSource = page.locator("#map-selection .map-selection-source");
        assertThat(selectedSource.getAttribute("href"))
                .isEqualTo("https://eaukcija.sud.rs/#/aukcije/34001");
        assertThat(selectedSource.getAttribute("rel")).isEqualTo("noopener noreferrer");
        page.waitForFunction("""
                previous => window.__auctionMap.getDiagnostics().requestsStarted > previous
                  && window.__auctionMap.getDiagnostics().lastState === 'ready'
                """, requestsBeforeKeyboardSelection);
        assertThat((Boolean) source.evaluate("element => element === document.activeElement"))
                .isTrue();
        assertThat((Boolean) source.evaluate("""
                element => {
                  const style = getComputedStyle(element);
                  return element.matches(':focus-visible')
                    && style.outlineStyle === 'solid'
                    && parseFloat(style.outlineWidth) >= 3
                    && style.boxShadow !== 'none';
                }
                """)).isTrue();
        assertFocusContrast(page);

        page.reload(new Page.ReloadOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        waitForReadyMap(page);
        assertThat(page.locator(".map-popup").count()).isZero();
        assertThat(page.locator("#map-selection").textContent()).contains("Изабрана аукција");
        page.locator(".map-selection-reopen").press("Enter");
        assertThat(page.locator(".map-popup").isVisible()).isTrue();

        page.locator("#workspace-filter-toggle").click();
        page.locator("#advanced-filters > summary").click();
        page.selectOption("#map-status-filter", "Verified");
        page.locator("#shared-filters button[type='submit']").click();
        page.waitForFunction("""
                window.__auctionMap.getDiagnostics().lastState === 'ready'
                  && window.__auctionMap.getDiagnostics().selectedAuctionId === '34001'
                """);
        assertThat(page.url()).contains("status=Verified", "auction=34001");
        assertThat(page.locator("#map-result-count").textContent()).isEqualTo("6");

        page.waitForTimeout(350);
        page.waitForFunction("window.__auctionMap.getDiagnostics().lastState === 'ready'");
        page.evaluate("""
                () => {
                  const source = window.__auctionMap.map.getSource('auction-points');
                  window.__realClusterLeaves = source.getClusterLeaves.bind(source);
                  source.getClusterLeaves = () => Promise.reject(new Error('stale cluster'));
                }
                """);
        page.evaluate("""
                async () => {
                  const map = window.__auctionMap.map;
                  const cluster = map.queryRenderedFeatures({layers: ['auction-clusters']})[0];
                  if (!cluster) throw new Error('no rendered cluster');
                  await window.__auctionMap.showCluster(cluster);
                }
                """);
        page.waitForSelector("#map-selection[role='alert']");
        assertThat(page.locator("#map-selection").textContent()).contains("Група аукција се променила");
        page.evaluate("""
                () => {
                  window.__auctionMap.map.getSource('auction-points').getClusterLeaves =
                    window.__realClusterLeaves;
                }
                """);
        // Applying filters dismissed transient details but retained the auction selection.
        assertThat(page.locator(".map-popup").count()).isZero();
        clickFirstCluster(page);
        page.waitForSelector("#map-selection:not([hidden]) .map-selection-button");
        assertThat(page.locator("#map-selection h4").textContent())
                .isEqualTo("3 објеката на овој локацији (3 учитаних аукција)");
        assertThat(page.locator("#map-selection .map-selection-button").count()).isEqualTo(3);
        page.waitForFunction("""
                window.__auctionMap.getDiagnostics().lastState === 'ready'
                  && window.__auctionMap.getDiagnostics().pendingRefresh === false
                """);

        Path evidence = evidenceDirectory();
        Files.createDirectories(evidence);
        Path desktop = evidence.resolve("issue-27-auction-map-desktop.png");
        page.locator(".auction-map-panel").screenshot(
                new Locator.ScreenshotOptions().setPath(desktop));
        assertThat(Files.size(desktop)).isGreaterThan(10_000);

        page.setViewportSize(390, 844);
        page.waitForTimeout(250);
        assertThat(((Number) page.evaluate("document.documentElement.scrollWidth")).intValue())
                .isLessThanOrEqualTo(390);
        assertThat((Boolean) page.evaluate("""
                () => {
                  const sidebar = document.querySelector('.map-sidebar').getBoundingClientRect();
                  const canvas = document.querySelector('.map-canvas-frame').getBoundingClientRect();
                  return canvas.bottom <= sidebar.top + 2 && canvas.height >= 390;
                }
                """)).isTrue();
        Path narrow = evidence.resolve("issue-27-auction-map-narrow.png");
        page.waitForFunction("""
                window.__auctionMap.getDiagnostics().lastState === 'ready'
                  && window.__auctionMap.getDiagnostics().pendingRefresh === false
                """);
        page.locator(".auction-map-panel").screenshot(
                new Locator.ScreenshotOptions().setPath(narrow));
        assertThat(Files.size(narrow)).isGreaterThan(10_000);

        clickFirstCluster(page); // Reopen the chooser after the responsive viewport refresh.
        page.locator("#map-selection .map-selection-button").first().press("Enter");
        page.waitForSelector(".map-popup");
        page.keyboard().press("Escape");
        assertThat(page.locator(".map-popup").count()).isZero();
        assertThat(page.evaluate("document.activeElement.matches('.map-result-button')")).isEqualTo(true);

        browser.network().assertOnlyLocalhostRequests();
        assertThat(browser.network().contactedHosts()).containsExactly("localhost");
        assertThat(browser.network().blockedHosts()).isEmpty();

        Map<String, Object> retained = Map.of(
                "basemapVersion", BASEMAP_VERSION,
                "precisions", PRECISIONS,
                "desktop", fileEvidence(desktop),
                "narrow", fileEvidence(narrow),
                "diagnostics", page.evaluate("window.__auctionMap.getDiagnostics()"),
                "contactedHosts", browser.network().contactedHosts());
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(
                evidence.resolve("issue-27-auction-map.json").toFile(), retained);
    }

    @Test
    void viewportRequestsCancelAndKeepPartialErrorAndEmptyStatesVisible() {
        Page page = browser.page();
        page.addInitScript(mockViewportFetchScript());
        page.navigate(applicationUri().toString(),
                new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        page.waitForFunction("window.__auctionMap?.map && window.__mapFetchStarted === 1");
        page.evaluate("""
                () => {
                  const map = window.__auctionMap.map;
                  map.jumpTo({center: [20.4605, 44.7902], zoom: 15});
                }
                """);
        page.waitForFunction("""
                window.__auctionMap?.ready === true
                  && window.__auctionMap.getDiagnostics().lastState === 'ready'
                  && window.__auctionMap.getDiagnostics().requestsCompleted >= 1
                  && !window.__auctionMap.getDiagnostics().pendingRefresh
                  && !window.__auctionMap.getDiagnostics().requestInFlight
                  && window.__auctionMap.map.getCanvas().clientHeight === window.__auctionMap.map.getContainer().clientHeight
                """);

        assertThat(((Number) page.evaluate("window.__mapFetchAborts")).intValue()).isPositive();
        assertThat(((Number) page.evaluate(
                "window.__auctionMap.getDiagnostics().requestsAborted")).intValue()).isPositive();
        assertThat(page.locator("#map-limit-warning").isVisible()).isTrue();
        assertThat(page.locator("#map-state").textContent()).contains("ограничен");
        assertThat(page.locator("#map-result-count").textContent()).isEqualTo("1");
        page.waitForFunction("!window.__auctionMap.getDiagnostics().pendingRefresh && !window.__auctionMap.getDiagnostics().requestInFlight");
        int settledRequests = ((Number) page.evaluate("window.__auctionMap.getDiagnostics().requestsStarted")).intValue();
        page.waitForTimeout(600); // Negative assertion: a wrapped/limit notice must not cause a resize/request loop.
        assertThat(page.evaluate("window.__auctionMap.getDiagnostics().requestsStarted")).isEqualTo(settledRequests);

        int beforeDeferredFilter = ((Number) page.evaluate(
                "window.__auctionMap.getDiagnostics().requestsStarted")).intValue();
        page.evaluate("""
                () => {
                  const map = window.__auctionMap.map;
                  window.__realIsStyleLoaded = map.isStyleLoaded.bind(map);
                  map.isStyleLoaded = () => false;
                }
                """);
        page.locator("#workspace-filter-toggle").click();
        page.locator("#advanced-filters > summary").click();
        page.selectOption("#map-status-filter", "Verified");
        page.locator("#shared-filters button[type='submit']").click();
        assertThat(page.url()).contains("status=Verified");
        assertThat(page.locator("#map-state").getAttribute("data-state")).isEqualTo("loading");
        assertThat((Boolean) page.evaluate(
                "window.__auctionMap.getDiagnostics().pendingRefresh")).isTrue();
        assertThat(((Number) page.evaluate(
                "window.__auctionMap.getDiagnostics().requestsStarted")).intValue())
                .isEqualTo(beforeDeferredFilter);
        page.evaluate("""
                () => {
                  const map = window.__auctionMap.map;
                  window.__mapResponses.push({status: 200, delay: 0, body: {
                    type: 'FeatureCollection',
                    features: [{
                      type: 'Feature', id: '99001:feature',
                      geometry: {type: 'Point', coordinates: [20.4605, 44.7902]},
                      properties: {
                        auctionId: 99001, title: 'Контролисани резултат', amount: 123000,
                        currency: 'RSD', endTime: '2030-08-24T10:00:00Z',
                        sourceStatus: 'Verified', propertyKind: 'Кућа', precision: 'ADDRESS',
                        detailUrl: 'https://eaukcija.sud.rs/#/aukcije/99001'
                      }
                    }],
                    numberReturned: 1, limit: 1000, truncated: false
                  }});
                  map.isStyleLoaded = window.__realIsStyleLoaded;
                  map.fire({type: 'styledata'});
                }
                """);
        page.waitForFunction("""
                expected => window.__auctionMap.getDiagnostics().requestsStarted > expected
                  && window.__auctionMap.getDiagnostics().lastState === 'ready'
                  && window.__auctionMap.getDiagnostics().pendingRefresh === false
                """, beforeDeferredFilter);

        page.evaluate("""
                () => window.__mapResponses.push({
                  status: 400,
                  delay: 0,
                  body: {
                    title: 'Invalid map request', code: 'INVALID_MAP_REQUEST', field: 'bbox',
                    detail: 'bbox area must not exceed 1000000 square kilometres'
                  }
                })
                """);
        page.evaluate("window.__auctionMap.refreshNow()");
        page.waitForFunction("window.__auctionMap.getDiagnostics().lastError === 'MAP_HTTP_400'");
        assertThat(page.locator("#map-state").getAttribute("role")).isEqualTo("alert");
        assertThat(page.locator("#map-state").getAttribute("aria-live")).isEqualTo("assertive");
        assertThat(page.locator("#map-state").textContent())
                .contains("bbox", "1000000", "Промените приказ или филтер")
                .doesNotContain("Покушајте поново");
        int rejectedRequests = ((Number) page.evaluate("window.__auctionMap.getDiagnostics().requestsStarted")).intValue();
        page.waitForTimeout(600);
        assertThat(page.evaluate("window.__auctionMap.getDiagnostics().requestsStarted")).isEqualTo(rejectedRequests);

        page.evaluate("""
                () => window.__mapResponses.push({status: 503, delay: 0})
                """);
        page.evaluate("window.__auctionMap.refreshNow()");
        page.waitForFunction("window.__auctionMap.getDiagnostics().lastError === 'MAP_HTTP_503'");
        assertThat(page.locator("#map-state").textContent())
                .contains("Претходних 1 резултата остаје приказано", "Покушајте поново");
        assertThat(page.locator("#map-result-count").textContent()).isEqualTo("1");

        page.evaluate("""
                () => window.__mapResponses.push({
                  status: 200,
                  delay: 0,
                  body: {
                    type: 'FeatureCollection', features: [], numberReturned: 0,
                    limit: 1000, truncated: false
                  }
                })
                """);
        page.evaluate("window.__auctionMap.refreshNow()");
        page.waitForFunction("window.__auctionMap.getDiagnostics().lastState === 'empty'");
        assertThat(page.locator("#map-state").textContent()).contains("Нема објеката");
        assertThat(page.locator("#map-result-count").textContent()).isEqualTo("0");
        assertThat(page.locator("#map-limit-warning").isHidden()).isTrue();

        page.evaluate("""
                () => window.__mapResponses.push({
                  status: 200,
                  delay: 0,
                  body: {
                    type: 'FeatureCollection',
                    features: [{
                      type: 'Feature', id: '99002:feature',
                      geometry: {type: 'Point', coordinates: [20.4605, 44.7902]},
                      properties: {
                        auctionId: 99002, title: 'Без датума', amount: 100,
                        currency: 'USD', endTime: null, sourceStatus: 'Verified',
                        propertyKind: 'Кућа', precision: 'ADDRESS',
                        detailUrl: 'https://eaukcija.sud.rs/#/aukcije/99002'
                      }
                    }],
                    numberReturned: 1, limit: 1000, truncated: false
                  }
                })
                """);
        page.evaluate("window.__auctionMap.refreshNow()");
        page.waitForFunction("window.__auctionMap.getDiagnostics().lastFeatureCount === 1");
        page.locator(".map-result-button").press("Enter");
        assertThat(page.locator(".map-popup").textContent())
                .contains("Није наведен", "RSD", "Verified")
                .doesNotContain("USD");

        browser.network().assertOnlyLocalhostRequests();
        assertThat(browser.network().contactedHosts()).containsExactly("localhost");
    }

    @Test
    void manyViewportResultsScrollWithoutChangingDesktopMapHeight() {
        Page page = browser.page();
        page.setViewportSize(1280, 900);
        page.addInitScript(manyViewportResultsFetchScript());
        page.navigate(applicationUri().toString(),
                new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        page.waitForFunction("""
                window.__auctionMap?.ready === true
                  && window.__auctionMap.getDiagnostics().lastState === 'ready'
                  && window.__auctionMap.getDiagnostics().lastFeatureCount === 160
                """);

        assertThat(page.locator("#map-result-list > li").count()).isEqualTo(160);
        assertThat(((Number) page.evaluate("window.__rsdNumberFormatConstructions")).intValue())
                .isEqualTo(1);
        @SuppressWarnings("unchecked")
        Map<String, Number> dimensions = (Map<String, Number>) page.evaluate("""
                () => {
                  const layout = document.querySelector('.auction-map-layout');
                  const results = document.querySelector('.map-results');
                  const canvas = document.querySelector('.map-canvas-frame');
                  return {
                    layoutHeight: layout.getBoundingClientRect().height,
                    canvasHeight: canvas.getBoundingClientRect().height,
                    resultsHeight: results.clientHeight,
                    resultsScrollHeight: results.scrollHeight
                  };
                }
                """);
        assertThat(dimensions.get("layoutHeight").doubleValue()).isGreaterThanOrEqualTo(320);
        assertThat(dimensions.get("canvasHeight").doubleValue())
                .isBetween(dimensions.get("layoutHeight").doubleValue() - 2, dimensions.get("layoutHeight").doubleValue());
        assertThat(dimensions.get("resultsScrollHeight").doubleValue())
                .isGreaterThan(dimensions.get("resultsHeight").doubleValue());
        assertThat(dimensions.get("resultsHeight").doubleValue())
                .isLessThanOrEqualTo(dimensions.get("canvasHeight").doubleValue() + 1);

        page.locator(".map-results").evaluate("el => { el.scrollTop = 450; }");
        page.waitForTimeout(50); // Allow the native scroll event to record the user's position.
        for (String mode : List.of("map", "table", "results")) {
            page.locator("#mode-" + mode).click();
            page.evaluate("window.__auctionMap.refreshNow()");
            page.waitForFunction("window.__auctionMap.getDiagnostics().lastState === 'ready' && !window.__auctionMap.getDiagnostics().requestInFlight");
        }
        assertThat(page.locator(".map-results").evaluate("el => el.scrollTop")).isEqualTo(450);
        page.locator("#workspace-filter-toggle").click();
        page.evaluate("window.__auctionMap.refreshNow()");
        assertThat(page.locator(".map-results").evaluate("el => el.scrollTop")).isEqualTo(450);

        browser.network().assertOnlyLocalhostRequests();
        assertThat(browser.network().contactedHosts()).containsExactly("localhost");
    }

    @Test
    void zoomingToResponsiveMinimumStaysWithinTheApiAreaContract() {
        Page page = browser.page();
        page.setViewportSize(1600, 900);
        page.navigate(applicationUri().toString(),
                new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        waitForReadyMap(page);

        assertThat((Boolean) page.evaluate("""
                () => {
                  const map = window.__auctionMap.map;
                  return map.getPitch() === 0
                    && map.getBearing() === 0
                    && map.dragRotate.isEnabled() === false
                    && map.touchPitch.isEnabled() === false
                    && map.touchZoomRotate.isEnabled() === true
                    && map.touchZoomRotate._rotationDisabled === true
                    && map.keyboard._rotationDisabled === true;
                }
                """)).isTrue();

        int requestsBefore = ((Number) page.evaluate(
                "window.__auctionMap.getDiagnostics().requestsStarted")).intValue();
        double minimum = ((Number) page.evaluate("window.__auctionMap.map.getMinZoom()"))
                .doubleValue();
        assertThat(minimum).isGreaterThan(5.0);
        page.evaluate("""
                () => {
                  const map = window.__auctionMap.map;
                  map.jumpTo({zoom: map.getMinZoom()});
                }
                """);
        page.waitForFunction("""
                previous => window.__auctionMap.getDiagnostics().requestsStarted > previous
                  && window.__auctionMap.getDiagnostics().lastState === 'ready'
                  && window.__auctionMap.getDiagnostics().pendingRefresh === false
                """, requestsBefore);

        Map<String, Object> diagnostics = diagnostics(page);
        assertThat(((Number) diagnostics.get("lastRequestAreaSquareKm")).doubleValue())
                .isLessThanOrEqualTo(1_000_000);
        assertThat(page.locator("#map-state").textContent())
                .doesNotContain("Није могуће", "Покушајте поново");
        browser.network().assertOnlyLocalhostRequests();
    }

    @Test
    void desktopWorkspaceFillsTheViewportAndDisclosuresAndModesAreKeyboardOperable() throws Exception {
        Page page = browser.page();
        page.emulateMedia(new Page.EmulateMediaOptions().setReducedMotion(ReducedMotion.REDUCE));
        page.setViewportSize(1366, 768);
        page.navigate(applicationUri().toString());
        waitForReadyMap(page);
        Path evidence = evidenceDirectory();
        Files.createDirectories(evidence);
        var measurements = new java.util.LinkedHashMap<String, Object>();
        for (ViewportSize size : List.of(new ViewportSize(1366, 768), new ViewportSize(1920, 1080),
                new ViewportSize(2560, 1080))) {
            page.setViewportSize(size.width, size.height);
            page.waitForFunction("""
                    () => {
                      const map = window.__auctionMap.map, container = map.getContainer(), canvas = map.getCanvas();
                      return canvas.clientWidth === container.clientWidth && canvas.clientHeight === container.clientHeight && map.areTilesLoaded();
                    }
                    """);
            waitForReadyMap(page);
            @SuppressWarnings("unchecked")
            Map<String, Number> bounds = (Map<String, Number>) page.evaluate("""
                    () => {
                      const rect = selector => document.querySelector(selector).getBoundingClientRect();
                      const map = rect('#auction-map'), rail = rect('.map-sidebar'), container = rect('#workspace');
                      const attribution = rect('#auction-map .maplibregl-ctrl-attrib');
                      return {mapWidth: map.width, mapHeight: map.height, mapTop: map.top, mapBottom: map.bottom,
                        mapRight: map.right, attributionBottom: attribution.bottom, attributionRight: attribution.right,
                        railWidth: rail.width, containerWidth: container.width, scrollWidth: document.documentElement.scrollWidth};
                    }
                    """);
            assertThat(bounds.get("containerWidth").doubleValue()).isEqualTo(size.width);
            assertThat(bounds.get("railWidth").doubleValue()).isBetween(320.0, 380.0);
            assertThat(bounds.get("mapWidth").doubleValue()).isGreaterThan(size.width - 430.0);
            assertThat(bounds.get("mapHeight").doubleValue()).isGreaterThanOrEqualTo(320);
            assertThat(bounds.get("mapBottom").doubleValue()).isBetween(size.height - 24.0, (double) size.height);
            assertThat(page.locator("#shared-filters").isHidden()).isTrue();
            assertThat(bounds.get("mapHeight").doubleValue()).isGreaterThanOrEqualTo(size.height * .75);
            assertThat(bounds.get("attributionBottom").doubleValue()).isLessThanOrEqualTo(bounds.get("mapBottom").doubleValue());
            assertThat(bounds.get("attributionRight").doubleValue()).isLessThanOrEqualTo(bounds.get("mapRight").doubleValue());
            assertThat(bounds.get("scrollWidth").intValue()).isLessThanOrEqualTo(size.width);
            assertThat(page.locator("#refresh-details").getAttribute("open")).isNull();
            assertThat(page.locator("#map-reference").getAttribute("open")).isNull();
            assertThat(page.locator("#auction-map .maplibregl-ctrl-attrib").isVisible()).isTrue();
            String label = size.width + "x" + size.height;
            Path image = evidence.resolve("issue-45-workspace-" + label + ".png");
            page.screenshot(new Page.ScreenshotOptions().setPath(image));
            measurements.put(label, Map.of("bounds", bounds, "screenshot", fileEvidence(image)));
        }
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(
                evidence.resolve("issue-45-workspace-bounds.json").toFile(), measurements);

        page.locator("#workspace-rail-toggle").focus();
        page.locator("#workspace-rail-toggle").press("Space");
        assertThat(page.locator("#workspace-rail-toggle").getAttribute("aria-expanded")).isEqualTo("false");
        assertThat(page.locator("#mode-map").getAttribute("aria-pressed")).isEqualTo("true");
        assertThat(page.locator(".map-sidebar").isHidden()).isTrue();
        assertThat(page.locator("#workspace-rail-toggle").evaluate("el => el === document.activeElement && el.matches(':focus-visible')")).isEqualTo(true);
        page.locator("#workspace-rail-toggle").press("Enter");
        assertThat(page.locator(".map-sidebar").isVisible()).isTrue();
        page.locator("#mode-table").press("Enter");
        assertThat(page.locator("#table-view").isVisible()).isTrue();
        assertThat(page.locator("#auction-map canvas").isHidden()).isTrue();
        page.locator("#mode-table").press("Tab");
        assertThat(page.locator("#workspace-filter-toggle").evaluate("el => el === document.activeElement")).isEqualTo(true);
        page.locator("#mode-results").press("Space");
        page.locator("#map-reference summary").press("Enter");
        assertThat(page.locator(".map-legend li").first().isVisible()).isTrue();
        assertThat(page.locator("#basemap-version").isVisible()).isTrue();
        page.locator("#map-reference summary").press("Escape");
        assertThat(page.locator("#map-reference").getAttribute("open")).isNull();
        assertThat(page.locator("#auction-map-description").isVisible()).isTrue();
        page.locator(".map-result-button").first().press("Enter");
        assertThat(page.locator("#map-selection").textContent()).contains("Парцела", "Проверена граница");
        page.locator("#mode-map").press("Enter");
        assertThat(page.locator("#map-selection").isHidden()).isTrue();
        assertThat(page.evaluate("matchMedia('(prefers-reduced-motion: reduce)').matches")).isEqualTo(true);
        assertThat(page.locator("#mode-map").evaluate("el => getComputedStyle(el).transitionDuration")).isEqualTo("0s");

        // 200% zoom's CSS viewport equivalent, plus a small phone-width window.
        for (ViewportSize size : List.of(new ViewportSize(683, 384), new ViewportSize(390, 844))) {
            page.setViewportSize(size.width, size.height);
            page.locator("#mode-table").press("Enter");
            waitForReadyMap(page);
            assertThat(page.evaluate("document.documentElement.scrollWidth <= innerWidth")).isEqualTo(true);
            assertThat(page.locator(".table-scroll").evaluate("el => el.scrollWidth > el.clientWidth")).isEqualTo(true);
            page.locator(".table-scroll").focus();
            page.locator(".table-scroll").press("ArrowRight");
            page.waitForFunction("document.querySelector('.table-scroll').scrollLeft > 0");
            page.evaluate("window.__auctionMap.refreshNow()");
            waitForReadyMap(page);
            assertThat(page.locator(".table-scroll").evaluate("el => el.scrollLeft > 0 && el === document.activeElement")).isEqualTo(true);
            page.locator("#mode-results").press("Enter");
            assertThat(page.evaluate("document.documentElement.scrollWidth <= innerWidth")).isEqualTo(true);
        }
        browser.network().assertOnlyLocalhostRequests();
    }

    @Test
    void railModeAndViewportResizingRecalculateTheMinimumAndNeverExceedTheApiCeiling() {
        Page page = browser.page();
        var areas = new java.util.ArrayList<Double>();
        var statuses = new java.util.ArrayList<Integer>();
        page.onResponse(response -> {
            if (!response.url().contains("/api/auctions/view?")) return;
            statuses.add(response.status());
            String bbox = java.net.URLDecoder.decode(response.url().split("bbox=")[1].split("&")[0], java.nio.charset.StandardCharsets.UTF_8);
            double[] b = java.util.Arrays.stream(bbox.split(",")).mapToDouble(Double::parseDouble).toArray();
            areas.add(6371.0088 * 6371.0088 * Math.toRadians(b[2] - b[0])
                    * Math.abs(Math.sin(Math.toRadians(b[3])) - Math.sin(Math.toRadians(b[1]))));
        });
        page.setViewportSize(1366, 768);
        page.navigate(applicationUri().toString());
        waitForReadyMap(page);
        var minima = new java.util.ArrayList<Double>();
        for (String action : List.of(
                "window.__auctionMap.map.jumpTo({zoom: window.__auctionMap.map.getMinZoom()})",
                "document.querySelector('#workspace-rail-toggle').click()",
                "document.querySelector('#workspace-filter-toggle').click()")) {
            int before = ((Number) page.evaluate("window.__auctionMap.getDiagnostics().requestsCompleted")).intValue();
            page.evaluate("() => {" + action + ";}");
            page.waitForFunction("before => window.__auctionMap.getDiagnostics().requestsCompleted > before", before);
            waitForReadyMap(page);
            minima.add(((Number) page.evaluate("window.__auctionMap.map.getMinZoom()")).doubleValue());
        }
        assertThat(minima.get(1)).isGreaterThan(minima.get(0)); // Closing the results rail widens the map.
        assertThat(minima.get(2)).isLessThan(minima.get(1)); // Opening the side form narrows it again.
        page.locator("#mode-table").press("Enter");
        page.setViewportSize(2560, 1440);
        page.waitForFunction("window.__auctionMap.map.getCanvas().clientWidth === document.querySelector('#auction-map').clientWidth");
        page.evaluate("() => { window.__auctionMap.map.jumpTo({zoom: window.__auctionMap.map.getMinZoom()}); }");
        page.evaluate("window.__auctionMap.refreshNow()");
        waitForReadyMap(page);
        page.locator("#mode-results").press("Enter");
        page.evaluate("window.__auctionMap.refreshNow()");
        waitForReadyMap(page);
        assertThat(areas).hasSizeGreaterThanOrEqualTo(5).allSatisfy(area -> assertThat(area).isBetween(1.0, 1_000_000.0));
        assertThat(statuses).allSatisfy(status -> assertThat(status).isEqualTo(200));
        assertThat(page.evaluate("window.__auctionMap.getDiagnostics().lastError")).isNull();
        browser.network().assertOnlyLocalhostRequests();
    }

    @Test
    void lateClusterChoicesCannotReplaceANewerExplicitPropertySelection() {
        Page page = browser.page();
        page.navigate(applicationUri().toString());
        waitForReadyMap(page);
        page.waitForFunction("window.__auctionMap.renderedClusterCount() > 0");
        page.evaluate("""
                () => {
                  const map = window.__auctionMap.map;
                  const source = map.getSource('auction-points');
                  source.getClusterLeaves = () => new Promise(resolve => { window.__finishLeaves = resolve; });
                  window.__pendingCluster = window.__auctionMap.showCluster(map.queryRenderedFeatures({layers:['auction-clusters']})[0]);
                }
                """);
        page.locator(".map-result-button").first().press("Enter");
        page.waitForSelector(".map-popup");
        page.evaluate("window.__focused = document.activeElement");
        page.evaluate("async () => { window.__finishLeaves([]); await window.__pendingCluster; }");
        assertThat(page.locator(".map-popup").isVisible()).isTrue();
        assertThat(page.locator(".map-selection-button").count()).isZero();
        assertThat(page.evaluate("window.__auctionMap.getDiagnostics().detailsOpen")).isEqualTo(true);
        assertThat(page.evaluate("window.__focused === document.activeElement && window.__focused.isConnected")).isEqualTo(true);
        browser.network().assertOnlyLocalhostRequests();
    }

    @Test
    void precisionContractFailureNamesTheApplicationContractNotTheBasemap() {
        Page page = browser.page();
        page.addInitScript("""
                (() => {
                  const observer = new MutationObserver(() => {
                    const option = document.querySelector(
                      '#map-precision-filter option[value="MUNICIPALITY"]');
                    if (option) {
                      option.remove();
                      observer.disconnect();
                    }
                  });
                  observer.observe(document, {childList: true, subtree: true});
                })();
                """);
        page.navigate(applicationUri().toString(),
                new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        page.waitForFunction("window.__auctionMap?.ready === true");

        assertThat(page.locator("#map-state").getAttribute("data-state")).isEqualTo("error");
        assertThat(page.locator("#map-state").textContent())
                .contains("Дефиниције прецизности карте нису усклађене", "грешка верзије апликације")
                .doesNotContain("основне карте", "PMTiles");
        assertThat(page.evaluate("window.__auctionMap.map")).isNull();
        assertThat(page.evaluate("window.__auctionMap.getDiagnostics().lastError"))
                .isEqualTo("MAP_PRECISION_CONTRACT_MISMATCH");
        browser.network().assertOnlyLocalhostRequests();
    }

    @Test
    void staleLastGoodMapRemainsExplicitOutsideCollapsedDiagnosticsInEveryMode() {
        Page page = browser.page();
        page.route("**/api/map/status", route -> route.fulfill(new com.microsoft.playwright.Route.FulfillOptions()
                .setContentType("application/json").setBody("""
                        {"available":true,"stale":true,"dataVersion":"retained-last-good",
                         "lastSuccessfulSync":"2026-08-21T09:00:00Z"}
                        """)));
        page.setViewportSize(1366, 768);
        page.navigate(applicationUri().toString());
        waitForReadyMap(page);
        String lastGood = page.locator("#map-last-sync").textContent();
        for (String mode : List.of("map", "table", "results")) {
            page.locator("#mode-" + mode).press("Enter");
            assertThat(page.locator("#map-reference").getAttribute("open")).isNull();
            assertThat(page.locator("#refresh-details").getAttribute("open")).isNull();
            assertThat(page.locator("#map-freshness-warning").isVisible()).isTrue();
            assertThat(page.locator("#map-freshness-warning").textContent()).contains("старији", lastGood);
            assertThat(page.locator("#refresh-start").isVisible()).isTrue();
        }
        browser.network().assertOnlyLocalhostRequests();
    }

    @Test
    void mapResourceErrorsAreClassifiedAndClearWhenTheirSourceRecovers() {
        Page page = browser.page();
        page.navigate(applicationUri().toString(),
                new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        waitForReadyMap(page);

        @SuppressWarnings("unchecked")
        Map<String, Object> auctionWarning = (Map<String, Object>) page.evaluate("""
                () => {
                  window.__auctionMap.map.fire({
                    type: 'error', sourceId: 'auction-points',
                    error: new Error('controlled GeoJSON source failure')
                  });
                  return {
                    text: document.querySelector('#map-freshness-warning').textContent,
                    errors: window.__auctionMap.getDiagnostics().auctionSourceErrors
                  };
                }
                """);
        assertThat(auctionWarning.get("text").toString())
                .contains("Слој аукција је пријавио привремени проблем")
                .doesNotContain("Основна карта је пријавила");
        assertThat(((Number) auctionWarning.get("errors")).intValue()).isEqualTo(1);

        page.evaluate("""
                () => {
                  window.__auctionMap.map.fire({
                    type: 'sourcedata', sourceId: 'auction-points',
                    sourceDataType: 'idle', isSourceLoaded: true
                  });
                }
                """);
        assertThat(page.locator("#map-freshness-warning").isHidden()).isTrue();

        @SuppressWarnings("unchecked")
        Map<String, Object> basemapWarning = (Map<String, Object>) page.evaluate("""
                () => {
                  window.__auctionMap.map.fire({
                    type: 'error', sourceId: 'serbia', tile: {id: 'controlled'},
                    error: new Error('controlled basemap tile failure')
                  });
                  return {
                    text: document.querySelector('#map-freshness-warning').textContent,
                    errors: window.__auctionMap.getDiagnostics().basemapErrors
                  };
                }
                """);
        assertThat(basemapWarning.get("text").toString())
                .contains("Основна карта је пријавила привремени проблем")
                .doesNotContain("Слој аукција је пријавио");
        assertThat(((Number) basemapWarning.get("errors")).intValue()).isEqualTo(1);

        page.evaluate("""
                () => {
                  window.__auctionMap.map.fire({
                    type: 'sourcedata', sourceId: 'serbia',
                    sourceDataType: 'idle', isSourceLoaded: true
                  });
                }
                """);
        assertThat(page.locator("#map-freshness-warning").isHidden()).isTrue();
        assertThat(page.evaluate("window.__auctionMap.getDiagnostics().activeResourceWarnings"))
                .isEqualTo(0);

        @SuppressWarnings("unchecked")
        Map<String, Object> genericWarning = (Map<String, Object>) page.evaluate("""
                () => {
                  window.__auctionMap.map.fire({
                    type: 'error', error: new Error('controlled generic resource failure')
                  });
                  return {
                    text: document.querySelector('#map-freshness-warning').textContent,
                    active: window.__auctionMap.getDiagnostics().activeResourceWarnings
                  };
                }
                """);
        assertThat(genericWarning.get("text").toString())
                .contains("Карта је пријавила привремени проблем са ресурсом")
                .doesNotContain("Основна карта", "Слој аукција");
        assertThat(((Number) genericWarning.get("active")).intValue()).isEqualTo(1);
        page.evaluate("""
                () => {
                  window.__auctionMap.map.fire({type: 'styledata'});
                }
                """);
        assertThat(page.locator("#map-freshness-warning").isVisible()).isTrue();
        assertThat(page.locator("#map-freshness-warning").textContent())
                .contains("Карта је пријавила привремени проблем са ресурсом");
        assertThat(page.evaluate("window.__auctionMap.getDiagnostics().activeResourceWarnings"))
                .isEqualTo(1);
        browser.network().assertOnlyLocalhostRequests();
    }

    @Test
    void initialStyleFailureRejectsImmediatelyButTileFailureRemainsRecoverable() {
        Page page = browser.page();
        page.navigate(applicationUri().toString(),
                new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        waitForReadyMap(page);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) page.evaluate("""
                async () => {
                  class ControlledMap {
                    constructor() {
                      this.listeners = new Map();
                    }
                    loaded() { return false; }
                    isStyleLoaded() { return false; }
                    on(type, listener) {
                      const listeners = this.listeners.get(type) || [];
                      listeners.push(listener);
                      this.listeners.set(type, listeners);
                    }
                    off(type, listener) {
                      this.listeners.set(type,
                        (this.listeners.get(type) || []).filter(value => value !== listener));
                    }
                    fire(event) {
                      for (const listener of [...(this.listeners.get(event.type) || [])]) {
                        listener(event);
                      }
                    }
                  }

                  const fatalMap = new ControlledMap();
                  const started = performance.now();
                  const fatalPromise = window.__auctionMap.waitForMapLoad(fatalMap, 250);
                  fatalMap.fire({
                    type: 'error', sourceId: 'serbia',
                    error: new Error('controlled style source failure')
                  });
                  let fatalError = null;
                  try {
                    await fatalPromise;
                  } catch (error) {
                    fatalError = error.message;
                  }

                  const basemapErrorsBefore =
                    window.__auctionMap.getDiagnostics().basemapErrors;
                  const recoverableMap = new ControlledMap();
                  const recoverablePromise = window.__auctionMap.waitForMapLoad(
                    recoverableMap, 250);
                  recoverableMap.fire({
                    type: 'error', sourceId: 'serbia', tile: {id: 'controlled'},
                    error: new Error('controlled tile failure')
                  });
                  recoverableMap.fire({type: 'load'});
                  await recoverablePromise;
                  const diagnosticsAfterLoad = window.__auctionMap.getDiagnostics();
                  const warningAfterLoad =
                    document.querySelector('#map-freshness-warning').textContent;

                  // Clear the controlled warning through the only valid recovery
                  // signal: the same concrete source reports that it loaded.
                  window.__auctionMap.map.fire({
                    type: 'sourcedata', sourceId: 'serbia',
                    sourceDataType: 'idle', isSourceLoaded: true
                  });

                  return {
                    fatalError,
                    elapsedMs: performance.now() - started,
                    recoverableResolved: true,
                    basemapErrorsBefore,
                    basemapErrorsAfter: diagnosticsAfterLoad.basemapErrors,
                    warningAfterLoad,
                    warningsAfterRecovery:
                      window.__auctionMap.getDiagnostics().activeResourceWarnings
                  };
                }
                """);

        assertThat(result.get("fatalError")).isEqualTo("BASEMAP_STYLE_LOAD_FAILED");
        assertThat(((Number) result.get("elapsedMs")).doubleValue()).isLessThan(200.0);
        assertThat(result.get("recoverableResolved")).isEqualTo(true);
        assertThat(((Number) result.get("basemapErrorsAfter")).intValue())
                .isEqualTo(((Number) result.get("basemapErrorsBefore")).intValue() + 1);
        assertThat(result.get("warningAfterLoad").toString())
                .contains("Основна карта је пријавила привремени проблем");
        assertThat(result.get("warningsAfterRecovery")).isEqualTo(0);
        browser.network().assertOnlyLocalhostRequests();
    }

    private void seedLocation(int index, long auctionId, String precision, String wkt) {
        String reference = uuid(index, 1);
        String geometry = uuid(index, 2);
        String attempt = uuid(index, 3);
        String hashCharacter = Integer.toHexString(index + 1);
        jdbc.update("""
                INSERT INTO property_references (
                    id, auction_id, reference_order, reference_type, source_field,
                    parser_version, extraction_status, canonical_key
                ) VALUES (?::uuid, ?, 0, ?, 'browser-map-fixture',
                          'browser-map-v1', 'EXTRACTED', ?)
                """,
                reference,
                auctionId,
                precision.equals("PARCEL") ? "PARCEL" : "STRUCTURED_LOCATION",
                "map-" + precision.toLowerCase());
        jdbc.update("""
                INSERT INTO spatial_resolution_geometries (
                    id, source_geometry, source_crs_authority, source_crs_code,
                    original_geometry_valid, make_valid_applied
                ) VALUES (?::uuid, ST_GeomFromText(?, 4326), 'EPSG', 4326, true, false)
                """, geometry, wkt);
        jdbc.update("""
                INSERT INTO location_resolution_attempts (
                    id, property_reference_id, resolver, resolver_version,
                    input_fingerprint, source_dataset, source_dataset_version,
                    source_dataset_sha256, source_feature_id, resolution_status,
                    location_precision, geometry_id, confidence_reason,
                    candidate_evidence, attempted_at, completed_at, resolved_at
                ) VALUES (
                    ?::uuid, ?::uuid, 'browser-map', 'v1', ?, 'fixture', 'v1', ?, ?,
                    'RESOLVED', ?, ?::uuid, 'issue 27 browser fixture', '[]'::jsonb,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                )
                """,
                attempt,
                reference,
                hashCharacter.repeat(64),
                Integer.toHexString(index + 9).substring(0, 1).repeat(64),
                "fixture-" + index,
                precision,
                geometry);
        jdbc.update("""
                INSERT INTO current_location_resolutions (
                    property_reference_id, resolution_attempt_id, selected_at, selection_reason
                ) VALUES (?::uuid, ?::uuid, CURRENT_TIMESTAMP, 'issue 27 browser fixture')
                """, reference, attempt);
    }

    private static String uuid(int index, int kind) {
        return "27000000-0000-0000-000" + kind + "-" + String.format("%012d", index + 1);
    }

    private static void waitForReadyMap(Page page) {
        page.waitForFunction("""
                window.__auctionMap?.ready === true
                  && window.__auctionMap.getDiagnostics().lastState === 'ready'
                  && window.__auctionMap.getDiagnostics().lastFeatureCount === 6
                  && !window.__auctionMap.getDiagnostics().pendingRefresh
                  && !window.__auctionMap.getDiagnostics().requestInFlight
                  && window.__auctionMap.map.getCanvas().clientHeight === window.__auctionMap.map.getContainer().clientHeight
                """, null, new Page.WaitForFunctionOptions().setTimeout(30_000));
    }

    private static void clickFirstCluster(Page page) {
        page.locator("#auction-map").scrollIntoViewIfNeeded();
        @SuppressWarnings("unchecked")
        Map<String, Number> point = (Map<String, Number>) page.evaluate("""
                () => {
                  const map = window.__auctionMap.map;
                  const feature = map.queryRenderedFeatures({layers: ['auction-clusters']})[0];
                  if (!feature) throw new Error('no rendered cluster');
                  const projected = map.project(feature.geometry.coordinates);
                  const bounds = document.getElementById('auction-map').getBoundingClientRect();
                  return {x: bounds.left + projected.x, y: bounds.top + projected.y};
                }
                """);
        page.mouse().click(point.get("x").doubleValue(), point.get("y").doubleValue());
    }

    @SuppressWarnings("unchecked")
    private static List<String> optionValues(Page page, String selector) {
        return (List<String>) page.evaluate("""
                selector => [...document.querySelector(selector).options]
                  .map(option => option.value)
                  .filter(Boolean)
                """, selector);
    }

    private static List<String> values(List<MapAuctionFilterOptions.Option> options) {
        return options.stream().map(MapAuctionFilterOptions.Option::value).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> diagnostics(Page page) {
        return (Map<String, Object>) page.evaluate("window.__auctionMap.getDiagnostics()");
    }

    @SuppressWarnings("unchecked")
    private static void assertFocusContrast(Page page) {
        Map<String, Number> ratios = (Map<String, Number>) page.evaluate("""
                () => {
                  const parse = value => value.match(/[0-9.]+/g).slice(0, 3).map(Number);
                  const resolveColor = value => {
                    const probe = document.createElement('span');
                    probe.style.color = value;
                    document.body.append(probe);
                    const resolved = getComputedStyle(probe).color;
                    probe.remove();
                    return resolved;
                  };
                  const luminance = value => {
                    const channels = parse(value).map(channel => {
                      const normalized = channel / 255;
                      return normalized <= .04045
                        ? normalized / 12.92
                        : ((normalized + .055) / 1.055) ** 2.4;
                    });
                    return .2126 * channels[0] + .7152 * channels[1] + .0722 * channels[2];
                  };
                  const contrast = (first, second) => {
                    const light = Math.max(luminance(first), luminance(second));
                    const dark = Math.min(luminance(first), luminance(second));
                    return (light + .05) / (dark + .05);
                  };
                  const root = getComputedStyle(document.documentElement);
                  const inner = resolveColor(root.getPropertyValue('--map-focus-inner').trim());
                  const outer = resolveColor(root.getPropertyValue('--map-focus-outer').trim());
                  const background = selector => getComputedStyle(document.querySelector(selector))
                    .backgroundColor;
                  return {
                    result: contrast(outer, background('.map-result-button')),
                    select: contrast(outer, background('#map-status-filter')),
                    date: contrast(outer, background('#map-from-filter')),
                    selection: contrast(outer, background('#map-selection')),
                    popup: contrast(outer, background('.map-sidebar')),
                    primary: contrast(inner, background('#shared-filters button[type="submit"]')),
                    twoTone: contrast(inner, outer)
                  };
                }
                """);
        assertThat(ratios)
                .allSatisfy((surface, ratio) -> assertThat(ratio.doubleValue())
                        .as("focus indicator contrast on %s", surface)
                        .isGreaterThanOrEqualTo(3.0));
    }

    private static String mockViewportFetchScript() {
        return viewFixtureAdapter() + """
                (() => {
                  const originalFetch = window.fetch.bind(window);
                  const feature = {
                    type: 'Feature', id: '99001:feature',
                    geometry: {type: 'Point', coordinates: [20.4605, 44.7902]},
                    properties: {
                      auctionId: 99001, title: 'Контролисани резултат', amount: 123000,
                      currency: 'RSD', endTime: '2030-08-24T10:00:00Z',
                      sourceStatus: 'Verified', propertyKind: 'Кућа', precision: 'ADDRESS',
                      detailUrl: 'https://eaukcija.sud.rs/#/aukcije/99001'
                    }
                  };
                  window.__mapResponses = [
                    {status: 200, delay: 1500, body: {
                      type: 'FeatureCollection', features: [feature], numberReturned: 1,
                      limit: 1000, truncated: false
                    }},
                    {status: 200, delay: 0, body: {
                      type: 'FeatureCollection', features: [feature], numberReturned: 1,
                      limit: 1000, truncated: true
                    }}
                  ];
                  window.__mapFetchStarted = 0;
                  window.__mapFetchAborts = 0;
                  window.fetch = (input, init = {}) => {
                    const url = new URL(typeof input === 'string' ? input : input.url, location.href);
                    if (url.pathname !== '/api/auctions/view') return originalFetch(input, init);
                    window.__mapFetchStarted++;
                    // Layout-only follow-up requests see the same controlled backend
                    // state until this test explicitly queues the next transition.
                    const response = window.__mapResponses.shift() || window.__lastMapResponse;
                    window.__lastMapResponse = response ? {...response, delay: 0} : null;
                    if (!response) return originalFetch(input, init);
                    return new Promise((resolve, reject) => {
                      const finish = () => resolve(new Response(
                        JSON.stringify(response.status === 200 ? window.__viewFixture(response.body, url) : response.body || {error: 'controlled'}),
                        {status: response.status, headers: {'Content-Type': 'application/geo+json'}}));
                      const timer = setTimeout(finish, response.delay || 0);
                      const abort = () => {
                        clearTimeout(timer);
                        window.__mapFetchAborts++;
                        reject(new DOMException('Aborted', 'AbortError'));
                      };
                      if (init.signal?.aborted) abort();
                      else init.signal?.addEventListener('abort', abort, {once: true});
                    });
                  };
                })();
                """;
    }

    private static String viewFixtureAdapter() {
        return """
                window.__viewFixture = (map, url) => {
                  url.searchParams.delete('bbox'); url.searchParams.delete('limit');
                  const sourceFrame = JSON.parse(document.getElementById('shared-results').dataset.sourceFrame);
                  return {map: {...map, sourceFrame, counts: {
                    filteredAuctionCount: map.features.length, unmappedAuctionCount: 0,
                    mappedAuctionCountInViewport: map.features.length, featureCountInViewport: map.features.length
                  }}, query: url.searchParams.toString(),
                  resultsHtml: document.getElementById('shared-results').outerHTML};
                };
                """;
    }

    private static String manyViewportResultsFetchScript() {
        return viewFixtureAdapter() + """
                (() => {
                  window.__rsdNumberFormatConstructions = 0;
                  Intl.NumberFormat = new Proxy(Intl.NumberFormat, {
                    construct(target, args) {
                      const options = args[1];
                      if (options?.style === 'currency' && options?.currency === 'RSD') {
                        window.__rsdNumberFormatConstructions++;
                      }
                      return Reflect.construct(target, args);
                    },
                    apply(target, thisArg, args) {
                      const options = args[1];
                      if (options?.style === 'currency' && options?.currency === 'RSD') {
                        window.__rsdNumberFormatConstructions++;
                      }
                      return Reflect.apply(target, thisArg, args);
                    }
                  });
                  const originalFetch = window.fetch.bind(window);
                  const precisions = [
                    'PARCEL', 'ADDRESS', 'STREET', 'CADASTRAL_MUNICIPALITY',
                    'SETTLEMENT', 'MUNICIPALITY'
                  ];
                  const features = Array.from({length: 160}, (_, index) => {
                    const auctionId = 99000 + index;
                    return {
                      type: 'Feature',
                      id: `${auctionId}:feature`,
                      geometry: {
                        type: 'Point',
                        coordinates: [20.455 + (index % 10) * .001, 44.785 + (index % 8) * .001]
                      },
                      properties: {
                        auctionId,
                        title: `Контролисани резултат ${index + 1}`,
                        amount: 100000 + index,
                        currency: 'RSD',
                        endTime: '2030-08-24T10:00:00Z',
                        sourceStatus: 'Verified',
                        propertyKind: 'Кућа',
                        precision: precisions[index % precisions.length],
                        detailUrl: `https://eaukcija.sud.rs/#/aukcije/${auctionId}`
                      }
                    };
                  });
                  window.fetch = (input, init = {}) => {
                    const url = new URL(typeof input === 'string' ? input : input.url, location.href);
                    if (url.pathname !== '/api/auctions/view') return originalFetch(input, init);
                    return Promise.resolve(new Response(JSON.stringify(window.__viewFixture({
                      type: 'FeatureCollection',
                      features,
                      numberReturned: features.length,
                      limit: 1000,
                      truncated: false
                    }, url)), {
                      status: 200,
                      headers: {'Content-Type': 'application/geo+json'}
                    }));
                  };
                })();
                """;
    }

    private static Path evidenceDirectory() {
        return Path.of(System.getProperty(
                "browser.artifact.dir", "build/browser-test-results/artifacts"))
                .resolveSibling("evidence");
    }

    private static Map<String, Object> fileEvidence(Path path) throws Exception {
        return Map.of(
                "filename", path.getFileName().toString(),
                "sizeBytes", Files.size(path),
                "sha256", HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
    }

    private static Path createAssetRoot() {
        try {
            return Files.createTempDirectory("aukcije-issue-27-basemap-browser-");
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
