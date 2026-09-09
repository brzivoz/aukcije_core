package rs.sud.eaukcija.browser;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
import rs.sud.eaukcija.testsupport.ParcelVisibilityFixtures;

/** #47: actual GeoJSON workers/rendering and bounded HTTP over real PostGIS; no public services. */
class ParcelMapBrowserTest extends PostgisBrowserFixture {
    private static final Path BASEMAP = basemap();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("basemap.assets.directory", BASEMAP::toString);
        registry.add("map.browser-test-hooks", () -> "true");
    }

    @RegisterExtension final BrowserHarnessExtension browser = new BrowserHarnessExtension();
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        browser.page().emulateMedia(new Page.EmulateMediaOptions().setReducedMotion(ReducedMotion.REDUCE));
        for (int i = 0; i < ParcelVisibilityFixtures.SHAPES.size(); i++) {
            var shape = ParcelVisibilityFixtures.SHAPES.get(i);
            auction(47001 + i, shape.name());
            location(47001 + i, 0, "PARCEL", shape.wkt());
        }
    }

    @Test
    void everyDifficultParcelStaysDiscoverableFromOverviewThroughMaximumZoomWithExactBoundaries() throws Exception {
        Page page = browser.page();
        for (var shape : ParcelVisibilityFixtures.SHAPES) {
            open("?search=" + shape.name());
            page.evaluate("""
                    async () => {
                      const map = window.__auctionMap.map;
                      window.__marker = (await map.getSource('auction-points').getData()).features[0];
                      window.__boundary = (await map.getSource('auction-areas').getData()).features[0];
                    }
                    """);
            for (double zoom : List.of(7.0, 12.9, 13.0, 16.9, 17.0, 18.0, 19.0, 20.0)) {
                page.evaluate("zoom => { window.__auctionMap.map.jumpTo({center: window.__marker.geometry.coordinates, zoom}); }", zoom);
                refresh(page);
                assertThat(page.locator("#map-result-count").textContent()).isEqualTo("1");
                assertThat(page.locator("#map-count-summary").textContent()).contains("1 аукција / 1 објеката");
                // Worker tiling may drop a sub-pixel fill. The on-geometry marker must still render.
                page.waitForFunction("""
                        () => window.__auctionMap.map.queryRenderedFeatures({layers:['auction-point-parcel']})
                          .some(f => f.properties.mapFeatureId === window.__marker.id)
                        """);
                assertThat(page.evaluate("""
                        async () => {
                          const map = window.__auctionMap.map;
                          const area = (await map.getSource('auction-areas').getData()).features[0];
                          const marker = (await map.getSource('auction-points').getData()).features[0];
                          return JSON.stringify(area.geometry) === JSON.stringify(window.__boundary.geometry)
                            && area.id === marker.id && area.id === window.__boundary.id
                            && marker.properties.precision === 'PARCEL';
                        }
                        """)).isEqualTo(true);
                assertThat(page.evaluate("""
                        () => {
                          const map = window.__auctionMap.map;
                          const visible = map.queryRenderedFeatures({layers:['auction-area-parcel']});
                          return map.getZoom() >= 13 || visible.length === 0;
                        }
                        """)).isEqualTo(true);
                // Real canvas keyboard activation resolves the marker back to the full property geometry.
                page.locator("#auction-map canvas").press("Enter");
                page.waitForSelector(".map-popup");
                assertThat(page.locator(".map-popup").getAttribute("data-feature-id"))
                        .isEqualTo(page.evaluate("window.__boundary.id"));
                assertThat(page.locator(".map-popup").textContent()).contains("није нужно обрис објекта");
                page.keyboard().press("Escape");
                refresh(page);
                page.waitForFunction("window.__auctionMap.map.queryRenderedFeatures({layers:['auction-selected-point']}).length > 0");
                assertThat(page.evaluate("window.__auctionMap.getDiagnostics().detailsOpen")).isEqualTo(false);
            }
        }
        // A near miss activates via pixels, not buffered cadastral geometry, at max zoom.
        open("?search=tiny");
        page.evaluate("""
                async () => {
                  const map = window.__auctionMap.map;
                  const marker = (await map.getSource('auction-points').getData()).features[0];
                  map.jumpTo({center: marker.geometry.coordinates, zoom: 20});
                }
                """);
        refresh(page);
        clickCenter(page, 14, 0);
        page.waitForSelector(".map-popup");
        Path evidence = Path.of("build/browser-test-results/evidence/issue-47-tiny-max-zoom.png");
        Files.createDirectories(evidence.getParent());
        page.screenshot(new Page.ScreenshotOptions().setPath(evidence));
        browser.network().assertOnlyLocalhostRequests();
        assertThat(browser.network().contactedHosts()).containsExactly("localhost");
    }

    @Test
    void separatedClustersExpandButCoincidentMixedPrecisionAndTerminalGroupsOfferKeyboardChoices() {
        Page page = open("");
        page.evaluate("() => { window.__auctionMap.map.jumpTo({center:[20.46,44.789],zoom:7}); }");
        refresh(page);
        page.waitForFunction("window.__auctionMap.renderedClusterCount() > 0");
        page.evaluate("window.__clusterZoom = window.__auctionMap.map.getZoom()");
        clickCenter(page, 0, 0); // The national group is centred within a few screen pixels.
        page.waitForFunction("window.__auctionMap.map.getZoom() > window.__clusterZoom");
        assertThat(page.locator(".map-selection-button").count()).isZero();

        // Two properties of one auction and another auction really share a location, but not precision.
        auction(47100, "coincident");
        auction(47101, "coincident");
        location(47100, 0, "ADDRESS", "POINT(20.46 44.789)");
        location(47100, 1, "CADASTRAL_MUNICIPALITY", "POINT(20.46 44.789)");
        location(47101, 0, "MUNICIPALITY", "POINT(20.46 44.789)");
        open("?search=coincident");
        page.evaluate("() => { window.__auctionMap.map.jumpTo({center:[20.46,44.789],zoom:20}); }");
        refresh(page);
        page.waitForFunction("window.__auctionMap.renderedClusterCount() > 0");
        page.locator("#auction-map canvas").press("Enter");
        page.waitForSelector(".map-selection-button");
        assertThat(page.locator("#map-selection h4").textContent())
                .isEqualTo("3 објеката на овој локацији (2 учитаних аукција)");
        assertThat(page.locator(".map-selection-button").count()).isEqualTo(3);
        page.locator("#map-selection").press("Tab");
        assertThat(page.evaluate("document.activeElement.matches('.map-selection-button')")).isEqualTo(true);
        String id = page.locator(".map-selection-button").nth(1).getAttribute("data-feature-id");
        page.locator(".map-selection-button").nth(1).press("Space");
        assertThat(page.locator(".map-popup").getAttribute("data-feature-id")).isEqualTo(id);
        page.keyboard().press("Escape");
        refresh(page);
        page.waitForFunction("window.__auctionMap.map.queryRenderedFeatures({layers:['auction-selected-point']}).length > 0");
        assertThat(page.locator("#map-result-count").textContent()).isEqualTo("3");
        assertThat(page.locator("#map-count-summary").textContent()).contains("2 аукција / 3 објеката");
        assertThat(page.evaluate("async () => (await window.__auctionMap.map.getSource('auction-areas').getData()).features.length"))
                .isEqualTo(0); // Coarse points never become cadastral parcels.

        // Distinct locations within one max-zoom cluster cannot expand further: chooser, honest label.
        jdbc.update("""
                UPDATE spatial_resolution_geometries SET source_geometry = ST_Translate(source_geometry, 0.000001, 0)
                WHERE id IN (SELECT a.geometry_id FROM location_resolution_attempts a
                  JOIN property_references r ON r.id=a.property_reference_id WHERE r.auction_id=47101)
                """);
        refresh(page);
        page.locator("#auction-map canvas").press("Enter");
        page.waitForSelector(".map-selection-button");
        assertThat(page.locator("#map-selection h4").textContent()).contains("у географској групи").doesNotContain("на овој локацији");
        page.locator(".map-selection-button").last().press("Enter");
        assertThat(page.locator(".map-popup").isVisible()).isTrue();
        browser.network().assertOnlyLocalhostRequests();
    }

    @Test
    void sharedBoundariesAndMarkersDeduplicateSelectionAndOnlyTheChosenSiblingIsHighlighted() {
        String shared = ParcelVisibilityFixtures.SHAPES.get(2).wkt();
        auction(47100, "overlap");
        auction(47101, "overlap");
        location(47100, 0, "PARCEL", shared);
        location(47100, 1, "PARCEL", shared);
        location(47101, 0, "PARCEL", shared);
        Page page = open("?search=overlap");
        page.evaluate("() => { window.__auctionMap.map.jumpTo({center:[20.46025,44.7908],zoom:19}); }");
        refresh(page);
        clickCenter(page, 0, 0); // Away from their shared marker; hit three overlapping canonical boundaries.
        page.waitForSelector(".map-selection-button");
        assertThat(page.locator(".map-selection-button").count()).isEqualTo(3);
        String id = page.locator(".map-selection-button").nth(1).getAttribute("data-feature-id");
        page.locator(".map-selection-button").nth(1).press("Enter");
        assertThat(page.locator(".map-popup").getAttribute("data-feature-id")).isEqualTo(id);
        page.keyboard().press("Escape");
        refresh(page);
        assertThat(page.evaluate("window.__auctionMap.map.getFilter('auction-selected-area')"))
                .isEqualTo(List.of("==", List.of("get", "mapFeatureId"), id));
        assertThat(page.evaluate("async () => (await window.__auctionMap.map.getSource('auction-selection').getData()).features.map(f => f.id)"))
                .isEqualTo(List.of(id));
        assertThat(page.locator("#map-count-summary").textContent()).contains("2 аукција / 3 објеката");
        assertThat(page.locator(".map-result-button[aria-current='true']").count()).isEqualTo(1);
        assertThat(page.locator(".map-result-button[aria-current='true']").getAttribute("data-feature-id")).isEqualTo(id);
        browser.network().assertOnlyLocalhostRequests();
    }

    @Test
    void realBrowserLimitCountsPropertiesOnceAndKeepsTheTerminalChooserBounded() {
        auction(47200, "limited");
        // 1001 distinct property keys in one auction, sharing one geometry; no duplicated representations.
        location(47200, 0, "PARCEL", ParcelVisibilityFixtures.SHAPES.get(0).wkt());
        jdbc.update("""
                INSERT INTO property_references(id, auction_id, reference_order, reference_type, source_field,
                    parser_version, extraction_status, canonical_key)
                SELECT md5('issue47ref' || i)::uuid, 47200, i, 'OTHER', 'fixture', 'issue47', 'EXTRACTED', 'property:' || i
                FROM generate_series(1,1000) i
                """);
        jdbc.update("""
                INSERT INTO location_resolution_attempts(id, property_reference_id, resolver, resolver_version,
                    input_fingerprint, source_dataset, source_dataset_version, source_dataset_sha256,
                    resolution_status, location_precision, geometry_id, confidence_reason, candidate_evidence,
                    attempted_at, completed_at, resolved_at)
                SELECT md5('issue47attempt' || i)::uuid, md5('issue47ref' || i)::uuid, 'fixture', 'issue47',
                    repeat('a',64), 'fixture', 'v1', repeat('b',64), 'RESOLVED', 'PARCEL', a.geometry_id,
                    'limit fixture', '[]'::jsonb, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                FROM generate_series(1,1000) i CROSS JOIN location_resolution_attempts a
                JOIN property_references r ON r.id=a.property_reference_id
                WHERE r.auction_id=47200 AND r.reference_order=0
                """);
        jdbc.update("""
                INSERT INTO current_location_resolutions(property_reference_id, resolution_attempt_id, selected_at, selection_reason)
                SELECT md5('issue47ref' || i)::uuid, md5('issue47attempt' || i)::uuid, CURRENT_TIMESTAMP, 'limit fixture'
                FROM generate_series(1,1000) i
                """);
        Page page = open("?search=limited");
        assertThat(page.locator("#map-result-count").textContent()).isEqualTo("1000");
        assertThat(page.locator("#map-limit-warning").isVisible()).isTrue();
        assertThat(page.locator("#map-count-summary").textContent()).contains("1 аукција / 1001 објеката", "1000 објеката (ограничено)");
        page.evaluate("""
                async () => {
                  const map = window.__auctionMap.map;
                  const marker = (await map.getSource('auction-points').getData()).features[0];
                  map.jumpTo({center:marker.geometry.coordinates, zoom:20});
                }
                """);
        refresh(page);
        page.locator("#auction-map canvas").press("Enter");
        page.waitForSelector(".map-selection-button");
        assertThat(page.locator("#map-selection h4").textContent()).contains("1000 објеката на овој локацији (1 учитаних аукција)");
        assertThat(page.locator(".map-selection-button").count()).isEqualTo(1000);
        assertThat(page.locator("#map-limit-warning").isVisible()).isTrue();
        String selected = page.locator(".map-selection-button").last().getAttribute("data-feature-id");
        page.locator(".map-selection-button").last().press("Enter");
        assertThat(page.locator(".map-popup").getAttribute("data-feature-id")).isEqualTo(selected);
        assertThat(page.evaluate("""
                async () => (await window.__auctionMap.map.getSource('auction-points').getData()).features.length
                """)).isEqualTo(1000);
        assertThat(page.locator("#map-result-count").textContent()).isEqualTo("1000");
        browser.network().assertOnlyLocalhostRequests();
    }

    @Test
    void viewportEdgeReanchorsOnVisiblePartWithoutDroppingThePolygonAndRevocationRemovesBothRepresentations() {
        Page page = open("?search=disjoint");
        page.evaluate("""
                async () => {
                  const map = window.__auctionMap.map;
                  window.__originalBoundary = (await map.getSource('auction-areas').getData()).features[0];
                  window.__originalMarker = (await map.getSource('auction-points').getData()).features[0];
                  map.jumpTo({center:[20.4644,44.7862],zoom:20});
                }
                """);
        refresh(page);
        assertThat(page.locator("#map-result-count").textContent()).isEqualTo("1");
        assertThat(page.evaluate("""
                async () => {
                  const map = window.__auctionMap.map;
                  const area = (await map.getSource('auction-areas').getData()).features[0];
                  const marker = (await map.getSource('auction-points').getData()).features[0];
                  return area.id === window.__originalBoundary.id && marker.id === area.id
                    && JSON.stringify(area.geometry) === JSON.stringify(window.__originalBoundary.geometry)
                    && !map.getBounds().contains(window.__originalMarker.geometry.coordinates)
                    && map.getBounds().contains(marker.geometry.coordinates);
                }
                """)).isEqualTo(true);
        page.locator(".map-result-button").press("Enter");
        page.keyboard().press("Escape");
        jdbc.update("UPDATE property_references SET extraction_status='INVALID' WHERE auction_id=47004");
        refresh(page);
        assertThat(page.locator("#map-result-count").textContent()).isEqualTo("0");
        assertThat(page.evaluate("""
                async () => (await Promise.all(['auction-points','auction-areas','auction-selection']
                    .map(id => window.__auctionMap.map.getSource(id).getData())))
                    .every(data => data.features.length === 0)
                """)).isEqualTo(true);
        browser.network().assertOnlyLocalhostRequests();
    }

    private Page open(String query) {
        Page page = browser.page();
        page.navigate(applicationUri() + query);
        ready(page);
        return page;
    }

    private static void refresh(Page page) {
        page.evaluate("window.__auctionMap.refreshNow()");
        ready(page);
    }

    private static void ready(Page page) {
        page.waitForFunction("""
                window.__auctionMap?.ready && ['ready','empty'].includes(window.__auctionMap.getDiagnostics().lastState)
                  && !window.__auctionMap.getDiagnostics().requestInFlight && !window.__auctionMap.getDiagnostics().pendingRefresh
                  && !window.__auctionMap.map.isMoving() && window.__auctionMap.map.areTilesLoaded()
                """);
    }

    private static void clickCenter(Page page, int offsetX, int offsetY) {
        page.locator("#auction-map canvas").scrollIntoViewIfNeeded();
        @SuppressWarnings("unchecked")
        Map<String, Number> point = (Map<String, Number>) page.evaluate("""
                () => {
                  const b = window.__auctionMap.map.getCanvas().getBoundingClientRect();
                  return {x:b.left + b.width/2, y:b.top + b.height/2};
                }
                """);
        page.mouse().click(point.get("x").doubleValue() + offsetX, point.get("y").doubleValue() + offsetY);
    }

    private void auction(long id, String name) {
        jdbc.update("""
                INSERT INTO auctions(id, auction_number, end_date, starting_price, status, category_name, first_sale, details_fetched)
                VALUES (?, ?, '2099-08-30T08:00:00Z', 100, 'Verified', 'Парцела', false, true)
                """, id, name);
    }

    private void location(long auction, int order, String precision, String wkt) {
        UUID reference = UUID.randomUUID(), geometry = UUID.randomUUID(), attempt = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO property_references(id, auction_id, reference_order, reference_type, source_field,
                    parser_version, extraction_status, canonical_key)
                VALUES (?, ?, ?, 'OTHER', 'fixture', 'issue47', 'EXTRACTED', ?)
                """, reference, auction, order, "property:" + order);
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
                VALUES (?, ?, 'fixture', 'issue47', repeat('a',64), 'fixture', 'v1', repeat('b',64),
                    'RESOLVED', ?, ?, 'visibility fixture', '[]'::jsonb, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, attempt, reference, precision, geometry);
        jdbc.update("""
                INSERT INTO current_location_resolutions(property_reference_id, resolution_attempt_id, selected_at, selection_reason)
                VALUES (?, ?, CURRENT_TIMESTAMP, 'visibility fixture')
                """, reference, attempt);
    }

    private static Path basemap() {
        try {
            Path root = Files.createTempDirectory("parcel-map-browser-");
            Path fixture = Path.of(ParcelMapBrowserTest.class.getResource("/fixtures/basemap-bundle").toURI());
            BasemapTestBundle.fromDirectory(root, "issue47", fixture);
            BasemapTestBundle.activate(root, "issue47");
            return root;
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
