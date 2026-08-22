package rs.sud.eaukcija.addressregistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KoDictionaryPublisherTest {

    @TempDir
    Path tempDirectory;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void buildsReviewedAliasesDuplicateReportAndByteIdenticalReplay() throws Exception {
        Path centroids = buildCentroids(
                "reproducible", AddressRegistryGpkgFixture.Fault.DUPLICATE_NAME_ACROSS_MUNICIPALITIES);
        Path aliases = writeAliases("aliases-reviewed.json", """
                {
                  "formatVersion": 2,
                  "datasetVersion": "fixture-1",
                  "koAliases": [{
                    "recordKind": "KO_ALIAS",
                    "id": "historical-caribrod",
                    "koCode": "702013",
                    "name": "Цариброд",
                    "normalizedName": "CARIBROD",
                    "kind": "HISTORICAL",
                    "provenance": "Official municipality history reference",
                    "sourceReference": "fixture://history/caribrod",
                    "reviewer": "fixture-reviewer",
                    "reviewedAt": "2026-08-22"
                  }],
                  "municipalityAliases": [{
                    "recordKind": "MUNICIPALITY_ALIAS",
                    "id": "portal-cajetina-grad",
                    "municipalityCode": "74631",
                    "name": "Чајетина-град",
                    "normalizedName": "CAJETINA GRAD",
                    "provenance": "Reviewed portal fixture",
                    "sourceReference": "fixture://municipality/cajetina",
                    "reviewer": "fixture-reviewer",
                    "reviewedAt": "2026-08-22"
                  }]
                }
                """);
        KoDictionaryProperties properties = dictionaryProperties(centroids, aliases, "dictionary");
        KoDictionaryPublisher publisher = new KoDictionaryPublisher(
                objectMapper, Clock.fixed(Instant.parse("2026-08-22T23:59:59Z"), ZoneOffset.UTC));

        KoDictionaryPublisher.BuildResult first = publisher.build(properties);
        Map<String, String> firstHashes = fileHashes(Path.of(first.versionDirectory()));
        KoDictionaryPublisher.BuildResult replay = publisher.build(properties);

        assertThat(first.outcome()).isEqualTo("SUCCEEDED");
        assertThat(replay.outcome()).isEqualTo("UNCHANGED");
        assertThat(replay.version()).isEqualTo(first.version());
        assertThat(fileHashes(Path.of(replay.versionDirectory()))).isEqualTo(firstHashes);
        assertThat(first.koEntries()).isEqualTo(3);
        assertThat(first.duplicateNameGroups()).isEqualTo(1);
        assertThat(first.aliasOverridesApplied()).isEqualTo(1);
        assertThat(first.municipalityAliasOverridesApplied()).isEqualTo(1);
        assertThat(first.municipalityAliasSha256()).matches("[0-9a-f]{64}");

        Path version = Path.of(first.versionDirectory());
        JsonNode ko = ndjson(version.resolve("ko-dictionary.ndjson")).stream()
                .filter(entry -> entry.path("koCode").asText().equals("702013"))
                .findFirst().orElseThrow();
        assertThat(ko.path("officialNameCyrillic").asText()).isEqualTo("ДИМИТРОВГРАД");
        assertThat(ko.path("officialNameLatin").asText()).isEqualTo("DIMITROVGRAD");
        assertThat(ko.path("settlements").get(0).path("code").asText()).isEqualTo("704156");
        assertThat(ko.path("municipalities").get(0).path("code").asText()).isEqualTo("70201");
        assertThat(ko.path("aliases").get(0).path("reviewer").asText()).isEqualTo("fixture-reviewer");
        assertThat(ko.path("sourceGpkgSha256").asText()).isEqualTo(first.gpkgSha256());
        JsonNode municipalityAliasTarget = ndjson(version.resolve("ko-dictionary.ndjson")).stream()
                .filter(entry -> entry.path("koCode").asText().equals("746312"))
                .findFirst().orElseThrow();
        assertThat(municipalityAliasTarget.path("municipalities").get(0).path("aliasIds").get(0).asText())
                .isEqualTo("portal-cajetina-grad");

        JsonNode index = ndjson(version.resolve("normalized-index.ndjson")).stream()
                .filter(entry -> entry.path("normalizedName").asText().equals("CARIBROD"))
                .findFirst().orElseThrow();
        assertThat(index.path("candidates").get(0).path("koCode").asText()).isEqualTo("702013");
        assertThat(index.path("candidates").get(0).path("aliasIds").get(0).asText())
                .isEqualTo("historical-caribrod");

        JsonNode report = objectMapper.readTree(version.resolve("report.json").toFile());
        assertThat(report.path("crossMunicipalityDuplicateNameGroupCount").asLong()).isEqualTo(1);
        assertThat(report.path("koAliasOverrides").path("applied").asLong()).isEqualTo(1);
        assertThat(report.path("municipalityAliasOverrides").path("applied").asLong()).isEqualTo(1);
        assertThat(report.path("sourceRows").path("rejected").asLong()).isEqualTo(1);
        assertThat(report.path("sourceRows").path("rejectedByReason").path("RETIRED").asLong()).isEqualTo(1);
        assertThat(objectMapper.readTree(version.resolve("manifest.json").toFile())
                .path("formatVersion").asInt()).isEqualTo(2);

        KoDictionaryPublisher.Status status = publisher.status(properties.getPublishDirectory());
        assertThat(status.activeVersion()).isEqualTo(first.version());
        assertThat(status.koEntries()).isEqualTo(3);
    }

    @Test
    void statusRejectsLegacyManifestFormatBeforeReadingFormatTwoFields() throws Exception {
        Path centroids = buildCentroids("legacy-manifest", AddressRegistryGpkgFixture.Fault.NONE);
        Path aliases = writeAliases(
                "legacy-manifest-aliases.json", "{\"formatVersion\":1,\"datasetVersion\":\"empty\",\"aliases\":[]}");
        KoDictionaryProperties properties = dictionaryProperties(centroids, aliases, "legacy-manifest");
        KoDictionaryPublisher publisher = new KoDictionaryPublisher(objectMapper);
        KoDictionaryPublisher.BuildResult result = publisher.build(properties);
        Path manifestFile = Path.of(result.versionDirectory()).resolve("manifest.json");
        com.fasterxml.jackson.databind.node.ObjectNode manifest =
                (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(manifestFile.toFile());
        manifest.put("formatVersion", 1);
        Files.writeString(manifestFile, objectMapper.writeValueAsString(manifest) + "\n", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> publisher.status(properties.getPublishDirectory()))
                .isInstanceOfSatisfying(AddressRegistryImportException.class, error -> {
                    assertThat(error.code()).isEqualTo("ACTIVE_VERSION_UNSUPPORTED");
                    assertThat(error.getMessage()).contains("formatVersion 1", "expected 2");
                });
    }

    @Test
    void corruptOrTruncatedSourceLeavesPreviouslyActiveDictionaryUntouched() throws Exception {
        Path centroids = buildCentroids("corrupt", AddressRegistryGpkgFixture.Fault.NONE);
        Path aliases = writeAliases(
                "aliases-corrupt.json", "{\"formatVersion\":1,\"datasetVersion\":\"empty\",\"aliases\":[]}");
        KoDictionaryProperties properties = dictionaryProperties(centroids, aliases, "atomic");
        KoDictionaryPublisher publisher = new KoDictionaryPublisher(
                objectMapper, Clock.fixed(Instant.parse("2026-08-22T23:59:59Z"), ZoneOffset.UTC));
        String previous = publisher.build(properties).version();
        Path sourceData = activeVersionDirectory(centroids).resolve("centroids.ndjson");
        Files.writeString(sourceData, Files.readString(sourceData).substring(0, 80), StandardCharsets.UTF_8);

        assertFailure(publisher, properties, "SOURCE_FILE_CHECKSUM_MISMATCH");

        assertThat(Files.readString(properties.getPublishDirectory().resolve("ACTIVE")).trim()).isEqualTo(previous);
        assertThat(publisher.status(properties.getPublishDirectory()).activeVersion()).isEqualTo(previous);
    }

    @Test
    void olderSourceDateCannotReplaceActiveButSameDateAliasRollbackRemainsExplicit() throws Exception {
        String emptyAliases = "{\"formatVersion\":1,\"datasetVersion\":\"empty\",\"aliases\":[]}";
        Path aliases = writeAliases("aliases-downgrade.json", emptyAliases);
        Path newerCentroids = buildCentroids(
                "newer", AddressRegistryGpkgFixture.Fault.NONE, LocalDate.of(2026, 8, 22), 0);
        Path olderCentroids = buildCentroids(
                "older", AddressRegistryGpkgFixture.Fault.NONE, LocalDate.of(2026, 8, 1), 1);
        KoDictionaryProperties newer = dictionaryProperties(newerCentroids, aliases, "downgrade");
        KoDictionaryProperties older = dictionaryProperties(olderCentroids, aliases, "downgrade-old");
        older.setPublishDirectory(newer.getPublishDirectory());
        KoDictionaryPublisher publisher = new KoDictionaryPublisher(objectMapper);
        String current = publisher.build(newer).version();

        assertFailure(publisher, older, "SOURCE_DATE_DOWNGRADE");
        assertThat(Files.readString(newer.getPublishDirectory().resolve("ACTIVE")).trim()).isEqualTo(current);

        Files.writeString(aliases, """
                {
                  "formatVersion": 1, "datasetVersion": "reviewed",
                  "aliases": [{
                    "id": "caribrod", "koCode": "702013", "name": "Caribrod",
                    "kind": "HISTORICAL", "provenance": "fixture",
                    "sourceReference": "fixture://history", "reviewer": "reviewer",
                    "reviewedAt": "2026-08-22"
                  }]
                }
                """);
        KoDictionaryPublisher.BuildResult withAlias = publisher.build(newer);
        assertThat(withAlias.version()).isNotEqualTo(current);

        Files.writeString(aliases, emptyAliases);
        KoDictionaryPublisher.BuildResult aliasRollback = publisher.build(newer);
        assertThat(aliasRollback.outcome()).isEqualTo("UNCHANGED");
        assertThat(aliasRollback.version()).isEqualTo(current);
        assertThat(Files.readString(newer.getPublishDirectory().resolve("ACTIVE")).trim()).isEqualTo(current);
    }

    @Test
    void alteredPublishedVersionCannotBeOverwrittenByAReplay() throws Exception {
        Path centroids = buildCentroids("immutable", AddressRegistryGpkgFixture.Fault.NONE);
        Path aliases = writeAliases(
                "aliases-immutable.json", "{\"formatVersion\":1,\"datasetVersion\":\"empty\",\"aliases\":[]}");
        KoDictionaryProperties properties = dictionaryProperties(centroids, aliases, "immutable");
        KoDictionaryPublisher publisher = new KoDictionaryPublisher(objectMapper);
        KoDictionaryPublisher.BuildResult first = publisher.build(properties);
        Files.writeString(
                Path.of(first.versionDirectory()).resolve("report.json"),
                "tampered\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);

        assertFailure(publisher, properties, "IMMUTABLE_VERSION_CONFLICT");

        assertThat(Files.readString(properties.getPublishDirectory().resolve("ACTIVE")).trim())
                .isEqualTo(first.version());
    }

    @Test
    void invalidKoAndMunicipalityAliasRecordsFailClosed() throws Exception {
        Path centroids = buildCentroids("aliases", AddressRegistryGpkgFixture.Fault.NONE);
        Path aliases = writeAliases(
                "aliases-invalid.json", "{\"formatVersion\":1,\"datasetVersion\":\"empty\",\"aliases\":[]}");
        KoDictionaryProperties properties = dictionaryProperties(centroids, aliases, "alias-validation");
        KoDictionaryPublisher publisher = new KoDictionaryPublisher(
                objectMapper, Clock.fixed(Instant.parse("2026-08-22T23:59:59Z"), ZoneOffset.UTC));
        String previous = publisher.build(properties).version();

        Files.writeString(aliases, """
                {
                  "formatVersion": 1, "datasetVersion": "unreviewed",
                  "aliases": [{
                    "id": "unreviewed", "koCode": "702013", "name": "Caribrod",
                    "kind": "HISTORICAL", "provenance": "fixture",
                    "sourceReference": "fixture://history", "reviewedAt": "2026-08-22"
                  }]
                }
                """);
        assertFailure(publisher, properties, "ALIAS_DATA_INVALID");

        Files.writeString(aliases, """
                {
                  "formatVersion": 1, "datasetVersion": "unknown-target",
                  "aliases": [{
                    "id": "unknown", "koCode": "999999", "name": "Unknown",
                    "kind": "COLLOQUIAL", "provenance": "fixture",
                    "sourceReference": "fixture://unknown", "reviewer": "reviewer",
                    "reviewedAt": "2026-08-22"
                  }]
                }
                """);
        assertFailure(publisher, properties, "ALIAS_DATA_INVALID");

        Files.writeString(aliases, municipalityAliasesJson("""
                {
                  "recordKind": "KO_ALIAS", "id": "wrong-record-kind",
                  "municipalityCode": "70201", "name": "Димитровград-град",
                  "normalizedName": "DIMITROVGRAD GRAD", "provenance": "fixture",
                  "sourceReference": "fixture://municipality", "reviewer": "reviewer",
                  "reviewedAt": "2026-08-22"
                }
                """));
        assertFailure(publisher, properties, "ALIAS_DATA_INVALID");

        Files.writeString(aliases, municipalityAliasesJson("""
                {
                  "recordKind": "MUNICIPALITY_ALIAS", "id": "future-review",
                  "municipalityCode": "70201", "name": "Димитровград-град",
                  "normalizedName": "DIMITROVGRAD GRAD", "provenance": "fixture",
                  "sourceReference": "fixture://municipality", "reviewer": "reviewer",
                  "reviewedAt": "2026-08-23"
                }
                """));
        assertFailure(publisher, properties, "ALIAS_DATA_INVALID");

        Files.writeString(aliases, """
                {
                  "formatVersion": 2,
                  "datasetVersion": "cross-kind-duplicate",
                  "koAliases": [{
                    "recordKind": "KO_ALIAS", "id": "duplicate-across-kinds",
                    "koCode": "702013", "name": "Caribrod", "normalizedName": "CARIBROD",
                    "kind": "HISTORICAL", "provenance": "fixture",
                    "sourceReference": "fixture://ko", "reviewer": "reviewer",
                    "reviewedAt": "2026-08-22"
                  }],
                  "municipalityAliases": [{
                    "recordKind": "MUNICIPALITY_ALIAS", "id": "duplicate-across-kinds",
                    "municipalityCode": "70201", "name": "Димитровград-град",
                    "normalizedName": "DIMITROVGRAD GRAD", "provenance": "fixture",
                    "sourceReference": "fixture://municipality", "reviewer": "reviewer",
                    "reviewedAt": "2026-08-22"
                  }]
                }
                """);
        assertFailure(publisher, properties, "ALIAS_DATA_INVALID");
        assertThat(Files.readString(properties.getPublishDirectory().resolve("ACTIVE")).trim()).isEqualTo(previous);
    }

    @Test
    void invalidMunicipalityAliasMetadataNormalizationAndTargetFailClosed() throws Exception {
        Path centroids = buildCentroids("municipality-alias-validation", AddressRegistryGpkgFixture.Fault.NONE);
        Path aliases = writeAliases(
                "municipality-aliases-invalid.json",
                "{\"formatVersion\":1,\"datasetVersion\":\"empty\",\"aliases\":[]}");
        KoDictionaryProperties properties = dictionaryProperties(centroids, aliases, "municipality-alias-validation");
        KoDictionaryPublisher publisher = new KoDictionaryPublisher(objectMapper);
        String previous = publisher.build(properties).version();

        Files.writeString(aliases, municipalityAliasesJson("""
                {
                  "recordKind": "MUNICIPALITY_ALIAS", "id": "missing-reviewer",
                  "municipalityCode": "70201", "name": "Димитровград-град",
                  "normalizedName": "DIMITROVGRAD GRAD", "provenance": "fixture",
                  "sourceReference": "fixture://municipality", "reviewedAt": "2026-08-22"
                }
                """));
        assertFailure(publisher, properties, "ALIAS_DATA_INVALID");

        Files.writeString(aliases, municipalityAliasesJson("""
                {
                  "recordKind": "MUNICIPALITY_ALIAS", "id": "wrong-normalization",
                  "municipalityCode": "70201", "name": "Димитровград-град",
                  "normalizedName": "DIMITROVGRAD", "provenance": "fixture",
                  "sourceReference": "fixture://municipality", "reviewer": "reviewer",
                  "reviewedAt": "2026-08-22"
                }
                """));
        assertFailure(publisher, properties, "ALIAS_DATA_INVALID");

        Files.writeString(aliases, municipalityAliasesJson("""
                {
                  "recordKind": "MUNICIPALITY_ALIAS", "id": "unknown-target",
                  "municipalityCode": "99999", "name": "Непознат-град",
                  "normalizedName": "NEPOZNAT GRAD", "provenance": "fixture",
                  "sourceReference": "fixture://municipality", "reviewer": "reviewer",
                  "reviewedAt": "2026-08-22"
                }
                """));
        assertFailure(publisher, properties, "ALIAS_DATA_INVALID");
        assertThat(Files.readString(properties.getPublishDirectory().resolve("ACTIVE")).trim()).isEqualTo(previous);
    }

    @Test
    void collidingMunicipalityAliasesArePublishedAsExplicitNonSelectingEvidence() throws Exception {
        Path centroids = buildCentroids(
                "municipality-alias-collision",
                AddressRegistryGpkgFixture.Fault.DUPLICATE_NAME_ACROSS_MUNICIPALITIES);
        Path aliases = writeAliases("municipality-aliases-collision.json", """
                {
                  "formatVersion": 2,
                  "datasetVersion": "collision-fixture",
                  "koAliases": [],
                  "municipalityAliases": [
                    {
                      "recordKind": "MUNICIPALITY_ALIAS", "id": "shared-70201",
                      "municipalityCode": "70201", "name": "Заједничка-портал",
                      "normalizedName": "ZAJEDNICKA PORTAL", "provenance": "fixture",
                      "sourceReference": "fixture://municipality/70201", "reviewer": "reviewer",
                      "reviewedAt": "2026-08-22"
                    },
                    {
                      "recordKind": "MUNICIPALITY_ALIAS", "id": "shared-74631",
                      "municipalityCode": "74631", "name": "Заједничка-портал",
                      "normalizedName": "ZAJEDNICKA PORTAL", "provenance": "fixture",
                      "sourceReference": "fixture://municipality/74631", "reviewer": "reviewer",
                      "reviewedAt": "2026-08-22"
                    },
                    {
                      "recordKind": "MUNICIPALITY_ALIAS", "id": "official-name-collision-74631",
                      "municipalityCode": "74631", "name": "Димитровград",
                      "normalizedName": "DIMITROVGRAD", "provenance": "fixture",
                      "sourceReference": "fixture://municipality/official-collision", "reviewer": "reviewer",
                      "reviewedAt": "2026-08-22"
                    }
                  ]
                }
                """);
        KoDictionaryPublisher.BuildResult result = new KoDictionaryPublisher(objectMapper)
                .build(dictionaryProperties(centroids, aliases, "municipality-alias-collision"));

        JsonNode report = objectMapper.readTree(Path.of(result.versionDirectory()).resolve("report.json").toFile());
        JsonNode collisions = report.path("municipalityAliasOverrides").path("collisions");
        assertThat(collisions).hasSize(2);
        JsonNode officialCollision = java.util.stream.StreamSupport.stream(collisions.spliterator(), false)
                .filter(collision -> collision.path("normalizedName").asText().equals("DIMITROVGRAD"))
                .findFirst().orElseThrow();
        assertThat(officialCollision.path("municipalityCodes")).extracting(JsonNode::asText)
                .containsExactly("70201", "74631");
        assertThat(officialCollision.path("aliasIds")).extracting(JsonNode::asText)
                .containsExactly("official-name-collision-74631");
    }

    @Test
    void municipalityAliasTargetWithoutAnyKoRelationshipFailsBeforePublication() throws Exception {
        Path centroids = buildCentroids("orphan-municipality", AddressRegistryGpkgFixture.Fault.NONE);
        appendOrphanMunicipality(centroids, "99999");
        Path aliases = writeAliases("orphan-municipality-alias.json", municipalityAliasesJson("""
                {
                  "recordKind": "MUNICIPALITY_ALIAS", "id": "orphan-target",
                  "municipalityCode": "99999", "name": "Град без КО",
                  "normalizedName": "GRAD BEZ KO", "provenance": "fixture",
                  "sourceReference": "fixture://municipality/orphan", "reviewer": "reviewer",
                  "reviewedAt": "2026-08-22"
                }
                """));
        KoDictionaryProperties properties = dictionaryProperties(centroids, aliases, "orphan-target");

        assertFailure(new KoDictionaryPublisher(objectMapper), properties, "ALIAS_DATA_INVALID");
        assertThat(Files.exists(properties.getPublishDirectory().resolve("ACTIVE"))).isFalse();
    }

    @Test
    void implausibleKoCountAndBrokenRelationshipsCannotPublish() throws Exception {
        Path centroids = buildCentroids("validation", AddressRegistryGpkgFixture.Fault.NONE);
        Path aliases = writeAliases(
                "aliases-validation.json", "{\"formatVersion\":1,\"datasetVersion\":\"empty\",\"aliases\":[]}");
        KoDictionaryProperties countProperties = dictionaryProperties(centroids, aliases, "count");
        countProperties.setMinimumKoEntries(4);
        assertFailure(new KoDictionaryPublisher(objectMapper), countProperties, "KO_COUNT_SANITY");
        assertThat(Files.exists(countProperties.getPublishDirectory().resolve("ACTIVE"))).isFalse();

        rewriteFirstKoSettlementReference(centroids, "missing-settlement");
        KoDictionaryProperties referenceProperties = dictionaryProperties(centroids, aliases, "references");
        assertFailure(new KoDictionaryPublisher(objectMapper), referenceProperties, "REFERENTIAL_INTEGRITY");
        assertThat(Files.exists(referenceProperties.getPublishDirectory().resolve("ACTIVE"))).isFalse();
    }

    private Path buildCentroids(String name, AddressRegistryGpkgFixture.Fault fault) throws Exception {
        return buildCentroids(name, fault, LocalDate.of(2026, 8, 22), 0);
    }

    private Path buildCentroids(
            String name,
            AddressRegistryGpkgFixture.Fault fault,
            LocalDate sourceDate,
            int variant) throws Exception {
        Path sourceRoot = tempDirectory.resolve(name + "-centroids");
        Path gpkg = AddressRegistryGpkgFixture.create(tempDirectory.resolve(name + "-gpkg"), variant, fault);
        AddressRegistryCentroidExtractProperties properties = new AddressRegistryCentroidExtractProperties();
        properties.setSourceUri(gpkg.toUri());
        properties.setSourceDate(sourceDate);
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
        properties.setMaximumGpkgBytes(10 * 1024 * 1024);
        properties.setWorkDirectory(tempDirectory.resolve(name + "-work"));
        properties.setPublishDirectory(sourceRoot);
        new AddressRegistryCentroidExtractor(
                new AddressRegistryArtifactStager(), new GeoPackageInspector(), objectMapper).build(properties);
        return sourceRoot;
    }

    private KoDictionaryProperties dictionaryProperties(Path centroids, Path aliases, String name) {
        KoDictionaryProperties properties = new KoDictionaryProperties();
        properties.setCentroidDirectory(centroids);
        properties.setPublishDirectory(tempDirectory.resolve(name + "-dictionary"));
        properties.setAliasOverrides(aliases);
        properties.setMinimumKoEntries(1);
        properties.setMaximumKoEntries(10);
        return properties;
    }

    private Path writeAliases(String name, String json) throws Exception {
        Path file = tempDirectory.resolve(name);
        Files.writeString(file, json, StandardCharsets.UTF_8);
        return file;
    }

    private static String municipalityAliasesJson(String alias) {
        return """
                {
                  "formatVersion": 2,
                  "datasetVersion": "invalid-fixture",
                  "koAliases": [],
                  "municipalityAliases": [%s]
                }
                """.formatted(alias);
    }

    private void rewriteFirstKoSettlementReference(Path centroids, String replacement) throws Exception {
        Path version = activeVersionDirectory(centroids);
        Path file = version.resolve("centroids.ndjson");
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int index = 0; index < lines.size(); index++) {
            JsonNode row = objectMapper.readTree(lines.get(index));
            if (row.path("level").asText().equals("KO")) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) row)
                        .set("settlementCodes", objectMapper.createArrayNode().add(replacement));
                lines.set(index, objectMapper.writeValueAsString(row));
                break;
            }
        }
        Files.write(file, (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
        Path manifestFile = version.resolve("manifest.json");
        com.fasterxml.jackson.databind.node.ObjectNode manifest =
                (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(manifestFile.toFile());
        for (JsonNode evidence : manifest.path("files")) {
            if (evidence.path("name").asText().equals("centroids.ndjson")) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) evidence).put("bytes", Files.size(file));
                ((com.fasterxml.jackson.databind.node.ObjectNode) evidence).put(
                        "sha256", AddressRegistryArtifactStager.sha256(file));
            }
        }
        Files.writeString(manifestFile, objectMapper.writeValueAsString(manifest) + "\n", StandardCharsets.UTF_8);
    }

    private void appendOrphanMunicipality(Path centroids, String municipalityCode) throws Exception {
        Path version = activeVersionDirectory(centroids);
        Path file = version.resolve("centroids.ndjson");
        JsonNode template;
        try (var lines = Files.lines(file, StandardCharsets.UTF_8)) {
            template = lines.map(line -> {
                try {
                    return objectMapper.readTree(line);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }).filter(row -> row.path("level").asText().equals("MUNICIPALITY"))
                    .findFirst().orElseThrow();
        }
        com.fasterxml.jackson.databind.node.ObjectNode orphan = template.deepCopy();
        orphan.put("officialCode", municipalityCode);
        orphan.put("nameCyrillic", "ГРАД БЕЗ КО");
        orphan.put("nameLatin", "GRAD BEZ KO");
        orphan.put("memberPointCount", 1);
        Files.writeString(file, objectMapper.writeValueAsString(orphan) + "\n", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND);

        Path manifestFile = version.resolve("manifest.json");
        com.fasterxml.jackson.databind.node.ObjectNode manifest =
                (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(manifestFile.toFile());
        com.fasterxml.jackson.databind.node.ObjectNode counts =
                (com.fasterxml.jackson.databind.node.ObjectNode) manifest.path("content").path("centroidCounts");
        counts.put("MUNICIPALITY", counts.path("MUNICIPALITY").asLong() + 1);
        for (JsonNode evidence : manifest.path("files")) {
            if (evidence.path("name").asText().equals("centroids.ndjson")) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) evidence).put("bytes", Files.size(file));
                ((com.fasterxml.jackson.databind.node.ObjectNode) evidence).put(
                        "sha256", AddressRegistryArtifactStager.sha256(file));
            }
        }
        Files.writeString(manifestFile, objectMapper.writeValueAsString(manifest) + "\n", StandardCharsets.UTF_8);
    }

    private Path activeVersionDirectory(Path root) throws Exception {
        return root.resolve("versions").resolve(Files.readString(root.resolve("ACTIVE")).trim());
    }

    private List<JsonNode> ndjson(Path file) throws Exception {
        try (var lines = Files.lines(file, StandardCharsets.UTF_8)) {
            return lines.map(line -> {
                try {
                    return objectMapper.readTree(line);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }).toList();
        }
    }

    private Map<String, String> fileHashes(Path directory) throws Exception {
        TreeMap<String, String> hashes = new TreeMap<>();
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                hashes.put(file.getFileName().toString(), AddressRegistryArtifactStager.sha256(file));
            }
        }
        return hashes;
    }

    private static void assertFailure(
            KoDictionaryPublisher publisher,
            KoDictionaryProperties properties,
            String code) {
        assertThatThrownBy(() -> publisher.build(properties))
                .isInstanceOfSatisfying(AddressRegistryImportException.class,
                        error -> assertThat(error.code()).isEqualTo(code));
    }
}
