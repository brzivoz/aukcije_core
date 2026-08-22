package rs.sud.eaukcija.addressregistry;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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
 * Validates the active #36 centroid artifact and atomically publishes a small,
 * immutable KO dictionary and normalized lookup index.
 */
final class KoDictionaryPublisher {

    private static final String ACTIVE_FILE = "ACTIVE";
    private static final String MANIFEST_FILE = "manifest.json";
    private static final String CENTROIDS_FILE = "centroids.ndjson";
    private static final String SOURCE_REPORT_FILE = "report.json";
    private static final String DICTIONARY_FILE = "ko-dictionary.ndjson";
    private static final String INDEX_FILE = "normalized-index.ndjson";
    private static final String REPORT_FILE = "report.json";
    private static final String ALIASES_FILE = "alias-overrides.json";
    private static final String ATTRIBUTION_FILE = "ATTRIBUTION.md";
    private static final String HASH_PATTERN = "[0-9a-f]{64}";
    private static final int DICTIONARY_MANIFEST_FORMAT_VERSION = 2;

    private final ObjectMapper objectMapper;
    private final Clock clock;

    KoDictionaryPublisher(ObjectMapper objectMapper) {
        this(objectMapper, Clock.systemUTC());
    }

    KoDictionaryPublisher(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    BuildResult build(KoDictionaryProperties properties) {
        properties.validateForBuild();
        UUID runId = UUID.randomUUID();
        Instant started = Instant.now();
        Path sourceRoot = properties.getCentroidDirectory().toAbsolutePath().normalize();
        Path publishRoot = properties.getPublishDirectory().toAbsolutePath().normalize();
        Path stagedVersion = null;
        long validationMillis = 0;
        long publicationMillis = 0;
        try {
            requireRegularFile(sourceRoot.resolve(".publish.lock"), "SOURCE_ARTIFACT_CORRUPT");
            createPublicationLayout(publishRoot);
            try (FileChannel sourceLockChannel = FileChannel.open(
                        sourceRoot.resolve(".publish.lock"), StandardOpenOption.WRITE);
                 FileLock ignoredSourceLock = sourceLockChannel.lock();
                 FileChannel publishLockChannel = FileChannel.open(
                        publishRoot.resolve(".publish.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignoredPublishLock = publishLockChannel.lock()) {
                pruneAbandonedStaging(publishRoot.resolve(".staging"));
                Instant validationStarted = Instant.now();
                SourceArtifact source = readSource(sourceRoot, properties);
                AliasDataset aliases = readAliases(properties.getAliasOverrides());
                Dictionary dictionary = buildDictionary(source, aliases, properties);
                byte[] aliasBytes = canonicalAliasBytes(aliases);
                String aliasSha256 = sha256(aliasBytes);
                byte[] municipalityAliasBytes = canonicalMunicipalityAliasBytes(aliases);
                String municipalityAliasSha256 = sha256(municipalityAliasBytes);
                String version = source.version() + "-aliases-" + aliasSha256;
                validationMillis = Duration.between(validationStarted, Instant.now()).toMillis();

                stagedVersion = Files.createTempDirectory(publishRoot.resolve(".staging"), "version-");
                writeVersion(stagedVersion, version, source, aliases, aliasBytes, aliasSha256,
                        municipalityAliasSha256, dictionary);
                long publishedBytes = directoryBytes(stagedVersion);

                Instant publicationStarted = Instant.now();
                Publication publication = publishVersion(
                        publishRoot, stagedVersion, version, source.sourceDate());
                stagedVersion = null;
                publicationMillis = Duration.between(publicationStarted, Instant.now()).toMillis();
                BuildResult result = new BuildResult(
                        publication.outcome(), runId, version, publication.activeVersionBeforeBuild(),
                        source.version(), source.sourceDate().toString(), source.gpkgSha256(),
                        aliases.datasetVersion(), aliasSha256, municipalityAliasSha256, dictionary.kos().size(),
                        dictionary.duplicateNames().size(), aliases.koAliases().size(),
                        aliases.municipalityAliases().size(),
                        source.rejectedSourceRows(), publishedBytes, validationMillis,
                        publicationMillis, Duration.between(started, Instant.now()).toMillis(),
                        publication.versionDirectory().toString());
                writeRunReport(publishRoot, result, started, Instant.now(), null);
                return result;
            }
        } catch (AddressRegistryImportException e) {
            writeFailedRunReport(publishRoot, runId, started, validationMillis, publicationMillis, e);
            throw e;
        } catch (IOException e) {
            AddressRegistryImportException failure = new AddressRegistryImportException(
                    "KO_DICTIONARY_BUILD_FAILED", "could not build canonical KO dictionary", e);
            writeFailedRunReport(publishRoot, runId, started, validationMillis, publicationMillis, failure);
            throw failure;
        } catch (RuntimeException e) {
            AddressRegistryImportException failure = classify(e);
            writeFailedRunReport(publishRoot, runId, started, validationMillis, publicationMillis, failure);
            throw failure;
        } finally {
            deleteDirectory(stagedVersion);
        }
    }

    Status status(Path configuredPublishDirectory) {
        Path publishRoot = configuredPublishDirectory.toAbsolutePath().normalize();
        String active = readActiveVersion(publishRoot, true);
        if (active == null) {
            return new Status(null, null, null, null, 0, 0, 0, 0, null);
        }
        Path directory = safeDictionaryVersionDirectory(publishRoot, active);
        requireDirectory(directory, "ACTIVE_VERSION_CORRUPT");
        Path manifest = directory.resolve(MANIFEST_FILE);
        requireRegularFile(manifest, "ACTIVE_VERSION_CORRUPT");
        try {
            JsonNode root = objectMapper.readTree(manifest.toFile());
            int formatVersion = root.path("formatVersion").asInt(-1);
            if (formatVersion != DICTIONARY_MANIFEST_FORMAT_VERSION) {
                throw new AddressRegistryImportException(
                        "ACTIVE_VERSION_UNSUPPORTED",
                        "active dictionary manifest formatVersion " + formatVersion
                                + " is unsupported; expected " + DICTIONARY_MANIFEST_FORMAT_VERSION);
            }
            String declaredVersion = requiredText(root, "dictionaryVersion", "ACTIVE_VERSION_CORRUPT");
            if (!active.equals(declaredVersion)) {
                throw new AddressRegistryImportException(
                        "ACTIVE_VERSION_CORRUPT", "active dictionary manifest version does not match ACTIVE");
            }
            return new Status(
                    active,
                    declaredVersion,
                    requiredText(root.path("source"), "datasetDate", "ACTIVE_VERSION_CORRUPT"),
                    requiredText(root.path("source"), "gpkgSha256", "ACTIVE_VERSION_CORRUPT"),
                    requiredLong(root.path("content"), "koEntries", "ACTIVE_VERSION_CORRUPT"),
                    requiredLong(root.path("content"), "duplicateNameGroups", "ACTIVE_VERSION_CORRUPT"),
                    requiredLong(root.path("aliases"), "koAliasCount", "ACTIVE_VERSION_CORRUPT"),
                    requiredLong(root.path("aliases"), "municipalityAliasCount", "ACTIVE_VERSION_CORRUPT"),
                    directory.toString());
        } catch (IOException e) {
            throw new AddressRegistryImportException(
                    "ACTIVE_VERSION_CORRUPT", "could not read active KO dictionary manifest", e);
        }
    }

    private SourceArtifact readSource(Path sourceRoot, KoDictionaryProperties properties) throws IOException {
        String active = readActiveVersion(sourceRoot, false);
        if (active == null) {
            throw new AddressRegistryImportException(
                    "NO_ACTIVE_CENTROID_EXTRACT", "the configured centroid directory has no ACTIVE version");
        }
        Path versionDirectory = safeCentroidVersionDirectory(sourceRoot, active);
        requireDirectory(versionDirectory, "SOURCE_ARTIFACT_CORRUPT");
        Path manifestFile = versionDirectory.resolve(MANIFEST_FILE);
        requireRegularFile(manifestFile, "SOURCE_ARTIFACT_CORRUPT");
        JsonNode manifest = objectMapper.readTree(manifestFile.toFile());
        if (manifest.path("formatVersion").asInt(-1) != 1
                || !active.equals(requiredText(manifest, "extractVersion", "SOURCE_ARTIFACT_CORRUPT"))) {
            throw sourceCorrupt("centroid manifest version does not match ACTIVE");
        }
        JsonNode source = manifest.path("source");
        LocalDate sourceDate = parseDate(requiredText(source, "datasetDate", "SOURCE_ARTIFACT_CORRUPT"));
        String gpkgSha256 = requiredHash(source, "gpkgSha256", "SOURCE_ARTIFACT_CORRUPT");
        if (!active.equals(sourceDate + "-" + gpkgSha256)) {
            throw sourceCorrupt("centroid ACTIVE version does not match source date and GPKG hash");
        }
        if (properties.getExpectedGpkgSha256() != null
                && !properties.getExpectedGpkgSha256().equals(gpkgSha256)) {
            throw new AddressRegistryImportException(
                    "SOURCE_HASH_MISMATCH", "active centroid GPKG hash does not match the operator-approved hash");
        }
        long sourceRows = requiredLong(source, "rowCount", "SOURCE_ARTIFACT_CORRUPT");
        JsonNode content = manifest.path("content");
        long activeRows = requiredLong(content, "activeSourceRows", "SOURCE_ARTIFACT_CORRUPT");
        long rejectedRows = requiredLong(content, "rejectedSourceRows", "SOURCE_ARTIFACT_CORRUPT");
        if (sourceRows < 1 || activeRows < 1 || rejectedRows < 0 || sourceRows != activeRows + rejectedRows) {
            throw sourceCorrupt("centroid manifest source-row accounting is inconsistent");
        }

        Map<String, FileEvidence> evidence = verifySourceFiles(versionDirectory, manifest.path("files"));
        requireEvidence(evidence, CENTROIDS_FILE);
        requireEvidence(evidence, SOURCE_REPORT_FILE);
        requireEvidence(evidence, ATTRIBUTION_FILE);
        SourceReport sourceReport = readSourceReport(
                versionDirectory.resolve(SOURCE_REPORT_FILE), active, gpkgSha256, sourceRows, activeRows, rejectedRows);
        SourceCentroids centroids = readCentroids(
                versionDirectory.resolve(CENTROIDS_FILE), active, sourceDate, gpkgSha256);
        JsonNode counts = content.path("centroidCounts");
        if (centroids.kos().size() != requiredLong(counts, "KO", "SOURCE_ARTIFACT_CORRUPT")
                || centroids.settlements().size() != requiredLong(counts, "SETTLEMENT", "SOURCE_ARTIFACT_CORRUPT")
                || centroids.municipalities().size() != requiredLong(counts, "MUNICIPALITY", "SOURCE_ARTIFACT_CORRUPT")) {
            throw sourceCorrupt("centroid entry counts do not match the manifest");
        }
        return new SourceArtifact(
                active, sourceDate, gpkgSha256,
                requiredText(source, "canonicalUrl", "SOURCE_ARTIFACT_CORRUPT"),
                requiredHash(source, "sourceSha256", "SOURCE_ARTIFACT_CORRUPT"),
                requiredHash(source, "schemaSha256", "SOURCE_ARTIFACT_CORRUPT"),
                sourceRows, activeRows, rejectedRows, sourceReport.rejectedByReason(),
                AddressRegistryArtifactStager.sha256(manifestFile), evidence.get(CENTROIDS_FILE).sha256(),
                centroids.kos(), centroids.settlements(), centroids.municipalities());
    }

    private Map<String, FileEvidence> verifySourceFiles(Path directory, JsonNode files) throws IOException {
        if (!files.isArray()) {
            throw sourceCorrupt("centroid manifest files must be an array");
        }
        Map<String, FileEvidence> evidence = new HashMap<>();
        for (JsonNode entry : files) {
            String name = requiredText(entry, "name", "SOURCE_ARTIFACT_CORRUPT");
            if (!name.matches("[A-Za-z0-9._-]+")) {
                throw sourceCorrupt("centroid manifest contains an unsafe filename");
            }
            long bytes = requiredLong(entry, "bytes", "SOURCE_ARTIFACT_CORRUPT");
            String sha256 = requiredHash(entry, "sha256", "SOURCE_ARTIFACT_CORRUPT");
            if (evidence.putIfAbsent(name, new FileEvidence(name, bytes, sha256)) != null) {
                throw sourceCorrupt("centroid manifest contains duplicate file evidence for " + name);
            }
            Path file = directory.resolve(name);
            requireRegularFile(file, "SOURCE_ARTIFACT_CORRUPT");
            if (Files.size(file) != bytes || !AddressRegistryArtifactStager.sha256(file).equals(sha256)) {
                throw new AddressRegistryImportException(
                        "SOURCE_FILE_CHECKSUM_MISMATCH", "centroid source file differs from its manifest: " + name);
            }
        }
        return evidence;
    }

    private SourceReport readSourceReport(
            Path reportFile,
            String version,
            String gpkgSha256,
            long sourceRows,
            long activeRows,
            long rejectedRows) throws IOException {
        JsonNode report = objectMapper.readTree(reportFile.toFile());
        if (!version.equals(requiredText(report, "extractVersion", "SOURCE_ARTIFACT_CORRUPT"))
                || !gpkgSha256.equals(requiredHash(report, "sourceGpkgSha256", "SOURCE_ARTIFACT_CORRUPT"))) {
            throw sourceCorrupt("centroid report provenance does not match its manifest");
        }
        JsonNode rows = report.path("sourceRows");
        if (requiredLong(rows, "total", "SOURCE_ARTIFACT_CORRUPT") != sourceRows
                || requiredLong(rows, "active", "SOURCE_ARTIFACT_CORRUPT") != activeRows
                || requiredLong(rows, "rejected", "SOURCE_ARTIFACT_CORRUPT") != rejectedRows) {
            throw sourceCorrupt("centroid report source-row accounting does not match its manifest");
        }
        JsonNode reasons = rows.path("rejectedByReason");
        if (!reasons.isObject()) {
            throw sourceCorrupt("centroid report rejectedByReason must be an object");
        }
        TreeMap<String, Long> rejectedByReason = new TreeMap<>();
        reasons.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isIntegralNumber()
                    || !entry.getValue().canConvertToLong()
                    || entry.getValue().asLong() < 0) {
                throw sourceCorrupt("centroid report contains an invalid rejection count");
            }
            rejectedByReason.put(entry.getKey(), entry.getValue().asLong());
        });
        long reasonTotal = rejectedByReason.values().stream().mapToLong(Long::longValue).sum();
        if (reasonTotal != rejectedRows) {
            throw sourceCorrupt("centroid rejection reasons do not sum to rejected rows");
        }
        return new SourceReport(java.util.Collections.unmodifiableNavigableMap(rejectedByReason));
    }

