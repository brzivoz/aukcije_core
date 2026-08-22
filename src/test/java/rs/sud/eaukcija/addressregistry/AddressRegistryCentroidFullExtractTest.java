package rs.sud.eaukcija.addressregistry;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/** Opt-in reproducibility proof against one retained, reviewed full snapshot. */
@EnabledIfEnvironmentVariable(named = "ADDRESS_REGISTRY_CENTROID_FULL_SOURCE", matches = ".+")
class AddressRegistryCentroidFullExtractTest {

    @TempDir
    Path tempDirectory;

    @Test
    void buildsTheCompleteSnapshotTwiceWithByteIdenticalVersionFiles() throws Exception {
        Path source = Path.of(requiredEnvironment("ADDRESS_REGISTRY_CENTROID_FULL_SOURCE"));
        AddressRegistryCentroidExtractProperties properties = new AddressRegistryCentroidExtractProperties();
        properties.setSourceUri(source.toUri());
        properties.setCanonicalUrl(AddressRegistryImportProperties.OFFICIAL_RESOURCE_URL);
        properties.setSourceDate(LocalDate.parse(requiredEnvironment("ADDRESS_REGISTRY_CENTROID_FULL_SOURCE_DATE")));
        properties.setExpectedSha256(requiredEnvironment("ADDRESS_REGISTRY_CENTROID_FULL_SOURCE_SHA256"));
        properties.setExpectedGpkgSha256(requiredEnvironment("ADDRESS_REGISTRY_CENTROID_FULL_GPKG_SHA256"));
        String expectedSchema = System.getenv("ADDRESS_REGISTRY_CENTROID_FULL_SCHEMA_SHA256");
        if (expectedSchema != null && !expectedSchema.isBlank()) {
            properties.setExpectedSchemaSha256(expectedSchema);
        }
        properties.setMinimumFreeBytes(0);
        properties.setWorkDirectory(tempDirectory.resolve("work"));
        properties.setPublishDirectory(tempDirectory.resolve("published"));

        ObjectMapper objectMapper = new ObjectMapper();
        AddressRegistryCentroidExtractor extractor = new AddressRegistryCentroidExtractor(
                new AddressRegistryArtifactStager(), new GeoPackageInspector(), objectMapper);
        AddressRegistryCentroidExtractor.BuildResult first = extractor.build(properties);
        Map<String, String> firstHashes = hashes(Path.of(first.versionDirectory()));
        AddressRegistryCentroidExtractor.BuildResult replay = extractor.build(properties);
        JsonNode report = objectMapper.readTree(Path.of(first.versionDirectory()).resolve("report.json").toFile());

        assertThat(first.outcome()).isEqualTo("SUCCEEDED");
        assertThat(first.sourceRows()).isEqualTo(2_488_562);
        assertThat(first.activeRows()).isEqualTo(first.sourceRows());
        assertThat(first.rejectedRows()).isZero();
        assertThat(first.koCentroids()).isEqualTo(4_497);
        assertThat(first.settlementCentroids()).isEqualTo(4_717);
        assertThat(first.municipalityCentroids()).isEqualTo(168);
        assertThat(first.duplicateNameGroups()).isEqualTo(824);
        assertThat(first.publishedArtifactBytes()).isEqualTo(4_209_510);
        assertThat(report.path("crossMunicipalityDuplicateNameGroupCount").asLong()).isEqualTo(824);
        assertThat(report.path("nameVariantEntryCount").asLong()).isZero();
        assertThat(report.path("validationGatesPassed").size()).isEqualTo(6);
        assertThat(report.path("validationGatesPassed").get(4).asText())
                .isEqualTo("ACTIVE_GEOMETRIES_WITHIN_SERBIA");
        assertThat(firstHashes).containsExactlyEntriesOf(new TreeMap<>(Map.of(
                "ATTRIBUTION.md", "e680b02cd55403554e2820ce270bc86a0175a990e8c78ed5079a6e19b5af3179",
                "centroids.ndjson", "162112e9fb2cb6ae22ff0a9b922cabcf454c393243524a28598e541203d26c5b",
                "manifest.json", "d8876b6cea99e84101bc57879af7bfb0e31c624809649110cfc8042cd492fe45",
                "report.json", "fdaf757a4bf7acb22c8c44337ede3d467cc81c12cd56ee3fe7dac5d4466092c5")));
        assertThat(replay.outcome()).isEqualTo("UNCHANGED");
        assertThat(replay.version()).isEqualTo(first.version());
        assertThat(hashes(Path.of(first.versionDirectory()))).isEqualTo(firstHashes);
        System.out.println("FULL_ADDRESS_REGISTRY_CENTROID_EXTRACT=" + first);
        System.out.println("FULL_ADDRESS_REGISTRY_CENTROID_REPLAY=" + replay);
    }

    private static Map<String, String> hashes(Path directory) throws Exception {
        Map<String, String> hashes = new TreeMap<>();
        try (var paths = Files.list(directory)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                hashes.put(path.getFileName().toString(), AddressRegistryArtifactStager.sha256(path));
            }
        }
        return hashes;
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required when the full centroid test is enabled");
        }
        return value;
    }
}
