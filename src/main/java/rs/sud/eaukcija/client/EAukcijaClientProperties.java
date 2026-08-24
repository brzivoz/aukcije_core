package rs.sud.eaukcija.client;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Source-safe HTTP limits for the undocumented eAukcija SPA backend.
 *
 * <p>The defaults deliberately favor a low request rate and bounded failure over
 * throughput. All values are validated again when the singleton client is built,
 * so invalid environment overrides fail before a source request can be made.
 */
@Component
@ConfigurationProperties(prefix = "eaukcija.api")
public class EAukcijaClientProperties {

    static final long KIBIBYTE = 1024L;
    static final long MEBIBYTE = 1024L * KIBIBYTE;

    private URI baseUrl = URI.create("https://eaukcija.sud.rs/WebApi.Proxy/api/EAukcija");
    private List<Integer> rootCategoryIds = new ArrayList<>(List.of(7, 8));
    private int pageSize = 3_000;
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration readTimeout = Duration.ofSeconds(20);
    private Duration callTimeout = Duration.ofSeconds(30);
    private int maxAttempts = 3;
    private Duration retryBaseDelay = Duration.ofMillis(500);
    private Duration retryMaxDelay = Duration.ofSeconds(10);
    private Duration maxRetryAfter = Duration.ofMinutes(2);
    private double requestsPerSecond = 2.0;
    private int maxConcurrency = 1;
    private long maxResponseBytes = 16L * MEBIBYTE;
    private String userAgent = "aukcije-core/0.0.1";
    private String contact = "https://github.com/brzivoz/aukcije_core/issues";

    public URI getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(URI baseUrl) {
        this.baseUrl = baseUrl;
    }

    public List<Integer> getRootCategoryIds() {
        return List.copyOf(rootCategoryIds);
    }

    public void setRootCategoryIds(List<Integer> rootCategoryIds) {
        this.rootCategoryIds = rootCategoryIds == null
                ? null
                : new ArrayList<>(rootCategoryIds);
    }

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    public Duration getCallTimeout() {
        return callTimeout;
    }

    public void setCallTimeout(Duration callTimeout) {
        this.callTimeout = callTimeout;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public Duration getRetryBaseDelay() {
        return retryBaseDelay;
    }

    public void setRetryBaseDelay(Duration retryBaseDelay) {
        this.retryBaseDelay = retryBaseDelay;
    }

    public Duration getRetryMaxDelay() {
        return retryMaxDelay;
    }

    public void setRetryMaxDelay(Duration retryMaxDelay) {
        this.retryMaxDelay = retryMaxDelay;
    }

    public Duration getMaxRetryAfter() {
        return maxRetryAfter;
    }

    public void setMaxRetryAfter(Duration maxRetryAfter) {
        this.maxRetryAfter = maxRetryAfter;
    }

    public double getRequestsPerSecond() {
        return requestsPerSecond;
    }

    public void setRequestsPerSecond(double requestsPerSecond) {
        this.requestsPerSecond = requestsPerSecond;
    }

    public int getMaxConcurrency() {
        return maxConcurrency;
    }

    public void setMaxConcurrency(int maxConcurrency) {
        this.maxConcurrency = maxConcurrency;
    }

    public long getMaxResponseBytes() {
        return maxResponseBytes;
    }

    public void setMaxResponseBytes(long maxResponseBytes) {
        this.maxResponseBytes = maxResponseBytes;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public String getContact() {
        return contact;
    }

    public void setContact(String contact) {
        this.contact = contact;
    }

    String requestUserAgent() {
        return userAgent.trim() + " (+" + contact.trim() + ")";
    }

    void validate() {
        validate(false);
    }

    void validate(boolean allowHttpForLoopbackTest) {
        if (baseUrl == null || baseUrl.getScheme() == null || baseUrl.getHost() == null) {
            throw invalid("base-url must be an absolute HTTPS URI");
        }
        boolean loopbackHttp = allowHttpForLoopbackTest
                && "http".equalsIgnoreCase(baseUrl.getScheme())
                && ("127.0.0.1".equals(baseUrl.getHost()) || "localhost".equals(baseUrl.getHost()));
        if (!"https".equalsIgnoreCase(baseUrl.getScheme()) && !loopbackHttp) {
            throw invalid("base-url must use HTTPS");
        }
        if (baseUrl.getUserInfo() != null || baseUrl.getQuery() != null || baseUrl.getFragment() != null) {
            throw invalid("base-url must not contain credentials, query, or fragment");
        }
        if (rootCategoryIds == null || rootCategoryIds.isEmpty()
                || rootCategoryIds.size() > 16
                || rootCategoryIds.size() != rootCategoryIds.stream().distinct().count()
                || rootCategoryIds.stream().anyMatch(id -> id == null || id < 1)) {
            throw invalid("root-category-ids must contain 1 to 16 distinct positive ids");
        }
        if (pageSize < 1 || pageSize > 3_000) {
            throw invalid("page-size must be between 1 and 3000");
        }
        requireDuration(connectTimeout, Duration.ofSeconds(30), "connect-timeout");
        requireDuration(readTimeout, Duration.ofMinutes(2), "read-timeout");
        requireDuration(callTimeout, Duration.ofMinutes(5), "call-timeout");
        if (callTimeout.compareTo(connectTimeout) < 0 || callTimeout.compareTo(readTimeout) < 0) {
            throw invalid("call-timeout must be at least connect-timeout and read-timeout");
        }
        if (maxAttempts < 1 || maxAttempts > 5) {
            throw invalid("max-attempts must be between 1 and 5");
        }
        requireDuration(retryBaseDelay, Duration.ofMinutes(1), "retry-base-delay");
        requireDuration(retryMaxDelay, Duration.ofMinutes(1), "retry-max-delay");
        if (retryMaxDelay.compareTo(retryBaseDelay) < 0) {
            throw invalid("retry-max-delay must be at least retry-base-delay");
        }
        requireDuration(maxRetryAfter, Duration.ofMinutes(15), "max-retry-after");
        if (!Double.isFinite(requestsPerSecond)
                || requestsPerSecond < 0.1
                || requestsPerSecond > 10.0) {
            throw invalid("requests-per-second must be between 0.1 and 10");
        }
        if (maxConcurrency < 1 || maxConcurrency > 4) {
            throw invalid("max-concurrency must be between 1 and 4");
        }
        if (maxResponseBytes < KIBIBYTE || maxResponseBytes > 256L * MEBIBYTE) {
            throw invalid("max-response-bytes must be between 1024 and 268435456");
        }
        requireHeaderValue(userAgent, "user-agent");
        requireHeaderValue(contact, "contact");
    }

    private static void requireDuration(Duration value, Duration maximum, String name) {
        if (value == null || value.isZero() || value.isNegative() || value.compareTo(maximum) > 0) {
            throw invalid(name + " must be positive and at most " + maximum);
        }
    }

    private static void requireHeaderValue(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 200
                || value.chars().anyMatch(character -> character < 0x20 || character == 0x7f)) {
            throw invalid(name + " must be a nonblank single-line value of at most 200 characters");
        }
    }

    private static IllegalStateException invalid(String message) {
        return new IllegalStateException("Invalid eaukcija.api configuration: " + message);
    }
}
