package rs.sud.eaukcija.basemap;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/** Fully validated immutable bundle selected by the ACTIVE pointer. */
public record BasemapSnapshot(
        String buildId,
        String artifactSha256,
        long artifactSizeBytes,
        Instant loadedAt,
        Map<String, BasemapAsset> assets) {

    public BasemapSnapshot {
        assets = Map.copyOf(assets);
    }

    public Optional<BasemapAsset> asset(String relativePath) {
        if (relativePath == null
                || relativePath.isBlank()
                || relativePath.startsWith("/")
                || relativePath.contains("\\")
                || relativePath.equals("..")
                || relativePath.startsWith("../")
                || relativePath.contains("/../")) {
            return Optional.empty();
        }
        return Optional.ofNullable(assets.get(relativePath));
    }
}
