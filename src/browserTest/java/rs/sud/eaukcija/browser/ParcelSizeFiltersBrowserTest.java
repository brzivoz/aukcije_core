package rs.sud.eaukcija.browser;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import rs.sud.eaukcija.basemap.BasemapTestBundle;
import rs.sud.eaukcija.testsupport.ParcelAreaFixture;

/** Real retained evidence and HTTP responses, with the shared localhost-only network guard. */
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class ParcelSizeFiltersBrowserTest extends PostgisBrowserFixture {
    private static final Path BASEMAP = basemap();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("basemap.assets.directory", BASEMAP::toString);
        registry.add("map.browser-test-hooks", () -> "true");
        registry.add("map.auto-refresh-interval-ms", () -> "1000");
    }
    @RegisterExtension final BrowserHarnessExtension browser = new BrowserHarnessExtension();
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void parcels() {
        browser.page().addInitScript("localStorage.setItem('eaukcija.workspace.v1.filters', 'true');");
        try (var fixture = new ParcelAreaFixture(jdbc)) {
            for (int i = 0; i < 34; i++) {
                long id = 57000 + i;
                fixture.auction(id);
                var ref = fixture.parcel(id, 0, Long.toString(id));
                var duplicate = i == 0 ? fixture.parcel(id, 1, Long.toString(id)) : null;
                var sibling = i == 0 ? fixture.parcel(id, 2, "90001") : null;
                var larger = i == 0 ? fixture.parcel(id, 3, "90002") : null;
                fixture.publish(id);
                fixture.rgz(ref, i < 31 ? "799.99" : i == 31 ? "800" : i == 32 ? "1500.01" : "null");
                if (i == 0) { fixture.rgz(duplicate, "799.99"); fixture.rgz(sibling, "700"); fixture.rgz(larger, "1600"); }
            }
        }
    }

    @Test void keyboardApplySortPageCopiedLinksHistoryAndAllPresetsKeepSelectionAndUnrelatedCriteria() {
        Page page = browser.page();
        page.navigate(applicationUri() + "?category=Кућа&timeScope=all&page=1&auction=57030&sortBy=startingPrice&sortDir=desc");
        ready(page);
        assertThat(page.locator("form").count()).isOne();
        Locator control = page.getByLabel("Површина парцеле", new Page.GetByLabelOptions().setExact(true));
        assertThat(control.inputValue()).isEmpty();
        assertThat(control.getAttribute("aria-describedby")).contains("parcel-size-note", "parcel-size-help");
        // Native select is fully keyboard-operable; draft changes alone do not navigate.
        String original = page.url();
        control.focus(); control.press("<"); control.press("Tab");
        assertThat(control.inputValue()).isEqualTo("under-8");
        assertThat(page.url()).isEqualTo(original);
        page.locator("#shared-filters button[type=submit]").press("Enter"); ready(page);
        assertThat(page.url()).contains("parcelSize=under-8", "timeScope=all", "page=0", "auction=57030", "sortDir=desc");
        assertThat(page.locator("#shared-results tbody tr[data-auction-id]").count()).isEqualTo(25);
        assertThat(page.locator("#map-result-count").textContent()).isEqualTo("32"); // 31 auctions, one genuine small sibling.
        assertThat(page.locator("#map-state").textContent()).contains("Филтрирано аукција: 31");
        assertThat(page.locator(".filter-chip[data-field=parcelSize]").textContent()).contains("Површина парцеле: < 8 ar");
        assertThat(page.locator("#active-criteria").textContent()).contains("Површина парцеле: < 8 ar");
        page.click("#mode-table");
        page.locator("#shared-results th a").filter(new Locator.FilterOptions().setHasText("Почетна цена")).click(); ready(page);
        assertThat(page.url()).contains("sortDir=asc", "parcelSize=under-8");
        page.locator(".pagination a").filter(new Locator.FilterOptions().setHasText("Следећа")).click(); ready(page);
        assertThat(page.url()).contains("page=1", "auction=57030");
        assertThat(page.locator("#shared-results tbody tr[data-auction-id]").count()).isEqualTo(6);
        String copied = page.url();
        page.reload(); ready(page); assertThat(page.url()).isEqualTo(copied);
        page.navigate(copied); ready(page); assertThat(control.inputValue()).isEqualTo("under-8");
        page.selectOption("#parcel-size-filter", "8-15"); apply(page);
        assertThat(page.url()).contains("page=0", "auction=57030", "sortDir=asc");
        assertThat(page.locator("#shared-results tbody tr[data-auction-id]").count()).isOne();
        assertThat(page.locator("#map-result-count").textContent()).isEqualTo("1");
        assertThat(page.locator("#map-selection").textContent()).contains("Избор је сачуван");
        page.goBack(); ready(page); assertThat(page.url()).isEqualTo(copied);
        assertThat(control.inputValue()).isEqualTo("under-8");
        page.goForward(); ready(page); assertThat(control.inputValue()).isEqualTo("8-15");
        page.selectOption("#parcel-size-filter", "over-15"); apply(page);
        assertThat(page.locator("#map-result-count").textContent()).isEqualTo("2");
        assertThat(page.locator("#shared-results tbody tr[data-auction-id]").count()).isEqualTo(2);
        page.selectOption("#parcel-size-filter", ""); apply(page);
        assertThat(page.url()).doesNotContain("parcelSize=").contains("auction=57030", "sortDir=asc", "timeScope=all");
        assertThat(page.locator("#map-state").textContent()).contains("Филтрирано аукција: 34");
        browser.network().assertOnlyLocalhostRequests();
    }

    @Test void dirtyAreaDraftSurvivesBackgroundRefreshChipRemovalResetAndNarrowKeyboardAccess() {
        Page page = browser.page();
        page.navigate(applicationUri() + "?category=Кућа&parcelSize=under-8&timeScope=all&page=1&auction=57030&sortDir=desc");
        ready(page);
        String applied = page.url();
        String asOf = page.locator("#shared-results").getAttribute("data-as-of");
        page.selectOption("#parcel-size-filter", "over-15"); page.fill("#search-filter", "несачуван нацрт");
        page.waitForFunction("previous => document.querySelector('#shared-results').dataset.asOf !== previous", asOf); ready(page);
        page.evaluate("window.dispatchEvent(new Event('eaukcija:refresh-complete'))"); ready(page);
        assertThat(page.url()).isEqualTo(applied);
        assertThat(page.locator("#parcel-size-filter").inputValue()).isEqualTo("over-15");
        assertThat(page.locator("#search-filter").inputValue()).isEqualTo("несачуван нацрт");
        assertThat(page.locator("#filter-dirty-indicator").isVisible()).isTrue();
        assertThat(page.locator(".filter-chip[data-field=parcelSize]").textContent()).contains("< 8 ar");
        assertThat(page.locator("#map-result-count").textContent()).isEqualTo("32");
        assertThat(page.locator("#shared-results tbody tr[data-auction-id]").count()).isEqualTo(6);
        // Panning changes only map membership, not the global table or the unsaved area draft.
        page.evaluate("() => { window.__auctionMap.map.jumpTo({center:[21.5,44.79],zoom:14}); }");
        page.waitForFunction("window.__auctionMap.getDiagnostics().lastFeatureCount === 0"); ready(page);
        assertThat(page.url()).isEqualTo(applied);
        assertThat(page.locator("#shared-results tbody tr[data-auction-id]").count()).isEqualTo(6);
        page.locator(".filter-chip[data-field=parcelSize]").press("Enter"); ready(page);
        assertThat(page.url()).doesNotContain("parcelSize=").contains("timeScope=all", "page=0", "sortDir=desc", "auction=57030");
        assertThat(page.locator("#parcel-size-filter").inputValue()).isEmpty();
        assertThat(page.locator("#search-filter").inputValue()).isEqualTo("несачуван нацрт");
        page.locator("#applied-filter-reset").press("Enter"); ready(page);
        assertThat(page.url()).contains("timeScope=not-ended", "page=0", "sortDir=desc", "auction=57030")
                .doesNotContain("parcelSize=", "category=", "search=");
        assertThat(page.locator("#search-filter").inputValue()).isEmpty();
        assertThat(page.locator("#filter-dirty-indicator").isHidden()).isTrue();
        page.setViewportSize(390, 844);
        page.locator("#parcel-size-filter").focus();
        assertThat(page.locator("#parcel-size-filter").evaluate("el => el === document.activeElement")).isEqualTo(true);
        page.locator("#parcel-size-details summary").press("Enter");
        assertThat(page.locator("#parcel-size-help").isVisible()).isTrue();
        assertThat(page.locator("#parcel-size-help").textContent()).contains("1 ar = 100 m²", "800", "1.500", "Непознате");
        assertThat(page.locator("#parcel-size-filter").evaluate("el => el.getBoundingClientRect().right <= innerWidth")).isEqualTo(true);
        assertThat(page.evaluate("document.documentElement.scrollWidth <= innerWidth")).isEqualTo(true);
        browser.network().assertOnlyLocalhostRequests();
    }

    private static void apply(Page page) { page.click("#shared-filters button[type=submit]"); ready(page); }
    private static void ready(Page page) {
        page.waitForFunction("window.__auctionMap?.ready && ['ready','empty'].includes(window.__auctionMap.getDiagnostics().lastState)");
    }
    private static Path basemap() {
        try {
            Path root = Files.createTempDirectory("parcel-size-browser-");
            Path fixture = Path.of(ParcelSizeFiltersBrowserTest.class.getResource("/fixtures/basemap-bundle").toURI());
            BasemapTestBundle.fromDirectory(root, "issue57", fixture); BasemapTestBundle.activate(root, "issue57"); return root;
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
}