    private SourceCentroids readCentroids(
            Path file,
            String version,
            LocalDate sourceDate,
            String gpkgSha256) throws IOException {
        TreeMap<String, Centroid> kos = new TreeMap<>();
        TreeMap<String, Centroid> settlements = new TreeMap<>();
        TreeMap<String, Centroid> municipalities = new TreeMap<>();
        long lineNumber = 0;
        try (var lines = Files.lines(file, StandardCharsets.UTF_8)) {
            for (String line : (Iterable<String>) lines::iterator) {
                lineNumber++;
                if (line.isBlank()) {
                    throw sourceCorrupt("centroid file contains a blank line at " + lineNumber);
                }
                JsonNode row;
                try {
                    row = objectMapper.readTree(line);
                } catch (IOException e) {
                    throw new AddressRegistryImportException(
                            "SOURCE_ARTIFACT_CORRUPT", "invalid centroid JSON at line " + lineNumber, e);
                }
                if (!version.equals(requiredText(row, "extractVersion", "SOURCE_ARTIFACT_CORRUPT"))
                        || !sourceDate.toString().equals(requiredText(row, "sourceDate", "SOURCE_ARTIFACT_CORRUPT"))
                        || !gpkgSha256.equals(requiredHash(row, "sourceGpkgSha256", "SOURCE_ARTIFACT_CORRUPT"))) {
                    throw sourceCorrupt("centroid provenance mismatch at line " + lineNumber);
                }
                Level level;
                try {
                    level = Level.valueOf(requiredText(row, "level", "SOURCE_ARTIFACT_CORRUPT"));
                } catch (IllegalArgumentException e) {
                    throw sourceCorrupt("unknown centroid level at line " + lineNumber);
                }
                String code = requiredText(row, "officialCode", "SOURCE_ARTIFACT_CORRUPT");
                String cyrillic = requiredUsableName(row, "nameCyrillic");
                String latin = optionalUsableName(row, "nameLatin");
                List<String> settlementCodes = stringArray(row, "settlementCodes");
                List<String> municipalityCodes = stringArray(row, "municipalityCodes");
                if (row.path("memberPointCount").asLong(0) < 1) {
                    throw sourceCorrupt("centroid has no member points at line " + lineNumber);
                }
                Centroid centroid = new Centroid(
                        level, code, cyrillic, latin, normalizedNames(cyrillic, latin),
                        settlementCodes, municipalityCodes);
                NavigableMap<String, Centroid> target = switch (level) {
                    case KO -> kos;
                    case SETTLEMENT -> settlements;
                    case MUNICIPALITY -> municipalities;
                };
                if (target.putIfAbsent(code, centroid) != null) {
                    throw sourceCorrupt("duplicate " + level + " official code " + code);
                }
            }
        }
        if (lineNumber == 0) {
            throw sourceCorrupt("centroid file is empty");
        }
        return new SourceCentroids(
                java.util.Collections.unmodifiableNavigableMap(kos),
                java.util.Collections.unmodifiableNavigableMap(settlements),
                java.util.Collections.unmodifiableNavigableMap(municipalities));
    }

