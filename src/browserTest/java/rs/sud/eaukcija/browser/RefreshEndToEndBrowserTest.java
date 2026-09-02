package rs.sud.eaukcija.browser;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitUntilState;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import rs.sud.eaukcija.basemap.BasemapTestBundle;
import rs.sud.eaukcija.coarselocation.CentroidTestArtifact;
import rs.sud.eaukcija.komatching.KoDictionaryTestArtifact;
import rs.sud.eaukcija.testsupport.Fixtures;

/** Real browser + HTTP fixture + PostGIS proof for the complete one-click path. */
class RefreshEndToEndBrowserTest extends PostgisBrowserFixture {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MockWebServer SOURCE = sourceServer();
    private static final Path CENTROIDS = centroids();
    private static final Path DICTIONARY = dictionary();
    private static final String BASEMAP_VERSION = "browser-issue-40-v1";
    private static final Path BASEMAP_ROOT = basemap();

    @DynamicPropertySource
    static void workflowProperties(DynamicPropertyRegistry registry) {
        registry.add("eaukcija.api.base-url",
                () -> SOURCE.url("/WebApi.Proxy/api/EAukcija").uri().toString());
        registry.add("eaukcija.api.allow-http-loopback-test", () -> "true");
        registry.add("eaukcija.api.requests-per-second", () -> "10");
        registry.add("eaukcija.refresh.schedule-cron", () -> "-");
        registry.add("eaukcija.refresh.poll-interval", () -> "PT0.05S");
        registry.add("eaukcija.enrichment.schedule-cron", () -> "-");
        registry.add("coarse.location.centroid-directory", CENTROIDS::toString);
        registry.add("ko.structured-match.dictionary-directory", DICTIONARY::toString);
        registry.add("basemap.assets.directory", BASEMAP_ROOT::toString);
        registry.add("basemap.assets.poll-interval", () -> "PT0.05S");
        registry.add("map.browser-test-hooks", () -> "true");
    }

    @RegisterExtension
    final BrowserHarnessExtension browser = new BrowserHarnessExtension();

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void startWithoutPreseededAuctionsOrSuccessfulMapRun() {
        jdbc.execute("""
                TRUNCATE TABLE
                    coarse_location_resolution_runs, refresh_runs,
                    enrichment_run_items, enrichment_state, enrichment_runs,
                    auction_enrichment_snapshot_observations,
                    auction_enrichment_input_snapshots,
                    sync_enrichment_queue, sync_run_listing_quarantines,
                    sync_run_detail_quarantines, sync_run_auction_observations,
                    auction_source_category_memberships, sync_run_errors,
                    sync_run_child_results, sync_run_root_results,
                    auction_structured_ko_matches, auctions, sync_runs,
                    eaukcija_taxonomies
                RESTART IDENTITY CASCADE
                """);
        jdbc.update("""
                UPDATE enrichment_control
                   SET paused = FALSE, changed_at = CURRENT_TIMESTAMP,
                       change_code = 'BROWSER_RESET'
                 WHERE singleton
                """);
    }

    @AfterAll
    static void stopSource() throws IOException {
        SOURCE.shutdown();
    }

