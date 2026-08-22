package rs.sud.eaukcija.addressregistry;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Streams the official GPKG into a small deterministic centroid extract and
 * publishes it through an atomic filesystem pointer. No house-number row is
 * retained outside the source artifact.
 */
final class AddressRegistryCentroidExtractor {

    private static final String SOURCE_SELECT = """
            SELECT fid, geom, vrsta_stanja, vrsta_stanja_lat, retired,
                   ko_maticni_broj, kat_opstina_ime, kat_opstina_ime_lat,
                   naselje_maticni_broj, naselje_ime, naselje_ime_lat,
                   opstina_maticni_broj, opstina_ime, opstina_ime_lat
            FROM kucni_broj
            ORDER BY fid
            """;
    private static final String ACTIVE_FILE = "ACTIVE";
    private static final String CENTROIDS_FILE = "centroids.ndjson";
    private static final String REPORT_FILE = "report.json";
    private static final String ATTRIBUTION_FILE = "ATTRIBUTION.md";
    private static final String MANIFEST_FILE = "manifest.json";
    private static final double SERBIA_MIN_LONGITUDE = 18.0;
    private static final double SERBIA_MIN_LATITUDE = 41.5;
    private static final double SERBIA_MAX_LONGITUDE = 23.5;
    private static final double SERBIA_MAX_LATITUDE = 46.5;

    private final AddressRegistryArtifactStager stager;
    private final GeoPackageInspector inspector;
    private final ObjectMapper objectMapper;

    AddressRegistryCentroidExtractor(
            AddressRegistryArtifactStager stager,
            GeoPackageInspector inspector,
            ObjectMapper objectMapper) {
        this.stager = stager;
        this.inspector = inspector;
        this.objectMapper = objectMapper;
    }

