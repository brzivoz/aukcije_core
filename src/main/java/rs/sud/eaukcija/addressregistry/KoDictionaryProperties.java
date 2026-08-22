package rs.sud.eaukcija.addressregistry;

import java.nio.file.Path;
import java.util.Locale;

/** Operator inputs and fail-closed bounds for canonical KO dictionary builds. */
public class KoDictionaryProperties {

    public enum Action {
        BUILD,
        STATUS
    }

    private Action action = Action.BUILD;
    private Path centroidDirectory = Path.of("data", "address-registry-centroids");
    private Path publishDirectory = Path.of("data", "address-registry-ko-dictionary");
    private Path aliasOverrides = Path.of("config", "address-registry", "ko-alias-overrides.json");
    private String expectedGpkgSha256;
    private int minimumKoEntries = 3_500;
    private int maximumKoEntries = 6_000;

    public Action getAction() {
        return action;
    }

    public void setAction(Action action) {
        this.action = action;
    }

    public Path getCentroidDirectory() {
        return centroidDirectory;
    }

    public void setCentroidDirectory(Path centroidDirectory) {
        this.centroidDirectory = centroidDirectory;
    }

    public Path getPublishDirectory() {
        return publishDirectory;
    }

    public void setPublishDirectory(Path publishDirectory) {
        this.publishDirectory = publishDirectory;
    }

    public Path getAliasOverrides() {
        return aliasOverrides;
    }

    public void setAliasOverrides(Path aliasOverrides) {
        this.aliasOverrides = aliasOverrides;
    }

    public String getExpectedGpkgSha256() {
        return expectedGpkgSha256;
    }

    public void setExpectedGpkgSha256(String expectedGpkgSha256) {
        this.expectedGpkgSha256 = expectedGpkgSha256 == null
                ? null
                : expectedGpkgSha256.trim().toLowerCase(Locale.ROOT);
    }

    public int getMinimumKoEntries() {
        return minimumKoEntries;
    }

    public void setMinimumKoEntries(int minimumKoEntries) {
        this.minimumKoEntries = minimumKoEntries;
    }

    public int getMaximumKoEntries() {
        return maximumKoEntries;
    }

    public void setMaximumKoEntries(int maximumKoEntries) {
        this.maximumKoEntries = maximumKoEntries;
    }

    void validateForBuild() {
        if (centroidDirectory == null || publishDirectory == null || aliasOverrides == null) {
            throw invalid("centroid-directory, publish-directory, and alias-overrides are required");
        }
        Path source = centroidDirectory.toAbsolutePath().normalize();
        Path target = publishDirectory.toAbsolutePath().normalize();
        Path aliases = aliasOverrides.toAbsolutePath().normalize();
        if (source.equals(target) || source.startsWith(target) || target.startsWith(source)) {
            throw invalid("centroid-directory and publish-directory must be separate and cannot contain each other");
        }
        if (aliases.startsWith(target)) {
            throw invalid("alias-overrides must be version-controlled outside publish-directory");
        }
        if (minimumKoEntries < 1 || maximumKoEntries < minimumKoEntries) {
            throw invalid("KO entry limits must satisfy 1 <= minimum <= maximum");
        }
        if (expectedGpkgSha256 != null && !expectedGpkgSha256.matches("[0-9a-f]{64}")) {
            throw invalid("expected-gpkg-sha256 must be a 64-character hexadecimal SHA-256");
        }
    }

    private static AddressRegistryImportException invalid(String message) {
        return new AddressRegistryImportException("INVALID_CONFIGURATION", message);
    }
}
