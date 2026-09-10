package rs.sud.eaukcija.browser;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class UnenhancedWorkspaceBrowserTest extends PostgisBrowserFixture {
    @RegisterExtension
    final BrowserHarnessExtension browser = new BrowserHarnessExtension(false);

    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test
    void parcelSizeUsesTheSingleNativeGetFormWithoutJavaScript() {
        try (var fixture = new rs.sud.eaukcija.testsupport.ParcelAreaFixture(jdbc)) {
            for (int i = 0; i < 3; i++) {
                fixture.auction(57000 + i);
                var ref = fixture.parcel(57000 + i, 0, Integer.toString(57000 + i)); fixture.publish(57000 + i);
                fixture.rgz(ref, i == 0 ? "799.99" : i == 1 ? "1500" : "1500.01");
            }
        }
        Page page = browser.page(); page.setViewportSize(390, 844);
        page.navigate(applicationUri() + "?sortDir=desc&auction=57001&page=1");
        assertThat(page.locator("form").count()).isOne();
        assertThat(page.locator("#shared-filters").getAttribute("method")).isEqualTo("get");
        int index = 0;
        for (String band : java.util.List.of("under-8", "8-15", "over-15")) {
            page.selectOption("#parcel-size-filter", band);
            page.locator("#shared-filters button[type=submit]").press("Enter");
            page.waitForURL("**parcelSize=" + band + "**");
            assertThat(page.locator("#shared-results tbody tr[data-auction-id]").count()).isOne();
            assertThat(page.locator("#shared-results tbody").textContent()).contains("Н57-" + (57000 + index++));
            assertThat(page.locator("#parcel-size-filter").inputValue()).isEqualTo(band);
            assertThat(page.url()).contains("sortDir=desc", "auction=57001").doesNotContain("page=1");
        }
        page.selectOption("#parcel-size-filter", "");
        page.locator("#shared-filters button[type=submit]").press("Enter"); page.waitForURL("**parcelSize=&**");
        assertThat(page.locator("#shared-results tbody tr[data-auction-id]").count()).isEqualTo(4);
        assertThat(page.locator("#shared-results tbody").textContent()).contains("Н34-001"); // Unknown remains available.
        page.click("#shared-filter-reset"); page.waitForURL("**page=0**");
        assertThat(page.url()).doesNotContain("parcelSize=").contains("sortDir=desc", "auction=57001");
        assertThat(page.evaluate("document.documentElement.scrollWidth <= innerWidth")).isEqualTo(true);
        browser.network().assertOnlyLocalhostRequests();
    }

    @Test
    void fullDescriptionAndSecondaryFieldsAreNativeKeyboardAccessibleWithoutHoverOrJavaScript() {
        String text = "<img src=https://evil.invalid/x>\n" + "Њива Čačak ".repeat(250) + "КРАЈ";
        jdbc.update("UPDATE auctions SET description=?, status='Closed' WHERE id=34001", text);
        Page page = browser.page(); page.setViewportSize(390, 844);
        page.navigate(applicationUri() + "?timeScope=all&sortBy=auctionNumber&sortDir=desc");
        assertThat(page.locator("thead th:visible").count()).isEqualTo(5);
        page.locator("#table-secondary").press("Space");
        assertThat(page.locator("thead th:visible").count()).isEqualTo(8);
        assertThat(page.locator("tbody").textContent()).contains("Затворено на извору");
        page.locator(".table-select").press("Enter"); page.waitForURL("**/auctions/34001?**");
        assertThat(page.locator(".auction-description").first().textContent()).isEqualTo(text);
        assertThat(page.locator("article img").count()).isZero();
        assertThat(page.evaluate("document.documentElement.scrollWidth <= innerWidth")).isEqualTo(true);
        page.getByText("Назад на резултате").press("Enter");
        page.waitForURL("**sortBy=auctionNumber**");
        assertThat(page.url()).contains("timeScope=all", "sortDir=desc");
        browser.network().assertOnlyLocalhostRequests();
    }

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
