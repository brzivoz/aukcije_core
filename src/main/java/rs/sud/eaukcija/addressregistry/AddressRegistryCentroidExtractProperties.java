package rs.sud.eaukcija.addressregistry;

import java.net.URI;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Locale;

/** Operator inputs manually bound by the database-free centroid CLI. */
public class AddressRegistryCentroidExtractProperties implements AddressRegistrySourceSettings {

    public enum Action {
        BUILD,
        STATUS
    }

    private Action action = Action.BUILD;
    private URI sourceUri = AddressRegistryImportProperties.OFFICIAL_DOWNLOAD_URI;
    private String canonicalUrl = AddressRegistryImportProperties.OFFICIAL_RESOURCE_URL;
    private LocalDate sourceDate;
    private String expectedSha256;
    private String expectedGpkgSha256;
    private String expectedSchemaSha256;
    private long minimumRows = 2_000_000;
    private long maximumRows = 3_500_000;
    private double minimumActiveFraction = 0.90;
    private int minimumKoCentroids = 3_500;
    private int maximumKoCentroids = 6_000;
    private int minimumSettlementCentroids = 3_500;
    private int maximumSettlementCentroids = 7_000;
    private int minimumMunicipalityCentroids = 100;
    private int maximumMunicipalityCentroids = 300;
    private int fetchSize = 5_000;
    private long minimumFreeBytes = 2L * 1024 * 1024 * 1024;
    private long maximumGpkgBytes = 2L * 1024 * 1024 * 1024;
    private Path workDirectory = Path.of(System.getProperty("java.io.tmpdir"), "aukcije-address-registry-centroids");
    private Path publishDirectory = Path.of("data", "address-registry-centroids");

    public Action getAction() {
        return action;
    }

    public void setAction(Action action) {
        this.action = action;
    }

    @Override
    public URI getSourceUri() {
        return sourceUri;
    }

    public void setSourceUri(URI sourceUri) {
        this.sourceUri = sourceUri;
    }

    public String getCanonicalUrl() {
        return canonicalUrl;
    }

    public void setCanonicalUrl(String canonicalUrl) {
        this.canonicalUrl = canonicalUrl;
    }

    public LocalDate getSourceDate() {
        return sourceDate;
    }

    public void setSourceDate(LocalDate sourceDate) {
        this.sourceDate = sourceDate;
    }

    @Override
    public String getExpectedSha256() {
        return expectedSha256;
    }

    public void setExpectedSha256(String expectedSha256) {
        this.expectedSha256 = normalizedHashInput(expectedSha256);
    }

    @Override
    public String getExpectedGpkgSha256() {
        return expectedGpkgSha256;
    }

    public void setExpectedGpkgSha256(String expectedGpkgSha256) {
        this.expectedGpkgSha256 = normalizedHashInput(expectedGpkgSha256);
    }

    @Override
    public String getExpectedSchemaSha256() {
        return expectedSchemaSha256;
    }

    public void setExpectedSchemaSha256(String expectedSchemaSha256) {
        this.expectedSchemaSha256 = normalizedHashInput(expectedSchemaSha256);
    }

    @Override
    public long getMinimumRows() {
        return minimumRows;
    }

    public void setMinimumRows(long minimumRows) {
        this.minimumRows = minimumRows;
    }

    @Override
    public long getMaximumRows() {
        return maximumRows;
    }

    public void setMaximumRows(long maximumRows) {
        this.maximumRows = maximumRows;
    }

    public double getMinimumActiveFraction() {
        return minimumActiveFraction;
    }

    public void setMinimumActiveFraction(double minimumActiveFraction) {
        this.minimumActiveFraction = minimumActiveFraction;
    }

    public int getMinimumKoCentroids() {
        return minimumKoCentroids;
    }

    public void setMinimumKoCentroids(int minimumKoCentroids) {
        this.minimumKoCentroids = minimumKoCentroids;
    }

    public int getMaximumKoCentroids() {
        return maximumKoCentroids;
    }

    public void setMaximumKoCentroids(int maximumKoCentroids) {
        this.maximumKoCentroids = maximumKoCentroids;
    }

    public int getMinimumSettlementCentroids() {
        return minimumSettlementCentroids;
    }

    public void setMinimumSettlementCentroids(int minimumSettlementCentroids) {
        this.minimumSettlementCentroids = minimumSettlementCentroids;
    }

    public int getMaximumSettlementCentroids() {
        return maximumSettlementCentroids;
    }

    public void setMaximumSettlementCentroids(int maximumSettlementCentroids) {
        this.maximumSettlementCentroids = maximumSettlementCentroids;
    }

