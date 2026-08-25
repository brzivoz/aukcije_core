package rs.sud.eaukcija.browser;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.options.WaitUntilState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Browser contract for persisted #40 progress, tabs, reloads, focus, and announcements. */
class RefreshWorkflowBrowserTest extends PostgisBrowserFixture {

    private static final String WORKFLOW = "40000000-0000-4000-8000-000000000040";
    private static final String RETRY_WORKFLOW = "40000000-0000-4000-8000-000000000041";

    @RegisterExtension
    final BrowserHarnessExtension browser = new BrowserHarnessExtension();

    @Test
    void oneActionSurvivesEveryStageReloadAndASecondTabBeforeMapReadySuccess() {
        AtomicBoolean active = new AtomicBoolean();
        AtomicBoolean succeeded = new AtomicBoolean();
        AtomicReference<String> stage = new AtomicReference<>("DOWNLOAD_LISTINGS");
        AtomicInteger starts = new AtomicInteger();
        Page first = browser.page();
        installRoutes(first, active, succeeded, stage, starts);
        first.navigate(applicationUri().toString(), new Page.NavigateOptions()
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        first.waitForFunction("document.querySelector('#refresh-status')?.textContent === 'Освежавање није покренуто.'");

        first.locator("#refresh-start").focus();
        assertFocusContrast(first, "#refresh-start");
        first.locator("#refresh-start").hover();
        assertFocusContrast(first, "#refresh-start");
        first.locator("#refresh-start").press("Enter");
        first.locator("#refresh-start").evaluate("element => element.click()");
        first.waitForFunction("document.querySelector('#refresh-start')?.getAttribute('aria-disabled') === 'true'");
        assertThat((Boolean) first.locator("#refresh-start").evaluate(
                "element => element === document.activeElement")).isTrue();
        assertThat(starts).hasValue(1);

        for (String current : new String[]{
                "DOWNLOAD_LISTINGS", "DOWNLOAD_DETAILS", "PROCESS_LOCATIONS", "PREPARE_MAP"}) {
            stage.set(current);
            first.waitForFunction("expected => document.querySelector(`[data-refresh-stage='${expected}']`)?.dataset.state === 'active'", current);
            assertThat(first.locator("#refresh-status").textContent()).doesNotContain("Карта је спремна", "Завршено");
            assertThat(first.locator("#refresh-result").isHidden()).isTrue();
            first.reload(new Page.ReloadOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            first.waitForFunction("expected => document.querySelector(`[data-refresh-stage='${expected}']`)?.dataset.state === 'active'", current);
        }

        Page second = browser.newPage();
        installRoutes(second, active, succeeded, stage, starts);
        second.navigate(applicationUri().toString(), new Page.NavigateOptions()
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        second.waitForFunction("document.querySelector('[data-refresh-stage=PREPARE_MAP]')?.dataset.state === 'active'");
        assertThat(second.locator("#refresh-status").textContent()).isEqualTo("У току: Припрема карте.");

        succeeded.set(true);
        first.waitForFunction("document.querySelector('#refresh-status')?.textContent.includes('Карта је спремна')");
        second.waitForFunction("document.querySelector('#refresh-status')?.textContent.includes('Карта је спремна')");
        assertThat(first.locator("#refresh-result").textContent())
                .contains("9 од 10", "центар КО: 8", "без положаја: 1");
        assertThat(first.locator("#refresh-last-success").textContent()).doesNotContain("Није");
        assertThat(first.locator("#refresh-polite").getAttribute("role")).isEqualTo("status");
        assertThat(first.locator("#refresh-polite").getAttribute("aria-live")).isEqualTo("polite");
        assertThat(first.locator("#refresh-polite").textContent()).contains("Карта је спремна");
        assertThat(first.locator("#refresh-alert").getAttribute("role")).isEqualTo("alert");
        assertThat(first.locator("#refresh-alert").getAttribute("aria-live")).isEqualTo("assertive");
        assertThat(first.locator("#refresh-schedule").textContent())
                .contains("једном дневно", "Europe/Belgrade", "Следеће покретање");
        browser.network().assertOnlyLocalhostRequests();
    }

    @Test
    void failedStageUsesAssertiveSerbianRecoveryWithoutLeakingInternalText() {
        Page page = browser.page();
        AtomicInteger retries = new AtomicInteger();
        AtomicInteger retryPolls = new AtomicInteger();
        page.route("**/api/operator/refresh", route -> {
            if ("POST".equals(route.request().method())) {
                retries.incrementAndGet();
                route.fulfill(json(202, """
                        {"workflowId":"%s","alreadyRunning":false,"replayed":false,
                         "statusUrl":"/api/operator/refresh/%s"}
                        """.formatted(RETRY_WORKFLOW, RETRY_WORKFLOW)));
            } else {
                route.fulfill(json(200, failedState(WORKFLOW)));
            }
        });
        page.route("**/api/operator/refresh/" + RETRY_WORKFLOW, route ->
                route.fulfill(json(200, retryPolls.incrementAndGet() == 1
                        ? runningState(RETRY_WORKFLOW, "PROCESS_LOCATIONS")
                        : failedState(RETRY_WORKFLOW))));
        page.navigate(applicationUri().toString(), new Page.NavigateOptions()
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        page.waitForFunction("document.querySelector('#refresh-retry')?.hidden === false");

        assertThat(page.locator("#refresh-alert").getAttribute("role")).isEqualTo("alert");
        assertThat(page.locator("#refresh-alert").getAttribute("aria-live")).isEqualTo("assertive");
        assertThat(page.locator("#refresh-alert").textContent())
                .contains("Обрада локација није завршена");
        assertThat(page.locator("#refresh-status").textContent())
                .isEqualTo("Обрада локација није завршена. Прегледајте дијагностику и покушајте поново.")
                .doesNotContain("ENRICHMENT_FAILED", "stack", "payload");
        assertThat(page.locator("[data-refresh-stage=PROCESS_LOCATIONS]").getAttribute("data-state"))
                .isEqualTo("error");
        assertThat(page.locator("#refresh-last-success").textContent()).doesNotContain("Није");
        page.locator("#refresh-retry").focus();
        assertFocusContrast(page, "#refresh-retry");
        page.locator("#refresh-retry").hover();
        assertFocusContrast(page, "#refresh-retry");
        page.locator("#refresh-retry").press("Enter");
        page.waitForFunction("document.querySelector('#refresh-retry')?.hidden === true"
                + " && document.activeElement?.id === 'refresh-start'");
        assertThat(page.locator("#refresh-start").getAttribute("aria-disabled")).isEqualTo("true");
        page.waitForFunction("document.querySelector('#refresh-retry')?.hidden === false"
                + " && document.activeElement?.id === 'refresh-retry'");
        assertThat(retries).hasValue(1);
    }

    @Test
    void terminalRestoreClearsTheRetainedIdempotencyKeyBeforeTheNextAction() {
        String staleKey = "50000000-0000-4000-8000-000000000050";
        AtomicReference<String> submittedKey = new AtomicReference<>();
        Page page = browser.page();
        page.addInitScript("sessionStorage.setItem('eaukcija.refresh.idempotencyKey', '"
                + staleKey + "')");
        page.route("**/api/operator/refresh", route -> {
            if ("POST".equals(route.request().method())) {
                submittedKey.set(route.request().headerValue("Idempotency-Key"));
                route.fulfill(json(202, """
                        {"workflowId":"%s","alreadyRunning":false,"replayed":false,
                         "statusUrl":"/api/operator/refresh/%s"}
                        """.formatted(RETRY_WORKFLOW, RETRY_WORKFLOW)));
            } else {
                route.fulfill(json(200, state("COMPLETED", true)));
            }
        });
        page.route("**/api/operator/refresh/" + RETRY_WORKFLOW,
                route -> route.fulfill(json(200, failedState(RETRY_WORKFLOW))));

        page.navigate(applicationUri().toString(), new Page.NavigateOptions()
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        page.waitForFunction("document.querySelector('#refresh-status')?.textContent.includes('Карта је спремна')");
        assertThat(page.evaluate(
                "sessionStorage.getItem('eaukcija.refresh.idempotencyKey')")).isNull();

        page.locator("#refresh-start").press("Enter");
        page.waitForFunction("document.querySelector('#refresh-retry')?.hidden === false");

        assertThat(submittedKey.get()).isNotNull().isNotEqualTo(staleKey);
    }

    @Test
    void locallyDisabledWorkflowIsExplainedAndCannotStartFromThePage() {
        AtomicInteger starts = new AtomicInteger();
        Page page = browser.page();
        page.route("**/api/operator/refresh", route -> {
            if ("POST".equals(route.request().method())) {
                starts.incrementAndGet();
                route.fulfill(json(503, "{}"));
            } else {
                route.fulfill(json(200,
                        idleState().replace("\"enabled\":true", "\"enabled\":false")));
            }
        });
        page.navigate(applicationUri().toString(), new Page.NavigateOptions()
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        page.waitForFunction("document.querySelector('#refresh-status')?.textContent.includes('паузирано локалном конфигурацијом')");

        assertThat(page.locator("#refresh-start").getAttribute("aria-disabled")).isEqualTo("true");
        page.locator("#refresh-start").press("Enter");
        assertThat(starts).hasValue(0);
    }

    private static void installRoutes(
            Page page,
            AtomicBoolean active,
            AtomicBoolean succeeded,
            AtomicReference<String> stage,
            AtomicInteger starts) {
        page.route("**/api/operator/refresh", route -> {
            if ("POST".equals(route.request().method())) {
                starts.incrementAndGet();
                active.set(true);
                route.fulfill(json(202, """
                        {"workflowId":"%s","alreadyRunning":false,"replayed":false,
                         "statusUrl":"/api/operator/refresh/%s"}
                        """.formatted(WORKFLOW, WORKFLOW)));
                return;
            }
            route.fulfill(json(200, active.get()
                    ? state(stage.get(), succeeded.get()) : idleState()));
        });
        page.route("**/api/operator/refresh/" + WORKFLOW, route ->
                route.fulfill(json(200, state(stage.get(), succeeded.get()))));
    }

    private static Route.FulfillOptions json(int status, String body) {
        return new Route.FulfillOptions()
                .setStatus(status)
                .setContentType("application/json")
                .setBody(body);
    }

    private static String idleState() {
        return base("null", "IDLE", "null", "null", "null", 0, 0, "{}", "null", "null");
    }

    private static String failedState(String workflowId) {
        return """
                {"enabled":true,"workflowId":"%s","trigger":"MANUAL","status":"FAILED",
                 "stage":"PROCESS_LOCATIONS","startedAt":"2026-08-25T10:00:00Z",
                 "finishedAt":"2026-08-25T10:01:00Z","elapsedSeconds":60,
                 "listingsProcessed":10,"listingsTotal":10,"detailsProcessed":10,"detailsTotal":10,
                 "locationsProcessed":4,"locationsTotal":10,"mappedCount":0,"populationCount":0,
                 "precisionSummary":{},"sourceSyncRunId":null,"enrichmentRunId":null,
                 "mapResolutionRunId":null,"mapDataVersion":null,"mapReadyAt":null,
                 "failureCode":"ENRICHMENT_FAILED",
                 "failureMessage":"Обрада локација није завршена. Прегледајте дијагностику и покушајте поново.",
                 "lastSuccessfulWorkflowId":"30000000-0000-4000-8000-000000000030",
                 "lastSuccessfulCompleteRefresh":"2026-08-24T09:00:00Z",
                 "scheduleEnabled":true,"scheduleZone":"Europe/Belgrade",
                 "nextScheduledRun":"2026-08-26T01:00:00Z"}
                """.formatted(workflowId);
    }

    private static String runningState(String workflowId, String stage) {
        return base(
                "\"" + workflowId + "\"", "RUNNING", "\"" + stage + "\"",
                "\"2026-08-25T10:00:00Z\"", "null", 0, 0, "{}", "null", "null");
    }

    private static void assertFocusContrast(Page page, String selector) {
        Number contrast = (Number) page.locator(selector).evaluate("""
                element => {
                  const rgb = value => (value.match(/[0-9.]+/g) || []).slice(0, 3).map(Number);
                  const luminance = value => {
                    const channels = rgb(value).map(channel => {
                      const normalized = channel / 255;
                      return normalized <= 0.04045
                        ? normalized / 12.92 : ((normalized + 0.055) / 1.055) ** 2.4;
                    });
                    return 0.2126 * channels[0] + 0.7152 * channels[1] + 0.0722 * channels[2];
                  };
                  const ratio = (left, right) => {
                    const high = Math.max(luminance(left), luminance(right));
                    const low = Math.min(luminance(left), luminance(right));
                    return (high + 0.05) / (low + 0.05);
                  };
                  const style = getComputedStyle(element);
                  const panel = getComputedStyle(element.closest('.refresh-panel'));
                  const shadowColor = style.boxShadow.match(/rgba?[(][^)]*[)]/)?.[0] || 'rgb(0,0,0)';
                  if (!element.matches(':focus-visible') || parseFloat(style.outlineWidth) < 3) {
                    return 0;
                  }
                  return Math.min(
                    ratio(style.outlineColor, style.backgroundColor),
                    ratio(shadowColor, panel.backgroundColor));
                }
                """);
        assertThat(contrast.doubleValue()).isGreaterThanOrEqualTo(3.0);
    }

    private static String state(String stage, boolean succeeded) {
        if (succeeded) {
            return base(
                    "\"" + WORKFLOW + "\"", "SUCCEEDED", "\"COMPLETED\"",
                    "\"2026-08-25T10:00:00Z\"", "\"2026-08-25T10:01:00Z\"",
                    9, 10, "{\"CADASTRAL_MUNICIPALITY\":8,\"ADDRESS\":1,\"NONE\":1}",
                    "\"map-v1\"", "\"2026-08-25T10:01:00Z\"");
        }
        return base(
                "\"" + WORKFLOW + "\"", "RUNNING", "\"" + stage + "\"",
                "\"2026-08-25T10:00:00Z\"", "null", 0, 0, "{}", "null", "null");
    }

    private static String base(
            String workflowId,
            String status,
            String stage,
            String startedAt,
            String finishedAt,
            int mapped,
            int population,
            String precision,
            String mapVersion,
            String mapReadyAt) {
        boolean terminal = "SUCCEEDED".equals(status);
        boolean active = !"null".equals(workflowId);
        return """
                {"enabled":true,"workflowId":%s,"trigger":%s,"status":"%s","stage":%s,
                 "startedAt":%s,"finishedAt":%s,"elapsedSeconds":60,
                 "listingsProcessed":%d,"listingsTotal":%d,"detailsProcessed":%d,"detailsTotal":%d,
                 "locationsProcessed":%d,"locationsTotal":%d,
                 "mappedCount":%d,"populationCount":%d,"precisionSummary":%s,
                 "sourceSyncRunId":null,"enrichmentRunId":null,"mapResolutionRunId":null,
                 "mapDataVersion":%s,"mapReadyAt":%s,"failureCode":null,"failureMessage":null,
                 "lastSuccessfulWorkflowId":%s,"lastSuccessfulCompleteRefresh":%s,
                 "scheduleEnabled":true,"scheduleZone":"Europe/Belgrade",
                 "nextScheduledRun":"2026-08-26T01:00:00Z"}
                """.formatted(
                workflowId, "null".equals(workflowId) ? "null" : "\"MANUAL\"", status, stage,
                startedAt, finishedAt,
                terminal ? 10 : active ? 4 : 0, active ? 10 : 0,
                terminal ? 10 : active ? 4 : 0, active ? 10 : 0,
                terminal ? 10 : active ? 4 : 0, active ? 10 : 0,
                mapped, population, precision, mapVersion, mapReadyAt,
                terminal ? workflowId : "null", terminal ? finishedAt : "null");
    }
}
