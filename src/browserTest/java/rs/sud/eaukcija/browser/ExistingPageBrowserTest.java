package rs.sud.eaukcija.browser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.options.WaitUntilState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class ExistingPageBrowserTest extends PostgisBrowserFixture {

    private static final String STORED_REFRESH_RUN_ID =
            "33333333-3333-4333-8333-333333333333";

    @RegisterExtension
    final BrowserHarnessExtension browser = new BrowserHarnessExtension();

    @Test
    void existingServerRenderedListLoadsWithoutShippingMapTestHooks() {
        Page page = browser.page();
        page.navigate(applicationUri().toString(), new Page.NavigateOptions()
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));

        assertThat(page.locator("header h1").isVisible()).isTrue();
        assertThat(page.locator("header h1").textContent())
                .contains("еАукција", "Претрага непокретности");
        assertThat(page.locator("tbody").textContent())
                .contains("Н34-001", "Детерминистичка browser-test аукција", "Београд");
        page.waitForFunction("""
                () => document.querySelector('#auction-map canvas') !== null
                  || document.querySelector('#map-state')?.dataset.state === 'error'
                """);
        assertThat((Boolean) page.evaluate("Object.hasOwn(window, '__auctionMap')")).isFalse();
    }

    @Test
    void staleStoredRefreshRunIsClearedAfterARealNotFoundStatusResponse() {
        Page page = browser.page();
        seedStoredRefreshRun(page);

        page.navigate(applicationUri().toString(), new Page.NavigateOptions()
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        page.waitForFunction("""
                () => localStorage.getItem('eaukcija.refresh.workflowId') === null
                  && document.querySelector('#refresh-status')?.textContent
                        === 'Освежавање није покренуто.'
                """);

        assertThat(page.locator("#refresh-start").getAttribute("aria-disabled")).isEqualTo("false");
    }

    @Test
    void secretBearingJsonStatusErrorIsNeverRendered() {
        String sentinel = "password=json-status-secret";
        Page page = browser.page();
        seedStoredRefreshRun(page);
        page.route("**/api/operator/refresh/" + STORED_REFRESH_RUN_ID, route -> route.fulfill(
                new Route.FulfillOptions()
                        .setStatus(400)
                        .setContentType("application/problem+json")
                        .setBody("{\"code\":\"UNTRUSTED\",\"detail\":\"" + sentinel + "\"}")));

        navigateAndWaitForStoredRunToClear(page);

        assertThat(page.locator("body").textContent()).doesNotContain(sentinel);
        assertThat(page.locator("#refresh-status").textContent())
                .isEqualTo("Статус освежавања тренутно није доступан.");
    }

    @Test
    void secretBearingNonJsonStatusErrorIsNeverRenderedByTheParserFallback() {
        String sentinel = "password=plain-status-secret";
        Page page = browser.page();
        seedStoredRefreshRun(page);
        page.route("**/api/operator/refresh/" + STORED_REFRESH_RUN_ID, route -> route.fulfill(
                new Route.FulfillOptions()
                        .setStatus(400)
                        .setContentType("text/plain")
                        .setBody(sentinel)));

        navigateAndWaitForStoredRunToClear(page);

        assertThat(page.locator("body").textContent()).doesNotContain(sentinel);
        assertThat(page.locator("#refresh-status").textContent())
                .isEqualTo("Статус освежавања тренутно није доступан.");
    }

    @Test
    void transientStatusFailureRetriesThenTracksRunningRunToTerminalCompletion() {
        String sentinel = "token=transient-status-secret";
        AtomicInteger requests = new AtomicInteger();
        Page page = browser.page();
        seedStoredRefreshRun(page);
        page.route("**/api/operator/refresh/" + STORED_REFRESH_RUN_ID, route -> {
            int request = requests.incrementAndGet();
            if (request == 1) {
                route.fulfill(new Route.FulfillOptions()
                        .setStatus(503)
                        .setContentType("application/problem+json")
                        .setBody("{\"detail\":\"" + sentinel + "\"}"));
                return;
            }
            boolean terminal = request >= 3;
            route.fulfill(new Route.FulfillOptions()
                    .setStatus(200)
                    .setContentType("application/json")
                    .setBody("""
                            {"enabled":true,"workflowId":"%s","trigger":"MANUAL",
                             "status":"%s","stage":"%s",
                             "startedAt":"2026-08-25T10:00:00Z",
                             "finishedAt":%s,"elapsedSeconds":60,
                             "listingsProcessed":1,"listingsTotal":1,
                             "detailsProcessed":1,"detailsTotal":1,
                             "locationsProcessed":1,"locationsTotal":1,
                             "mappedCount":%d,"populationCount":%d,
                             "precisionSummary":%s,
                             "sourceSyncRunId":null,"enrichmentRunId":null,
                             "mapResolutionRunId":null,"mapDataVersion":%s,
                             "mapReadyAt":%s,"failureCode":null,"failureMessage":null,
                             "lastSuccessfulWorkflowId":%s,
                             "lastSuccessfulCompleteRefresh":%s,
                             "scheduleEnabled":true,"scheduleZone":"Europe/Belgrade",
                             "nextScheduledRun":"2026-08-26T01:00:00Z"}
                            """.formatted(
                                    STORED_REFRESH_RUN_ID,
                                    terminal ? "SUCCEEDED" : "RUNNING",
                                    terminal ? "COMPLETED" : "PROCESS_LOCATIONS",
                                    terminal ? "\"2026-08-25T10:01:00Z\"" : "null",
                                    terminal ? 1 : 0,
                                    terminal ? 1 : 0,
                                    terminal ? "{\"CADASTRAL_MUNICIPALITY\":1}" : "{}",
                                    terminal ? "\"map-v1\"" : "null",
                                    terminal ? "\"2026-08-25T10:01:00Z\"" : "null",
                                    terminal ? "\"" + STORED_REFRESH_RUN_ID + "\"" : "null",
                                    terminal ? "\"2026-08-25T10:01:00Z\"" : "null")));
        });

        page.navigate(applicationUri().toString(), new Page.NavigateOptions()
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        page.waitForFunction("""
                () => document.querySelector('#refresh-status')?.textContent
                        === 'Карта је спремна. Мапирано је 1 од 1 аукција.'
                  && localStorage.getItem('eaukcija.refresh.workflowId') === null
                """);

        assertThat(requests).hasValue(3);
        assertThat(page.locator("body").textContent()).doesNotContain(sentinel);
    }

    @Test
    void externalAssetOnTheRealPageProvesTheGuardWouldFailTheSuite() {
        Page page = browser.page();
        page.navigate(applicationUri().toString(), new Page.NavigateOptions()
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        page.evaluate("""
                async externalUrl => await new Promise(resolve => {
                  const image = document.createElement('img');
                  image.alt = 'negative control';
                  image.addEventListener('error', resolve, {once: true});
                  image.src = externalUrl;
                  document.body.appendChild(image);
                })
                """, "https://cdn.example.invalid/a[1]^x|y.png");

        assertThat(browser.network().contactedHosts())
                .containsExactly("cdn.example.invalid", "localhost");
        assertThat(browser.network().blockedHosts()).containsExactly("cdn.example.invalid");
        assertThatThrownBy(browser.network()::assertOnlyLocalhostRequests)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("cdn.example.invalid");
    }

    @Test
    void loopbackWebSocketConnectsWhileExternalWebSocketIsRecordedAndClosed() throws IOException {
        Page page = browser.page();
        page.navigate(applicationUri().toString(), new Page.NavigateOptions()
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));

        try (LoopbackWebSocketServer server = new LoopbackWebSocketServer()) {
            assertThat(openWebSocket(page, server.url())).isEqualTo("open");
        }
        assertThat(browser.network().contactedWebSocketHosts()).containsExactly("127.0.0.1");
        assertThat(browser.network().blockedWebSocketHosts()).isEmpty();

        assertThat(openWebSocket(page, "wss://socket.example.invalid/network[1]^x|y"))
                .isEqualTo("closed");
        assertThat(browser.network().contactedWebSocketHosts())
                .containsExactly("127.0.0.1", "socket.example.invalid");
        assertThat(browser.network().blockedWebSocketHosts())
                .containsExactly("socket.example.invalid");
        assertThat(browser.network().contactedHosts())
                .containsExactly("127.0.0.1", "localhost", "socket.example.invalid");
        assertThatThrownBy(browser.network()::assertOnlyLocalhostRequests)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("socket.example.invalid");
    }

    private static String openWebSocket(Page page, String url) {
        return (String) page.evaluate("""
                async webSocketUrl => await new Promise(resolve => {
                  const socket = new WebSocket(webSocketUrl);
                  const timeout = window.setTimeout(() => {
                    socket.close();
                    resolve('timeout');
                  }, 5000);
                  socket.addEventListener('open', () => {
                    window.clearTimeout(timeout);
                    socket.close();
                    resolve('open');
                  }, {once: true});
                  socket.addEventListener('close', () => {
                    window.clearTimeout(timeout);
                    resolve('closed');
                  }, {once: true});
                })
                """, url);
    }

    private static void seedStoredRefreshRun(Page page) {
        page.addInitScript("""
                if (window.name !== 'eaukcija-refresh-seeded') {
                  window.name = 'eaukcija-refresh-seeded';
                  localStorage.setItem('eaukcija.refresh.workflowId', '%s');
                }
                """.formatted(STORED_REFRESH_RUN_ID));
    }

    private void navigateAndWaitForStoredRunToClear(Page page) {
        page.navigate(applicationUri().toString(), new Page.NavigateOptions()
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        page.waitForFunction("""
                () => localStorage.getItem('eaukcija.refresh.workflowId') === null
                """);
    }

}