    private AliasDataset readAliases(Path configuredPath) throws IOException {
        Path file = configuredPath.toAbsolutePath().normalize();
        requireRegularFile(file, "ALIAS_DATA_INVALID");
        JsonNode root = objectMapper.readTree(file.toFile());
        int formatVersion = root.path("formatVersion").asInt(-1);
        if (formatVersion != 1 && formatVersion != 2) {
            throw aliasInvalid("alias data formatVersion must be 1 or 2");
        }
        String datasetVersion = requiredText(root, "datasetVersion", "ALIAS_DATA_INVALID");
        if (!datasetVersion.matches("[A-Za-z0-9._-]{1,64}")) {
            throw aliasInvalid("alias datasetVersion contains unsupported characters");
        }
        JsonNode entries = formatVersion == 1 ? root.path("aliases") : root.path("koAliases");
        JsonNode municipalityEntries = formatVersion == 1
                ? objectMapper.createArrayNode()
                : root.path("municipalityAliases");
        if (!entries.isArray() || !municipalityEntries.isArray()) {
            throw aliasInvalid(formatVersion == 1
                    ? "aliases must be an array"
                    : "koAliases and municipalityAliases must be arrays");
        }
        List<Alias> aliases = new ArrayList<>();
        List<MunicipalityAlias> municipalityAliases = new ArrayList<>();
        Set<String> ids = new TreeSet<>();
        for (JsonNode entry : entries) {
            String id = requiredText(entry, "id", "ALIAS_DATA_INVALID");
            if (formatVersion == 2
                    && !"KO_ALIAS".equals(requiredText(entry, "recordKind", "ALIAS_DATA_INVALID"))) {
                throw aliasInvalid("KO alias " + id + " recordKind must be KO_ALIAS");
            }
            String koCode = requiredText(entry, "koCode", "ALIAS_DATA_INVALID");
            String name = requiredText(entry, "name", "ALIAS_DATA_INVALID");
            String normalizedName = SerbianNameNormalizer.normalize(name);
            if (normalizedName == null) {
                throw aliasInvalid("alias " + id + " has no usable name characters");
            }
            if (formatVersion == 2
                    && !normalizedName.equals(requiredText(entry, "normalizedName", "ALIAS_DATA_INVALID"))) {
                throw aliasInvalid("alias " + id + " normalizedName differs from "
                        + SerbianNameNormalizer.CONTRACT_VERSION);
            }
            String kind = requiredText(entry, "kind", "ALIAS_DATA_INVALID");
            if (!Set.of("HISTORICAL", "COLLOQUIAL").contains(kind)) {
                throw aliasInvalid("alias " + id + " kind must be HISTORICAL or COLLOQUIAL");
            }
            String provenance = requiredText(entry, "provenance", "ALIAS_DATA_INVALID");
            String sourceReference = requiredText(entry, "sourceReference", "ALIAS_DATA_INVALID");
            String reviewer = requiredText(entry, "reviewer", "ALIAS_DATA_INVALID");
            LocalDate reviewedAt = parseAliasDate(entry, "reviewedAt", id);
            if (reviewedAt.isAfter(LocalDate.now(clock))) {
                throw aliasInvalid("alias " + id + " reviewedAt cannot be in the future");
            }
            if (!ids.add(id)) {
                throw aliasInvalid("duplicate alias id " + id);
            }
            aliases.add(new Alias(
                    id, koCode, name, normalizedName, kind, provenance,
                    sourceReference, reviewer, reviewedAt));
        }
        aliases.sort(Comparator.comparing(Alias::koCode).thenComparing(Alias::id));
        for (JsonNode entry : municipalityEntries) {
            String id = requiredText(entry, "id", "ALIAS_DATA_INVALID");
            if (!"MUNICIPALITY_ALIAS".equals(requiredText(entry, "recordKind", "ALIAS_DATA_INVALID"))) {
                throw aliasInvalid("municipality alias " + id + " recordKind must be MUNICIPALITY_ALIAS");
            }
            String municipalityCode = requiredText(entry, "municipalityCode", "ALIAS_DATA_INVALID");
            String name = requiredText(entry, "name", "ALIAS_DATA_INVALID");
            String normalizedName = SerbianNameNormalizer.normalize(name);
            if (normalizedName == null) {
                throw aliasInvalid("municipality alias " + id + " has no usable name characters");
            }
            if (!normalizedName.equals(requiredText(entry, "normalizedName", "ALIAS_DATA_INVALID"))) {
                throw aliasInvalid("municipality alias " + id + " normalizedName differs from "
                        + SerbianNameNormalizer.CONTRACT_VERSION);
            }
            String provenance = requiredText(entry, "provenance", "ALIAS_DATA_INVALID");
            String sourceReference = requiredText(entry, "sourceReference", "ALIAS_DATA_INVALID");
            String reviewer = requiredText(entry, "reviewer", "ALIAS_DATA_INVALID");
            LocalDate reviewedAt = parseAliasDate(entry, "reviewedAt", id);
            if (reviewedAt.isAfter(LocalDate.now(clock))) {
                throw aliasInvalid("municipality alias " + id + " reviewedAt cannot be in the future");
            }
            if (!ids.add(id)) {
                throw aliasInvalid("duplicate alias id " + id);
            }
            municipalityAliases.add(new MunicipalityAlias(
                    id, municipalityCode, name, normalizedName, provenance,
                    sourceReference, reviewer, reviewedAt));
        }
        municipalityAliases.sort(Comparator.comparing(MunicipalityAlias::municipalityCode)
                .thenComparing(MunicipalityAlias::id));
        return new AliasDataset(datasetVersion, List.copyOf(aliases), List.copyOf(municipalityAliases));
    }

