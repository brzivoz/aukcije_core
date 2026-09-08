package rs.sud.eaukcija.rgz;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Explicitly activated automatic RGZ access contract implemented by issue #21. */
@Component
@ConfigurationProperties(prefix = "rgz")
public class RgzParcelProperties {

    static final long MAX_CONFIGURED_RESPONSE_BYTES = 16L * 1024L * 1024L;

    private boolean enabled;
    private String accessMode = "OWNER_AUTHORIZED_AUTOMATIC_PRIVATE_LOCAL_EXPLICIT_ACTIVATION";
    private URI baseUrl = URI.create("https://ogc-tmp.geosrbija.rs/regdkp/ows");
    private String featureType = "dkp:dkp_parcels_weekly_only_utm";
    private String datasetVersion = "";
    private String capabilitiesSha256 = "";
    private String schemaSha256 = "";
    private double requestsPerSecond = 0.2;
    private int maxConcurrency = 1;
    private int maxLogicalLookupsPerRun = 100;
    private int maxAttempts = 3;
    private List<Duration> retryDelays = new ArrayList<>(
            List.of(Duration.ofSeconds(5), Duration.ofSeconds(15)));
    private Duration maxRetryAfter = Duration.ofSeconds(60);
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration readTimeout = Duration.ofSeconds(20);
    private Duration callTimeout = Duration.ofSeconds(25);
    private long maxResponseBytes = 5_000_000;
    private String userAgent = "aukcije-core/0.0.1";
    private String contact = "https://github.com/brzivoz/aukcije_core/issues/41";
    private Path killSwitchPath = Path.of("data/control/rgz.disabled");

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getAccessMode() {
        return accessMode;
    }

    public void setAccessMode(String accessMode) {
        this.accessMode = accessMode;
    }

