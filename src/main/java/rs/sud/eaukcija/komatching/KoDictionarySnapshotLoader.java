package rs.sud.eaukcija.komatching;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import rs.sud.eaukcija.addressregistry.SerbianNameNormalizer;

/**
 * Loads #14's active dictionary only after validating its immutable manifest,
 * file hashes, row provenance, aliases, and normalized index relationships.
 */
@Component
final class KoDictionarySnapshotLoader {

    private static final String HASH = "[0-9a-f]{64}";
    private static final String VERSION = "[0-9]{4}-[0-9]{2}-[0-9]{2}-" + HASH + "-aliases-" + HASH;
    private static final int DICTIONARY_MANIFEST_FORMAT_VERSION = 2;
    private static final Set<String> REQUIRED_FILES = Set.of(
            "ko-dictionary.ndjson", "normalized-index.ndjson", "report.json",
            "alias-overrides.json", "ATTRIBUTION.md");

    private final ObjectMapper objectMapper;

    KoDictionarySnapshotLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    KoDictionarySnapshot load(Path configuredRoot) {
        Path root = configuredRoot.toAbsolutePath().normalize();
        try {
            requireDirectory(root);
            Path activeFile = root.resolve("ACTIVE");
            requireRegularFile(activeFile);
            String active = Files.readString(activeFile, StandardCharsets.UTF_8).trim();
            if (!active.matches(VERSION)) {
                throw corrupt("ACTIVE contains an invalid dictionary version");
            }
            Path versions = root.resolve("versions");
            requireDirectory(versions);
            Path directory = versions.resolve(active).normalize();
            if (!directory.getParent().equals(versions) || !directory.startsWith(root)) {
                throw corrupt("ACTIVE escapes the dictionary versions directory");
            }
            requireDirectory(directory);

            Path manifestFile = directory.resolve("manifest.json");
            requireRegularFile(manifestFile);
            JsonNode manifest = objectMapper.readTree(manifestFile.toFile());
            int formatVersion = manifest.path("formatVersion").asInt(-1);
            if (formatVersion != DICTIONARY_MANIFEST_FORMAT_VERSION) {
                throw new KoStructuredMatchException(
                        "DICTIONARY_FORMAT_VERSION_MISMATCH",
                        "dictionary manifest formatVersion " + formatVersion
                                + " is unsupported; expected " + DICTIONARY_MANIFEST_FORMAT_VERSION);
            }
            if (!active.equals(requiredText(manifest, "dictionaryVersion"))) {
                throw corrupt("manifest version does not match ACTIVE");
            }
            String normalizer = requiredText(manifest, "normalizerContract");
            if (!SerbianNameNormalizer.CONTRACT_VERSION.equals(normalizer)) {
                throw new KoStructuredMatchException(
                        "NORMALIZER_VERSION_MISMATCH",
                        "dictionary requires " + normalizer + " but matcher implements "
                                + SerbianNameNormalizer.CONTRACT_VERSION);
            }
            JsonNode source = manifest.path("source");
            LocalDate sourceDate = requiredDate(source, "datasetDate");
            String gpkgSha256 = requiredHash(source, "gpkgSha256");
            JsonNode aliasesNode = manifest.path("aliases");
            String aliasDatasetVersion = requiredText(aliasesNode, "datasetVersion");
            String aliasSha256 = requiredHash(aliasesNode, "sha256");
            JsonNode municipalityAliasesNode = manifest.path("municipalityAliases");
            String municipalityAliasDatasetVersion = requiredText(municipalityAliasesNode, "datasetVersion");
            String municipalityAliasSha256 = requiredHash(municipalityAliasesNode, "sha256");
            if (!aliasDatasetVersion.equals(municipalityAliasDatasetVersion)) {
                throw corrupt("municipality alias dataset version differs from the review dataset");
            }
            if (!active.equals(sourceDate + "-" + gpkgSha256 + "-aliases-" + aliasSha256)) {
                throw corrupt("dictionary version does not match source and alias provenance");
            }

            verifyManifestFiles(directory, manifest.path("files"));
            ParsedAliases parsedAliases = readAliases(
                    directory.resolve("alias-overrides.json"), aliasDatasetVersion, aliasSha256,
                    municipalityAliasSha256);
            Map<String, KoDictionarySnapshot.AliasReview> aliases = parsedAliases.koAliases();
            Map<String, KoDictionarySnapshot.MunicipalityAliasReview> municipalityAliases =
                    parsedAliases.municipalityAliases();
            Map<String, KoDictionarySnapshot.KoEntry> entries = readDictionary(
                    directory.resolve("ko-dictionary.ndjson"), active, sourceDate, gpkgSha256,
                    aliases, municipalityAliases);
            Map<String, List<KoDictionarySnapshot.IndexCandidate>> index = readIndex(
                    directory.resolve("normalized-index.ndjson"), active, sourceDate, gpkgSha256, entries, aliases);

            JsonNode content = manifest.path("content");
            if (requiredLong(content, "koEntries") != entries.size()
                    || requiredLong(content, "normalizedIndexKeys") != index.size()
                    || requiredLong(aliasesNode, "koAliasCount") != aliases.size()
                    || requiredLong(aliasesNode, "municipalityAliasCount") != municipalityAliases.size()
                    || requiredLong(municipalityAliasesNode, "count") != municipalityAliases.size()) {
                throw corrupt("manifest content counts do not match dictionary files");
            }
            validateIndex(entries, aliases, index);
            return new KoDictionarySnapshot(
                    active, sourceDate, gpkgSha256, normalizer, aliasDatasetVersion, aliasSha256,
                    municipalityAliasSha256, Map.copyOf(entries), Map.copyOf(index),
                    municipalityCodesByNormalizedName(entries), Map.copyOf(aliases),
                    Map.copyOf(municipalityAliases), municipalityAliasesByNormalizedName(municipalityAliases));
        } catch (KoStructuredMatchException e) {
            throw e;
        } catch (IOException e) {
            throw new KoStructuredMatchException(
                    "DICTIONARY_CORRUPT", "could not validate the active KO dictionary", e);
        }
    }