    private Dictionary buildDictionary(
            SourceArtifact source,
            AliasDataset aliases,
            KoDictionaryProperties properties) {
        if (source.kos().size() < properties.getMinimumKoEntries()
                || source.kos().size() > properties.getMaximumKoEntries()) {
            throw new AddressRegistryImportException(
                    "KO_COUNT_SANITY", "KO entry count " + source.kos().size() + " is outside configured range "
                            + properties.getMinimumKoEntries() + "-" + properties.getMaximumKoEntries());
        }
        for (Centroid settlement : source.settlements().values()) {
            for (String municipalityCode : settlement.municipalityCodes()) {
                requireReference(source.municipalities(), municipalityCode,
                        "settlement " + settlement.code() + " municipality");
            }
        }
        TreeSet<String> koReferencedMunicipalityCodes = new TreeSet<>();
        for (Centroid ko : source.kos().values()) {
            if (ko.settlementCodes().isEmpty() || ko.municipalityCodes().isEmpty()) {
                throw new AddressRegistryImportException(
                        "REFERENTIAL_INTEGRITY", "KO " + ko.code() + " has no settlement or municipality relationship");
            }
            for (String municipalityCode : ko.municipalityCodes()) {
                requireReference(source.municipalities(), municipalityCode, "KO " + ko.code() + " municipality");
                koReferencedMunicipalityCodes.add(municipalityCode);
            }
            for (String settlementCode : ko.settlementCodes()) {
                Centroid settlement = requireReference(
                        source.settlements(), settlementCode, "KO " + ko.code() + " settlement");
                if (disjoint(ko.municipalityCodes(), settlement.municipalityCodes())) {
                    throw new AddressRegistryImportException(
                            "REFERENTIAL_INTEGRITY", "KO " + ko.code() + " and settlement " + settlementCode
                                    + " have disjoint municipality relationships");
                }
            }
        }

        TreeMap<String, List<Alias>> aliasesByKo = new TreeMap<>();
        for (Alias alias : aliases.koAliases()) {
            if (!source.kos().containsKey(alias.koCode())) {
                throw aliasInvalid("alias " + alias.id() + " targets unknown KO code " + alias.koCode());
            }
            aliasesByKo.computeIfAbsent(alias.koCode(), ignored -> new ArrayList<>()).add(alias);
        }
        aliasesByKo.replaceAll((ignored, values) -> List.copyOf(values));
        TreeMap<String, List<MunicipalityAlias>> aliasesByMunicipality = new TreeMap<>();
        for (MunicipalityAlias alias : aliases.municipalityAliases()) {
            if (!source.municipalities().containsKey(alias.municipalityCode())) {
                throw aliasInvalid("municipality alias " + alias.id()
                        + " targets unknown municipality code " + alias.municipalityCode());
            }
            if (!koReferencedMunicipalityCodes.contains(alias.municipalityCode())) {
                throw aliasInvalid("municipality alias " + alias.id()
                        + " targets municipality code " + alias.municipalityCode()
                        + " that is not referenced by any KO entry");
            }
            aliasesByMunicipality.computeIfAbsent(alias.municipalityCode(), ignored -> new ArrayList<>()).add(alias);
        }
        aliasesByMunicipality.replaceAll((ignored, values) -> List.copyOf(values));
        List<DuplicateNameGroup> duplicates = duplicateOfficialNames(source.kos());
        NavigableMap<String, List<IndexCandidate>> index = buildIndex(source.kos(), aliasesByKo);
        return new Dictionary(
                source.kos(), source.settlements(), source.municipalities(),
                java.util.Collections.unmodifiableNavigableMap(aliasesByKo),
                java.util.Collections.unmodifiableNavigableMap(aliasesByMunicipality),
                municipalityAliasCollisions(aliases.municipalityAliases(), source.municipalities()), duplicates, index);
    }

    private List<MunicipalityAliasCollision> municipalityAliasCollisions(
            List<MunicipalityAlias> aliases,
            NavigableMap<String, Centroid> municipalities) {
        TreeMap<String, TreeSet<String>> codesByName = new TreeMap<>();
        TreeMap<String, TreeSet<String>> idsByName = new TreeMap<>();
        for (MunicipalityAlias alias : aliases) {
            codesByName.computeIfAbsent(alias.normalizedName(), ignored -> new TreeSet<>())
                    .add(alias.municipalityCode());
            idsByName.computeIfAbsent(alias.normalizedName(), ignored -> new TreeSet<>()).add(alias.id());
        }
        for (Centroid municipality : municipalities.values()) {
            for (String normalizedName : municipality.normalizedNames()) {
                if (codesByName.containsKey(normalizedName)) {
                    codesByName.get(normalizedName).add(municipality.code());
                }
            }
        }
        List<MunicipalityAliasCollision> collisions = new ArrayList<>();
        codesByName.forEach((name, codes) -> {
            if (codes.size() > 1) {
                collisions.add(new MunicipalityAliasCollision(
                        name, List.copyOf(codes), List.copyOf(idsByName.get(name))));
            }
        });
        return List.copyOf(collisions);
    }

    private List<DuplicateNameGroup> duplicateOfficialNames(NavigableMap<String, Centroid> kos) {
        List<Centroid> entries = new ArrayList<>(kos.values());
        List<DuplicateNameGroup> duplicates = new ArrayList<>();
        for (List<Centroid> group : NormalizedNameGroups.connectedComponents(
                entries, Centroid::normalizedNames)) {
            if (group.size() < 2) {
                continue;
            }
            TreeSet<String> normalizedNames = new TreeSet<>();
            TreeSet<String> koCodes = new TreeSet<>();
            TreeSet<String> municipalityCodes = new TreeSet<>();
            for (Centroid ko : group) {
                normalizedNames.addAll(ko.normalizedNames());
                koCodes.add(ko.code());
                municipalityCodes.addAll(ko.municipalityCodes());
            }
            if (municipalityCodes.size() > 1) {
                duplicates.add(new DuplicateNameGroup(
                        List.copyOf(normalizedNames), List.copyOf(koCodes), List.copyOf(municipalityCodes)));
            }
        }
        duplicates.sort(Comparator.comparing(group -> group.normalizedNames().get(0)));
        return List.copyOf(duplicates);
    }

    private NavigableMap<String, List<IndexCandidate>> buildIndex(
            NavigableMap<String, Centroid> kos,
            NavigableMap<String, List<Alias>> aliasesByKo) {
        TreeMap<String, TreeMap<String, CandidateBuilder>> builders = new TreeMap<>();
        for (Centroid ko : kos.values()) {
            for (String normalized : ko.normalizedNames()) {
                builders.computeIfAbsent(normalized, ignored -> new TreeMap<>())
                        .computeIfAbsent(ko.code(), ignored -> new CandidateBuilder(ko))
                        .officialName = true;
            }
            for (Alias alias : aliasesByKo.getOrDefault(ko.code(), List.of())) {
                builders.computeIfAbsent(alias.normalizedName(), ignored -> new TreeMap<>())
                        .computeIfAbsent(ko.code(), ignored -> new CandidateBuilder(ko))
                        .aliasIds.add(alias.id());
            }
        }
        TreeMap<String, List<IndexCandidate>> index = new TreeMap<>();
        for (Map.Entry<String, TreeMap<String, CandidateBuilder>> entry : builders.entrySet()) {
            index.put(entry.getKey(), entry.getValue().values().stream().map(CandidateBuilder::finish).toList());
        }
        return index;
    }

