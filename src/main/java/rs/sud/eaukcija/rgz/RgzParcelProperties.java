package rs.sud.eaukcija.rgz;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
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

    public static final String LOCAL_CACHE_EPOCH = "private-local-first-observation-v1";

    private boolean enabled;
    private boolean autoConfigure;
    private boolean warmupEnabled;
    private int warmupBatchSize = 100;
    private Duration autoRetryDelay = Duration.ofMinutes(15);
    private volatile RgzSourceContract discoveredContract;
    private volatile String activationFailure;
    private volatile String warmupState = "IDLE";
    private boolean allowHttpLoopbackTest;
    private String accessMode = "OWNER_AUTHORIZED_AUTOMATIC_PRIVATE_LOCAL_EXPLICIT_ACTIVATION";
    private URI baseUrl = URI.create("https://ogc-tmp.geosrbija.rs/regdkp/ows");
    private String featureType = "dkp:dkp_parcels_weekly_only_utm";
    private String datasetVersion = "";
    private String invalidResultRecheckVersion = "";
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
        return autoConfigure && (datasetVersion == null || datasetVersion.isBlank())
                ? LOCAL_CACHE_EPOCH : datasetVersion;
    }

    /** Explicit operator epoch; only INVALID cache entries may be retried once per epoch. */
    public String getInvalidResultRecheckVersion() { return invalidResultRecheckVersion; }
    public void setInvalidResultRecheckVersion(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._-]{0,80}")) {
            throw new IllegalArgumentException("invalid-result-recheck-version must be a bounded version token");
        }
        invalidResultRecheckVersion = value;
    }

    public String datasetVersionPolicy() {
        return LOCAL_CACHE_EPOCH.equals(getDatasetVersion()) ? "PRIVATE_FIRST_OBSERVATION" : "OPERATOR_PINNED";
    }

    public boolean isAutoConfigure() { return autoConfigure; }
    public void setAutoConfigure(boolean value) { autoConfigure = value; }
    public boolean isWarmupEnabled() { return warmupEnabled; }
    public void setWarmupEnabled(boolean value) { warmupEnabled = value; }
    public int getWarmupBatchSize() { return warmupBatchSize; }
    public void setWarmupBatchSize(int value) { warmupBatchSize = value; }
    public Duration getAutoRetryDelay() { return autoRetryDelay; }
    public void setAutoRetryDelay(Duration value) { autoRetryDelay = value; }

    public String sourceContractKey() {
        return rs.sud.eaukcija.enrichment.EnrichmentHashing.sha256(
                baseUrl.toString(), featureType, getDatasetVersion(), "rgz-source-contract-v1");
    }

    public RgzSourceContract discoveredContract() {
        RgzSourceContract contract = discoveredContract;
        return contract != null && sourceContractKey().equals(contract.sourceKey()) ? contract : null;
    }

    void installContract(RgzSourceContract contract) {
        if (!sourceContractKey().equals(contract.sourceKey())) {
            throw new IllegalArgumentException("RGZ source contract does not match configuration");
        }
        discoveredContract = contract;
        activationFailure = null;
    }

    void activationFailed(String code) { activationFailure = code; }
    void warmupState(String value) { warmupState = value; }

    public boolean sourceContractReady() {
        return getDatasetVersion() != null && !getDatasetVersion().isBlank()
                && getCapabilitiesSha256() != null && getCapabilitiesSha256().matches("[0-9a-f]{64}")
                && getSchemaSha256() != null && getSchemaSha256().matches("[0-9a-f]{64}");
    }

    public void setDatasetVersion(String datasetVersion) {
        this.datasetVersion = datasetVersion;
        this.discoveredContract = null;
        this.activationFailure = null;
    }

    public String getCapabilitiesSha256() {
        RgzSourceContract contract = discoveredContract();
        return contract != null && (capabilitiesSha256 == null || capabilitiesSha256.isBlank())
                ? contract.capabilitiesSha256() : capabilitiesSha256;
    }

    public void setCapabilitiesSha256(String capabilitiesSha256) {
        this.capabilitiesSha256 = capabilitiesSha256;
    }

    public String getSchemaSha256() {
        RgzSourceContract contract = discoveredContract();
        return contract != null && (schemaSha256 == null || schemaSha256.isBlank())
                ? contract.schemaSha256() : schemaSha256;
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

    public boolean isAllowHttpLoopbackTest() {
        return allowHttpLoopbackTest;
    }

    public void setAllowHttpLoopbackTest(boolean allowHttpLoopbackTest) {
        this.allowHttpLoopbackTest = allowHttpLoopbackTest;
    }

    public boolean killSwitchEngaged() {
        try {
            // Unknown/inaccessible state and dangling symlinks must also stop requests.
            return !Files.notExists(killSwitchPath.toAbsolutePath().normalize(), LinkOption.NOFOLLOW_LINKS);
        } catch (SecurityException denied) {
            return true;
        }
    }

    public boolean metadataNetworkAllowed() {
        return enabled && !killSwitchEngaged();
    }

    public boolean networkAllowed() {
        return metadataNetworkAllowed() && sourceContractReady();
    }

    public RgzAccessStatus status() {
        boolean killed = killSwitchEngaged();
        boolean ready = sourceContractReady();
        RgzSourceContract contract = discoveredContract();
        return new RgzAccessStatus(
                killed ? "KILL_SWITCH_ENGAGED" : !enabled ? "DISABLED" : ready ? "ENABLED"
                        : activationFailure == null ? "AWAITING_SOURCE_CONTRACT" : activationFailure,
                enabled, killed, enabled && ready && !killed,
                getDatasetVersion() != null && !getDatasetVersion().isBlank(),
                RgzParcelResolutionService.DECISION_VERSION,
                requestsPerSecond, maxConcurrency, maxLogicalLookupsPerRun, maxAttempts,
                autoConfigure, ready, getDatasetVersion(), datasetVersionPolicy(),
                contract == null ? null : contract.observedAt(), warmupEnabled, warmupState);
    }

    String requestUserAgent() {
        return userAgent.trim() + " (+" + contact.trim() + ")";
    }

    public void validate() {
        validate(false);
    }

    void validate(boolean allowLoopbackHttp) {
        if (!"OWNER_AUTHORIZED_AUTOMATIC_PRIVATE_LOCAL_EXPLICIT_ACTIVATION".equals(accessMode)
                && !"OWNER_AUTHORIZED_AUTOMATIC_PRIVATE_LOCAL_POC".equals(accessMode)) {
            throw invalid("access-mode must be the recorded issue-41 decision");
        }
        if (baseUrl == null || baseUrl.getScheme() == null || baseUrl.getHost() == null) {
            throw invalid("base-url must be an absolute HTTPS URI");
        }
        boolean loopback = (allowLoopbackHttp || allowHttpLoopbackTest)
                && "http".equalsIgnoreCase(baseUrl.getScheme())
                && ("127.0.0.1".equals(baseUrl.getHost()) || "localhost".equals(baseUrl.getHost()));
        if (!"https".equalsIgnoreCase(baseUrl.getScheme()) && !loopback) {
            throw invalid("base-url must use HTTPS");
        }
        if (baseUrl.getUserInfo() != null || baseUrl.getQuery() != null || baseUrl.getFragment() != null) {
            throw invalid("base-url must not contain credentials, query, or fragment");
        }
        if (!"dkp:dkp_parcels_weekly_only_utm".equals(featureType)) {
            throw invalid("feature-type is not authorized by issue-41; building fetching is prohibited");
        }
        if (warmupBatchSize < 1 || warmupBatchSize > 1000) {
            throw invalid("warmup-batch-size must be between 1 and 1000");
        }
        requireDuration(autoRetryDelay, Duration.ofDays(1), "auto-retry-delay");
        if (enabled && autoConfigure) {
            requireToken(getDatasetVersion(), "dataset-version", 256);
            validateOptionalSha256(capabilitiesSha256, "capabilities-sha256");
            validateOptionalSha256(schemaSha256, "schema-sha256");
            if ((capabilitiesSha256 == null || capabilitiesSha256.isBlank())
                    != (schemaSha256 == null || schemaSha256.isBlank())) {
                throw invalid("provide both source pins or let auto-configure discover both");
            }
        } else if (enabled) {
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
