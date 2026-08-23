package rs.sud.eaukcija.browser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitUntilState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class ExistingPageBrowserTest extends PostgisBrowserFixture {

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

}
