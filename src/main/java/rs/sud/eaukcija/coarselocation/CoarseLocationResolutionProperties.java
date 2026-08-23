package rs.sud.eaukcija.coarselocation;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Filesystem contract for the active immutable #36 centroid extract. */
@Component
@ConfigurationProperties(prefix = "coarse.location")
public class CoarseLocationResolutionProperties {

    private Path centroidDirectory = Path.of("data", "address-registry-centroids");

    public Path getCentroidDirectory() {
        return centroidDirectory;
    }

    public void setCentroidDirectory(Path centroidDirectory) {
        this.centroidDirectory = centroidDirectory;
    }

    void validate() {
        if (centroidDirectory == null) {
            throw new CoarseLocationResolutionException(
                    "INVALID_CONFIGURATION", "coarse.location.centroid-directory is required");
        }
    }
}