    BuildResult build(AddressRegistryCentroidExtractProperties properties) {
        properties.validateForBuild();
        UUID runId = UUID.randomUUID();
        Instant started = Instant.now();
        AddressRegistryArtifactStager.Artifact artifact = null;
        Path stagedVersion = null;
        Path publishDirectory = properties.getPublishDirectory().toAbsolutePath().normalize();
        long validationMillis = 0;
        long extractionMillis = 0;
        long publicationMillis = 0;
        try {
            createPublicationLayout(publishDirectory);
            try (FileChannel lockChannel = FileChannel.open(
                    publishDirectory.resolve(".publish.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = lockChannel.lock()) {
                pruneAbandonedStaging(publishDirectory.resolve(".staging"));
                artifact = stager.stage(properties);
                Instant validationStarted = Instant.now();
                GeoPackageInspector.Schema schema = inspector.inspect(artifact.gpkg(), properties);
                validationMillis = Duration.between(validationStarted, Instant.now()).toMillis();

                Instant extractionStarted = Instant.now();
                Extraction extraction = extract(artifact.gpkg(), schema.rowCount(), properties);
                extractionMillis = Duration.between(extractionStarted, Instant.now()).toMillis();
                String version = properties.getSourceDate() + "-" + artifact.gpkgSha256();

                stagedVersion = Files.createTempDirectory(publishDirectory.resolve(".staging"), "version-");
                writeVersion(stagedVersion, version, properties, artifact, schema, extraction);
                long publishedBytes = directoryBytes(stagedVersion);

                Instant publicationStarted = Instant.now();
                Publication publication = publishVersion(publishDirectory, stagedVersion, version, properties.getSourceDate());
                stagedVersion = null;
                publicationMillis = Duration.between(publicationStarted, Instant.now()).toMillis();

                long totalMillis = Duration.between(started, Instant.now()).toMillis();
                BuildResult result = new BuildResult(
                        publication.outcome(), runId, version, publication.activeVersionBeforeBuild(),
                        properties.getSourceDate().toString(), artifact.sourceSha256(), artifact.gpkgSha256(),
                        schema.fingerprint(), schema.rowCount(), extraction.activeRows(), extraction.rejectedRows(),
                        extraction.count(Level.KO), extraction.count(Level.SETTLEMENT),
                        extraction.count(Level.MUNICIPALITY), extraction.duplicateNames().size(),
                        publishedBytes, artifact.downloadMillis(), validationMillis, extractionMillis,
                        publicationMillis, totalMillis,
                        publication.versionDirectory().toString());
                writeRunReport(publishDirectory, result, started, Instant.now(), properties, artifact, null);
                return result;
            }
        } catch (AddressRegistryImportException e) {
            writeFailedRunReport(publishDirectory, runId, started, properties, artifact,
                    validationMillis, extractionMillis, publicationMillis, e);
            throw e;
        } catch (IOException | SQLException e) {
            AddressRegistryImportException failure = new AddressRegistryImportException(
                    "CENTROID_EXTRACT_FAILED", "could not build Address Registry centroid extract", e);
            writeFailedRunReport(publishDirectory, runId, started, properties, artifact,
                    validationMillis, extractionMillis, publicationMillis, failure);
            throw failure;
        } catch (RuntimeException e) {
            AddressRegistryImportException failure = classify(e);
            writeFailedRunReport(publishDirectory, runId, started, properties, artifact,
                    validationMillis, extractionMillis, publicationMillis, failure);
            throw failure;
        } finally {
            deleteDirectory(stagedVersion);
            if (artifact != null) {
                artifact.close();
            }
        }
    }

    Status status(Path configuredPublishDirectory) {
        Path publishDirectory = configuredPublishDirectory.toAbsolutePath().normalize();
        String active = readActiveVersion(publishDirectory);
        if (active == null) {
            return new Status(null, null, null, 0, 0, 0, null);
        }
        Path versionDirectory = safeVersionDirectory(publishDirectory, active);
        Path manifest = versionDirectory.resolve(MANIFEST_FILE);
        if (!Files.isRegularFile(manifest)) {
            throw new AddressRegistryImportException(
                    "ACTIVE_VERSION_CORRUPT", "active centroid version has no manifest: " + active);
        }
        try {
            JsonNode root = objectMapper.readTree(manifest.toFile());
            JsonNode counts = root.path("content").path("centroidCounts");
            return new Status(
                    active,
                    root.path("source").path("datasetDate").asText(),
                    root.path("source").path("gpkgSha256").asText(),
                    counts.path("KO").asLong(),
                    counts.path("SETTLEMENT").asLong(),
                    counts.path("MUNICIPALITY").asLong(),
                    versionDirectory.toString());
        } catch (IOException e) {
            throw new AddressRegistryImportException(
                    "ACTIVE_VERSION_CORRUPT", "could not read active centroid manifest", e);
        }
    }

    private Extraction extract(
            Path gpkg,
            long expectedRows,
            AddressRegistryCentroidExtractProperties properties) throws SQLException {
        EnumMap<Level, NavigableMap<String, Aggregate>> aggregates = new EnumMap<>(Level.class);
        for (Level level : Level.values()) {
            aggregates.put(level, new TreeMap<>());
        }
        NavigableMap<String, Long> rejectedByReason = new TreeMap<>();
        long seen = 0;
        long active = 0;
        try (Connection sqlite = GeoPackageInspector.openReadOnly(gpkg);
             PreparedStatement source = sqlite.prepareStatement(SOURCE_SELECT)) {
            source.setFetchSize(properties.getFetchSize());
            try (ResultSet rows = source.executeQuery()) {
                while (rows.next()) {
                    seen++;
                    String statusCyrillic = text(rows, "vrsta_stanja");
                    String statusLatin = text(rows, "vrsta_stanja_lat");
                    String retired = text(rows, "retired");
                    if (!AddressRegistryNormalizer.isActive(statusCyrillic, statusLatin, retired)) {
                        String reason = retired == null ? "INACTIVE_STATUS" : "RETIRED";
                        rejectedByReason.merge(reason, 1L, Long::sum);
                        continue;
                    }

                    GeoPackagePointReader.Point sourcePoint = GeoPackagePointReader.read(rows, "geom");
                    Etrs89Utm34ToWgs84.Point point = Etrs89Utm34ToWgs84.transform(
                            sourcePoint.easting(), sourcePoint.northing());
                    validateSerbiaBounds(point, rows.getLong("fid"));

                    String koCode = required(rows, "ko_maticni_broj");
                    String koName = requiredName(rows, "kat_opstina_ime");
                    String koLatin = optionalName(rows, "kat_opstina_ime_lat");
                    String settlementCode = required(rows, "naselje_maticni_broj");
                    String settlementName = requiredName(rows, "naselje_ime");
                    String settlementLatin = optionalName(rows, "naselje_ime_lat");
                    String municipalityCode = required(rows, "opstina_maticni_broj");
                    String municipalityName = requiredName(rows, "opstina_ime");
                    String municipalityLatin = optionalName(rows, "opstina_ime_lat");

                    aggregate(aggregates, Level.KO, koCode, koName, koLatin,
                            settlementCode, municipalityCode, point);
                    aggregate(aggregates, Level.SETTLEMENT, settlementCode, settlementName, settlementLatin,
                            null, municipalityCode, point);
                    aggregate(aggregates, Level.MUNICIPALITY, municipalityCode, municipalityName, municipalityLatin,
                            null, null, point);
                    active++;
                }
            }
        }

        if (seen != expectedRows) {
            throw new AddressRegistryImportException(
                    "ROW_COUNT_CHANGED",
                    "GPKG changed while being read: inspected " + expectedRows + " rows but streamed " + seen);
        }
        long rejected = rejectedByReason.values().stream().mapToLong(Long::longValue).sum();
        if (active + rejected != seen) {
            throw new AddressRegistryImportException("ROW_ACCOUNTING", "source row accounting is inconsistent");
        }
        long minimumActive = Math.max(1, (long) Math.ceil(seen * properties.getMinimumActiveFraction()));
        if (active < minimumActive) {
            throw new AddressRegistryImportException(
                    "ACTIVE_ROW_COUNT_SANITY",
                    "only " + active + " of " + seen + " source rows are active; at least " + minimumActive
                            + " are required before publication");
        }
        validateCount(Level.KO, aggregates.get(Level.KO).size(),
                properties.getMinimumKoCentroids(), properties.getMaximumKoCentroids());
        validateCount(Level.SETTLEMENT, aggregates.get(Level.SETTLEMENT).size(),
                properties.getMinimumSettlementCentroids(), properties.getMaximumSettlementCentroids());
        validateCount(Level.MUNICIPALITY, aggregates.get(Level.MUNICIPALITY).size(),
                properties.getMinimumMunicipalityCentroids(), properties.getMaximumMunicipalityCentroids());

        List<Centroid> centroids = new ArrayList<>();
        for (Level level : Level.values()) {
            aggregates.get(level).values().forEach(aggregate -> centroids.add(aggregate.finish()));
        }
        List<DuplicateNameGroup> duplicateNames = duplicateNames(centroids);
        return new Extraction(
                List.copyOf(centroids), Collections.unmodifiableNavigableMap(new TreeMap<>(rejectedByReason)),
                List.copyOf(duplicateNames),
                seen, active, rejected);
    }

    private static void aggregate(
            EnumMap<Level, NavigableMap<String, Aggregate>> aggregates,
            Level level,
            String code,
            String name,
            String latinName,
            String settlementCode,
            String municipalityCode,
            Etrs89Utm34ToWgs84.Point point) {
        aggregates.get(level)
                .computeIfAbsent(code, ignored -> new Aggregate(level, code))
                .add(name, latinName, settlementCode, municipalityCode, point);
    }

    private static void validateSerbiaBounds(Etrs89Utm34ToWgs84.Point point, long fid) {
        if (point.longitude() < SERBIA_MIN_LONGITUDE || point.longitude() > SERBIA_MAX_LONGITUDE
                || point.latitude() < SERBIA_MIN_LATITUDE || point.latitude() > SERBIA_MAX_LATITUDE) {
            throw new AddressRegistryImportException(
                    "GEOMETRY_OUTSIDE_SERBIA",
                    "source fid " + fid + " transforms outside the Serbia sanity envelope");
        }
    }

    private static void validateCount(Level level, int count, int minimum, int maximum) {
        if (count < minimum || count > maximum) {
            throw new AddressRegistryImportException(
                    "CENTROID_COUNT_SANITY",
                    level + " centroid count " + count + " is outside [" + minimum + ", " + maximum + "]");
        }
    }

    private static List<DuplicateNameGroup> duplicateNames(List<Centroid> centroids) {
        List<DuplicateNameGroup> duplicates = new ArrayList<>();
        for (List<Centroid> group : NormalizedNameGroups.connectedComponents(
                centroids,
                centroid -> normalizedNameVariants(centroid).stream()
                        .map(normalized -> centroid.level() + "|" + normalized)
                        .toList())) {
            TreeSet<String> officialCodes = new TreeSet<>();
            TreeSet<String> municipalityCodes = new TreeSet<>();
            TreeSet<String> normalizedNames = new TreeSet<>();
            for (Centroid centroid : group) {
                officialCodes.add(centroid.officialCode());
                normalizedNames.addAll(normalizedNameVariants(centroid));
                if (centroid.level() == Level.MUNICIPALITY) {
                    municipalityCodes.add(centroid.officialCode());
                } else {
                    municipalityCodes.addAll(centroid.municipalityCodes());
                }
            }
            if (officialCodes.size() > 1) {
                Centroid first = group.get(0);
                duplicates.add(new DuplicateNameGroup(
                        first.level(), List.copyOf(normalizedNames),
                        List.copyOf(officialCodes), List.copyOf(municipalityCodes),
                        municipalityCodes.size() > 1));
            }
        }
        duplicates.sort(Comparator
                .comparing(DuplicateNameGroup::level)
                .thenComparing(group -> group.normalizedNames().get(0))
                .thenComparing(group -> group.officialCodes().get(0)));
        return duplicates;
    }

    private static Set<String> normalizedNameVariants(Centroid centroid) {
        TreeSet<String> normalized = new TreeSet<>();
        normalized.add(AddressRegistryNormalizer.name(centroid.nameCyrillic()));
        if (centroid.nameLatin() != null) {
            normalized.add(AddressRegistryNormalizer.name(centroid.nameLatin()));
        }
        return normalized;
    }

    private void writeVersion(
            Path directory,
            String version,
            AddressRegistryCentroidExtractProperties properties,
            AddressRegistryArtifactStager.Artifact artifact,
            GeoPackageInspector.Schema schema,
            Extraction extraction) throws IOException {
        writeCentroids(directory.resolve(CENTROIDS_FILE), version, properties, artifact, extraction.centroids());
        writeReport(directory.resolve(REPORT_FILE), version, properties, artifact, extraction);
        Files.writeString(
                directory.resolve(ATTRIBUTION_FILE), attribution(properties.getCanonicalUrl()),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);

        List<FileEvidence> evidence = List.of(
                evidence(directory.resolve(CENTROIDS_FILE)),
                evidence(directory.resolve(REPORT_FILE)),
                evidence(directory.resolve(ATTRIBUTION_FILE)));
        writeManifest(directory.resolve(MANIFEST_FILE), version, properties, artifact, schema, extraction, evidence);
    }

    private void writeCentroids(
            Path target,
            String version,
            AddressRegistryCentroidExtractProperties properties,
            AddressRegistryArtifactStager.Artifact artifact,
            List<Centroid> centroids) throws IOException {
        try (JsonGenerator json = objectMapper.getFactory().createGenerator(Files.newOutputStream(
                target, StandardOpenOption.CREATE_NEW))) {
            json.setRootValueSeparator(null);
            for (Centroid centroid : centroids) {
                json.writeStartObject();
                json.writeStringField("extractVersion", version);
                json.writeStringField("sourceDate", properties.getSourceDate().toString());
                json.writeStringField("sourceGpkgSha256", artifact.gpkgSha256());
                json.writeStringField("level", centroid.level().name());
                json.writeStringField("officialCode", centroid.officialCode());
                json.writeStringField("nameCyrillic", centroid.nameCyrillic());
                if (centroid.nameLatin() == null) {
                    json.writeNullField("nameLatin");
                } else {
                    json.writeStringField("nameLatin", centroid.nameLatin());
                }
                writeStringArray(json, "settlementCodes", centroid.settlementCodes());
                writeStringArray(json, "municipalityCodes", centroid.municipalityCodes());
                json.writeNumberField("memberPointCount", centroid.memberPointCount());
                json.writeFieldName("longitude");
                json.writeNumber(coordinate(centroid.longitude()));
                json.writeFieldName("latitude");
                json.writeNumber(coordinate(centroid.latitude()));
                json.writeEndObject();
                json.writeRaw('\n');
            }
        }
    }

    private void writeReport(
            Path target,
            String version,
            AddressRegistryCentroidExtractProperties properties,
            AddressRegistryArtifactStager.Artifact artifact,
            Extraction extraction) throws IOException {
        try (JsonGenerator json = objectMapper.getFactory().createGenerator(Files.newOutputStream(
                target, StandardOpenOption.CREATE_NEW))) {
            json.writeStartObject();
            json.writeStringField("extractVersion", version);
            json.writeStringField("sourceDate", properties.getSourceDate().toString());
            json.writeStringField("sourceGpkgSha256", artifact.gpkgSha256());
            json.writeObjectFieldStart("centroidCounts");
            for (Level level : Level.values()) {
                json.writeNumberField(level.name(), extraction.count(level));
            }
            json.writeEndObject();
            json.writeNumberField("duplicateNameGroupCount", extraction.duplicateNames().size());
            json.writeNumberField("crossMunicipalityDuplicateNameGroupCount",
                    extraction.duplicateNames().stream()
                            .filter(DuplicateNameGroup::spansMultipleMunicipalities).count());
            json.writeArrayFieldStart("duplicateNameGroups");
            for (DuplicateNameGroup duplicate : extraction.duplicateNames()) {
                json.writeStartObject();
                json.writeStringField("level", duplicate.level().name());
                json.writeStringField("normalizedName", duplicate.normalizedNames().get(0));
                writeStringArray(json, "normalizedNames", duplicate.normalizedNames());
                writeStringArray(json, "officialCodes", duplicate.officialCodes());
                writeStringArray(json, "municipalityCodes", duplicate.municipalityCodes());
                json.writeBooleanField("spansMultipleMunicipalities", duplicate.spansMultipleMunicipalities());
                json.writeEndObject();
            }
            json.writeEndArray();
            List<Centroid> nameVariants = extraction.centroids().stream()
                    .filter(centroid -> centroid.nameCyrillicVariants().size() > 1
                            || centroid.nameLatinVariants().size() > 1)
                    .toList();
            json.writeNumberField("nameVariantEntryCount", nameVariants.size());
            json.writeArrayFieldStart("nameVariantEntries");
            for (Centroid centroid : nameVariants) {
                json.writeStartObject();
                json.writeStringField("level", centroid.level().name());
                json.writeStringField("officialCode", centroid.officialCode());
                writeStringArray(json, "nameCyrillicVariants", centroid.nameCyrillicVariants());
                writeStringArray(json, "nameLatinVariants", centroid.nameLatinVariants());
                json.writeEndObject();
            }
            json.writeEndArray();
            List<Centroid> ambiguousParents = extraction.centroids().stream()
                    .filter(centroid -> centroid.level() != Level.MUNICIPALITY)
                    .filter(centroid -> centroid.municipalityCodes().size() > 1)
                    .toList();
            json.writeNumberField("ambiguousParentEntryCount", ambiguousParents.size());
            json.writeArrayFieldStart("ambiguousParentEntries");
            for (Centroid centroid : ambiguousParents) {
                json.writeStartObject();
                json.writeStringField("level", centroid.level().name());
                json.writeStringField("officialCode", centroid.officialCode());
                json.writeStringField("nameCyrillic", centroid.nameCyrillic());
                if (centroid.nameLatin() == null) {
                    json.writeNullField("nameLatin");
                } else {
                    json.writeStringField("nameLatin", centroid.nameLatin());
                }
                writeStringArray(json, "municipalityCodes", centroid.municipalityCodes());
                json.writeEndObject();
            }
            json.writeEndArray();
            json.writeObjectFieldStart("sourceRows");
            json.writeNumberField("total", extraction.sourceRows());
            json.writeNumberField("active", extraction.activeRows());
            json.writeNumberField("rejected", extraction.rejectedRows());
            json.writeObjectFieldStart("rejectedByReason");
            for (Map.Entry<String, Long> entry : extraction.rejectedByReason().entrySet()) {
                json.writeNumberField(entry.getKey(), entry.getValue());
            }
            json.writeEndObject();
            json.writeEndObject();
            json.writeArrayFieldStart("validationGatesPassed");
            json.writeString("SOURCE_ROW_COUNT");
            json.writeString("ACTIVE_ROW_FRACTION");
            json.writeString("UNIQUE_OFFICIAL_CODES");
            json.writeString("REQUIRED_OFFICIAL_NAMES");
            json.writeString("ACTIVE_GEOMETRIES_WITHIN_SERBIA");
            json.writeString("CENTROID_COUNT_MAGNITUDE");
            json.writeEndArray();
            json.writeEndObject();
            json.writeRaw('\n');
        }
    }

    private void writeManifest(
            Path target,
            String version,
            AddressRegistryCentroidExtractProperties properties,
            AddressRegistryArtifactStager.Artifact artifact,
            GeoPackageInspector.Schema schema,
            Extraction extraction,
            List<FileEvidence> files) throws IOException {
        try (JsonGenerator json = objectMapper.getFactory().createGenerator(Files.newOutputStream(
                target, StandardOpenOption.CREATE_NEW))) {
            json.writeStartObject();
            json.writeNumberField("formatVersion", 1);
            json.writeStringField("extractVersion", version);
            json.writeObjectFieldStart("source");
            json.writeStringField("canonicalUrl", properties.getCanonicalUrl());
            json.writeStringField("datasetDate", properties.getSourceDate().toString());
            json.writeNumberField("sourceBytes", artifact.sourceBytes());
            json.writeStringField("sourceSha256", artifact.sourceSha256());
            if (artifact.archiveMember() == null) {
                json.writeNullField("archiveMember");
            } else {
                json.writeStringField("archiveMember", artifact.archiveMember());
            }
            json.writeNumberField("gpkgBytes", artifact.gpkgBytes());
            json.writeStringField("gpkgSha256", artifact.gpkgSha256());
            json.writeStringField("table", schema.table());
            json.writeStringField("geometryColumn", schema.geometryColumn());
            json.writeStringField("geometryType", schema.geometryType());
            json.writeStringField("schemaSha256", schema.fingerprint());
            json.writeNumberField("sourceCrs", schema.sourceSrid());
            json.writeNumberField("targetCrs", 4326);
            json.writeNumberField("rowCount", schema.rowCount());
            json.writeEndObject();
            json.writeObjectFieldStart("license");
            json.writeStringField("publisher", "Republički geodetski zavod (RGZ)");
            json.writeStringField("identifier", "sodl");
            json.writeStringField("name", "Srpska licenca za otvorene podatke");
            json.writeStringField("declaredUpdateFrequency", "weekly");
            json.writeEndObject();
            json.writeObjectFieldStart("content");
            json.writeNumberField("activeSourceRows", extraction.activeRows());
            json.writeNumberField("rejectedSourceRows", extraction.rejectedRows());
            json.writeObjectFieldStart("centroidCounts");
            for (Level level : Level.values()) {
                json.writeNumberField(level.name(), extraction.count(level));
            }
            json.writeEndObject();
            json.writeEndObject();
            json.writeArrayFieldStart("files");
            for (FileEvidence file : files) {
                json.writeStartObject();
                json.writeStringField("name", file.name());
                json.writeNumberField("bytes", file.bytes());
                json.writeStringField("sha256", file.sha256());
                json.writeEndObject();
            }
            json.writeEndArray();
            json.writeEndObject();
            json.writeRaw('\n');
        }
    }

    private Publication publishVersion(
            Path publishDirectory,
            Path stagedVersion,
            String version,
            LocalDate sourceDate) throws IOException {
        Path destination = safeVersionDirectory(publishDirectory, version);
        String active = readActiveVersion(publishDirectory);
        if (active != null && !active.equals(version)) {
            LocalDate activeDate = activeSourceDate(publishDirectory, active);
            if (sourceDate.isBefore(activeDate)) {
                throw new AddressRegistryImportException(
                        "SOURCE_DATE_DOWNGRADE",
                        "snapshot date " + sourceDate + " is older than active extract date " + activeDate
                                + "; implicit downgrade is not allowed");
            }
        }
        String outcome;
        if (Files.exists(destination)) {
            if (!directoriesEqual(stagedVersion, destination)) {
                throw new AddressRegistryImportException(
                        "IMMUTABLE_VERSION_CONFLICT",
                        "published centroid version differs from a reproducible rebuild: " + version);
            }
            deleteDirectory(stagedVersion);
            outcome = "UNCHANGED";
        } else {
            atomicMove(stagedVersion, destination, false);
            outcome = "SUCCEEDED";
        }

        if (!version.equals(active)) {
            writeActiveVersion(publishDirectory, version);
        }
        return new Publication(outcome, active, destination);
    }

    private LocalDate activeSourceDate(Path publishDirectory, String active) throws IOException {
        Path manifest = safeVersionDirectory(publishDirectory, active).resolve(MANIFEST_FILE);
        if (!Files.isRegularFile(manifest)) {
            throw new AddressRegistryImportException(
                    "ACTIVE_VERSION_CORRUPT", "active centroid version has no manifest: " + active);
        }
        JsonNode root = objectMapper.readTree(manifest.toFile());
        try {
            return LocalDate.parse(root.path("source").path("datasetDate").asText());
        } catch (RuntimeException e) {
            throw new AddressRegistryImportException(
                    "ACTIVE_VERSION_CORRUPT", "active centroid manifest has no valid source date", e);
        }
    }

    private static void createPublicationLayout(Path publishDirectory) throws IOException {
        Files.createDirectories(publishDirectory.resolve("versions"));
        Files.createDirectories(publishDirectory.resolve("runs"));
        Files.createDirectories(publishDirectory.resolve(".staging"));
    }

    private static void pruneAbandonedStaging(Path stagingDirectory) throws IOException {
        try (var paths = Files.list(stagingDirectory)) {
            for (Path candidate : paths.toList()) {
                deleteDirectoryStrict(candidate);
            }
        }
    }

    private static void deleteDirectoryStrict(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private static Path safeVersionDirectory(Path publishDirectory, String version) {
        if (version == null || !version.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}-[0-9a-f]{64}")) {
            throw new AddressRegistryImportException("ACTIVE_VERSION_CORRUPT", "invalid centroid version id");
        }
        Path versions = publishDirectory.resolve("versions").toAbsolutePath().normalize();
        Path resolved = versions.resolve(version).normalize();
        if (!resolved.getParent().equals(versions)) {
            throw new AddressRegistryImportException("ACTIVE_VERSION_CORRUPT", "centroid version escapes versions directory");
        }
        return resolved;
    }

    private static String readActiveVersion(Path publishDirectory) {
        Path active = publishDirectory.resolve(ACTIVE_FILE);
        if (!Files.exists(active)) {
            return null;
        }
        try {
            String version = Files.readString(active, StandardCharsets.UTF_8).trim();
            safeVersionDirectory(publishDirectory, version);
            return version;
        } catch (IOException e) {
            throw new AddressRegistryImportException("ACTIVE_VERSION_CORRUPT", "could not read active version", e);
        }
    }

    private static void writeActiveVersion(Path publishDirectory, String version) throws IOException {
        Path temporary = publishDirectory.resolve(".ACTIVE-" + UUID.randomUUID() + ".tmp");
        try (FileChannel channel = FileChannel.open(
                temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            channel.write(StandardCharsets.UTF_8.encode(version + "\n"));
            channel.force(true);
        }
        try {
            atomicMove(temporary, publishDirectory.resolve(ACTIVE_FILE), true);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void atomicMove(Path source, Path target, boolean replace) throws IOException {
        try {
            if (replace) {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (AtomicMoveNotSupportedException e) {
            throw new AddressRegistryImportException(
                    "ATOMIC_PUBLICATION_UNSUPPORTED", "filesystem does not support atomic centroid publication", e);
        }
    }

    private static boolean directoriesEqual(Path left, Path right) throws IOException {
        List<Path> leftFiles = relativeFiles(left);
        List<Path> rightFiles = relativeFiles(right);
        if (!leftFiles.equals(rightFiles)) {
            return false;
        }
        for (Path relative : leftFiles) {
            if (Files.mismatch(left.resolve(relative), right.resolve(relative)) != -1) {
                return false;
            }
        }
        return true;
    }

    private static List<Path> relativeFiles(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            return paths.filter(Files::isRegularFile)
                    .map(directory::relativize)
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
    }

    private void writeRunReport(
            Path publishDirectory,
            BuildResult result,
            Instant started,
            Instant finished,
            AddressRegistryCentroidExtractProperties properties,
            AddressRegistryArtifactStager.Artifact artifact,
            AddressRegistryImportException failure) {
        Path runs = publishDirectory.resolve("runs");
        if (!Files.isDirectory(runs)) {
            return;
        }
        Path temporary = runs.resolve("." + result.runId() + ".tmp");
        Path target = runs.resolve(started.toEpochMilli() + "-" + result.runId() + ".json");
        try (JsonGenerator json = objectMapper.getFactory().createGenerator(Files.newOutputStream(
                temporary, StandardOpenOption.CREATE_NEW))) {
            json.writeStartObject();
            json.writeStringField("runId", result.runId().toString());
            json.writeStringField("outcome", failure == null ? result.outcome() : "FAILED");
            json.writeStringField("startedAt", started.toString());
            json.writeStringField("finishedAt", finished.toString());
            json.writeStringField("sourceUri", properties.getSourceUri().toString());
            if (artifact == null) {
                json.writeNullField("downloadedAt");
            } else {
                json.writeStringField("downloadedAt", artifact.downloadedAt().toString());
            }
            if (result.version() == null) {
                json.writeNullField("extractVersion");
            } else {
                json.writeStringField("extractVersion", result.version());
            }
            json.writeNumberField("publishedArtifactBytes", result.publishedArtifactBytes());
            json.writeObjectFieldStart("phaseMillis");
            json.writeNumberField("download", result.downloadMillis());
            json.writeNumberField("validation", result.validationMillis());
            json.writeNumberField("extraction", result.extractionMillis());
            json.writeNumberField("publication", result.publicationMillis());
            json.writeNumberField("total", result.totalMillis());
            json.writeEndObject();
            if (failure == null) {
                json.writeNullField("errorCode");
                json.writeNullField("errorMessage");
            } else {
                json.writeStringField("errorCode", failure.code());
                json.writeStringField("errorMessage", failure.getMessage());
            }
            json.writeEndObject();
            json.writeRaw('\n');
        } catch (IOException ignored) {
            deleteFile(temporary);
            return;
        }
        try {
            atomicMove(temporary, target, false);
        } catch (IOException | RuntimeException ignored) {
            deleteFile(temporary);
        }
    }

    private void writeFailedRunReport(
            Path publishDirectory,
            UUID runId,
            Instant started,
            AddressRegistryCentroidExtractProperties properties,
            AddressRegistryArtifactStager.Artifact artifact,
            long validationMillis,
            long extractionMillis,
            long publicationMillis,
            AddressRegistryImportException failure) {
        long totalMillis = Duration.between(started, Instant.now()).toMillis();
        BuildResult failed = new BuildResult(
                "FAILED", runId, null, readActiveVersionIfPossible(publishDirectory),
                properties.getSourceDate() == null ? null : properties.getSourceDate().toString(),
                artifact == null ? null : artifact.sourceSha256(), artifact == null ? null : artifact.gpkgSha256(),
                null, 0, 0, 0, 0, 0, 0, 0, 0, artifact == null ? 0 : artifact.downloadMillis(),
                validationMillis, extractionMillis, publicationMillis, totalMillis, null);
        writeRunReport(publishDirectory, failed, started, Instant.now(), properties, artifact, failure);
    }

    private static String readActiveVersionIfPossible(Path publishDirectory) {
        try {
            return readActiveVersion(publishDirectory);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static FileEvidence evidence(Path file) throws IOException {
        return new FileEvidence(file.getFileName().toString(), Files.size(file), AddressRegistryArtifactStager.sha256(file));
    }

    private static long directoryBytes(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            return paths.filter(Files::isRegularFile).mapToLong(path -> {
                try {
                    return Files.size(path);
                } catch (IOException e) {
                    throw new DirectorySizeException(e);
                }
            }).sum();
        } catch (DirectorySizeException e) {
            throw e.cause();
        }
    }

    private static String attribution(String canonicalUrl) {
        return """
                # Address Registry attribution

                This derived centroid extract uses the official **Adresni registar** dataset
                published by **Republički geodetski zavod (RGZ)**.

                - Resource: %s
                - License identifier: `sodl`
                - License: Srpska licenca za otvorene podatke
                - Declared update frequency: weekly

                Preserve this attribution together with the extract manifest, which identifies
                the exact source date and SHA-256 hashes used for this immutable version.
                """.formatted(canonicalUrl);
    }

    private static void writeStringArray(JsonGenerator json, String field, List<String> values) throws IOException {
        json.writeArrayFieldStart(field);
        for (String value : values) {
            json.writeString(value);
        }
        json.writeEndArray();
    }

    private static BigDecimal coordinate(double value) {
        return BigDecimal.valueOf(value).setScale(7, RoundingMode.HALF_UP);
    }

    private static String text(ResultSet row, String column) throws SQLException {
        String value = row.getString(column);
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String required(ResultSet row, String column) throws SQLException {
        String value = text(row, column);
        if (value == null) {
            throw new AddressRegistryImportException("REQUIRED_VALUE_MISSING", column + " is null or blank");
        }
        return value;
    }

    private static String requiredName(ResultSet row, String column) throws SQLException {
        String value = required(row, column);
        if (AddressRegistryNormalizer.name(value) == null) {
            throw new AddressRegistryImportException(
                    "REQUIRED_VALUE_MISSING", column + " is present but contains no usable name characters");
        }
        return value;
    }

    private static String optionalName(ResultSet row, String column) throws SQLException {
        String value = text(row, column);
        if (value != null && AddressRegistryNormalizer.name(value) == null) {
            throw new AddressRegistryImportException(
                    "REQUIRED_VALUE_MISSING", column + " is present but contains no usable name characters");
        }
        return value;
    }

    private static AddressRegistryImportException classify(RuntimeException failure) {
        Throwable cursor = failure;
        while (cursor != null) {
            if (cursor instanceof AddressRegistryImportException importFailure) {
                return importFailure;
            }
            cursor = cursor.getCause();
        }
        return new AddressRegistryImportException(
                "CENTROID_EXTRACT_FAILED", "Address Registry centroid extraction failed", failure);
    }

    private static void deleteDirectory(Path directory) {
        if (directory == null) {
            return;
        }
        try {
            if (!Files.exists(directory)) {
                return;
            }
            try (var paths = Files.walk(directory)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        // Best effort for abandoned staging only.
                    }
                });
            }
        } catch (IOException ignored) {
            // Best effort for abandoned staging only.
        }
    }

    private static void deleteFile(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // Best-effort cleanup of a run-report temporary file.
        }
    }

    private enum Level {
        KO,
        SETTLEMENT,
        MUNICIPALITY
    }

    private static final class Aggregate {

        private final Level level;
        private final String code;
        private final Set<String> names = new TreeSet<>();
        private final Set<String> latinNames = new TreeSet<>();
        private final Set<String> normalizedNames = new TreeSet<>();
        private final Set<String> normalizedLatinNames = new TreeSet<>();
        private final Set<String> settlementCodes = new TreeSet<>();
        private final Set<String> municipalityCodes = new TreeSet<>();
        private final KahanSum longitude = new KahanSum();
        private final KahanSum latitude = new KahanSum();
        private long memberPointCount;

        private Aggregate(Level level, String code) {
            this.level = level;
            this.code = code;
        }

        private void add(
                String name,
                String latinName,
                String settlementCode,
                String municipalityCode,
                Etrs89Utm34ToWgs84.Point point) {
            names.add(name);
            normalizedNames.add(AddressRegistryNormalizer.name(name));
            if (latinName != null) {
                latinNames.add(latinName);
                normalizedLatinNames.add(AddressRegistryNormalizer.name(latinName));
            }
            if (normalizedNames.size() > 1 || normalizedLatinNames.size() > 1) {
                throw new AddressRegistryImportException(
                        "IDENTIFIER_NAME_CONFLICT",
                        level + " code " + code + " maps to conflicting normalized official names; Cyrillic="
                                + names + ", Latin=" + latinNames);
            }
            if (settlementCode != null) {
                settlementCodes.add(settlementCode);
            }
            if (municipalityCode != null) {
                municipalityCodes.add(municipalityCode);
            }
            longitude.add(point.longitude());
            latitude.add(point.latitude());
            memberPointCount++;
        }

        private Centroid finish() {
            if (memberPointCount < 1 || names.isEmpty() || normalizedNames.size() != 1) {
                throw new AddressRegistryImportException(
                        "CENTROID_AGGREGATION_INVALID", level + " code " + code + " has no valid members");
            }
            return new Centroid(
                    level, code, names.iterator().next(), latinNames.isEmpty() ? null : latinNames.iterator().next(),
                    List.copyOf(names), List.copyOf(latinNames),
                    List.copyOf(settlementCodes), List.copyOf(municipalityCodes), memberPointCount,
                    longitude.value() / memberPointCount, latitude.value() / memberPointCount);
        }
    }

    private static final class KahanSum {

        private double sum;
        private double correction;

        private void add(double value) {
            double adjusted = value - correction;
            double next = sum + adjusted;
            correction = (next - sum) - adjusted;
            sum = next;
        }

        private double value() {
            return sum;
        }
    }

    private record Centroid(
            Level level,
            String officialCode,
            String nameCyrillic,
            String nameLatin,
            List<String> nameCyrillicVariants,
            List<String> nameLatinVariants,
            List<String> settlementCodes,
            List<String> municipalityCodes,
            long memberPointCount,
            double longitude,
            double latitude) {
    }

    private record DuplicateNameGroup(
            Level level,
            List<String> normalizedNames,
            List<String> officialCodes,
            List<String> municipalityCodes,
            boolean spansMultipleMunicipalities) {
    }

    private record Extraction(
            List<Centroid> centroids,
            NavigableMap<String, Long> rejectedByReason,
            List<DuplicateNameGroup> duplicateNames,
            long sourceRows,
            long activeRows,
            long rejectedRows) {

        private long count(Level level) {
            return centroids.stream().filter(centroid -> centroid.level() == level).count();
        }
    }

    private record FileEvidence(String name, long bytes, String sha256) {
    }

    private record Publication(String outcome, String activeVersionBeforeBuild, Path versionDirectory) {
    }

    private static final class DirectorySizeException extends RuntimeException {

        private final IOException cause;

        private DirectorySizeException(IOException cause) {
            super(cause);
            this.cause = cause;
        }

        private IOException cause() {
            return cause;
        }
    }

    record BuildResult(
            String outcome,
            UUID runId,
            String version,
            String activeVersionBeforeBuild,
            String sourceDate,
            String sourceSha256,
            String gpkgSha256,
            String schemaSha256,
            long sourceRows,
            long activeRows,
            long rejectedRows,
            long koCentroids,
            long settlementCentroids,
            long municipalityCentroids,
            long duplicateNameGroups,
            long publishedArtifactBytes,
            long downloadMillis,
            long validationMillis,
            long extractionMillis,
            long publicationMillis,
            long totalMillis,
            String versionDirectory) {
    }

    record Status(
            String activeVersion,
            String sourceDate,
            String gpkgSha256,
            long koCentroids,
            long settlementCentroids,
            long municipalityCentroids,
            String versionDirectory) {
    }
}