    private void writeVersion(
            Path directory,
            String version,
            SourceArtifact source,
            AliasDataset aliases,
            byte[] aliasBytes,
            String aliasSha256,
            String municipalityAliasSha256,
            Dictionary dictionary) throws IOException {
        writeDictionary(directory.resolve(DICTIONARY_FILE), version, source, dictionary);
        writeIndex(directory.resolve(INDEX_FILE), version, source, dictionary.index());
        writeReport(directory.resolve(REPORT_FILE), version, source, aliases, aliasSha256,
                municipalityAliasSha256, dictionary);
        Files.write(directory.resolve(ALIASES_FILE), aliasBytes, StandardOpenOption.CREATE_NEW);
        Files.writeString(directory.resolve(ATTRIBUTION_FILE), attribution(source), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW);
        List<FileEvidence> evidence = List.of(
                evidence(directory.resolve(DICTIONARY_FILE)),
                evidence(directory.resolve(INDEX_FILE)),
                evidence(directory.resolve(REPORT_FILE)),
                evidence(directory.resolve(ALIASES_FILE)),
                evidence(directory.resolve(ATTRIBUTION_FILE)));
        writeManifest(directory.resolve(MANIFEST_FILE), version, source, aliases, aliasSha256,
                municipalityAliasSha256, dictionary, evidence);
    }

    private void writeDictionary(Path target, String version, SourceArtifact source, Dictionary dictionary)
            throws IOException {
        try (JsonGenerator json = generator(target)) {
            for (Centroid ko : dictionary.kos().values()) {
                json.writeStartObject();
                writeProvenance(json, version, source);
                json.writeStringField("koCode", ko.code());
                json.writeStringField("officialNameCyrillic", ko.nameCyrillic());
                writeNullableString(json, "officialNameLatin", ko.nameLatin());
                writeStringArray(json, "normalizedNames", ko.normalizedNames());
                json.writeArrayFieldStart("municipalities");
                for (String code : ko.municipalityCodes()) {
                    writeMunicipalityRelationship(
                            json,
                            dictionary.municipalities().get(code),
                            dictionary.municipalityAliasesByMunicipality().getOrDefault(code, List.of()));
                }
                json.writeEndArray();
                json.writeArrayFieldStart("settlements");
                for (String code : ko.settlementCodes()) {
                    Centroid settlement = dictionary.settlements().get(code);
                    json.writeStartObject();
                    json.writeStringField("code", settlement.code());
                    json.writeStringField("nameCyrillic", settlement.nameCyrillic());
                    writeNullableString(json, "nameLatin", settlement.nameLatin());
                    writeStringArray(json, "municipalityCodes", settlement.municipalityCodes());
                    json.writeEndObject();
                }
                json.writeEndArray();
                json.writeArrayFieldStart("aliases");
                for (Alias alias : dictionary.aliasesByKo().getOrDefault(ko.code(), List.of())) {
                    writeAlias(json, alias);
                }
                json.writeEndArray();
                json.writeEndObject();
                json.writeRaw('\n');
            }
        }
    }

    private void writeIndex(
            Path target,
            String version,
            SourceArtifact source,
            NavigableMap<String, List<IndexCandidate>> index) throws IOException {
        try (JsonGenerator json = generator(target)) {
            for (Map.Entry<String, List<IndexCandidate>> entry : index.entrySet()) {
                json.writeStartObject();
                writeProvenance(json, version, source);
                json.writeStringField("normalizedName", entry.getKey());
                json.writeArrayFieldStart("candidates");
                for (IndexCandidate candidate : entry.getValue()) {
                    json.writeStartObject();
                    json.writeStringField("koCode", candidate.koCode());
                    writeStringArray(json, "municipalityCodes", candidate.municipalityCodes());
                    json.writeBooleanField("officialName", candidate.officialName());
                    writeStringArray(json, "aliasIds", candidate.aliasIds());
                    json.writeEndObject();
                }
                json.writeEndArray();
                json.writeEndObject();
                json.writeRaw('\n');
            }
        }
    }

    private void writeReport(
            Path target,
            String version,
            SourceArtifact source,
            AliasDataset aliases,
            String aliasSha256,
            String municipalityAliasSha256,
            Dictionary dictionary) throws IOException {
        try (JsonGenerator json = generator(target)) {
            json.writeStartObject();
            writeProvenance(json, version, source);
            json.writeNumberField("totalKoEntries", dictionary.kos().size());
            json.writeNumberField("crossMunicipalityDuplicateNameGroupCount", dictionary.duplicateNames().size());
            json.writeArrayFieldStart("crossMunicipalityDuplicateNameGroups");
            for (DuplicateNameGroup duplicate : dictionary.duplicateNames()) {
                json.writeStartObject();
                writeStringArray(json, "normalizedNames", duplicate.normalizedNames());
                writeStringArray(json, "koCodes", duplicate.koCodes());
                writeStringArray(json, "municipalityCodes", duplicate.municipalityCodes());
                json.writeEndObject();
            }
            json.writeEndArray();
            json.writeObjectFieldStart("koAliasOverrides");
            json.writeStringField("datasetVersion", aliases.datasetVersion());
            json.writeStringField("sha256", aliasSha256);
            json.writeNumberField("applied", aliases.koAliases().size());
            writeStringArray(json, "aliasIds", aliases.koAliases().stream().map(Alias::id).sorted().toList());
            json.writeEndObject();
            json.writeObjectFieldStart("municipalityAliasOverrides");
            json.writeStringField("datasetVersion", aliases.datasetVersion());
            json.writeStringField("sha256", municipalityAliasSha256);
            json.writeNumberField("applied", aliases.municipalityAliases().size());
            writeStringArray(json, "aliasIds",
                    aliases.municipalityAliases().stream().map(MunicipalityAlias::id).sorted().toList());
            json.writeArrayFieldStart("collisions");
            for (MunicipalityAliasCollision collision : dictionary.municipalityAliasCollisions()) {
                json.writeStartObject();
                json.writeStringField("normalizedName", collision.normalizedName());
                writeStringArray(json, "municipalityCodes", collision.municipalityCodes());
                writeStringArray(json, "aliasIds", collision.aliasIds());
                json.writeEndObject();
            }
            json.writeEndArray();
            json.writeEndObject();
            json.writeObjectFieldStart("sourceRows");
            json.writeNumberField("total", source.sourceRows());
            json.writeNumberField("active", source.activeSourceRows());
            json.writeNumberField("rejected", source.rejectedSourceRows());
            json.writeObjectFieldStart("rejectedByReason");
            for (Map.Entry<String, Long> reason : source.rejectedByReason().entrySet()) {
                json.writeNumberField(reason.getKey(), reason.getValue());
            }
            json.writeEndObject();
            json.writeEndObject();
            json.writeArrayFieldStart("validationGatesPassed");
            json.writeString("SOURCE_MANIFEST_AND_FILE_HASHES");
            json.writeString("KO_COUNT_MAGNITUDE");
            json.writeString("UNIQUE_KO_CODES");
            json.writeString("REQUIRED_OFFICIAL_NAMES");
            json.writeString("REFERENTIAL_CONSISTENCY");
            json.writeString("REVIEWED_KO_ALIAS_RECORDS");
            json.writeString("REVIEWED_MUNICIPALITY_ALIAS_RECORDS");
            json.writeString("MUNICIPALITY_ALIAS_COLLISIONS_PRESERVED");
            json.writeEndArray();
            json.writeEndObject();
            json.writeRaw('\n');
        }
    }

