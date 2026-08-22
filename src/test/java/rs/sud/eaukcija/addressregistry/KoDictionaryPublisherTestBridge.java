package rs.sud.eaukcija.addressregistry;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Test-only bridge that exposes real #36 -> #14 artifact publication to downstream tests. */
public final class KoDictionaryPublisherTestBridge {

    private KoDictionaryPublisherTestBridge() {
    }

    public static Path publishDuplicateNameArtifact(Path root, ObjectMapper objectMapper) throws Exception {
        return publish(root, objectMapper, AddressRegistryGpkgFixture.Fault.DUPLICATE_NAME_ACROSS_MUNICIPALITIES);
    }

    public static Path publishMultiParentArtifact(Path root, ObjectMapper objectMapper) throws Exception {
        return publish(root, objectMapper, AddressRegistryGpkgFixture.Fault.PARENT_CONFLICT);
    }

    private static Path publish(
            Path root,
            ObjectMapper objectMapper,
            AddressRegistryGpkgFixture.Fault fault) throws Exception {
        Path gpkg = AddressRegistryGpkgFixture.create(root.resolve("gpkg"), 0, fault);
        Path centroids = root.resolve("centroids");
        AddressRegistryCentroidExtractProperties extract = new AddressRegistryCentroidExtractProperties();
        extract.setSourceUri(gpkg.toUri());
        extract.setSourceDate(LocalDate.of(2026, 8, 22));
        extract.setExpectedSha256(AddressRegistryArtifactStager.sha256(gpkg));
        extract.setExpectedGpkgSha256(AddressRegistryArtifactStager.sha256(gpkg));
        extract.setMinimumRows(1);
        extract.setMaximumRows(10);
        extract.setMinimumActiveFraction(0.5);
        extract.setMinimumKoCentroids(1);
        extract.setMaximumKoCentroids(10);
        extract.setMinimumSettlementCentroids(1);
        extract.setMaximumSettlementCentroids(10);
        extract.setMinimumMunicipalityCentroids(1);
        extract.setMaximumMunicipalityCentroids(10);
        extract.setMinimumFreeBytes(0);
        extract.setMaximumGpkgBytes(10 * 1024 * 1024);
        extract.setWorkDirectory(root.resolve("work"));
        extract.setPublishDirectory(centroids);
        new AddressRegistryCentroidExtractor(
                new AddressRegistryArtifactStager(), new GeoPackageInspector(), objectMapper).build(extract);

        Path aliases = root.resolve("aliases.json");
        Files.writeString(aliases, """
                {
                  "formatVersion": 1,
                  "datasetVersion": "publisher-loader-fixture-1",
                  "aliases": [{
                    "id": "historical-caribrod",
                    "koCode": "702013",
                    "name": "Caribrod",
                    "kind": "HISTORICAL",
                    "provenance": "Publisher-loader compatibility fixture",
                    "sourceReference": "fixture://history/caribrod",
                    "reviewer": "fixture-reviewer",
                    "reviewedAt": "2026-08-22"
                  }]
                }
                """, StandardCharsets.UTF_8);
        Path dictionary = root.resolve("dictionary");
        KoDictionaryProperties properties = new KoDictionaryProperties();
        properties.setCentroidDirectory(centroids);
        properties.setPublishDirectory(dictionary);
        properties.setAliasOverrides(aliases);
        properties.setMinimumKoEntries(1);
        properties.setMaximumKoEntries(10);
        new KoDictionaryPublisher(objectMapper).build(properties);
        return dictionary;
    }
}