    private void verifyManifestFiles(Path directory, JsonNode files) throws IOException {
        if (!files.isArray()) {
            throw corrupt("manifest files must be an array");
        }
        Set<String> seen = new HashSet<>();
        for (JsonNode evidence : files) {
            String name = requiredText(evidence, "name");
            if (!REQUIRED_FILES.contains(name) || !seen.add(name)) {
                throw corrupt("manifest contains an unexpected or duplicate file: " + name);
            }
            Path file = directory.resolve(name).normalize();
            if (!file.getParent().equals(directory)) {
                throw corrupt("manifest contains an unsafe filename");
            }
            requireRegularFile(file);
            long bytes = requiredLong(evidence, "bytes");
            String expectedHash = requiredHash(evidence, "sha256");
            if (Files.size(file) != bytes || !sha256(file).equals(expectedHash)) {
                throw new KoStructuredMatchException(
                        "DICTIONARY_FILE_CHECKSUM_MISMATCH", "dictionary file differs from manifest: " + name);
            }
        }
        if (!seen.equals(REQUIRED_FILES)) {
            throw corrupt("manifest does not list the complete dictionary file set");
        }
    }

    private ParsedAliases readAliases(
            Path file,
            String expectedDatasetVersion,
            String expectedHash,
            String expectedMunicipalityAliasHash) throws IOException {
        if (!sha256(file).equals(expectedHash)) {
            throw new KoStructuredMatchException(
                    "DICTIONARY_FILE_CHECKSUM_MISMATCH", "alias file hash does not match manifest");
        }
        JsonNode root = objectMapper.readTree(file.toFile());
        if (root.path("formatVersion").asInt(-1) != 2
                || !expectedDatasetVersion.equals(requiredText(root, "datasetVersion"))
                || !SerbianNameNormalizer.CONTRACT_VERSION.equals(requiredText(root, "normalizerContract"))
                || !root.path("koAliases").isArray()
                || !root.path("municipalityAliases").isArray()) {
            throw corrupt("alias data does not match the manifest contract");
        }
        TreeMap<String, KoDictionarySnapshot.AliasReview> aliases = new TreeMap<>();
        Set<String> allIds = new HashSet<>();
        for (JsonNode row : root.path("koAliases")) {
            KoDictionarySnapshot.AliasReview alias = alias(row);
            if (!allIds.add(alias.id()) || aliases.putIfAbsent(alias.id(), alias) != null) {
                throw corrupt("duplicate alias review id " + alias.id());
            }
        }
        TreeMap<String, KoDictionarySnapshot.MunicipalityAliasReview> municipalityAliases = new TreeMap<>();
        for (JsonNode row : root.path("municipalityAliases")) {
            KoDictionarySnapshot.MunicipalityAliasReview alias = municipalityAlias(row);
            if (!allIds.add(alias.id()) || municipalityAliases.putIfAbsent(alias.id(), alias) != null) {
                throw corrupt("duplicate alias review id " + alias.id());
            }
        }
        if (!sha256(canonicalMunicipalityAliasBytes(expectedDatasetVersion, municipalityAliases))
                .equals(expectedMunicipalityAliasHash)) {
            throw corrupt("municipality alias semantic hash does not match the manifest");
        }
        return new ParsedAliases(Map.copyOf(aliases), Map.copyOf(municipalityAliases));
    }