    public URI getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(URI baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getFeatureType() {
        return featureType;
    }

    public void setFeatureType(String featureType) {
        this.featureType = featureType;
    }

    public String getDatasetVersion() {
        return datasetVersion;
    }

    public void setDatasetVersion(String datasetVersion) {
        this.datasetVersion = datasetVersion;
    }

    public String getCapabilitiesSha256() {
        return capabilitiesSha256;
    }

    public void setCapabilitiesSha256(String capabilitiesSha256) {
        this.capabilitiesSha256 = capabilitiesSha256;
    }

    public String getSchemaSha256() {
        return schemaSha256;
    }

    public void setSchemaSha256(String schemaSha256) {
        this.schemaSha256 = schemaSha256;
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

    public int getMaxLogicalLookupsPerRun() {
        return maxLogicalLookupsPerRun;
    }

    public void setMaxLogicalLookupsPerRun(int maxLogicalLookupsPerRun) {
        this.maxLogicalLookupsPerRun = maxLogicalLookupsPerRun;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public List<Duration> getRetryDelays() {
        return List.copyOf(retryDelays);
    }

    public void setRetryDelays(List<Duration> retryDelays) {
        this.retryDelays = retryDelays == null ? null : new ArrayList<>(retryDelays);
    }

    public Duration getMaxRetryAfter() {
        return maxRetryAfter;
    }

    public void setMaxRetryAfter(Duration maxRetryAfter) {
        this.maxRetryAfter = maxRetryAfter;
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

    public Path getKillSwitchPath() {
        return killSwitchPath;
    }

    public void setKillSwitchPath(Path killSwitchPath) {
        this.killSwitchPath = killSwitchPath;
    }

    public boolean networkAllowed() {
        return enabled && !Files.exists(killSwitchPath.toAbsolutePath().normalize());
    }

    String requestUserAgent() {
        return userAgent.trim() + " (+" + contact.trim() + ")";
    }

    public void validate() {
        validate(false);
    }

    void validate(boolean allowLoopbackHttp) {
        requireToken(accessMode, "access-mode", 128);
        if (baseUrl == null || baseUrl.getScheme() == null || baseUrl.getHost() == null) {
            throw invalid("base-url must be an absolute HTTPS URI");
        }
        boolean loopback = allowLoopbackHttp
                && "http".equalsIgnoreCase(baseUrl.getScheme())
                && ("127.0.0.1".equals(baseUrl.getHost()) || "localhost".equals(baseUrl.getHost()));
        if (!"https".equalsIgnoreCase(baseUrl.getScheme()) && !loopback) {
            throw invalid("base-url must use HTTPS");
        }
        if (baseUrl.getUserInfo() != null || baseUrl.getQuery() != null || baseUrl.getFragment() != null) {
            throw invalid("base-url must not contain credentials, query, or fragment");
        }
        if (featureType == null || !featureType.matches("[A-Za-z0-9_]+:[A-Za-z0-9_]+")) {
            throw invalid("feature-type must be a qualified WFS name");
        }
        if (enabled) {
            requireToken(datasetVersion, "dataset-version", 256);
            requireSha256(capabilitiesSha256, "capabilities-sha256");
            requireSha256(schemaSha256, "schema-sha256");
        } else {
            validateOptionalToken(datasetVersion, "dataset-version", 256);
            validateOptionalSha256(capabilitiesSha256, "capabilities-sha256");
            validateOptionalSha256(schemaSha256, "schema-sha256");
        }
        if (!Double.isFinite(requestsPerSecond)
                || requestsPerSecond < 0.05 || requestsPerSecond > 5.0) {
            throw invalid("requests-per-second must be between 0.05 and 5");
        }
        if (maxConcurrency < 1 || maxConcurrency > 4) {
            throw invalid("max-concurrency must be between 1 and 4");
        }
        if (maxLogicalLookupsPerRun < 1 || maxLogicalLookupsPerRun > 10_000) {
            throw invalid("max-logical-lookups-per-run must be between 1 and 10000");
        }
        if (maxAttempts < 1 || maxAttempts > 5) {
            throw invalid("max-attempts must be between 1 and 5");
        }
        if (retryDelays == null || retryDelays.size() != maxAttempts - 1) {
            throw invalid("retry-delays must contain exactly max-attempts minus one values");
        }
        retryDelays.forEach(delay -> requireDuration(delay, Duration.ofMinutes(1), "retry-delay"));
        requireDuration(maxRetryAfter, Duration.ofMinutes(5), "max-retry-after");
        requireDuration(connectTimeout, Duration.ofSeconds(30), "connect-timeout");
        requireDuration(readTimeout, Duration.ofMinutes(2), "read-timeout");
        requireDuration(callTimeout, Duration.ofMinutes(3), "call-timeout");
        if (callTimeout.compareTo(connectTimeout) < 0 || callTimeout.compareTo(readTimeout) < 0) {
            throw invalid("call-timeout must be at least connect-timeout and read-timeout");
        }
        if (maxResponseBytes < 1024 || maxResponseBytes > MAX_CONFIGURED_RESPONSE_BYTES) {
            throw invalid("max-response-bytes must be between 1024 and 16777216");
        }
        requireHeader(userAgent, "user-agent");
        requireHeader(contact, "contact");
        if (killSwitchPath == null || killSwitchPath.toString().isBlank()) {
            throw invalid("kill-switch-path is required");
        }
    }

    private static void requireDuration(Duration value, Duration maximum, String name) {
        if (value == null || value.isNegative() || value.isZero() || value.compareTo(maximum) > 0) {
            throw invalid(name + " must be positive and no greater than " + maximum);
        }
    }

    private static void requireToken(String value, String name, int maximum) {
        if (value == null || value.isBlank() || value.trim().length() > maximum) {
            throw invalid(name + " must contain at most " + maximum + " characters");
        }
    }

    private static void requireSha256(String value, String name) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw invalid(name + " must be a lowercase SHA-256 value");
        }
    }

    private static void validateOptionalToken(String value, String name, int maximum) {
        if (value != null && !value.isBlank()) {
            requireToken(value, name, maximum);
        }
    }

    private static void validateOptionalSha256(String value, String name) {
        if (value != null && !value.isBlank()) {
            requireSha256(value, name);
        }
    }

    private static void requireHeader(String value, String name) {
        requireToken(value, name, 256);
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw invalid(name + " must not contain line breaks");
        }
    }

    private static IllegalStateException invalid(String detail) {
        return new IllegalStateException("invalid rgz configuration: " + detail);
    }
}
