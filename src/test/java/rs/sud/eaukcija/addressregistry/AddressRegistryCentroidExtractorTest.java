package rs.sud.eaukcija.addressregistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AddressRegistryCentroidExtractorTest {

    @TempDir
    Path tempDirectory;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AddressRegistryCentroidExtractor extractor;

    @BeforeEach
    void createExtractor() {
        extractor = new AddressRegistryCentroidExtractor(
                new AddressRegistryArtifactStager(), new GeoPackageInspector(), objectMapper);
    }

    @Test
    void publishesDeterministicImmutableExtractWithCompleteProvenanceAndRelationships() throws Exception {
        Path gpkg = AddressRegistryGpkgFixture.create(
                tempDirectory.resolve("source"), 0, AddressRegistryGpkgFixture.Fault.NONE);
        AddressRegistryCentroidExtractProperties properties = properties(gpkg);

        AddressRegistryCentroidExtractor.BuildResult first = extractor.build(properties);

        assertThat(first.outcome()).isEqualTo("SUCCEEDED");
        assertThat(first.sourceRows()).isEqualTo(4);
        assertThat(first.activeRows()).isEqualTo(3);
        assertThat(first.rejectedRows()).isEqualTo(1);
        assertThat(first.koCentroids()).isEqualTo(3);
        assertThat(first.settlementCentroids()).isEqualTo(3);
        assertThat(first.municipalityCentroids()).isEqualTo(3);
        assertThat(first.publishedArtifactBytes()).isPositive();

        Path version = Path.of(first.versionDirectory());
        Map<String, String> firstHashes = hashes(version);
        List<String> lines = Files.readAllLines(version.resolve("centroids.ndjson"), StandardCharsets.UTF_8);
        assertThat(lines).allSatisfy(line -> assertThat(line).startsWith("{").endsWith("}"));
        List<JsonNode> entries = lines.stream().map(this::readJson).toList();
        assertThat(entries).hasSize(9);

        JsonNode dimitrovgradKo = entries.stream()
                .filter(entry -> entry.path("level").asText().equals("KO"))
                .filter(entry -> entry.path("officialCode").asText().equals("702013"))
                .findFirst().orElseThrow();
        assertThat(dimitrovgradKo.path("nameCyrillic").asText()).isEqualTo("ДИМИТРОВГРАД");
        assertThat(dimitrovgradKo.path("nameLatin").asText()).isEqualTo("DIMITROVGRAD");
        assertThat(strings(dimitrovgradKo.path("settlementCodes"))).containsExactly("704156");
        assertThat(strings(dimitrovgradKo.path("municipalityCodes"))).containsExactly("70201");
        assertThat(dimitrovgradKo.path("memberPointCount").asLong()).isEqualTo(1);
        assertThat(dimitrovgradKo.path("longitude").asDouble()).isCloseTo(22.780484, within(0.000001));
        assertThat(dimitrovgradKo.path("latitude").asDouble()).isCloseTo(43.013322, within(0.000001));
        assertThat(dimitrovgradKo.path("extractVersion").asText()).isEqualTo(first.version());
        assertThat(dimitrovgradKo.path("sourceDate").asText()).isEqualTo("2026-08-21");
        assertThat(dimitrovgradKo.path("sourceGpkgSha256").asText()).isEqualTo(first.gpkgSha256());

        JsonNode manifest = objectMapper.readTree(version.resolve("manifest.json").toFile());
        assertThat(manifest.path("source").path("canonicalUrl").asText())
                .isEqualTo(AddressRegistryImportProperties.OFFICIAL_RESOURCE_URL);
        assertThat(manifest.path("source").path("sourceSha256").asText()).isEqualTo(first.sourceSha256());
        assertThat(manifest.path("source").path("gpkgSha256").asText()).isEqualTo(first.gpkgSha256());
        assertThat(manifest.path("source").path("schemaSha256").asText()).isEqualTo(first.schemaSha256());
        assertThat(manifest.path("source").path("rowCount").asLong()).isEqualTo(4);
        assertThat(manifest.path("source").path("sourceCrs").asInt()).isEqualTo(25834);
        assertThat(manifest.path("source").path("targetCrs").asInt()).isEqualTo(4326);
        assertThat(manifest.path("license").path("identifier").asText()).isEqualTo("sodl");

        JsonNode report = objectMapper.readTree(version.resolve("report.json").toFile());
        assertThat(report.path("sourceRows").path("rejectedByReason").path("RETIRED").asLong()).isEqualTo(1);
        assertThat(strings(report.path("validationGatesPassed"))).contains("UNIQUE_OFFICIAL_CODES");
        assertThat(Files.readString(version.resolve("ATTRIBUTION.md")))
                .contains("Republički geodetski zavod", "sodl",
                        AddressRegistryImportProperties.OFFICIAL_RESOURCE_URL);

        AddressRegistryCentroidExtractor.BuildResult replay = extractor.build(properties);

        assertThat(replay.outcome()).isEqualTo("UNCHANGED");
        assertThat(replay.version()).isEqualTo(first.version());
        assertThat(hashes(version)).isEqualTo(firstHashes);
        assertThat(Files.readString(properties.getPublishDirectory().resolve("ACTIVE")).trim())
                .isEqualTo(first.version());
        assertThat(fileCount(properties.getPublishDirectory().resolve("versions"))).isEqualTo(1);
        assertThat(fileCount(properties.getPublishDirectory().resolve("runs"))).isEqualTo(2);

        AddressRegistryCentroidExtractor.Status status = extractor.status(properties.getPublishDirectory());
        assertThat(status.activeVersion()).isEqualTo(first.version());
        assertThat(status.sourceDate()).isEqualTo("2026-08-21");
        assertThat(status.koCentroids()).isEqualTo(3);
    }

    @Test
    void failedRefreshLeavesPreviousVersionActiveAndRecordsFailure() throws Exception {
        Path valid = AddressRegistryGpkgFixture.create(
                tempDirectory.resolve("atomic"), 0, AddressRegistryGpkgFixture.Fault.NONE);
        AddressRegistryCentroidExtractProperties good = properties(valid);
        String active = extractor.build(good).version();

        Path outside = AddressRegistryGpkgFixture.create(
                tempDirectory.resolve("atomic"), 1, AddressRegistryGpkgFixture.Fault.OUTSIDE_SERBIA);
        AddressRegistryCentroidExtractProperties bad = properties(outside);

        assertThatThrownBy(() -> extractor.build(bad))
                .isInstanceOf(AddressRegistryImportException.class)
                .extracting(error -> ((AddressRegistryImportException) error).code())
                .isEqualTo("GEOMETRY_OUTSIDE_SERBIA");

        assertThat(Files.readString(good.getPublishDirectory().resolve("ACTIVE")).trim()).isEqualTo(active);
        assertThat(fileCount(good.getPublishDirectory().resolve("versions"))).isEqualTo(1);
        List<JsonNode> runReports;
        try (var paths = Files.list(good.getPublishDirectory().resolve("runs"))) {
            runReports = paths.map(this::readJsonFile).toList();
        }
        assertThat(runReports).anySatisfy(run -> {
            assertThat(run.path("outcome").asText()).isEqualTo("FAILED");
            assertThat(run.path("errorCode").asText()).isEqualTo("GEOMETRY_OUTSIDE_SERBIA");
        });
    }

    @Test
    void freshOlderSnapshotCannotPublishOrMoveActiveBackwards() throws Exception {
        Path current = AddressRegistryGpkgFixture.create(
                tempDirectory.resolve("downgrade"), 0, AddressRegistryGpkgFixture.Fault.NONE);
        AddressRegistryCentroidExtractProperties currentProperties = properties(current);
        currentProperties.setSourceDate(LocalDate.of(2026, 8, 22));
        String active = extractor.build(currentProperties).version();

        Path older = AddressRegistryGpkgFixture.create(
                tempDirectory.resolve("downgrade"), 1, AddressRegistryGpkgFixture.Fault.NONE);
        AddressRegistryCentroidExtractProperties olderProperties = properties(older);
        olderProperties.setSourceDate(LocalDate.of(2026, 8, 1));

        assertThatThrownBy(() -> extractor.build(olderProperties))
                .isInstanceOf(AddressRegistryImportException.class)
                .extracting(error -> ((AddressRegistryImportException) error).code())
                .isEqualTo("SOURCE_DATE_DOWNGRADE");
        assertThat(Files.readString(currentProperties.getPublishDirectory().resolve("ACTIVE")).trim())
                .isEqualTo(active);
        assertThat(fileCount(currentProperties.getPublishDirectory().resolve("versions"))).isEqualTo(1);
    }

    @Test
    void retiredOutOfBoundsGeometryIsRejectedBeforeGeometryEvaluation() throws Exception {
        Path gpkg = AddressRegistryGpkgFixture.create(
                tempDirectory.resolve("retired-geometry"), 0,
                AddressRegistryGpkgFixture.Fault.RETIRED_OUTSIDE_SERBIA);

        AddressRegistryCentroidExtractor.BuildResult result = extractor.build(properties(gpkg));
        JsonNode report = objectMapper.readTree(Path.of(result.versionDirectory()).resolve("report.json").toFile());

        assertThat(result.outcome()).isEqualTo("SUCCEEDED");
        assertThat(result.activeRows()).isEqualTo(3);
        assertThat(result.rejectedRows()).isEqualTo(1);
        assertThat(report.path("sourceRows").path("rejectedByReason").path("RETIRED").asLong())
                .isEqualTo(1);
    }

    @Test
    void validationRejectsConflictingNamesInvalidNamesAndImplausibleCountsBeforePublication() throws Exception {
        assertFailure(AddressRegistryGpkgFixture.Fault.CONFLICTING_OFFICIAL_NAME, "IDENTIFIER_NAME_CONFLICT");
        assertFailure(AddressRegistryGpkgFixture.Fault.REQUIRED_NAME_NORMALIZES_EMPTY, "REQUIRED_VALUE_MISSING");

        Path valid = AddressRegistryGpkgFixture.create(
                tempDirectory.resolve("count"), 0, AddressRegistryGpkgFixture.Fault.NONE);
        AddressRegistryCentroidExtractProperties properties = properties(valid);
        properties.setMinimumKoCentroids(4);
        properties.setMaximumKoCentroids(10);

        assertThatThrownBy(() -> extractor.build(properties))
                .isInstanceOf(AddressRegistryImportException.class)
                .extracting(error -> ((AddressRegistryImportException) error).code())
                .isEqualTo("CENTROID_COUNT_SANITY");
        assertThat(Files.exists(properties.getPublishDirectory().resolve("ACTIVE"))).isFalse();
    }

    @Test
    void reportSurfacesDuplicateNamesAcrossMunicipalitiesWithoutDroppingCodes() throws Exception {
        Path gpkg = AddressRegistryGpkgFixture.create(
                tempDirectory.resolve("duplicates"), 0,
                AddressRegistryGpkgFixture.Fault.DUPLICATE_NAME_ACROSS_MUNICIPALITIES);

        AddressRegistryCentroidExtractor.BuildResult result = extractor.build(properties(gpkg));
        JsonNode report = objectMapper.readTree(Path.of(result.versionDirectory()).resolve("report.json").toFile());

        assertThat(report.path("duplicateNameGroupCount").asLong()).isEqualTo(1);
        JsonNode duplicate = report.path("duplicateNameGroups").get(0);
        assertThat(duplicate.path("level").asText()).isEqualTo("KO");
        assertThat(duplicate.path("spansMultipleMunicipalities").asBoolean()).isTrue();
        assertThat(strings(duplicate.path("officialCodes"))).containsExactly("702013", "746312");
        assertThat(strings(duplicate.path("municipalityCodes"))).containsExactly("70201", "74631");
    }

    @Test
    void duplicateReportIncludesSameMunicipalityMissingLatinAndMunicipalityLevelAmbiguities() throws Exception {
        Path sameMunicipality = AddressRegistryGpkgFixture.create(
                tempDirectory.resolve("same-municipality"), 0,
                AddressRegistryGpkgFixture.Fault.DUPLICATE_NAME_SAME_MUNICIPALITY_MISSING_LATIN);
        AddressRegistryCentroidExtractor.BuildResult sameResult = extractor.build(properties(sameMunicipality));
        JsonNode sameReport = objectMapper.readTree(
                Path.of(sameResult.versionDirectory()).resolve("report.json").toFile());
        JsonNode koDuplicate = java.util.stream.StreamSupport.stream(
                        sameReport.path("duplicateNameGroups").spliterator(), false)
                .filter(group -> group.path("level").asText().equals("KO"))
                .findFirst().orElseThrow();
        assertThat(koDuplicate.path("spansMultipleMunicipalities").asBoolean()).isFalse();
        assertThat(strings(koDuplicate.path("officialCodes"))).containsExactly("702013", "746312");
        assertThat(strings(koDuplicate.path("municipalityCodes"))).containsExactly("70201");

        Path municipality = AddressRegistryGpkgFixture.create(
                tempDirectory.resolve("municipality-name"), 0,
                AddressRegistryGpkgFixture.Fault.DUPLICATE_MUNICIPALITY_NAME);
        AddressRegistryCentroidExtractProperties municipalityProperties = properties(municipality);
        municipalityProperties.setPublishDirectory(tempDirectory.resolve("municipality-published"));
        AddressRegistryCentroidExtractor.BuildResult municipalityResult = extractor.build(municipalityProperties);
        JsonNode municipalityReport = objectMapper.readTree(
                Path.of(municipalityResult.versionDirectory()).resolve("report.json").toFile());
        assertThat(java.util.stream.StreamSupport.stream(
                        municipalityReport.path("duplicateNameGroups").spliterator(), false)
                .anyMatch(group -> group.path("level").asText().equals("MUNICIPALITY")))
                .isTrue();
    }

    @Test
    void normalizedNameVariantsDoNotFailAndRawVariantsRemainReported() throws Exception {
        Path gpkg = AddressRegistryGpkgFixture.create(
                tempDirectory.resolve("name-variants"), 0,
                AddressRegistryGpkgFixture.Fault.NORMALIZED_NAME_VARIANT);

        AddressRegistryCentroidExtractor.BuildResult result = extractor.build(properties(gpkg));
        JsonNode report = objectMapper.readTree(Path.of(result.versionDirectory()).resolve("report.json").toFile());

        assertThat(result.outcome()).isEqualTo("SUCCEEDED");
        assertThat(report.path("nameVariantEntryCount").asLong()).isEqualTo(1);
        JsonNode variant = report.path("nameVariantEntries").get(0);
        assertThat(variant.path("officialCode").asText()).isEqualTo("702013");
        assertThat(strings(variant.path("nameCyrillicVariants")))
                .containsExactly("ДИМИТРОВГРАД", "димитровград");
        assertThat(strings(variant.path("nameLatinVariants")))
                .containsExactly("DIMITROVGRAD", "Dimitrovgrad");
    }

    @Test
    void reportNamesPassedGatesInsteadOfPresentingHardcodedMeasurementCounts() throws Exception {
        Path gpkg = AddressRegistryGpkgFixture.create(
                tempDirectory.resolve("validation-report"), 0, AddressRegistryGpkgFixture.Fault.NONE);
        AddressRegistryCentroidExtractor.BuildResult result = extractor.build(properties(gpkg));
        JsonNode report = objectMapper.readTree(Path.of(result.versionDirectory()).resolve("report.json").toFile());

        assertThat(report.has("validation")).isFalse();
        assertThat(strings(report.path("validationGatesPassed"))).containsExactly(
                "SOURCE_ROW_COUNT",
                "ACTIVE_ROW_FRACTION",
                "UNIQUE_OFFICIAL_CODES",
                "REQUIRED_OFFICIAL_NAMES",
                "ACTIVE_GEOMETRIES_WITHIN_SERBIA",
                "CENTROID_COUNT_MAGNITUDE");
    }

    @Test
    void oneOfficialCodeWithMultipleParentsRemainsOneCentroidAndReportsEveryParent() throws Exception {
        Path gpkg = AddressRegistryGpkgFixture.create(
                tempDirectory.resolve("ambiguous-parent"), 0, AddressRegistryGpkgFixture.Fault.PARENT_CONFLICT);

        AddressRegistryCentroidExtractor.BuildResult result = extractor.build(properties(gpkg));
        Path version = Path.of(result.versionDirectory());
        List<JsonNode> entries = Files.readAllLines(version.resolve("centroids.ndjson"), StandardCharsets.UTF_8)
                .stream().map(this::readJson).toList();
        List<JsonNode> koEntries = entries.stream()
                .filter(entry -> entry.path("level").asText().equals("KO"))
                .filter(entry -> entry.path("officialCode").asText().equals("702013"))
                .toList();
        assertThat(koEntries).hasSize(1);
        assertThat(strings(koEntries.get(0).path("municipalityCodes"))).containsExactly("70201", "74631");

        JsonNode report = objectMapper.readTree(version.resolve("report.json").toFile());
        assertThat(report.path("ambiguousParentEntryCount").asLong()).isEqualTo(1);
        assertThat(report.path("ambiguousParentEntries").get(0).path("officialCode").asText())
                .isEqualTo("702013");
    }

    @Test
    void alteredPublishedVersionCannotBeOverwrittenByAReplay() throws Exception {
        Path gpkg = AddressRegistryGpkgFixture.create(
                tempDirectory.resolve("immutable"), 0, AddressRegistryGpkgFixture.Fault.NONE);
        AddressRegistryCentroidExtractProperties properties = properties(gpkg);
        AddressRegistryCentroidExtractor.BuildResult result = extractor.build(properties);
        Path report = Path.of(result.versionDirectory()).resolve("report.json");
        Files.writeString(report, "corruption", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);

        assertThatThrownBy(() -> extractor.build(properties))
                .isInstanceOf(AddressRegistryImportException.class)
                .extracting(error -> ((AddressRegistryImportException) error).code())
                .isEqualTo("IMMUTABLE_VERSION_CONFLICT");
        assertThat(Files.readString(properties.getPublishDirectory().resolve("ACTIVE")).trim())
                .isEqualTo(result.version());
    }

    @Test
    void workAndPublishDirectoriesCannotContainEachOther() throws Exception {
        Path gpkg = AddressRegistryGpkgFixture.create(
                tempDirectory.resolve("contained-paths"), 0, AddressRegistryGpkgFixture.Fault.NONE);
        AddressRegistryCentroidExtractProperties publishInsideWork = properties(gpkg);
        publishInsideWork.setWorkDirectory(tempDirectory.resolve("root"));
        publishInsideWork.setPublishDirectory(tempDirectory.resolve("root/published"));
        assertThatThrownBy(publishInsideWork::validateForBuild)
                .isInstanceOf(AddressRegistryImportException.class)
                .extracting(error -> ((AddressRegistryImportException) error).code())
                .isEqualTo("INVALID_CONFIGURATION");

        AddressRegistryCentroidExtractProperties workInsidePublish = properties(gpkg);
        workInsidePublish.setPublishDirectory(tempDirectory.resolve("other-root"));
        workInsidePublish.setWorkDirectory(tempDirectory.resolve("other-root/work"));
        assertThatThrownBy(workInsidePublish::validateForBuild)
                .isInstanceOf(AddressRegistryImportException.class)
                .extracting(error -> ((AddressRegistryImportException) error).code())
                .isEqualTo("INVALID_CONFIGURATION");
    }

    @Test
    void abandonedStagingDirectoriesArePrunedUnderThePublicationLock() throws Exception {
        Path gpkg = AddressRegistryGpkgFixture.create(
                tempDirectory.resolve("staging-prune"), 0, AddressRegistryGpkgFixture.Fault.NONE);
        AddressRegistryCentroidExtractProperties properties = properties(gpkg);
        extractor.build(properties);
        Path abandoned = properties.getPublishDirectory().resolve(".staging/version-abandoned");
        Files.createDirectories(abandoned);
        Files.writeString(abandoned.resolve("partial"), "incomplete");

        assertThat(extractor.build(properties).outcome()).isEqualTo("UNCHANGED");
        assertThat(fileCount(properties.getPublishDirectory().resolve(".staging"))).isZero();
    }

    private void assertFailure(AddressRegistryGpkgFixture.Fault fault, String expectedCode) throws Exception {
        Path gpkg = AddressRegistryGpkgFixture.create(
                tempDirectory.resolve("failure-" + fault), 0, fault);
        AddressRegistryCentroidExtractProperties properties = properties(gpkg);
        assertThatThrownBy(() -> extractor.build(properties))
                .isInstanceOf(AddressRegistryImportException.class)
                .extracting(error -> ((AddressRegistryImportException) error).code())
                .isEqualTo(expectedCode);
        assertThat(Files.exists(properties.getPublishDirectory().resolve("ACTIVE"))).isFalse();
    }

    private AddressRegistryCentroidExtractProperties properties(Path gpkg) throws Exception {
        AddressRegistryCentroidExtractProperties properties = new AddressRegistryCentroidExtractProperties();
        properties.setSourceUri(gpkg.toUri());
        properties.setCanonicalUrl(AddressRegistryImportProperties.OFFICIAL_RESOURCE_URL);
        properties.setSourceDate(LocalDate.of(2026, 8, 21));
        properties.setExpectedSha256(AddressRegistryArtifactStager.sha256(gpkg));
        properties.setExpectedGpkgSha256(AddressRegistryArtifactStager.sha256(gpkg));
        properties.setMinimumRows(1);
        properties.setMaximumRows(10);
        properties.setMinimumActiveFraction(0.5);
        properties.setMinimumKoCentroids(1);
        properties.setMaximumKoCentroids(10);
        properties.setMinimumSettlementCentroids(1);
        properties.setMaximumSettlementCentroids(10);
        properties.setMinimumMunicipalityCentroids(1);
        properties.setMaximumMunicipalityCentroids(10);
        properties.setMinimumFreeBytes(0);
        properties.setWorkDirectory(tempDirectory.resolve("work"));
        properties.setPublishDirectory(tempDirectory.resolve("published"));
        return properties;
    }

    private Map<String, String> hashes(Path directory) throws Exception {
        Map<String, String> hashes = new TreeMap<>();
        try (var paths = Files.list(directory)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                hashes.put(path.getFileName().toString(), AddressRegistryArtifactStager.sha256(path));
            }
        }
        return hashes;
    }

    private static long fileCount(Path directory) throws Exception {
        try (var paths = Files.list(directory)) {
            return paths.count();
        }
    }

    private JsonNode readJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private JsonNode readJsonFile(Path path) {
        try {
            return objectMapper.readTree(path.toFile());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static List<String> strings(JsonNode array) {
        return java.util.stream.StreamSupport.stream(array.spliterator(), false)
                .map(JsonNode::asText).toList();
    }

    private static org.assertj.core.data.Offset<Double> within(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }
}
