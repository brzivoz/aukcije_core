package rs.sud.eaukcija.coarselocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CentroidSnapshotLoaderTest {

    @TempDir
    Path temporaryDirectory;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void validatesTheActiveManifestRowsNamesAndMemberCounts() throws Exception {
        Path root = CentroidTestArtifact.create(temporaryDirectory.resolve("centroids"), objectMapper);

        CentroidSnapshot snapshot = new CentroidSnapshotLoader(objectMapper).load(root);

        assertThat(snapshot.version()).isEqualTo(CentroidTestArtifact.VERSION);
        assertThat(snapshot.sourceGpkgSha256()).isEqualTo(CentroidTestArtifact.SOURCE_HASH);
        assertThat(snapshot.koByCode().get("K100").memberPointCount()).isEqualTo(101);
        assertThat(snapshot.settlementsByNormalizedName().get("CAJETINA"))
                .extracting(CentroidSnapshot.Centroid::officialCode)
                .containsExactly("S100");
        assertThat(snapshot.settlementsByNormalizedName().get("GRAD"))
                .extracting(CentroidSnapshot.Centroid::officialCode)
                .containsExactly("S200", "S300");
    }

    @Test
    void rejectsChangedBytesBeforeAnyCentroidCanBeUsed() throws Exception {
        Path root = CentroidTestArtifact.create(temporaryDirectory.resolve("centroids"), objectMapper);
        Path file = root.resolve("versions").resolve(CentroidTestArtifact.VERSION).resolve("centroids.ndjson");
        Files.writeString(file, "{}\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);

        assertThatThrownBy(() -> new CentroidSnapshotLoader(objectMapper).load(root))
                .isInstanceOf(CoarseLocationResolutionException.class)
                .extracting(failure -> ((CoarseLocationResolutionException) failure).getCode())
                .isEqualTo("CENTROID_FILE_CHECKSUM_MISMATCH");
    }
}
