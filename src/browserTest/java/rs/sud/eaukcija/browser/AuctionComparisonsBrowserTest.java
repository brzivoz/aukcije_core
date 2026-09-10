package rs.sud.eaukcija.browser;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import rs.sud.eaukcija.basemap.BasemapTestBundle;
import rs.sud.eaukcija.sync.persistence.SourcePublicationFixture;

class AuctionComparisonsBrowserTest extends PostgisBrowserFixture {
    private static final Path BASEMAP = basemap();
    private static final String KEY = "eaukcija.comparisons.v1";
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("basemap.assets.directory", BASEMAP::toString); r.add("map.browser-test-hooks", () -> "true");
        r.add("map.auto-refresh-interval-ms", () -> "0");
    }
    @RegisterExtension final BrowserHarnessExtension browser = new BrowserHarnessExtension();
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource ds;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager tx;
    SourcePublicationFixture fixture;
    Instant t;
    @BeforeEach void publications() throws Exception {
        jdbc.execute("TRUNCATE auctions, sync_runs CASCADE");
        fixture = new SourcePublicationFixture(ds, json, tx);
        t = Instant.now().minusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        fixture.publish(t, fixture.candidate(5601, "private A"), fixture.candidate(5602, "private A"));
        SourcePublicationFixture.point(jdbc, 5601, "POINT(20.46 44.79)");
        SourcePublicationFixture.point(jdbc, 5602, "POINT(20.461 44.79)");
        browser.page().addInitScript("localStorage.setItem('eaukcija.workspace.v1.filters','true')");
    }
    @org.junit.jupiter.api.AfterEach void clearPublications() { jdbc.execute("TRUNCATE auctions, sync_runs CASCADE"); }
    @Test void explicitCheckpointCopiedLinkChipsDraftSafeRefreshAndResetLeaveAcknowledgementsAlone() throws Exception {
        Page page = browser.page(); page.navigate(applicationUri().toString()); ready(page);
        page.locator("#comparison-tools summary").click();
        page.locator("#checkpoint-save").press("Enter");
        page.waitForFunction("JSON.parse(localStorage.getItem('" + KEY + "'))?.checkpoint");
        String checkpoint = (String) page.evaluate("JSON.stringify(JSON.parse(localStorage.getItem('" + KEY + "')).checkpoint)");
        page.locator("#map-result-list .map-result-button").first().press("Enter");
        assertThat(page.evaluate("Object.keys(JSON.parse(localStorage.getItem('" + KEY + "')).reviews).length")).isEqualTo(0);
        page.locator(".map-popup .mark-reviewed").press("Enter");
        page.waitForFunction("Object.keys(JSON.parse(localStorage.getItem('" + KEY + "')).reviews).length===1");
        fixture.publish(t.plusSeconds(1), fixture.candidate(5601, "private B"), fixture.candidate(5602, "private A"));
        page.selectOption("#changes-since", "checkpoint"); apply(page);
        assertThat(page.url()).contains("since=publication", "publication=", "sinceAt=").doesNotContain("since=checkpoint", "review");
        assertThat(page.locator("#map-result-list .change-badge").textContent()).isEqualTo("Измењена");
        assertThat(page.locator("#map-comparison-summary").textContent()).contains("Нове: 0", "Измењене: 1");
        assertThat(page.locator("#applied-filter-chips [data-field=since]").textContent()).contains("Сачувана тачка");
        String copied = page.url();
        page.fill("#search-filter", "несачуван нацрт");
        page.evaluate("window.__auctionMap.refreshNow()"); ready(page);
        assertThat(page.locator("#search-filter").inputValue()).isEqualTo("несачуван нацрт");
        assertThat(page.locator("#filter-dirty-indicator").isVisible()).isTrue();
        assertThat(page.url()).isEqualTo(copied);
        assertThat(page.evaluate("JSON.stringify(JSON.parse(localStorage.getItem('" + KEY + "')).checkpoint)")).isEqualTo(checkpoint);
        page.reload(); ready(page); assertThat(page.url()).isEqualTo(copied);
        page.locator("#applied-filter-chips [data-field=since]").press("Enter"); ready(page);
        assertThat(page.url()).doesNotContain("since=", "publication=", "sinceAt=");
        page.goBack(); ready(page); assertThat(page.url()).isEqualTo(copied);
        page.goForward(); ready(page);
        page.click("#shared-filter-reset"); ready(page);
        assertThat(page.evaluate("Object.keys(JSON.parse(localStorage.getItem('" + KEY + "')).reviews).length")).isEqualTo(1);
        assertThat(page.evaluate("JSON.stringify(JSON.parse(localStorage.getItem('" + KEY + "')).checkpoint)")).isEqualTo(checkpoint);
        browser.network().assertOnlyLocalhostRequests();
    }
    @Test void reviewOlderDisplayedContentCannotAcknowledgeNewerUnseenChangeAndReviewedViewRelaxesScope() throws Exception {
        Page page = browser.page(); page.navigate(applicationUri().toString()); ready(page);
        page.locator("#map-result-list .map-result-button").first().press("Enter");
        ready(page);
        var old = page.locator(".map-popup .mark-reviewed").getAttribute("data-review");
        fixture.publish(t.plusSeconds(1), fixture.candidate(5601, "private B", "101", "10", Instant.now().minusSeconds(10), "Completed"), fixture.candidate(5602, "private A"));
        page.locator(".map-popup .mark-reviewed").press("Enter");
        page.waitForFunction("JSON.parse(localStorage.getItem('" + KEY + "')).reviews['5601']");
        assertThat(json.readTree((String) page.evaluate("JSON.stringify(JSON.parse(localStorage.getItem('" + KEY + "')).reviews['5601'].review)"))).isEqualTo(json.readTree(old));
        page.evaluate("window.__auctionMap.refreshNow()"); ready(page);
        assertThat(page.locator("#map-result-list .map-result-button[data-auction-id='5601']").count()).isZero();
        page.locator("#comparison-tools summary").click(); page.locator("#reviewed-toggle").press("Enter");
        page.waitForFunction("document.querySelector('#reviewed-list').textContent.includes('Промењена од прегледа')");
        assertThat(page.locator("#reviewed-results").textContent()).contains("БЕЗ садашњих филтера", "Завршена по познатом року", "Почетна цена");
        page.onDialog(dialog -> dialog.accept());
        page.locator("#reviewed-list .mark-reviewed").press("Enter");
        page.waitForFunction("document.querySelector('#reviewed-list li')===null");
        page.locator("#reviews-clear").press("Enter");
        page.waitForFunction("Object.keys(JSON.parse(localStorage.getItem('" + KEY + "')).reviews).length===0");
        browser.network().assertOnlyLocalhostRequests();
    }
    @Test void firstUsePreviousVisitFreezeBlockedStorageAndChosenDateKeyboardAtNarrowWidth() {
        Page page = browser.page(); page.setViewportSize(390, 844); page.navigate(applicationUri().toString()); ready(page);
        page.locator("#comparison-tools summary").click();
        assertThat(page.locator("#previous-visit-state").textContent()).contains("Нема претходне посете");
        page.selectOption("#changes-since", "previous"); page.locator("#shared-filters button[type=submit]").press("Enter");
        assertThat(page.locator("#comparison-feedback").textContent()).contains("Нема доступне тачке");
        assertThat(page.url()).doesNotContain("since=previous");
        page.reload(); ready(page);
        assertThat(page.locator("#previous-visit-state").textContent()).contains("Нема претходне посете");
        // New tab session: latest successful display from the preceding tab; later refresh does not move it.
        try (Page next = page.context().newPage()) {
            next.navigate(applicationUri().toString()); ready(next);
            assertThat(next.locator("#previous-visit-state").textContent()).contains("Претходна посета:");
            String previous = next.locator("#previous-visit-state").textContent();
            next.reload(); ready(next); assertThat(next.locator("#previous-visit-state").textContent()).isEqualTo(previous);
        }
        page.selectOption("#changes-since", "date");
        page.locator("#shared-filters button[type=submit]").press("Enter");
        page.waitForFunction("document.querySelector('#map-state').dataset.state==='error'");
        assertThat(page.locator("#since-local").evaluate("el => el === document.activeElement")).isEqualTo(true);
        page.fill("#since-local", "2026-08-24T12:30"); apply(page);
        assertThat(page.url()).contains("since=date", "sinceAt=2026-08-24T10%3A30%3A00Z").doesNotContain("sinceLocal");
        assertThat(page.locator("#filter-dirty-indicator").isVisible()).isFalse();
        assertThat(page.evaluate("document.documentElement.scrollWidth <= innerWidth + 1")).isEqualTo(true);
        page.addInitScript("Object.defineProperty(window, 'localStorage', {get(){throw new Error('blocked')}})");
        page.reload(); ready(page);
        assertThat(page.locator("#comparison-feedback").textContent()).contains("није доступно");
        assertThat(page.locator("#map-result-list .map-result-button").count()).isEqualTo(2);
    }
    @Test void ordinaryDetailsExplainTheirOwnReviewedBaselineAndIncompleteReviewResponsesAreNotZeroChanges() throws Exception {
        Page page = browser.page(); page.navigate(applicationUri().toString()); ready(page);
        page.locator("#map-result-list .map-result-button").first().press("Enter"); ready(page);
        var oldReview = json.readTree(page.locator(".map-popup .mark-reviewed").getAttribute("data-review"));
        // The action begins on old content; publication and DOM replacement happen before its click.
        page.locator(".map-popup .mark-reviewed").dispatchEvent("keydown", java.util.Map.of("key", " "));
        fixture.publish(t.plusSeconds(1), fixture.candidate(5601, "private B"), fixture.candidate(5602, "private A"));
        page.evaluate("window.__auctionMap.refreshNow()"); ready(page);
        assertThat(json.readTree(page.locator(".map-popup .mark-reviewed").getAttribute("data-review"))).isNotEqualTo(oldReview);
        page.locator(".map-popup .mark-reviewed").dispatchEvent("click");
        page.waitForFunction("JSON.parse(localStorage.getItem('" + KEY + "')).reviews['5601']");
        assertThat(json.readTree((String) page.evaluate("JSON.stringify(JSON.parse(localStorage.getItem('" + KEY + "')).reviews['5601'].review)"))).isEqualTo(oldReview);
        page.waitForFunction("document.querySelector('.map-popup .review-reasons')?.textContent.includes('Опис')");
        assertThat(page.locator(".map-popup .review-state").textContent()).contains("Промењена од прегледа");
        assertThat(page.locator(".map-popup").textContent()).doesNotContain("private A", "private B");
        fixture.publish(t.plusSeconds(2), fixture.candidate(5601, "private A"), fixture.candidate(5602, "private A"));
        page.evaluate("window.__auctionMap.refreshNow()"); ready(page);
        assertThat(page.locator(".map-popup .review-reasons").textContent()).contains("враћене на почетне");
        page.route("**/api/auctions/reviews", route -> {
            try {
                var request = json.readTree(route.request().postData());
                var invalid = json.createObjectNode(); invalid.set("frame", request.path("frame")); invalid.putObject("evidence");
                route.fulfill(new com.microsoft.playwright.Route.FulfillOptions().setContentType("application/json").setBody(json.writeValueAsString(invalid)));
            } catch (java.io.IOException e) { throw new IllegalStateException(e); }
        });
        page.evaluate("window.__auctionMap.refreshNow()"); ready(page);
        assertThat(page.locator(".map-popup .review-state").textContent()).contains("није доступно");
        assertThat(page.locator("#reviewed-status").textContent()).contains("НЕ значи да нема промена");
        assertThat(page.evaluate("Object.keys(JSON.parse(localStorage.getItem('" + KEY + "')).reviews).length")).isEqualTo(1);
        browser.network().assertOnlyLocalhostRequests();
    }
    @Test void mixedPublicationRefreshKeepsLastCoherentDisplayAndCheckpointCannotSkipTheUnseenPublication() throws Exception {
        Page page = browser.page(); page.navigate(applicationUri().toString()); ready(page);
        page.waitForFunction("JSON.parse(localStorage.getItem('" + KEY + "'))?.lastDisplay");
        var original = json.readTree(page.locator("#shared-results").getAttribute("data-source-frame"));
        fixture.publish(t.plusSeconds(1), fixture.candidate(5601, "private B"), fixture.candidate(5602, "private A"));
        page.route("**/api/auctions/view?*", route -> {
            var response = route.fetch();
            try {
                var view = json.readTree(response.body());
                ((com.fasterxml.jackson.databind.node.ObjectNode) view.path("map")).set("sourceFrame", original);
                route.fulfill(new com.microsoft.playwright.Route.FulfillOptions().setContentType("application/json").setBody(json.writeValueAsString(view)));
            } catch (java.io.IOException e) { throw new IllegalStateException(e); }
            finally { response.dispose(); }
        });
        page.evaluate("window.__auctionMap.refreshNow()");
        page.waitForFunction("document.querySelector('#map-state').dataset.state==='error'");
        assertThat(json.readTree(page.locator("#shared-results").getAttribute("data-source-frame"))).isEqualTo(original);
        assertThat(page.evaluate("JSON.parse(localStorage.getItem('" + KEY + "')).lastDisplay.publication.sequence"))
                .isEqualTo(original.path("publication").path("sequence").asInt());
        page.locator("#comparison-tools summary").click(); page.locator("#checkpoint-save").press("Enter");
        page.waitForFunction("JSON.parse(localStorage.getItem('" + KEY + "')).checkpoint");
        assertThat(json.readTree((String) page.evaluate("JSON.stringify(JSON.parse(localStorage.getItem('" + KEY + "')).checkpoint)"))).isEqualTo(original);
        page.unroute("**/api/auctions/view?*"); page.locator("#comparison-tools summary").click();
        page.locator("#map-retry").click(); ready(page);
        assertThat(json.readTree(page.locator("#shared-results").getAttribute("data-source-frame")).path("publication"))
                .isNotEqualTo(original.path("publication"));
        assertThat(json.readTree((String) page.evaluate("JSON.stringify(JSON.parse(localStorage.getItem('" + KEY + "')).checkpoint)"))).isEqualTo(original);
        browser.network().assertOnlyLocalhostRequests();
    }
    @Test void nativeNoJavascriptDateGetRetainsOrdinaryBrowsingAndExplicitLimitation() {
        try (var context = browser.page().context().browser().newContext(new Browser.NewContextOptions().setJavaScriptEnabled(false))) {
            Page page = context.newPage(); page.navigate(applicationUri().toString());
            page.selectOption("#changes-since", "date"); page.fill("#since-local", "2026-08-24T12:30");
            page.locator("#shared-filters button[type=submit]").click();
            assertThat(page.url()).contains("sinceAt=2026-08-24T10%3A30%3A00Z").doesNotContain("sinceLocal=");
            assertThat(page.locator("#comparison-summary").textContent()).contains("НЕ нула промена");
            assertThat(page.locator("noscript").textContent()).contains("захтевају JavaScript");
            assertThat(page.locator("#shared-results tr[data-auction-id]").count()).isEqualTo(2);
        }
    }
    private static void ready(Page page) {
        page.waitForFunction("window.__auctionMap?.ready && ['ready','empty'].includes(document.querySelector('#map-state').dataset.state) && !window.__auctionMap.getDiagnostics().requestInFlight && !window.__auctionMap.getDiagnostics().pendingRefresh && !window.__auctionMap.map.isMoving()");
    }
    private static void apply(Page page) { page.locator("#shared-filters button[type=submit]").click(); ready(page); }
    private static Path basemap() {
        try {
            Path root = Files.createTempDirectory("comparison-basemap-");
            Path fixture = Path.of(AuctionComparisonsBrowserTest.class.getResource("/fixtures/basemap-bundle").toURI());
            BasemapTestBundle.fromDirectory(root, "issue56", fixture); BasemapTestBundle.activate(root, "issue56"); return root;
        }
        catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
}
