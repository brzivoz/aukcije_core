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
                .contains("само аукције које се још нису завршиле", "тренутку захтева");

        assertThat(optionValues(page, "#map-status-filter"))
                .containsExactlyElementsOf(values(MapAuctionFilterOptions.statuses()));
        assertThat(optionValues(page, "#map-kind-filter"))
                .containsExactlyElementsOf(values(MapAuctionFilterOptions.kinds()));
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
        unsafeTitle.focus();
        assertThat((Boolean) unsafeTitle.evaluate("element => element.matches(':focus-visible')")).isTrue();
        unsafeTitle.press("Enter");
        page.waitForSelector(".maplibregl-popup .map-popup");

        assertThat(page.url()).contains("auction=34001").doesNotContain("%3Cimg", "onerror");
        assertThat(page.locator(".map-popup").textContent())
                .contains("<img src=x onerror=window.__popupXss=true>")
                .contains("RSD", "Парцела", "Проверена граница", "Проверено")
                .doesNotContain("Verified");
        assertThat(page.locator(".map-popup img").count()).isZero();
        assertThat(page.evaluate("window.__popupXss ?? null")).isNull();
        Locator source = page.locator(".map-popup a");
        assertThat(source.getAttribute("href"))
                .isEqualTo("https://eaukcija.sud.rs/#/aukcije/34001");
        assertThat(source.getAttribute("rel")).isEqualTo("noopener noreferrer");
        Locator selectedSource = page.locator("#map-selection .map-selection-source");
        assertThat(selectedSource.getAttribute("href"))
                .isEqualTo("https://eaukcija.sud.rs/#/aukcije/34001");
        assertThat(selectedSource.getAttribute("rel")).isEqualTo("noopener noreferrer");
        assertThat((Boolean) selectedSource.evaluate("element => element === document.activeElement"))
                .isTrue();
        assertThat((Boolean) selectedSource.evaluate("""
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
        assertThat(page.locator(".map-popup").isVisible()).isTrue();
        assertThat(page.locator("#map-selection").textContent()).contains("Изабрана аукција");

        page.selectOption("#map-status-filter", "Verified");
        page.locator("#map-filters button[type='submit']").click();
        page.waitForFunction("""
                window.__auctionMap.getDiagnostics().lastState === 'ready'
                  && window.__auctionMap.getDiagnostics().selectedAuctionId === null
                """);
        assertThat(page.url()).contains("mapStatus=Verified").doesNotContain("auction=");
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
        clickFirstCluster(page);
        page.waitForSelector("#map-selection:not([hidden]) .map-selection-button");
        assertThat(page.locator("#map-selection h4").textContent())
                .isEqualTo("3 аукција на овој локацији");
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
                  return sidebar.bottom <= canvas.top + 2 && canvas.height >= 390;
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
                """);

        assertThat(((Number) page.evaluate("window.__mapFetchAborts")).intValue()).isPositive();
        assertThat(((Number) page.evaluate(
                "window.__auctionMap.getDiagnostics().requestsAborted")).intValue()).isPositive();
        assertThat(page.locator("#map-limit-warning").isVisible()).isTrue();
        assertThat(page.locator("#map-state").textContent()).contains("ограничен");
        assertThat(page.locator("#map-result-count").textContent()).isEqualTo("1");

        int beforeDeferredFilter = ((Number) page.evaluate(
                "window.__auctionMap.getDiagnostics().requestsStarted")).intValue();
        page.evaluate("""
                () => {
                  const map = window.__auctionMap.map;
                  window.__realIsStyleLoaded = map.isStyleLoaded.bind(map);
                  map.isStyleLoaded = () => false;
                }
                """);
        page.selectOption("#map-status-filter", "Verified");
        page.locator("#map-filters button[type='submit']").click();
        assertThat(page.url()).contains("mapStatus=Verified");
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
        assertThat(page.locator("#map-state").textContent()).contains("нема аукција");
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
                .contains("Није наведен", "RSD", "Проверено")
                .doesNotContain("USD", "Verified");

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
                    popup: contrast(outer, background('.maplibregl-popup-content')),
                    primary: contrast(inner, background('#map-filters button[type="submit"]')),
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
        return """
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
                    if (url.pathname !== '/api/map/auctions') return originalFetch(input, init);
                    window.__mapFetchStarted++;
                    const response = window.__mapResponses.shift();
                    if (!response) return originalFetch(input, init);
                    return new Promise((resolve, reject) => {
                      const finish = () => resolve(new Response(
                        JSON.stringify(response.body || {error: 'controlled'}),
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