    public int getMinimumMunicipalityCentroids() {
        return minimumMunicipalityCentroids;
    }

    public void setMinimumMunicipalityCentroids(int minimumMunicipalityCentroids) {
        this.minimumMunicipalityCentroids = minimumMunicipalityCentroids;
    }

    public int getMaximumMunicipalityCentroids() {
        return maximumMunicipalityCentroids;
    }

    public void setMaximumMunicipalityCentroids(int maximumMunicipalityCentroids) {
        this.maximumMunicipalityCentroids = maximumMunicipalityCentroids;
    }

    public int getFetchSize() {
        return fetchSize;
    }

    public void setFetchSize(int fetchSize) {
        this.fetchSize = fetchSize;
    }

    @Override
    public long getMinimumFreeBytes() {
        return minimumFreeBytes;
    }

    public void setMinimumFreeBytes(long minimumFreeBytes) {
        this.minimumFreeBytes = minimumFreeBytes;
    }

    @Override
    public long getMaximumGpkgBytes() {
        return maximumGpkgBytes;
    }

    public void setMaximumGpkgBytes(long maximumGpkgBytes) {
        this.maximumGpkgBytes = maximumGpkgBytes;
    }

    @Override
    public Path getWorkDirectory() {
        return workDirectory;
    }

    public void setWorkDirectory(Path workDirectory) {
        this.workDirectory = workDirectory;
    }

    public Path getPublishDirectory() {
        return publishDirectory;
    }

    public void setPublishDirectory(Path publishDirectory) {
        this.publishDirectory = publishDirectory;
    }

    void validateForBuild() {
        if (sourceUri == null) {
            throw invalid("source-uri is required");
        }
        if (canonicalUrl == null || canonicalUrl.isBlank()) {
            throw invalid("canonical-url is required");
        }
        if (sourceDate == null) {
            throw invalid("source-date is required and must name the official snapshot date");
        }
        if (sourceDate.isAfter(LocalDate.now())) {
            throw invalid("source-date cannot be in the future");
        }
        validateHash(expectedSha256, "expected-sha256", true);
        validateHash(expectedGpkgSha256, "expected-gpkg-sha256", false);
        validateHash(expectedSchemaSha256, "expected-schema-sha256", false);
        if (minimumRows < 1 || maximumRows < minimumRows) {
            throw invalid("row-count limits must satisfy 1 <= minimum-rows <= maximum-rows");
        }
        if (!Double.isFinite(minimumActiveFraction)
                || minimumActiveFraction <= 0 || minimumActiveFraction > 1) {
            throw invalid("minimum-active-fraction must be greater than 0 and at most 1");
        }
        validateRange("KO", minimumKoCentroids, maximumKoCentroids);
        validateRange("settlement", minimumSettlementCentroids, maximumSettlementCentroids);
        validateRange("municipality", minimumMunicipalityCentroids, maximumMunicipalityCentroids);
        if (fetchSize < 1 || fetchSize > 50_000) {
            throw invalid("fetch-size must be between 1 and 50000");
        }
        if (minimumFreeBytes < 0 || maximumGpkgBytes < 1) {
            throw invalid("disk limits must be non-negative and maximum-gpkg-bytes must be positive");
        }
        if (workDirectory == null || publishDirectory == null) {
            throw invalid("work-directory and publish-directory are required");
        }
        Path normalizedWork = workDirectory.toAbsolutePath().normalize();
        Path normalizedPublish = publishDirectory.toAbsolutePath().normalize();
        if (normalizedWork.equals(normalizedPublish)
                || normalizedWork.startsWith(normalizedPublish)
                || normalizedPublish.startsWith(normalizedWork)) {
            throw invalid("work-directory and publish-directory must be separate and cannot contain each other");
        }
    }

    private static void validateRange(String level, int minimum, int maximum) {
        if (minimum < 1 || maximum < minimum) {
            throw invalid(level + " centroid limits must satisfy 1 <= minimum <= maximum");
        }
    }

    private static void validateHash(String value, String name, boolean required) {
        if (value == null || value.isBlank()) {
            if (required) {
                throw invalid(name + " is required and must be independently reviewed");
            }
            return;
        }
        if (!value.matches("[0-9a-f]{64}")) {
            throw invalid(name + " must be a 64-character hexadecimal SHA-256");
        }
    }

    private static String normalizedHashInput(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private static AddressRegistryImportException invalid(String message) {
        return new AddressRegistryImportException("INVALID_CONFIGURATION", message);
    }
}
