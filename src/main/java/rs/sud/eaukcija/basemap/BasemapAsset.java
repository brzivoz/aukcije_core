package rs.sud.eaukcija.basemap;

import java.nio.file.Path;

/** One checksum-validated file in an immutable basemap snapshot. */
public record BasemapAsset(
        String relativePath,
        Path path,
        long sizeBytes,
        String sha256,
        String contentType) {

    public String etag() {
        return "\"sha256-" + sha256 + "\"";
    }
}
