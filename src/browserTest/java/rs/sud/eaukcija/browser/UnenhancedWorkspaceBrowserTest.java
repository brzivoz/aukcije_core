package rs.sud.eaukcija.browser;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class UnenhancedWorkspaceBrowserTest extends PostgisBrowserFixture {
    @RegisterExtension
    final BrowserHarnessExtension browser = new BrowserHarnessExtension(false);

    @Test
    void theSingleGetFormAndScrollableTableRemainUsableWithoutJavaScript() {
        Page page = browser.page();
        page.setViewportSize(683, 384);
        page.navigate(applicationUri().toString());
        assertThat(page.locator("form").count()).isOne();
        assertThat(page.locator(".workspace-toolbar").isHidden()).isTrue();
        assertThat(page.locator("#shared-results").isVisible()).isTrue();
        assertThat(page.locator("#shared-results tbody").textContent()).contains("Н34-001");
        page.fill("#search-filter", "no-such-auction");
        page.locator("#shared-filters button[type=submit]").press("Enter");
        page.waitForURL("**search=no-such-auction**");
        assertThat(page.locator("#shared-results tbody").textContent()).contains("Нема резултата");
        assertThat(page.evaluate("document.documentElement.scrollWidth <= innerWidth")).isEqualTo(true);
        assertThat(page.locator(".table-scroll").evaluate("el => el.scrollWidth > el.clientWidth")).isEqualTo(true);
        browser.network().assertOnlyLocalhostRequests();
    }
}
