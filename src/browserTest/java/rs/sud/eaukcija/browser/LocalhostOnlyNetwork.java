package rs.sud.eaukcija.browser;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.WebSocketRoute;

/**
 * Shared browser guard for pages that must work without public-network access.
 *
 * <p>Only loopback HTTP(S) and WebSocket hosts are allowed. Every contacted
 * host is retained so a test has to prove both that the page made real requests
 * and that none escaped the local application boundary. Full URLs are
 * intentionally not retained because future query strings may contain
 * operator-entered filters.
 */
public final class LocalhostOnlyNetwork {

    private static final String UNPARSEABLE_URL = "<unparseable-url>";
    private static final Set<String> LOOPBACK_HOSTS = Set.of(
            "localhost", "127.0.0.1", "::1", "[::1]");

    private final Set<String> contactedHosts = ConcurrentHashMap.newKeySet();
    private final Set<String> blockedHosts = ConcurrentHashMap.newKeySet();
    private final Set<String> contactedWebSocketHosts = ConcurrentHashMap.newKeySet();
    private final Set<String> blockedWebSocketHosts = ConcurrentHashMap.newKeySet();

    public LocalhostOnlyNetwork(BrowserContext context) {
        context.route("**/*", route -> {
            String requestUrl = route.request().url();
            if (hasBrowserLocalScheme(requestUrl)) {
                // blob: and data: resources are browser-local and do not
                // depend on a JDK URLStreamHandler.
                route.resume();
                return;
            }

            URL url;
            try {
                // Chromium can leave square brackets raw in a path even while
                // percent-encoding other characters. URL accepts that browser
                // form; URI.create rejects it before the guard can fail closed.
                url = new URL(requestUrl);
            } catch (MalformedURLException | IllegalArgumentException exception) {
                block(route, UNPARSEABLE_URL);
                return;
            }

            String normalizedHost = url.getHost() == null || url.getHost().isBlank()
                    ? "<missing-host>"
                    : url.getHost().toLowerCase(Locale.ROOT);
            contactedHosts.add(normalizedHost);

            if (LOOPBACK_HOSTS.contains(normalizedHost)) {
                route.resume();
            } else {
                block(route, normalizedHost);
            }
        });

        // HTTP request routing does not observe WebSocket handshakes. Register
        // this before a page is created so external sockets cannot bypass the
        // same contacted/blocked-host assertion.
        context.routeWebSocket("**/*", this::routeWebSocket);
    }

    public Set<String> contactedHosts() {
        return immutableSortedCopy(contactedHosts);
    }

    public Set<String> blockedHosts() {
        return immutableSortedCopy(blockedHosts);
    }

    public Set<String> contactedWebSocketHosts() {
        return immutableSortedCopy(contactedWebSocketHosts);
    }

    public Set<String> blockedWebSocketHosts() {
        return immutableSortedCopy(blockedWebSocketHosts);
    }

    /** Fails if the fixture observed no traffic or any non-loopback host. */
    public void assertOnlyLocalhostRequests() {
        if (contactedHosts.isEmpty()) {
            throw new AssertionError("localhost-only guard observed no network requests");
        }
        if (!blockedHosts.isEmpty()) {
            throw new AssertionError("external browser hosts were blocked: " + blockedHosts());
        }
    }

    private static Set<String> immutableSortedCopy(Set<String> hosts) {
        return Collections.unmodifiableSet(new TreeSet<>(hosts));
    }

    private void block(com.microsoft.playwright.Route route, String host) {
        contactedHosts.add(host);
        blockedHosts.add(host);
        route.abort();
    }

    private void routeWebSocket(WebSocketRoute route) {
        String normalizedHost = webSocketHost(route.url());
        contactedHosts.add(normalizedHost);
        contactedWebSocketHosts.add(normalizedHost);

        if (LOOPBACK_HOSTS.contains(normalizedHost)) {
            route.connectToServer();
        } else {
            blockedHosts.add(normalizedHost);
            blockedWebSocketHosts.add(normalizedHost);
            route.close();
        }
    }

    private static String webSocketHost(String webSocketUrl) {
        String scheme = schemeOf(webSocketUrl);
        if (!scheme.equals("ws") && !scheme.equals("wss")) {
            return UNPARSEABLE_URL;
        }

        try {
            // java.net.URL has no ws:/wss: protocol handlers. Substitute only
            // the equivalent authority-parsing scheme; no connection is made.
            String parseableUrl = (scheme.equals("ws") ? "http" : "https")
                    + webSocketUrl.substring(scheme.length());
            URL url = new URL(parseableUrl);
            return url.getHost() == null || url.getHost().isBlank()
                    ? "<missing-host>"
                    : url.getHost().toLowerCase(Locale.ROOT);
        } catch (MalformedURLException | IllegalArgumentException exception) {
            return UNPARSEABLE_URL;
        }
    }

    private static String schemeOf(String url) {
        int colon = url.indexOf(':');
        if (colon <= 0 || !isAsciiLetter(url.charAt(0))) {
            return "";
        }
        for (int index = 1; index < colon; index++) {
            char character = url.charAt(index);
            if (!isAsciiLetter(character)
                    && (character < '0' || character > '9')
                    && character != '+'
                    && character != '-'
                    && character != '.') {
                return "";
            }
        }
        return url.substring(0, colon).toLowerCase(Locale.ROOT);
    }

    static boolean hasBrowserLocalScheme(String url) {
        String scheme = schemeOf(url);
        return scheme.equals("blob") || scheme.equals("data");
    }

    private static boolean isAsciiLetter(char character) {
        return (character >= 'a' && character <= 'z')
                || (character >= 'A' && character <= 'Z');
    }
}
