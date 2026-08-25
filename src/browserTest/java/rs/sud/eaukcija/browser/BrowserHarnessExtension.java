package rs.sud.eaukcija.browser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Tracing;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;

/** Creates an isolated browser and retains a screenshot and trace on failure. */
public final class BrowserHarnessExtension implements BeforeEachCallback, TestWatcher {

    private Playwright playwright;
    private Browser browser;
    private BrowserContext context;
    private Page page;
    private LocalhostOnlyNetwork network;

    @Override
    public void beforeEach(ExtensionContext extensionContext) {
        boolean headless = Boolean.parseBoolean(System.getProperty("browser.headless", "true"));
        playwright = Playwright.create();
        browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions().setHeadless(headless));
        context = browser.newContext();
        network = new LocalhostOnlyNetwork(context);
        context.tracing().start(new Tracing.StartOptions()
                .setScreenshots(true)
                .setSnapshots(true)
                .setSources(true));
        page = context.newPage();
    }

    @Override
    public void testSuccessful(ExtensionContext extensionContext) {
        close(false, extensionContext, null);
    }

    @Override
    public void testAborted(ExtensionContext extensionContext, Throwable cause) {
        close(true, extensionContext, cause);
    }

    @Override
    public void testFailed(ExtensionContext extensionContext, Throwable cause) {
        close(true, extensionContext, cause);
    }

    public Page page() {
        ensureStarted();
        return page;
    }

    public Page newPage() {
        ensureStarted();
        return context.newPage();
    }

    public LocalhostOnlyNetwork network() {
        ensureStarted();
        return network;
    }

    private void close(boolean retainArtifacts, ExtensionContext extensionContext, Throwable failure) {
        if (playwright == null) {
            return;
        }

        try {
            if (retainArtifacts) {
                Path artifactDirectory = artifactDirectory(extensionContext);
                Files.createDirectories(artifactDirectory);
                page.screenshot(new Page.ScreenshotOptions()
                        .setFullPage(true)
                        .setPath(artifactDirectory.resolve("failure.png")));
                context.tracing().stop(new Tracing.StopOptions()
                        .setPath(artifactDirectory.resolve("trace.zip")));
                System.err.println("Playwright failure evidence: " + artifactDirectory.toAbsolutePath());
            } else {
                context.tracing().stop();
            }
        } catch (Exception artifactFailure) {
            if (failure != null) {
                failure.addSuppressed(artifactFailure);
            } else {
                throw new IllegalStateException("could not finalize Playwright evidence", artifactFailure);
            }
        } finally {
            try {
                if (context != null) {
                    context.close();
                }
            } finally {
                try {
                    if (browser != null) {
                        browser.close();
                    }
                } finally {
                    playwright.close();
                    playwright = null;
                    browser = null;
                    context = null;
                    page = null;
                    network = null;
                }
            }
        }
    }

    private static Path artifactDirectory(ExtensionContext extensionContext) {
        String root = System.getProperty("browser.artifact.dir", "build/browser-test-results/artifacts");
        String testClass = sanitize(extensionContext.getRequiredTestClass().getSimpleName());
        String testMethod = sanitize(extensionContext.getRequiredTestMethod().getName());
        return Path.of(root, testClass, testMethod);
    }

    private static String sanitize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "-");
    }

    private void ensureStarted() {
        if (page == null) {
            throw new IllegalStateException("browser fixture is not active");
        }
    }
}
