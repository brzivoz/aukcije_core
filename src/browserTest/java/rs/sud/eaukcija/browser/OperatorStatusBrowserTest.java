package rs.sud.eaukcija.browser;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitUntilState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.test.web.server.LocalManagementPort;

/** Real-page proof for the loopback operator status shell and local API fetch. */
class OperatorStatusBrowserTest extends PostgisBrowserFixture {

    @RegisterExtension
    final BrowserHarnessExtension browser = new BrowserHarnessExtension();

    @LocalManagementPort
    private int managementPort;

    @Test
    void statusPageRendersFailClosedPersistedEvidenceWithoutExternalTraffic() {
        Page page = browser.page();
        page.navigate(applicationUri().resolve("/operator/status").toString(),
                new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        page.waitForFunction("""
                () => !document.querySelector('#summary').textContent.includes('Loading')
                """);

        assertThat(page.locator("h1").textContent()).isEqualTo("Pipeline status");
        assertThat(page.locator("#summary").textContent())
                .contains("UNAVAILABLE", "readiness DOWN");
        assertThat(page.locator("main section").count()).isEqualTo(5);
        assertThat(page.locator("#signals").textContent())
                .contains("NO_SUCCESSFUL_SYNC", "ADDRESS_REGISTRY_ARTIFACT_MISSING");
        assertThat(page.locator("#evidence").textContent())
                .contains("\"readinessFailures\"", "\"lastAttempt\"");
        assertThat(browser.network().contactedHosts()).containsExactly("localhost");
        browser.network().assertOnlyLocalhostRequests();
    }

    @Test
    void actuatorIsAbsentFromTheApplicationPortAndAvailableOnItsLoopbackListener() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> applicationResponse = client.send(
                HttpRequest.newBuilder(applicationUri().resolve("/actuator/health/readiness")).build(),
                HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> managementResponse = client.send(
                HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + managementPort + "/actuator/health/readiness")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(applicationResponse.statusCode()).isEqualTo(404);
        assertThat(managementResponse.statusCode()).isEqualTo(503);
        assertThat(managementResponse.body()).contains("\"status\":\"DOWN\"");
    }
}
