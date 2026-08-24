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

    private static final String STORED_SYNC_RUN_ID =
            "33333333-3333-4333-8333-333333333333";
    private static final String STORED_IDEMPOTENCY_KEY =
            "44444444-4444-4444-8444-444444444444";

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
    void staleStoredSyncRunIsClearedAfterARealNotFoundStatusResponse() {
        Page page = browser.page();
        seedStoredSyncRun(page);

        page.navigate(applicationUri().toString(), new Page.NavigateOptions()
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        page.waitForFunction("""
                () => sessionStorage.getItem('eaukcija.sync.runId') === null
                  && sessionStorage.getItem('eaukcija.sync.idempotencyKey') === null
                  && !document.querySelector('#btnSync').disabled
                """);

        assertThat(page.locator("#progressBar").isHidden()).isTrue();
        assertThat(page.locator("#syncStatus").textContent())
                .isEqualTo("Сачувана синхронизација више не постоји.");
    }

    @Test
    void secretBearingJsonStatusErrorIsNeverRendered() {
        String sentinel = "password=json-status-secret";
        Page page = browser.page();
        seedStoredSyncRun(page);
        page.route("**/api/sync/runs/" + STORED_SYNC_RUN_ID, route -> route.fulfill(
                new Route.FulfillOptions()
                        .setStatus(400)
                        .setContentType("application/problem+json")
                        .setBody("{\"code\":\"UNTRUSTED\",\"detail\":\"" + sentinel + "\"}")));

        navigateAndWaitForStoredRunToClear(page);

        assertThat(page.locator("body").textContent()).doesNotContain(sentinel);
        assertThat(page.locator("#syncStatus").textContent())
                .isEqualTo("Сачувани идентификатор синхронизације није важећи.");
    }

    @Test
    void secretBearingNonJsonStatusErrorIsNeverRenderedByTheParserFallback() {
        String sentinel = "password=plain-status-secret";
        Page page = browser.page();
        seedStoredSyncRun(page);
        page.route("**/api/sync/runs/" + STORED_SYNC_RUN_ID, route -> route.fulfill(
                new Route.FulfillOptions()
                        .setStatus(400)
                        .setContentType("text/plain")
                        .setBody(sentinel)));

        navigateAndWaitForStoredRunToClear(page);

        assertThat(page.locator("body").textContent()).doesNotContain(sentinel);
        assertThat(page.locator("#syncStatus").textContent())
                .isEqualTo("Сачувани идентификатор синхронизације није важећи.");
    }

    @Test
    void transientStatusFailureRetriesThenTracksRunningRunToTerminalCompletion() {
        String sentinel = "token=transient-status-secret";
        AtomicInteger requests = new AtomicInteger();
        Page page = browser.page();
        seedStoredSyncRun(page);
        page.route("**/api/sync/runs/" + STORED_SYNC_RUN_ID, route -> {
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
                            {"runId":"%s","status":"%s","stage":"%s",
                             "pagesExpected":1,"pagesCompleted":1,
                             "detailsRequired":1,"detailsSucceeded":1}
                            """.formatted(
                                    STORED_SYNC_RUN_ID,
                                    terminal ? "SUCCEEDED" : "RUNNING",
                                    terminal ? "COMPLETED" : "DETAILS")));
        });

        page.navigate(applicationUri().toString(), new Page.NavigateOptions()
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        page.waitForFunction("""
                () => document.querySelector('#syncStatus')?.textContent
                        === 'SUCCEEDED — COMPLETED'
                  && sessionStorage.getItem('eaukcija.sync.runId') === null
                  && sessionStorage.getItem('eaukcija.sync.idempotencyKey') === null
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

    private static void seedStoredSyncRun(Page page) {
        page.addInitScript("""
                if (window.name !== 'eaukcija-sync-seeded') {
                  window.name = 'eaukcija-sync-seeded';
                  sessionStorage.setItem('eaukcija.sync.runId', '%s');
                  sessionStorage.setItem('eaukcija.sync.idempotencyKey', '%s');
                }
                """.formatted(STORED_SYNC_RUN_ID, STORED_IDEMPOTENCY_KEY));
    }

    private void navigateAndWaitForStoredRunToClear(Page page) {
        page.navigate(applicationUri().toString(), new Page.NavigateOptions()
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        page.waitForFunction("""
                () => sessionStorage.getItem('eaukcija.sync.runId') === null
                  && sessionStorage.getItem('eaukcija.sync.idempotencyKey') === null
                """);
    }

}
