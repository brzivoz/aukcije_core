package rs.sud.eaukcija.coarselocation;

import java.io.BufferedReader;
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
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import rs.sud.eaukcija.addressregistry.SerbianNameNormalizer;

/** Validates ACTIVE, manifest checksums, provenance, and every centroid row before use. */
@Component
final class CentroidSnapshotLoader {

    private static final Pattern VERSION = Pattern.compile("\\d{4}-\\d{2}-\\d{2}-[0-9a-f]{64}");
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> REQUIRED_FILES = Set.of(
            "centroids.ndjson", "report.json", "ATTRIBUTION.md");

    private final ObjectMapper objectMapper;

    CentroidSnapshotLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    CentroidSnapshot load(Path configuredRoot) {
        Path root = configuredRoot.toAbsolutePath().normalize();
        try {
            requireDirectory(root);
            Path activeFile = root.resolve("ACTIVE");
            requireRegularFile(activeFile);
            String active = Files.readString(activeFile, StandardCharsets.UTF_8).trim();
            if (!VERSION.matcher(active).matches()) {
                throw corrupt("ACTIVE contains an invalid centroid extract version");
            }
            Path versions = root.resolve("versions");
            requireDirectory(versions);
            Path directory = versions.resolve(active).normalize();
            if (!directory.getParent().equals(versions) || !directory.startsWith(root)) {
                throw corrupt("ACTIVE escapes the centroid versions directory");
            }
            requireDirectory(directory);

            Path manifestFile = directory.resolve("manifest.json");
            requireRegularFile(manifestFile);
            JsonNode manifest = objectMapper.readTree(manifestFile.toFile());
            if (manifest.path("formatVersion").asInt(-1) != 1) {
                throw new CoarseLocationResolutionException(
                        "CENTROID_FORMAT_VERSION_MISMATCH",
                        "centroid manifest formatVersion is unsupported; expected 1");
            }
            if (!active.equals(requiredText(manifest, "extractVersion"))) {
                throw corrupt("manifest extractVersion does not match ACTIVE");
            }
            JsonNode source = manifest.path("source");
            LocalDate sourceDate = requiredDate(source, "datasetDate");
            String sourceGpkgSha256 = requiredHash(source, "gpkgSha256");
            if (!active.equals(sourceDate + "-" + sourceGpkgSha256)) {
                throw corrupt("extract version does not match source date and GPKG hash");
            }
            if (source.path("targetCrs").asInt(-1) != 4326) {
                throw corrupt("centroid target CRS must be EPSG:4326");
            }

            verifyManifestFiles(directory, manifest.path("files"));
            ParsedCentroids parsed = readCentroids(
                    directory.resolve("centroids.ndjson"), active, sourceDate, sourceGpkgSha256);
            JsonNode counts = manifest.path("content").path("centroidCounts");
            requireCount(counts, "KO", parsed.koByCode().size());
            requireCount(counts, "SETTLEMENT", parsed.settlementByCode().size());
            requireCount(counts, "MUNICIPALITY", parsed.municipalityByCode().size());

            return new CentroidSnapshot(
                    active,
                    sourceDate,
                    sourceGpkgSha256,
                    Map.copyOf(parsed.koByCode()),
                    Map.copyOf(parsed.settlementByCode()),
                    Map.copyOf(parsed.municipalityByCode()),
                    normalizedIndex(parsed.settlementByCode()),
                    normalizedIndex(parsed.municipalityByCode()));
        } catch (CoarseLocationResolutionException e) {
            throw e;
        } catch (IOException e) {
            throw new CoarseLocationResolutionException(
                    "CENTROID_ARTIFACT_CORRUPT", "could not validate the active centroid extract", e);
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
            if (Files.size(file) != requiredLong(evidence, "bytes")
                    || !sha256(file).equals(requiredHash(evidence, "sha256"))) {
                throw new CoarseLocationResolutionException(
                        "CENTROID_FILE_CHECKSUM_MISMATCH", "centroid file differs from manifest: " + name);
            }
        }
        if (!seen.equals(REQUIRED_FILES)) {
            throw corrupt("manifest does not list the complete centroid file set");
        }
    }