    private Map<String, KoDictionarySnapshot.KoEntry> readDictionary(
            Path file,
            String version,
            LocalDate sourceDate,
            String gpkgSha256,
            Map<String, KoDictionarySnapshot.AliasReview> aliases,
            Map<String, KoDictionarySnapshot.MunicipalityAliasReview> municipalityAliases) throws IOException {
        TreeMap<String, KoDictionarySnapshot.KoEntry> entries = new TreeMap<>();
        Set<String> seenAliases = new TreeSet<>();
        Set<String> seenMunicipalityAliasTargets = new TreeSet<>();
        Map<String, List<String>> municipalityAliasIdsByTarget = municipalityAliases.values().stream()
                .collect(Collectors.groupingBy(
                        KoDictionarySnapshot.MunicipalityAliasReview::municipalityCode,
                        TreeMap::new,
                        Collectors.mapping(
                                KoDictionarySnapshot.MunicipalityAliasReview::id,
                                Collectors.collectingAndThen(Collectors.toCollection(TreeSet::new), List::copyOf))));
        long lineNumber = 0;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            lineNumber++;
            JsonNode row = parseLine(line, "dictionary", lineNumber);
            requireProvenance(row, version, sourceDate, gpkgSha256, lineNumber);
            String code = requiredText(row, "koCode");
            String cyrillic = requiredText(row, "officialNameCyrillic");
            String latin = optionalText(row, "officialNameLatin");
            List<String> normalizedNames = stringList(row, "normalizedNames");
            TreeSet<String> expectedNames = new TreeSet<>();
            addNormalized(expectedNames, cyrillic);
            addNormalized(expectedNames, latin);
            if (!normalizedNames.equals(List.copyOf(expectedNames))) {
                throw corrupt("dictionary name normalization differs from the shared implementation at line "
                        + lineNumber);
            }
            List<KoDictionarySnapshot.Municipality> municipalities = new ArrayList<>();
            JsonNode municipalityRows = row.path("municipalities");
            if (!municipalityRows.isArray() || municipalityRows.isEmpty()) {
                throw corrupt("KO has no municipality relationship at line " + lineNumber);
            }
            Set<String> municipalityCodes = new HashSet<>();
            for (JsonNode municipality : municipalityRows) {
                String municipalityCode = requiredText(municipality, "code");
                String municipalityCyrillic = requiredText(municipality, "nameCyrillic");
                String municipalityLatin = optionalText(municipality, "nameLatin");
                if (!municipalityCodes.add(municipalityCode)) {
                    throw corrupt("duplicate municipality relationship at line " + lineNumber);
                }
                TreeSet<String> municipalityNames = new TreeSet<>();
                addNormalized(municipalityNames, municipalityCyrillic);
                addNormalized(municipalityNames, municipalityLatin);
                List<String> municipalityAliasIds = stringList(municipality, "aliasIds");
                List<String> expectedAliasIds = municipalityAliasIdsByTarget.getOrDefault(
                        municipalityCode, List.of());
                if (!municipalityAliasIds.equals(expectedAliasIds)) {
                    throw corrupt("municipality alias links differ from the reviewed records at line "
                            + lineNumber);
                }
                if (!municipalityAliasIds.isEmpty()) {
                    seenMunicipalityAliasTargets.add(municipalityCode);
                }
                municipalities.add(new KoDictionarySnapshot.Municipality(
                        municipalityCode, municipalityCyrillic, municipalityLatin,
                        List.copyOf(municipalityNames), municipalityAliasIds));
            }
            municipalities.sort(Comparator.comparing(KoDictionarySnapshot.Municipality::code));

            List<KoDictionarySnapshot.Settlement> settlements = new ArrayList<>();
            JsonNode settlementRows = row.path("settlements");
            if (!settlementRows.isArray() || settlementRows.isEmpty()) {
                throw corrupt("KO has no settlement relationship at line " + lineNumber);
            }
            Set<String> settlementCodes = new HashSet<>();
            for (JsonNode settlement : settlementRows) {
                String settlementCode = requiredText(settlement, "code");
                String settlementCyrillic = requiredText(settlement, "nameCyrillic");
                String settlementLatin = optionalText(settlement, "nameLatin");
                if (!settlementCodes.add(settlementCode)) {
                    throw corrupt("duplicate settlement relationship at line " + lineNumber);
                }
                TreeSet<String> settlementNames = new TreeSet<>();
                addNormalized(settlementNames, settlementCyrillic);
                addNormalized(settlementNames, settlementLatin);
                settlements.add(new KoDictionarySnapshot.Settlement(
                        settlementCode, settlementCyrillic, settlementLatin,
                        List.copyOf(settlementNames), stringList(settlement, "municipalityCodes")));
            }
            settlements.sort(Comparator.comparing(KoDictionarySnapshot.Settlement::code));

            JsonNode aliasRows = row.path("aliases");
            if (!aliasRows.isArray()) {
                throw corrupt("KO aliases must be an array at line " + lineNumber);
            }
            for (JsonNode aliasRow : aliasRows) {
                KoDictionarySnapshot.AliasReview embedded = alias(aliasRow);
                KoDictionarySnapshot.AliasReview canonical = aliases.get(embedded.id());
                if (!embedded.koCode().equals(code) || !embedded.equals(canonical) || !seenAliases.add(embedded.id())) {
                    throw corrupt("embedded alias does not match its reviewed record: " + embedded.id());
                }
            }
            KoDictionarySnapshot.KoEntry entry = new KoDictionarySnapshot.KoEntry(
                    code, cyrillic, latin, normalizedNames,
                    List.copyOf(municipalities), List.copyOf(settlements));
            if (entries.putIfAbsent(code, entry) != null) {
                throw corrupt("duplicate KO code " + code);
            }
        }
        if (entries.isEmpty() || !seenAliases.equals(aliases.keySet())) {
            throw corrupt("dictionary is empty or does not embed every reviewed alias");
        }
        Set<String> expectedMunicipalityAliasTargets = municipalityAliases.values().stream()
                .map(KoDictionarySnapshot.MunicipalityAliasReview::municipalityCode)
                .collect(Collectors.toSet());
        if (!seenMunicipalityAliasTargets.equals(expectedMunicipalityAliasTargets)) {
            throw corrupt("dictionary does not link every reviewed municipality alias target");
        }
        return entries;
    }

    private Map<String, List<KoDictionarySnapshot.IndexCandidate>> readIndex(
            Path file,
            String version,
            LocalDate sourceDate,
            String gpkgSha256,
            Map<String, KoDictionarySnapshot.KoEntry> entries,
            Map<String, KoDictionarySnapshot.AliasReview> aliases) throws IOException {
        TreeMap<String, List<KoDictionarySnapshot.IndexCandidate>> index = new TreeMap<>();
        long lineNumber = 0;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            lineNumber++;
            JsonNode row = parseLine(line, "normalized index", lineNumber);
            requireProvenance(row, version, sourceDate, gpkgSha256, lineNumber);
            String normalizedName = requiredText(row, "normalizedName");
            if (!normalizedName.equals(SerbianNameNormalizer.normalize(normalizedName))) {
                throw corrupt("index key is not canonical at line " + lineNumber);
            }
            JsonNode candidateRows = row.path("candidates");
            if (!candidateRows.isArray() || candidateRows.isEmpty()) {
                throw corrupt("index row has no candidates at line " + lineNumber);
            }
            List<KoDictionarySnapshot.IndexCandidate> candidates = new ArrayList<>();
            Set<String> codes = new HashSet<>();
            for (JsonNode candidate : candidateRows) {
                String code = requiredText(candidate, "koCode");
                if (!entries.containsKey(code) || !codes.add(code)) {
                    throw corrupt("index contains an unknown or duplicate KO code at line " + lineNumber);
                }
                List<String> municipalityCodes = stringList(candidate, "municipalityCodes");
                List<String> aliasIds = stringList(candidate, "aliasIds");
                for (String aliasId : aliasIds) {
                    KoDictionarySnapshot.AliasReview alias = aliases.get(aliasId);
                    if (alias == null || !alias.koCode().equals(code)) {
                        throw corrupt("index alias does not trace to its reviewed KO at line " + lineNumber);
                    }
                }
                candidates.add(new KoDictionarySnapshot.IndexCandidate(
                        code, municipalityCodes, candidate.path("officialName").asBoolean(false), aliasIds));
            }
            candidates.sort(Comparator.comparing(KoDictionarySnapshot.IndexCandidate::koCode));
            if (index.putIfAbsent(normalizedName, List.copyOf(candidates)) != null) {
                throw corrupt("duplicate normalized index key " + normalizedName);
            }
        }
        return index;
    }

    private void validateIndex(
            Map<String, KoDictionarySnapshot.KoEntry> entries,
            Map<String, KoDictionarySnapshot.AliasReview> aliases,
            Map<String, List<KoDictionarySnapshot.IndexCandidate>> actual) {
        TreeMap<String, TreeMap<String, ExpectedCandidate>> expected = new TreeMap<>();
        for (KoDictionarySnapshot.KoEntry entry : entries.values()) {
            for (String name : entry.normalizedNames()) {
                expected.computeIfAbsent(name, ignored -> new TreeMap<>())
                        .computeIfAbsent(entry.code(), ignored -> new ExpectedCandidate(entry))
                        .officialName = true;
            }
        }
        for (KoDictionarySnapshot.AliasReview alias : aliases.values()) {
            KoDictionarySnapshot.KoEntry entry = entries.get(alias.koCode());
            if (entry == null) {
                throw corrupt("alias targets unknown KO code " + alias.koCode());
            }
            expected.computeIfAbsent(alias.normalizedName(), ignored -> new TreeMap<>())
                    .computeIfAbsent(entry.code(), ignored -> new ExpectedCandidate(entry))
                    .aliasIds.add(alias.id());
        }
        TreeMap<String, List<KoDictionarySnapshot.IndexCandidate>> completed = new TreeMap<>();
        expected.forEach((name, byCode) -> completed.put(
                name, byCode.values().stream().map(ExpectedCandidate::finish).toList()));
        if (!completed.equals(actual)) {
            throw corrupt("normalized index does not exactly represent official names and reviewed aliases");
        }
    }

    private KoDictionarySnapshot.AliasReview alias(JsonNode row) {
        if (!"KO_ALIAS".equals(requiredText(row, "recordKind"))) {
            throw corrupt("KO alias recordKind must be KO_ALIAS");
        }
        String name = requiredText(row, "name");
        String normalizedName = requiredText(row, "normalizedName");
        if (!normalizedName.equals(SerbianNameNormalizer.normalize(name))) {
            throw corrupt("alias normalization differs from the shared implementation");
        }
        return new KoDictionarySnapshot.AliasReview(
                requiredText(row, "id"), requiredText(row, "koCode"), name, normalizedName,
                requiredText(row, "kind"), requiredText(row, "provenance"),
                requiredText(row, "sourceReference"), requiredText(row, "reviewer"),
                requiredDate(row, "reviewedAt"));
    }

    private KoDictionarySnapshot.MunicipalityAliasReview municipalityAlias(JsonNode row) {
        if (!"MUNICIPALITY_ALIAS".equals(requiredText(row, "recordKind"))) {
            throw corrupt("municipality alias recordKind must be MUNICIPALITY_ALIAS");
        }
        String name = requiredText(row, "name");
        String normalizedName = requiredText(row, "normalizedName");
        if (!normalizedName.equals(SerbianNameNormalizer.normalize(name))) {
            throw corrupt("municipality alias normalization differs from the shared implementation");
        }
        return new KoDictionarySnapshot.MunicipalityAliasReview(
                requiredText(row, "id"), requiredText(row, "municipalityCode"), name, normalizedName,
                requiredText(row, "provenance"), requiredText(row, "sourceReference"),
                requiredText(row, "reviewer"), requiredDate(row, "reviewedAt"));
    }

    private byte[] canonicalMunicipalityAliasBytes(
            String datasetVersion,
            Map<String, KoDictionarySnapshot.MunicipalityAliasReview> aliases) throws IOException {
        // Must stay byte-identical with KoDictionaryPublisher's independent canonical serialization.
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (JsonGenerator json = objectMapper.getFactory().createGenerator(output)) {
            json.writeStartObject();
            json.writeNumberField("formatVersion", 1);
            json.writeStringField("datasetVersion", datasetVersion);
            json.writeStringField("normalizerContract", SerbianNameNormalizer.CONTRACT_VERSION);
            json.writeArrayFieldStart("municipalityAliases");
            for (KoDictionarySnapshot.MunicipalityAliasReview alias : aliases.values().stream()
                    .sorted(Comparator.comparing(KoDictionarySnapshot.MunicipalityAliasReview::municipalityCode)
                            .thenComparing(KoDictionarySnapshot.MunicipalityAliasReview::id))
                    .toList()) {
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
            json.writeEndArray();
            json.writeEndObject();
            json.writeRaw('\n');
        }
        return output.toByteArray();
    }

    private static Map<String, List<String>> municipalityCodesByNormalizedName(
            Map<String, KoDictionarySnapshot.KoEntry> entries) {
        TreeMap<String, TreeSet<String>> codesByName = new TreeMap<>();
        for (KoDictionarySnapshot.KoEntry entry : entries.values()) {
            for (KoDictionarySnapshot.Municipality municipality : entry.municipalities()) {
                String normalizedCode = SerbianNameNormalizer.normalize(municipality.code());
                if (normalizedCode != null) {
                    codesByName.computeIfAbsent(normalizedCode, ignored -> new TreeSet<>())
                            .add(municipality.code());
                }
                for (String normalizedName : municipality.normalizedNames()) {
                    codesByName.computeIfAbsent(normalizedName, ignored -> new TreeSet<>())
                            .add(municipality.code());
                }
            }
        }
        TreeMap<String, List<String>> completed = new TreeMap<>();
        codesByName.forEach((name, codes) -> completed.put(name, List.copyOf(codes)));
        return Map.copyOf(completed);
    }

    private static Map<String, List<KoDictionarySnapshot.MunicipalityAliasReview>>
            municipalityAliasesByNormalizedName(
                    Map<String, KoDictionarySnapshot.MunicipalityAliasReview> aliases) {
        TreeMap<String, List<KoDictionarySnapshot.MunicipalityAliasReview>> byName = new TreeMap<>();
        aliases.values().stream()
                .collect(Collectors.groupingBy(
                        KoDictionarySnapshot.MunicipalityAliasReview::normalizedName, TreeMap::new,
                        Collectors.toList()))
                .forEach((name, reviews) -> byName.put(name, reviews.stream()
                        .sorted(Comparator.comparing(KoDictionarySnapshot.MunicipalityAliasReview::municipalityCode)
                                .thenComparing(KoDictionarySnapshot.MunicipalityAliasReview::id))
                        .toList()));
        return Map.copyOf(byName);
    }

    private void requireProvenance(
            JsonNode row, String version, LocalDate sourceDate, String gpkgSha256, long lineNumber) {
        if (!version.equals(requiredText(row, "dictionaryVersion"))
                || !sourceDate.toString().equals(requiredText(row, "sourceDate"))
                || !gpkgSha256.equals(requiredHash(row, "sourceGpkgSha256"))) {
            throw corrupt("dictionary provenance mismatch at line " + lineNumber);
        }
    }

    private JsonNode parseLine(String line, String description, long lineNumber) throws IOException {
        if (line.isBlank()) {
            throw corrupt(description + " contains a blank line at " + lineNumber);
        }
        try {
            return objectMapper.readTree(line);
        } catch (IOException e) {
            throw new KoStructuredMatchException(
                    "DICTIONARY_CORRUPT", "invalid " + description + " JSON at line " + lineNumber, e);
        }
    }

    private static List<String> stringList(JsonNode parent, String field) {
        JsonNode array = parent.path(field);
        if (!array.isArray()) {
            throw corrupt(field + " must be an array");
        }
        List<String> values = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        for (JsonNode value : array) {
            if (!value.isTextual() || value.asText().isBlank() || !unique.add(value.asText())) {
                throw corrupt(field + " contains an invalid or duplicate value");
            }
            values.add(value.asText());
        }
        List<String> sorted = values.stream().sorted().toList();
        if (!values.equals(sorted)) {
            throw corrupt(field + " must use deterministic ordering");
        }
        return List.copyOf(values);
    }

    private static String requiredText(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw corrupt("missing required text field " + field);
        }
        return value.asText();
    }

    private static String optionalText(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (!value.isTextual() || value.asText().isBlank()) {
            throw corrupt("invalid optional text field " + field);
        }
        return value.asText();
    }

    private static String requiredHash(JsonNode parent, String field) {
        String value = requiredText(parent, field);
        if (!value.matches(HASH)) {
            throw corrupt("invalid SHA-256 field " + field);
        }
        return value;
    }

    private static long requiredLong(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < 0) {
            throw corrupt("invalid non-negative integer field " + field);
        }
        return value.asLong();
    }

    private static LocalDate requiredDate(JsonNode parent, String field) {
        try {
            return LocalDate.parse(requiredText(parent, field));
        } catch (DateTimeParseException e) {
            throw new KoStructuredMatchException(
                    "DICTIONARY_CORRUPT", "invalid date field " + field, e);
        }
    }

    private static void addNormalized(Set<String> target, String value) {
        String normalized = SerbianNameNormalizer.normalize(value);
        if (normalized != null) {
            target.add(normalized);
        }
    }

    private static void requireDirectory(Path path) {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw corrupt("required dictionary directory is missing or unsafe: " + path);
        }
    }

    private static void requireRegularFile(Path path) {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw corrupt("required dictionary file is missing or unsafe: " + path);
        }
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM has no SHA-256 implementation", e);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM has no SHA-256 implementation", e);
        }
    }

    private static KoStructuredMatchException corrupt(String message) {
        return new KoStructuredMatchException("DICTIONARY_CORRUPT", message);
    }

    private static final class ExpectedCandidate {
        private final KoDictionarySnapshot.KoEntry entry;
        private boolean officialName;
        private final Set<String> aliasIds = new TreeSet<>();

        private ExpectedCandidate(KoDictionarySnapshot.KoEntry entry) {
            this.entry = entry;
        }

        private KoDictionarySnapshot.IndexCandidate finish() {
            return new KoDictionarySnapshot.IndexCandidate(
                    entry.code(),
                    entry.municipalities().stream().map(KoDictionarySnapshot.Municipality::code).sorted().toList(),
                    officialName,
                    List.copyOf(aliasIds));
        }
    }

    private record ParsedAliases(
            Map<String, KoDictionarySnapshot.AliasReview> koAliases,
            Map<String, KoDictionarySnapshot.MunicipalityAliasReview> municipalityAliases) {
    }
}
