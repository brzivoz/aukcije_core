package rs.sud.eaukcija.operations;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

import jakarta.servlet.http.HttpServletRequest;

/** Loopback and browser same-origin gate for local operator mutations. */
public final class OperatorRequestGuard {

    public static final String REQUEST_HEADER = "X-Operator-Request";
    public static final String REQUEST_VALUE = "refresh-v1";

    private OperatorRequestGuard() {
    }

    public static boolean isLoopback(HttpServletRequest request) {
        String address = request.getRemoteAddr();
        if (address == null || address.isBlank()) {
            return false;
        }
        try {
            return InetAddress.getByName(address).isLoopbackAddress();
        } catch (UnknownHostException invalid) {
            return false;
        }
    }

    public static boolean isTrustedMutation(HttpServletRequest request) {
        if (!isLoopback(request)
                || !REQUEST_VALUE.equals(request.getHeader(REQUEST_HEADER))
                || !isSameOriginBrowserContext(request)) {
            return false;
        }
        return true;
    }

    /** Allows non-browser operator clients while rejecting browser cross-site metadata. */
    public static boolean isSameOriginBrowserContext(HttpServletRequest request) {
        String fetchSite = request.getHeader("Sec-Fetch-Site");
        if (fetchSite != null
                && !"same-origin".equals(fetchSite)
                && !"none".equals(fetchSite)) {
            return false;
        }
        String origin = request.getHeader("Origin");
        if (origin == null) {
            return true;
        }
        try {
            URI parsed = URI.create(origin);
            int originPort = parsed.getPort() >= 0
                    ? parsed.getPort() : "https".equals(parsed.getScheme()) ? 443 : 80;
            return parsed.getUserInfo() == null
                    && parsed.getPath().isEmpty()
                    && parsed.getQuery() == null
                    && parsed.getFragment() == null
                    && request.getScheme().equalsIgnoreCase(parsed.getScheme())
                    && request.getServerName().equalsIgnoreCase(parsed.getHost())
                    && request.getServerPort() == originPort;
        } catch (RuntimeException invalid) {
            return false;
        }
    }
}