    private ParsedCentroids readCentroids(
            Path file,
            String version,
            LocalDate sourceDate,
            String sourceGpkgSha256) throws IOException {
        TreeMap<String, CentroidSnapshot.Centroid> ko = new TreeMap<>();
        TreeMap<String, CentroidSnapshot.Centroid> settlements = new TreeMap<>();
        TreeMap<String, CentroidSnapshot.Centroid> municipalities = new TreeMap<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            long lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                JsonNode row;
                try {
                    row = objectMapper.readTree(line);
                } catch (IOException e) {
                    throw corrupt("invalid centroid JSON at line " + lineNumber);
                }
                if (!version.equals(requiredText(row, "extractVersion"))
                        || !sourceDate.equals(requiredDate(row, "sourceDate"))
                        || !sourceGpkgSha256.equals(requiredHash(row, "sourceGpkgSha256"))) {
                    throw corrupt("centroid provenance differs from the manifest at line " + lineNumber);
                }
                CentroidSnapshot.Level level;
                try {
                    level = CentroidSnapshot.Level.valueOf(requiredText(row, "level"));
                } catch (IllegalArgumentException e) {
                    throw corrupt("unknown centroid level at line " + lineNumber);
                }
                String officialCode = requiredText(row, "officialCode");
                String cyrillic = requiredText(row, "nameCyrillic");
                String latin = optionalText(row, "nameLatin");
                long memberPointCount = requiredLong(row, "memberPointCount");
                double longitude = requiredFinite(row, "longitude");
                double latitude = requiredFinite(row, "latitude");
                if (memberPointCount < 1) {
                    throw corrupt("memberPointCount must be positive at line " + lineNumber);
                }
                if (longitude < 18 || longitude > 24 || latitude < 41 || latitude > 47) {
                    throw corrupt("centroid lies outside Serbia bounds at line " + lineNumber);
                }
                CentroidSnapshot.Centroid centroid = new CentroidSnapshot.Centroid(
                        level,
                        officialCode,
                        cyrillic,
                        latin,
                        stringList(row, "settlementCodes"),
                        stringList(row, "municipalityCodes"),
                        memberPointCount,
                        longitude,
                        latitude);
                Map<String, CentroidSnapshot.Centroid> target = switch (level) {
                    case KO -> ko;
                    case SETTLEMENT -> settlements;
                    case MUNICIPALITY -> municipalities;
                };
                if (target.putIfAbsent(officialCode, centroid) != null) {
                    throw corrupt("duplicate " + level + " code " + officialCode);
                }
            }
        }
        if (ko.isEmpty() || settlements.isEmpty() || municipalities.isEmpty()) {
            throw corrupt("centroid extract is missing one or more required levels");
        }
        return new ParsedCentroids(ko, settlements, municipalities);
    }

    private static Map<String, List<CentroidSnapshot.Centroid>> normalizedIndex(
            Map<String, CentroidSnapshot.Centroid> centroids) {
        TreeMap<String, TreeMap<String, CentroidSnapshot.Centroid>> indexed = new TreeMap<>();
        for (CentroidSnapshot.Centroid centroid : centroids.values()) {
            addName(indexed, centroid.nameCyrillic(), centroid);
            addName(indexed, centroid.nameLatin(), centroid);
        }
        TreeMap<String, List<CentroidSnapshot.Centroid>> result = new TreeMap<>();
        indexed.forEach((name, byCode) -> result.put(name, List.copyOf(byCode.values())));
        return Map.copyOf(result);
    }

    private static void addName(
            Map<String, TreeMap<String, CentroidSnapshot.Centroid>> index,
            String value,
            CentroidSnapshot.Centroid centroid) {
        String normalized = SerbianNameNormalizer.normalize(value);
        if (normalized != null) {
            index.computeIfAbsent(normalized, ignored -> new TreeMap<>())
                    .put(centroid.officialCode(), centroid);
        }
    }

    private static void requireCount(JsonNode counts, String name, int actual) {
        if (requiredLong(counts, name) != actual) {
            throw corrupt("manifest " + name + " count differs from centroid rows");
        }
    }

    private static List<String> stringList(JsonNode parent, String name) {
        JsonNode values = parent.path(name);
        if (!values.isArray()) {
            throw corrupt(name + " must be an array");
        }
        List<String> result = new ArrayList<>();
        for (JsonNode value : values) {
            if (!value.isTextual() || value.asText().isBlank()) {
                throw corrupt(name + " contains an invalid value");
            }
            result.add(value.asText());
        }
        return List.copyOf(result);
    }

    private static String requiredText(JsonNode parent, String name) {
        JsonNode value = parent.path(name);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw corrupt(name + " is required");
        }
        return value.asText();
    }

    private static String optionalText(JsonNode parent, String name) {
        JsonNode value = parent.path(name);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    private static String requiredHash(JsonNode parent, String name) {
        String value = requiredText(parent, name);
        if (!HASH.matcher(value).matches()) {
            throw corrupt(name + " is not a lowercase SHA-256 hash");
        }
        return value;
    }

    private static LocalDate requiredDate(JsonNode parent, String name) {
        try {
            return LocalDate.parse(requiredText(parent, name));
        } catch (DateTimeParseException e) {
            throw corrupt(name + " is not an ISO date");
        }
    }

    private static long requiredLong(JsonNode parent, String name) {
        JsonNode value = parent.path(name);
        if (!value.canConvertToLong()) {
            throw corrupt(name + " is required and must be an integer");
        }
        return value.asLong();
    }

    private static double requiredFinite(JsonNode parent, String name) {
        JsonNode value = parent.path(name);
        if (!value.isNumber() || !Double.isFinite(value.asDouble())) {
            throw corrupt(name + " is required and must be finite");
        }
        return value.asDouble();
    }

    private static void requireDirectory(Path path) {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw corrupt("required directory is missing or unsafe: " + path);
        }
    }

    private static void requireRegularFile(Path path) {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw corrupt("required file is missing or unsafe: " + path);
        }
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM has no SHA-256 implementation", e);
        }
    }

    private static CoarseLocationResolutionException corrupt(String message) {
        return new CoarseLocationResolutionException("CENTROID_ARTIFACT_CORRUPT", message);
    }

    private record ParsedCentroids(
            TreeMap<String, CentroidSnapshot.Centroid> koByCode,
            TreeMap<String, CentroidSnapshot.Centroid> settlementByCode,
            TreeMap<String, CentroidSnapshot.Centroid> municipalityByCode) {
    }
}