    private byte[] canonicalAliasBytes(AliasDataset aliases) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (JsonGenerator json = objectMapper.getFactory().createGenerator(output)) {
            json.writeStartObject();
            json.writeNumberField("formatVersion", 2);
            json.writeStringField("datasetVersion", aliases.datasetVersion());
            json.writeStringField("normalizerContract", SerbianNameNormalizer.CONTRACT_VERSION);
            json.writeArrayFieldStart("koAliases");
            for (Alias alias : aliases.koAliases()) {
                writeAlias(json, alias);
            }
            json.writeEndArray();
            json.writeArrayFieldStart("municipalityAliases");
            for (MunicipalityAlias alias : aliases.municipalityAliases()) {
                writeMunicipalityAlias(json, alias);
            }
            json.writeEndArray();
            json.writeEndObject();
            json.writeRaw('\n');
        }
        return output.toByteArray();
    }

    private byte[] canonicalMunicipalityAliasBytes(AliasDataset aliases) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (JsonGenerator json = objectMapper.getFactory().createGenerator(output)) {
            json.writeStartObject();
            // Must stay byte-identical with KoDictionarySnapshotLoader's independent rebuild.
            json.writeNumberField("formatVersion", 1);
            json.writeStringField("datasetVersion", aliases.datasetVersion());
            json.writeStringField("normalizerContract", SerbianNameNormalizer.CONTRACT_VERSION);
            json.writeArrayFieldStart("municipalityAliases");
            for (MunicipalityAlias alias : aliases.municipalityAliases()) {
                writeMunicipalityAlias(json, alias);
            }
            json.writeEndArray();
            json.writeEndObject();
            json.writeRaw('\n');
        }
        return output.toByteArray();
    }

    private void writeManifest(
            Path target,
            String version,
            SourceArtifact source,
            AliasDataset aliases,
            String aliasSha256,
            String municipalityAliasSha256,
            Dictionary dictionary,
            List<FileEvidence> files) throws IOException {
        try (JsonGenerator json = generator(target)) {
            json.writeStartObject();
            json.writeNumberField("formatVersion", DICTIONARY_MANIFEST_FORMAT_VERSION);
            json.writeStringField("dictionaryVersion", version);
            json.writeStringField("normalizerContract", SerbianNameNormalizer.CONTRACT_VERSION);
            json.writeObjectFieldStart("source");
            json.writeStringField("centroidExtractVersion", source.version());
            json.writeStringField("canonicalUrl", source.canonicalUrl());
            json.writeStringField("datasetDate", source.sourceDate().toString());
            json.writeStringField("sourceSha256", source.sourceSha256());
            json.writeStringField("gpkgSha256", source.gpkgSha256());
            json.writeStringField("schemaSha256", source.schemaSha256());
            json.writeStringField("centroidManifestSha256", source.manifestSha256());
            json.writeStringField("centroidFileSha256", source.centroidFileSha256());
            json.writeNumberField("rowCount", source.sourceRows());
            json.writeNumberField("activeRows", source.activeSourceRows());
            json.writeNumberField("rejectedRows", source.rejectedSourceRows());
            json.writeEndObject();
            json.writeObjectFieldStart("aliases");
            json.writeStringField("datasetVersion", aliases.datasetVersion());
            json.writeStringField("sha256", aliasSha256);
            json.writeNumberField("count", aliases.koAliases().size() + aliases.municipalityAliases().size());
            json.writeNumberField("koAliasCount", aliases.koAliases().size());
            json.writeNumberField("municipalityAliasCount", aliases.municipalityAliases().size());
            json.writeEndObject();
            json.writeObjectFieldStart("municipalityAliases");
            json.writeStringField("datasetVersion", aliases.datasetVersion());
            json.writeStringField("sha256", municipalityAliasSha256);
            json.writeNumberField("count", aliases.municipalityAliases().size());
            json.writeNumberField("collisionCount", dictionary.municipalityAliasCollisions().size());
            json.writeEndObject();
            json.writeObjectFieldStart("content");
            json.writeNumberField("koEntries", dictionary.kos().size());
            json.writeNumberField("normalizedIndexKeys", dictionary.index().size());
            json.writeNumberField("duplicateNameGroups", dictionary.duplicateNames().size());
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
            Path publishRoot,
            Path stagedVersion,
            String version,
            LocalDate sourceDate) throws IOException {
        Path destination = safeDictionaryVersionDirectory(publishRoot, version);
        String active = readActiveVersion(publishRoot, true);
        if (active != null && !active.equals(version)) {
            LocalDate activeDate = activeSourceDate(publishRoot, active);
            if (sourceDate.isBefore(activeDate)) {
                throw new AddressRegistryImportException(
                        "SOURCE_DATE_DOWNGRADE",
                        "source date " + sourceDate + " is older than active dictionary source date " + activeDate
                                + "; implicit downgrade is not allowed");
            }
        }
        String outcome;
        if (Files.exists(destination)) {
            requireDirectory(destination, "IMMUTABLE_VERSION_CONFLICT");
            if (!directoriesEqual(stagedVersion, destination)) {
                throw new AddressRegistryImportException(
                        "IMMUTABLE_VERSION_CONFLICT",
                        "published KO dictionary differs from a reproducible rebuild: " + version);
            }
            deleteDirectory(stagedVersion);
            outcome = "UNCHANGED";
        } else {
            atomicMove(stagedVersion, destination, false);
            outcome = "SUCCEEDED";
        }
        if (!version.equals(active)) {
            writeActiveVersion(publishRoot, version);
        }
        return new Publication(outcome, active, destination);
    }

    private LocalDate activeSourceDate(Path publishRoot, String active) throws IOException {
        Path versionDirectory = safeDictionaryVersionDirectory(publishRoot, active);
        requireDirectory(versionDirectory, "ACTIVE_VERSION_CORRUPT");
        Path manifestFile = versionDirectory.resolve(MANIFEST_FILE);
        requireRegularFile(manifestFile, "ACTIVE_VERSION_CORRUPT");
        JsonNode manifest = objectMapper.readTree(manifestFile.toFile());
        if (!active.equals(requiredText(manifest, "dictionaryVersion", "ACTIVE_VERSION_CORRUPT"))) {
            throw new AddressRegistryImportException(
                    "ACTIVE_VERSION_CORRUPT", "active dictionary manifest version does not match ACTIVE");
        }
        try {
            return LocalDate.parse(requiredText(
                    manifest.path("source"), "datasetDate", "ACTIVE_VERSION_CORRUPT"));
        } catch (RuntimeException e) {
            throw new AddressRegistryImportException(
                    "ACTIVE_VERSION_CORRUPT", "active dictionary manifest has no valid source date", e);
        }
    }

    private void writeRunReport(
            Path publishRoot,
            BuildResult result,
            Instant started,
            Instant finished,
            AddressRegistryImportException failure) {
        Path runs = publishRoot.resolve("runs");
        if (!Files.isDirectory(runs)) {
            return;
        }
        Path temporary = runs.resolve("." + result.runId() + ".tmp");
        Path target = runs.resolve(started.toEpochMilli() + "-" + result.runId() + ".json");
        try (JsonGenerator json = generator(temporary)) {
            json.writeStartObject();
            json.writeStringField("runId", result.runId().toString());
            json.writeStringField("outcome", failure == null ? result.outcome() : "FAILED");
            json.writeStringField("startedAt", started.toString());
            json.writeStringField("finishedAt", finished.toString());
            writeNullableString(json, "dictionaryVersion", result.version());
            writeNullableString(json, "sourceCentroidVersion", result.sourceCentroidVersion());
            json.writeObjectFieldStart("phaseMillis");
            json.writeNumberField("validation", result.validationMillis());
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
            Path publishRoot,
            UUID runId,
            Instant started,
            long validationMillis,
            long publicationMillis,
            AddressRegistryImportException failure) {
        BuildResult failed = new BuildResult(
                "FAILED", runId, null, readActiveVersionIfPossible(publishRoot), null, null, null,
                null, null, null, 0, 0, 0, 0, 0, 0, validationMillis, publicationMillis,
                Duration.between(started, Instant.now()).toMillis(), null);
        writeRunReport(publishRoot, failed, started, Instant.now(), failure);
    }

    private static void writeProvenance(JsonGenerator json, String version, SourceArtifact source) throws IOException {
        json.writeStringField("dictionaryVersion", version);
        json.writeStringField("sourceDate", source.sourceDate().toString());
        json.writeStringField("sourceGpkgSha256", source.gpkgSha256());
    }

    private static void writeMunicipalityRelationship(
            JsonGenerator json, Centroid relationship, List<MunicipalityAlias> aliases) throws IOException {
        json.writeStartObject();
        json.writeStringField("code", relationship.code());
        json.writeStringField("nameCyrillic", relationship.nameCyrillic());
        writeNullableString(json, "nameLatin", relationship.nameLatin());
        writeStringArray(json, "aliasIds", aliases.stream().map(MunicipalityAlias::id).sorted().toList());
        json.writeEndObject();
    }

    private static void writeAlias(JsonGenerator json, Alias alias) throws IOException {
        json.writeStartObject();
        json.writeStringField("recordKind", "KO_ALIAS");
        json.writeStringField("id", alias.id());
        json.writeStringField("koCode", alias.koCode());
        json.writeStringField("name", alias.name());
        json.writeStringField("normalizedName", alias.normalizedName());
        json.writeStringField("kind", alias.kind());
        json.writeStringField("provenance", alias.provenance());
        json.writeStringField("sourceReference", alias.sourceReference());
        json.writeStringField("reviewer", alias.reviewer());
        json.writeStringField("reviewedAt", alias.reviewedAt().toString());
        json.writeEndObject();
    }

    private static void writeMunicipalityAlias(JsonGenerator json, MunicipalityAlias alias) throws IOException {
        json.writeStartObject();
        json.writeStringField("recordKind", "MUNICIPALITY_ALIAS");
        json.writeStringField("id", alias.id());
        json.writeStringField("municipalityCode", alias.municipalityCode());
        json.writeStringField("name", alias.name());
        json.writeStringField("normalizedName", alias.normalizedName());
        json.writeStringField("provenance", alias.provenance());
        json.writeStringField("sourceReference", alias.sourceReference());
        json.writeStringField("reviewer", alias.reviewer());
        json.writeStringField("reviewedAt", alias.reviewedAt().toString());
        json.writeEndObject();
    }

    private static void writeStringArray(JsonGenerator json, String field, List<String> values) throws IOException {
        json.writeArrayFieldStart(field);
        for (String value : values) {
            json.writeString(value);
        }
        json.writeEndArray();
    }

    private static void writeNullableString(JsonGenerator json, String field, String value) throws IOException {
        if (value == null) {
            json.writeNullField(field);
        } else {
            json.writeStringField(field, value);
        }
    }

    private JsonGenerator generator(Path target) throws IOException {
        JsonGenerator generator = objectMapper.getFactory().createGenerator(
                Files.newOutputStream(target, StandardOpenOption.CREATE_NEW));
        generator.setRootValueSeparator(null);
        return generator;
    }

    private static List<String> normalizedNames(String cyrillic, String latin) {
        TreeSet<String> normalized = new TreeSet<>();
        String normalizedCyrillic = SerbianNameNormalizer.normalize(cyrillic);
        if (normalizedCyrillic != null) {
            normalized.add(normalizedCyrillic);
        }
        if (latin != null) {
            String normalizedLatin = SerbianNameNormalizer.normalize(latin);
            if (normalizedLatin != null) {
                normalized.add(normalizedLatin);
            }
        }
        if (normalized.isEmpty()) {
            throw sourceCorrupt("official name has no normalized form");
        }
        return List.copyOf(normalized);
    }

    private static String requiredUsableName(JsonNode node, String field) {
        String value = requiredText(node, field, "SOURCE_ARTIFACT_CORRUPT");
        if (SerbianNameNormalizer.normalize(value) == null) {
            throw sourceCorrupt(field + " contains no usable name characters");
        }
        return value;
    }

    private static String optionalUsableName(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual() || value.asText().isBlank() || SerbianNameNormalizer.normalize(value.asText()) == null) {
            throw sourceCorrupt(field + " is not null or a usable name");
        }
        return value.asText();
    }

    private static List<String> stringArray(JsonNode node, String field) {
        JsonNode values = node.path(field);
        if (!values.isArray()) {
            throw sourceCorrupt(field + " must be an array");
        }
        TreeSet<String> sorted = new TreeSet<>();
        for (JsonNode value : values) {
            if (!value.isTextual() || value.asText().isBlank() || !sorted.add(value.asText())) {
                throw sourceCorrupt(field + " must contain unique nonblank strings");
            }
        }
        return List.copyOf(sorted);
    }

    private static String requiredText(JsonNode node, String field, String code) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new AddressRegistryImportException(code, field + " is missing or blank");
        }
        return value.asText();
    }

    private static String requiredHash(JsonNode node, String field, String code) {
        String value = requiredText(node, field, code);
        if (!value.matches(HASH_PATTERN)) {
            throw new AddressRegistryImportException(code, field + " is not a SHA-256 hash");
        }
        return value;
    }

    private static long requiredLong(JsonNode node, String field, String code) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new AddressRegistryImportException(code, field + " is missing or not an integer");
        }
        return value.asLong();
    }

    private static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException e) {
            throw new AddressRegistryImportException(
                    "SOURCE_ARTIFACT_CORRUPT", "source datasetDate is invalid", e);
        }
    }

    private static LocalDate parseAliasDate(JsonNode entry, String field, String aliasId) {
        try {
            return LocalDate.parse(requiredText(entry, field, "ALIAS_DATA_INVALID"));
        } catch (RuntimeException e) {
            throw new AddressRegistryImportException(
                    "ALIAS_DATA_INVALID", "alias " + aliasId + " has invalid " + field, e);
        }
    }

    private static <T> T requireReference(Map<String, T> values, String code, String description) {
        T value = values.get(code);
        if (value == null) {
            throw new AddressRegistryImportException(
                    "REFERENTIAL_INTEGRITY", description + " references missing official code " + code);
        }
        return value;
    }

    private static boolean disjoint(List<String> left, List<String> right) {
        for (String value : left) {
            if (right.contains(value)) {
                return false;
            }
        }
        return true;
    }

    private static void requireEvidence(Map<String, FileEvidence> evidence, String name) {
        if (!evidence.containsKey(name)) {
            throw sourceCorrupt("centroid manifest has no evidence for " + name);
        }
    }

    private static void requireRegularFile(Path file, String code) {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new AddressRegistryImportException(code, "required regular file is missing: " + file);
        }
    }

    private static void requireDirectory(Path directory, String code) {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new AddressRegistryImportException(code, "required directory is missing: " + directory);
        }
    }

    private static void createPublicationLayout(Path publishRoot) throws IOException {
        Files.createDirectories(publishRoot.resolve("versions"));
        Files.createDirectories(publishRoot.resolve("runs"));
        Files.createDirectories(publishRoot.resolve(".staging"));
    }

    private static void pruneAbandonedStaging(Path staging) throws IOException {
        try (var paths = Files.list(staging)) {
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

    private static String readActiveVersion(Path root, boolean dictionary) {
        Path active = root.resolve(ACTIVE_FILE);
        if (!Files.exists(active)) {
            return null;
        }
        requireRegularFile(active, dictionary ? "ACTIVE_VERSION_CORRUPT" : "SOURCE_ARTIFACT_CORRUPT");
        try {
            String version = Files.readString(active, StandardCharsets.UTF_8).trim();
            if (dictionary) {
                safeDictionaryVersionDirectory(root, version);
            } else {
                safeCentroidVersionDirectory(root, version);
            }
            return version;
        } catch (IOException e) {
            throw new AddressRegistryImportException(
                    dictionary ? "ACTIVE_VERSION_CORRUPT" : "SOURCE_ARTIFACT_CORRUPT",
                    "could not read ACTIVE version", e);
        }
    }

    private static String readActiveVersionIfPossible(Path root) {
        try {
            return readActiveVersion(root, true);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Path safeCentroidVersionDirectory(Path root, String version) {
        if (version == null || !version.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}-" + HASH_PATTERN)) {
            throw sourceCorrupt("invalid centroid version id");
        }
        return safeChild(root.resolve("versions"), version, "SOURCE_ARTIFACT_CORRUPT");
    }

    private static Path safeDictionaryVersionDirectory(Path root, String version) {
        if (version == null || !version.matches(
                "[0-9]{4}-[0-9]{2}-[0-9]{2}-" + HASH_PATTERN + "-aliases-" + HASH_PATTERN)) {
            throw new AddressRegistryImportException("ACTIVE_VERSION_CORRUPT", "invalid KO dictionary version id");
        }
        return safeChild(root.resolve("versions"), version, "ACTIVE_VERSION_CORRUPT");
    }

    private static Path safeChild(Path parent, String name, String code) {
        Path normalizedParent = parent.toAbsolutePath().normalize();
        Path resolved = normalizedParent.resolve(name).normalize();
        if (!resolved.getParent().equals(normalizedParent)) {
            throw new AddressRegistryImportException(code, "version path escapes versions directory");
        }
        return resolved;
    }

    private static void writeActiveVersion(Path root, String version) throws IOException {
        Path temporary = root.resolve(".ACTIVE-" + UUID.randomUUID() + ".tmp");
        try (FileChannel channel = FileChannel.open(
                temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            channel.write(StandardCharsets.UTF_8.encode(version + "\n"));
            channel.force(true);
        }
        try {
            atomicMove(temporary, root.resolve(ACTIVE_FILE), true);
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
                    "ATOMIC_PUBLICATION_UNSUPPORTED", "filesystem does not support atomic KO dictionary publication", e);
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

    private static FileEvidence evidence(Path file) throws IOException {
        return new FileEvidence(file.getFileName().toString(), Files.size(file),
                AddressRegistryArtifactStager.sha256(file));
    }

    private static long directoryBytes(Path directory) throws IOException {
        long bytes = 0;
        try (var paths = Files.walk(directory)) {
            for (Path file : paths.filter(Files::isRegularFile).toList()) {
                bytes += Files.size(file);
            }
        }
        return bytes;
    }

    private static String sha256(byte[] bytes) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM has no SHA-256 implementation", e);
        }
    }

    private static String attribution(SourceArtifact source) {
        return """
                # Address Registry KO dictionary attribution

                This canonical KO dictionary is derived from the official **Adresni registar**
                centroid extract published by **Republički geodetski zavod (RGZ)**.

                - Resource: %s
                - Source dataset date: `%s`
                - Source GPKG SHA-256: `%s`
                - License identifier: `sodl`
                - License: Srpska licenca za otvorene podatke
                - Declared update frequency: weekly

                Preserve this attribution, the manifest, and reviewed alias records together.
                """.formatted(source.canonicalUrl(), source.sourceDate(), source.gpkgSha256());
    }

    private static AddressRegistryImportException sourceCorrupt(String message) {
        return new AddressRegistryImportException("SOURCE_ARTIFACT_CORRUPT", message);
    }

    private static AddressRegistryImportException aliasInvalid(String message) {
        return new AddressRegistryImportException("ALIAS_DATA_INVALID", message);
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
                "KO_DICTIONARY_BUILD_FAILED", "canonical KO dictionary build failed", failure);
    }

    private static void deleteDirectory(Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best-effort cleanup only; the publication result is already known.
                }
            });
        } catch (IOException ignored) {
            // Best-effort cleanup only.
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

    private static final class CandidateBuilder {
        private final Centroid ko;
        private boolean officialName;
        private final Set<String> aliasIds = new TreeSet<>();

        private CandidateBuilder(Centroid ko) {
            this.ko = ko;
        }

        private IndexCandidate finish() {
            return new IndexCandidate(
                    ko.code(), ko.municipalityCodes(), officialName, List.copyOf(aliasIds));
        }
    }

    private record Centroid(
            Level level,
            String code,
            String nameCyrillic,
            String nameLatin,
            List<String> normalizedNames,
            List<String> settlementCodes,
            List<String> municipalityCodes) {
    }

    private record Alias(
            String id,
            String koCode,
            String name,
            String normalizedName,
            String kind,
            String provenance,
            String sourceReference,
            String reviewer,
            LocalDate reviewedAt) {
    }

    private record MunicipalityAlias(
            String id,
            String municipalityCode,
            String name,
            String normalizedName,
            String provenance,
            String sourceReference,
            String reviewer,
            LocalDate reviewedAt) {
    }

    private record AliasDataset(
            String datasetVersion,
            List<Alias> koAliases,
            List<MunicipalityAlias> municipalityAliases) {
    }

    private record MunicipalityAliasCollision(
            String normalizedName,
            List<String> municipalityCodes,
            List<String> aliasIds) {
    }

    private record DuplicateNameGroup(
            List<String> normalizedNames,
            List<String> koCodes,
            List<String> municipalityCodes) {
    }

    private record IndexCandidate(
            String koCode,
            List<String> municipalityCodes,
            boolean officialName,
            List<String> aliasIds) {
    }

    private record SourceCentroids(
            NavigableMap<String, Centroid> kos,
            NavigableMap<String, Centroid> settlements,
            NavigableMap<String, Centroid> municipalities) {
    }

    private record SourceReport(NavigableMap<String, Long> rejectedByReason) {
    }

    private record SourceArtifact(
            String version,
            LocalDate sourceDate,
            String gpkgSha256,
            String canonicalUrl,
            String sourceSha256,
            String schemaSha256,
            long sourceRows,
            long activeSourceRows,
            long rejectedSourceRows,
            NavigableMap<String, Long> rejectedByReason,
            String manifestSha256,
            String centroidFileSha256,
            NavigableMap<String, Centroid> kos,
            NavigableMap<String, Centroid> settlements,
            NavigableMap<String, Centroid> municipalities) {
    }

    private record Dictionary(
            NavigableMap<String, Centroid> kos,
            NavigableMap<String, Centroid> settlements,
            NavigableMap<String, Centroid> municipalities,
            NavigableMap<String, List<Alias>> aliasesByKo,
            NavigableMap<String, List<MunicipalityAlias>> municipalityAliasesByMunicipality,
            List<MunicipalityAliasCollision> municipalityAliasCollisions,
            List<DuplicateNameGroup> duplicateNames,
            NavigableMap<String, List<IndexCandidate>> index) {
    }

    private record FileEvidence(String name, long bytes, String sha256) {
    }

    private record Publication(String outcome, String activeVersionBeforeBuild, Path versionDirectory) {
    }

    record BuildResult(
            String outcome,
            UUID runId,
            String version,
            String activeVersionBeforeBuild,
            String sourceCentroidVersion,
            String sourceDate,
            String gpkgSha256,
            String aliasDatasetVersion,
            String aliasSha256,
            String municipalityAliasSha256,
            long koEntries,
            long duplicateNameGroups,
            long aliasOverridesApplied,
            long municipalityAliasOverridesApplied,
            long rejectedSourceRows,
            long publishedArtifactBytes,
            long validationMillis,
            long publicationMillis,
            long totalMillis,
            String versionDirectory) {
    }

    record Status(
            String activeVersion,
            String dictionaryVersion,
            String sourceDate,
            String gpkgSha256,
            long koEntries,
            long duplicateNameGroups,
            long aliasOverrides,
            long municipalityAliasOverrides,
            String versionDirectory) {
    }
}
