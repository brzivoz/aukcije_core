package rs.sud.eaukcija.addressregistry;

import java.net.URI;
import java.nio.file.Path;
import java.time.LocalDate;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Operator inputs and conservative safety limits for one registry refresh. */
@Component
@ConfigurationProperties(prefix = "address-registry.import")
public class AddressRegistryImportProperties implements AddressRegistrySourceSettings {

    public enum Action {
        IMPORT,
        ROLLBACK,
        STATUS
    }

    public static final URI OFFICIAL_DOWNLOAD_URI = URI.create(
            "https://download.geosrbija.rs/download-api/opendata-proxy/export"
                    + "?category=ar&layer=kucni_broj_ar&geometry=true"
                    + "&fileName=kucni_br_gpkg&format=gpkg");
    public static final String OFFICIAL_RESOURCE_URL =
            "https://data.gov.rs/sr/datasets/r/be7c80e3-206b-46af-b31d-4b9f6ae596f9";

    private Action action = Action.IMPORT;
    private URI sourceUri = OFFICIAL_DOWNLOAD_URI;
    private String canonicalUrl = OFFICIAL_RESOURCE_URL;
    private LocalDate sourceDate;
    private String expectedSha256;
    private String expectedGpkgSha256;
    private String expectedSchemaSha256;
    private long minimumRows = 2_000_000;
    private long maximumRows = 3_500_000;
    private double minimumActiveFraction = 0.90;
    private int batchSize = 5_000;
    private int retainedSnapshots = 3;
    private long minimumFreeBytes = 4L * 1024 * 1024 * 1024;
    private long maximumGpkgBytes = 2L * 1024 * 1024 * 1024;
    private Path workDirectory = Path.of(System.getProperty("java.io.tmpdir"), "aukcije-address-registry");

    public Action getAction() {
        return action;
    }

    public void setAction(Action action) {
        this.action = action;
    }

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

    public String getExpectedSha256() {
        return expectedSha256;
    }

    public void setExpectedSha256(String expectedSha256) {
        this.expectedSha256 = expectedSha256;
    }

    public String getExpectedGpkgSha256() {
        return expectedGpkgSha256;
    }

    public void setExpectedGpkgSha256(String expectedGpkgSha256) {
        this.expectedGpkgSha256 = expectedGpkgSha256;
    }

    public String getExpectedSchemaSha256() {
        return expectedSchemaSha256;
    }

    public void setExpectedSchemaSha256(String expectedSchemaSha256) {
        this.expectedSchemaSha256 = expectedSchemaSha256;
    }

    public long getMinimumRows() {
        return minimumRows;
    }

    public void setMinimumRows(long minimumRows) {
        this.minimumRows = minimumRows;
    }

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

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getRetainedSnapshots() {
        return retainedSnapshots;
    }

    public void setRetainedSnapshots(int retainedSnapshots) {
        this.retainedSnapshots = retainedSnapshots;
    }

    public long getMinimumFreeBytes() {
        return minimumFreeBytes;
    }

    public void setMinimumFreeBytes(long minimumFreeBytes) {
        this.minimumFreeBytes = minimumFreeBytes;
    }

    public long getMaximumGpkgBytes() {
        return maximumGpkgBytes;
    }

    public void setMaximumGpkgBytes(long maximumGpkgBytes) {
        this.maximumGpkgBytes = maximumGpkgBytes;
    }

    public Path getWorkDirectory() {
        return workDirectory;
    }

    public void setWorkDirectory(Path workDirectory) {
        this.workDirectory = workDirectory;
    }

    public void validateForImport() {
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
        expectedSha256 = normalizedHash(expectedSha256, "expected-sha256", true);
        expectedGpkgSha256 = normalizedHash(expectedGpkgSha256, "expected-gpkg-sha256", false);
        expectedSchemaSha256 = normalizedHash(expectedSchemaSha256, "expected-schema-sha256", false);
        if (minimumRows < 1 || maximumRows < minimumRows) {
            throw invalid("row-count limits must satisfy 1 <= minimum-rows <= maximum-rows");
        }
        if (!Double.isFinite(minimumActiveFraction)
                || minimumActiveFraction <= 0
                || minimumActiveFraction > 1) {
            throw invalid("minimum-active-fraction must be greater than 0 and at most 1");
        }
        if (batchSize < 1 || batchSize > 50_000) {
            throw invalid("batch-size must be between 1 and 50000");
        }
        if (retainedSnapshots < 2) {
            throw invalid("retained-snapshots must be at least 2 so current and previous remain available");
        }
        if (minimumFreeBytes < 0 || maximumGpkgBytes < 1) {
            throw invalid("disk limits must be non-negative and maximum-gpkg-bytes must be positive");
        }
        if (workDirectory == null) {
            throw invalid("work-directory is required");
        }
    }

    private static String normalizedHash(String value, String name, boolean required) {
        if (value == null || value.isBlank()) {
            if (required) {
                throw invalid(name + " is required; the official endpoint does not publish checksums");
            }
            return null;
        }
        String normalized = value.trim().toLowerCase();
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw invalid(name + " must be a 64-character hexadecimal SHA-256");
        }
        return normalized;
    }

    private static AddressRegistryImportException invalid(String message) {
        return new AddressRegistryImportException("INVALID_CONFIGURATION", message);
    }
}