    @Test
    void oneBrowserActionRunsFixtureSourceAndLocalEnrichmentUntilMapStatusIsCorrelated() {
        Page page = browser.page();
        page.navigate(applicationUri().toString(), new Page.NavigateOptions()
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        page.waitForFunction("document.querySelector('#refresh-status')?.textContent === 'Освежавање није покренуто.'");
        assertThat(page.locator("#map-last-sync").textContent())
                .matches("Учитавање…|Није забележено");

        page.locator("#refresh-start").click();
        page.waitForFunction("document.querySelector('#refresh-status')?.textContent.includes('Карта је спремна')");

        assertThat(page.locator("#refresh-result").textContent())
                .contains("1 од 1", "центар КО: 1");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM refresh_runs ORDER BY started_at DESC LIMIT 1", String.class))
                .isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("""
                SELECT source_sync_run_id IS NOT NULL
                   AND enrichment_run_id IS NOT NULL
                   AND map_resolution_run_id IS NOT NULL
                  FROM refresh_runs ORDER BY started_at DESC LIMIT 1
                """, Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM coarse_location_resolution_runs map_run
                  JOIN refresh_runs refresh ON refresh.id = map_run.refresh_run_id
                 WHERE refresh.status = 'SUCCEEDED'
                   AND refresh.map_resolution_run_id = map_run.id
                """, Long.class)).isOne();

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> status = (java.util.Map<String, Object>) page.evaluate("""
                async () => await (await fetch('/api/map/status', {cache: 'no-store'})).json()
                """);
        assertThat(status.get("available")).isEqualTo(true);
        assertThat(status.get("lastSuccessfulSync")).isNotNull();
        assertThat(((Number) status.get("mappedAuctionCount")).intValue()).isEqualTo(1);

        page.waitForFunction("window.__auctionMap?.ready === true && window.__auctionMap.map !== null");
        page.evaluate("() => { window.__auctionMap.map.jumpTo({center: [21.2, 44.2], zoom: 14}); }");
        page.waitForFunction("window.__auctionMap.getDiagnostics().lastFeatureCount === 1");
        assertThat(page.locator("#map-result-count").textContent()).isEqualTo("1");
        assertThat(page.locator("#map-result-list").textContent()).contains("Н40-001");
        assertThat(SOURCE.getRequestCount()).isGreaterThanOrEqualTo(9);
        browser.network().assertOnlyLocalhostRequests();
    }

    private static MockWebServer sourceServer() {
        try {
            MockWebServer server = new MockWebServer();
            server.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) {
                    String path = request.getPath();
                    if (path == null) {
                        return response(404, "{}");
                    }
                    if (path.endsWith("/GetCategories")) {
                        return response(200, Fixtures.read("eaukcija/categories.json"));
                    }
                    if (path.endsWith("/GetAuctionsByCategoryId")) {
                        String body = request.getBody().readUtf8();
                        boolean populated = body.contains("\"CategoryId\":7")
                                || body.contains("\"CategoryId\":47");
                        return response(200, populated ? listing() : emptyListing());
                    }
                    if (path.endsWith("/GetImmovablePropertyDetails")) {
                        return response(200, detail());
                    }
                    return response(404, "{}");
                }
            });
            server.start();
            return server;
        } catch (IOException failure) {
            throw new IllegalStateException("could not start source fixture", failure);
        }
    }

    private static MockResponse response(int status, String body) {
        return new MockResponse()
                .setResponseCode(status)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }

    private static String listing() {
        return """
                {"ResultCode":"0","ResultMessage":"OK","Data":{"TotalCount":1,"Auctions":[{
                  "Id":40001,"AuctionNumber":"Н40-001",
                  "StartDate":"2026-08-25T08:00:00Z","EndDate":"2026-09-10T12:00:00Z",
                  "StartingPrice":100000.00,"CurrentPrice":null,"MaxOfferedPrice":null,
                  "ShortDescription":"парцела у КО ГРАД","Status":"Verified",
                  "IsFirstSale":true,"PropertyType":"ImmovableProperties"}]}}
                """;
    }

    private static String emptyListing() {
        return """
                {"ResultCode":"0","ResultMessage":"OK","Data":{"TotalCount":0,"Auctions":[]}}
                """;
    }

    private static String detail() {
        try {
            JsonNode root = MAPPER.readTree(Fixtures.read("eaukcija/immovable-property-detail.json"));
            ObjectNode data = (ObjectNode) root.get("Data");
            data.put("Id", 40001);
            data.put("AuctionNumber", "Н40-001");
            data.put("StartDate", "2026-08-25T08:00:00Z");
            data.put("EndDate", "2026-09-10T12:00:00Z");
            data.put("ShortDescription", "парц. бр. 1572, КО ГРАД");
            data.put("Description",
                    "Непокретност у КО ГРАД, катастарска парцела број 1572.");
            ObjectNode place = (ObjectNode) data.get("Place");
            place.put("Name", "Насеље Б");
            place.put("Municipality", "Општина Б-град");
            place.put("Cadastral", "ГРАД");
            return MAPPER.writeValueAsString(root);
        } catch (IOException failure) {
            throw new IllegalStateException("could not prepare detail fixture", failure);
        }
    }

    private static Path centroids() {
        try {
            return CentroidTestArtifact.create(
                    Files.createTempDirectory("issue-40-centroids-"), MAPPER);
        } catch (Exception failure) {
            throw new IllegalStateException("could not create centroid fixture", failure);
        }
    }

    private static Path dictionary() {
        try {
            return KoDictionaryTestArtifact.create(
                    Files.createTempDirectory("issue-40-dictionary-"), MAPPER);
        } catch (Exception failure) {
            throw new IllegalStateException("could not create dictionary fixture", failure);
        }
    }

    private static Path basemap() {
        URL fixture = RefreshEndToEndBrowserTest.class.getResource("/fixtures/basemap-bundle");
        if (fixture == null) {
            throw new IllegalStateException("compact PMTiles browser fixture is missing");
        }
        try {
            Path root = Files.createTempDirectory("issue-40-basemap-");
            BasemapTestBundle.fromDirectory(root, BASEMAP_VERSION, Path.of(fixture.toURI()));
            BasemapTestBundle.activate(root, BASEMAP_VERSION);
            return root;
        } catch (Exception failure) {
            throw new IllegalStateException("could not stage issue #40 basemap fixture", failure);
        }
    }
}
