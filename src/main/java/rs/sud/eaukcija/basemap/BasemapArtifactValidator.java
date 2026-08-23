package rs.sud.eaukcija.basemap;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/** Validates the #24 manifest and every byte before a bundle can become active. */
@Component
public final class BasemapArtifactValidator {

    static final String ARCHIVE = "serbia.pmtiles";
    static final String MANIFEST = "build-manifest.json";
    static final Pattern BUILD_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final Pattern GLYPH = Pattern.compile("glyphs/[^/]+/[0-9]+-[0-9]+\\.pbf");
    private static final String STYLE_ATTRIBUTION =
            "© <a href=\"https://www.openstreetmap.org/copyright\" target=\"_blank\" "
                    + "rel=\"noopener noreferrer\">OpenStreetMap contributors</a>";
    private static final Set<String> REQUIRED_FILES = Set.of(
            ARCHIVE,
            "style.json",
            "THIRD_PARTY_NOTICES.md",
            "sprites/light.json",
            "sprites/light.png",
            "sprites/light@2x.json",
            "sprites/light@2x.png",
            "licenses/Noto-OFL-1.1.txt",
            "licenses/Tangram-Icons-MIT.md",
            "glyphs/Noto Sans Regular/0-255.pbf",
            "glyphs/Noto Sans Regular/256-511.pbf",
            "glyphs/Noto Sans Regular/512-767.pbf",
            "glyphs/Noto Sans Regular/768-1023.pbf",
            "glyphs/Noto Sans Regular/1024-1279.pbf",
            "glyphs/Noto Sans Regular/8192-8447.pbf");

    private final ObjectMapper objectMapper;

    public BasemapArtifactValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public BasemapSnapshot validate(Path assetDirectory, String buildId) {
        requireBuildId(buildId);
        Path root = assetDirectory.toAbsolutePath().normalize();
        Path builds = root.resolve("builds");
        Path bundle = builds.resolve(buildId).normalize();
        requireRealDirectory(root, "basemap asset root is not a real directory");
        requireRealDirectory(builds, "basemap builds root is not a real directory");
        if (!bundle.getParent().equals(builds)) {
            throw new BasemapArtifactException("basemap build id escapes the builds directory");
        }
        requireRealDirectory(bundle, "basemap bundle is not a real directory");

        JsonNode manifest = readJson(bundle.resolve(MANIFEST), "invalid basemap build manifest");
        require(manifest.path("schemaVersion").asInt(-1) == 1,
                "unsupported basemap manifest schema");
        require(buildId.equals(text(manifest, "buildId")),
                "manifest buildId does not match its immutable directory");

        Map<String, ExpectedFile> expectedFiles = new HashMap<>();
        JsonNode artifact = manifest.path("artifact");
        require(ARCHIVE.equals(text(artifact, "filename")),
                "manifest must name the canonical PMTiles archive");
        addExpected(expectedFiles, ARCHIVE, artifact);

        JsonNode bundleFiles = manifest.path("bundleFiles");
        require(bundleFiles.isArray() && !bundleFiles.isEmpty(),
                "manifest bundleFiles is missing");
        for (JsonNode file : bundleFiles) {
            String relativePath = text(file, "path");
            requireSafeRelativePath(relativePath);
            addExpected(expectedFiles, relativePath, file);
        }

        require(expectedFiles.keySet().containsAll(REQUIRED_FILES),
                "manifest omits required runtime basemap assets");
        require(expectedFiles.keySet().stream().anyMatch(path -> GLYPH.matcher(path).matches()),
                "manifest contains no local glyph range");
        validateInventory(bundle, expectedFiles.keySet());

        Map<String, BasemapAsset> assets = new HashMap<>();
        for (Map.Entry<String, ExpectedFile> entry : expectedFiles.entrySet()) {
            Path path = bundle.resolve(entry.getKey()).normalize();
            require(path.getParent() != null && path.startsWith(bundle),
                    "manifest asset escapes the immutable bundle");
            requireRealFile(path, "manifest asset is missing or is not a regular file");
            ExpectedFile expected = entry.getValue();
            long actualSize = size(path);
            require(actualSize == expected.sizeBytes(),
                    "manifest asset size mismatch: " + entry.getKey());
            String actualHash = sha256(path);
            require(actualHash.equals(expected.sha256()),
                    "manifest asset hash mismatch: " + entry.getKey());
            assets.put(entry.getKey(), new BasemapAsset(
                    entry.getKey(), path, actualSize, actualHash, contentType(entry.getKey())));
        }

        validatePmtilesHeader(bundle.resolve(ARCHIVE));
        validateStyle(bundle.resolve("style.json"));
        validateAttribution(manifest.path("attribution"));
        ExpectedFile archive = expectedFiles.get(ARCHIVE);
        return new BasemapSnapshot(
                buildId, archive.sha256(), archive.sizeBytes(), Instant.now(), assets);
    }

    static void requireBuildId(String buildId) {
        if (buildId == null || !BUILD_ID.matcher(buildId).matches()) {
            throw new BasemapArtifactException("invalid basemap build id");
        }
    }

    private void validateStyle(Path stylePath) {
        JsonNode style = readJson(stylePath, "invalid basemap style JSON");
        require(style.path("version").asInt(-1) == 8,
                "basemap style must use style specification version 8");
        require("/basemap/glyphs/{fontstack}/{range}.pbf".equals(text(style, "glyphs")),
                "basemap glyph URL is not the reviewed same-origin path");
        require("/basemap/sprites/light".equals(text(style, "sprite")),
                "basemap sprite URL is not the reviewed same-origin path");
        JsonNode source = style.path("sources").path("serbia");
        require("vector".equals(text(source, "type")), "basemap Serbia source is not vector");
        require("pmtiles:///basemap/serbia.pmtiles".equals(text(source, "url")),
                "basemap PMTiles URL is not the reviewed same-origin path");
        require(STYLE_ATTRIBUTION.equals(text(source, "attribution")),
                "basemap style attribution differs from the reviewed linked OSM notice");
        rejectExternalRuntimeUrls(style, false);
    }

    private static void rejectExternalRuntimeUrls(JsonNode node, boolean attributionField) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                rejectExternalRuntimeUrls(field.getValue(), "attribution".equals(field.getKey()));
            }
        } else if (node.isArray()) {
            node.forEach(child -> rejectExternalRuntimeUrls(child, attributionField));
        } else if (node.isTextual() && !attributionField) {
            String value = node.asText().toLowerCase();
            boolean reviewedPmtiles =
                    value.equals("pmtiles:///basemap/serbia.pmtiles");
            require(reviewedPmtiles || (!value.contains("://") && !value.startsWith("//")),
                    "basemap style contains an external runtime URL");
            require(!value.contains("http://") && !value.contains("https://")
                            && !value.contains("//tile.openstreetmap.org")
                            && !value.contains("//unpkg.com")
                            && !value.contains("//cdn.jsdelivr.net"),
                    "basemap style contains an external runtime URL");
        }
    }

    private static void validateAttribution(JsonNode attribution) {
        require("© OpenStreetMap contributors".equals(text(attribution, "requiredText")),
                "manifest attribution text mismatch");
        require("https://www.openstreetmap.org/copyright".equals(
                        text(attribution, "copyrightUrl")),
                "manifest attribution URL mismatch");
        require(attribution.path("visibleInStyle").asBoolean(false),
                "manifest does not attest visible attribution");
    }

    private static void validatePmtilesHeader(Path archive) {
        byte[] header = new byte[8];
        try (InputStream input = Files.newInputStream(archive)) {
            if (input.readNBytes(header, 0, header.length) != header.length) {
                throw new BasemapArtifactException("PMTiles archive is truncated");
            }
        } catch (IOException exception) {
            throw new BasemapArtifactException("could not read PMTiles header", exception);
        }
        String magic = new String(header, 0, 7, StandardCharsets.US_ASCII);
        require("PMTiles".equals(magic) && Byte.toUnsignedInt(header[7]) == 3,
                "active archive is not PMTiles v3");
    }

    private static void validateInventory(Path bundle, Set<String> expectedFiles) {
        Set<String> actualFiles = new HashSet<>();
        try (Stream<Path> paths = Files.walk(bundle)) {
            paths.filter(path -> !path.equals(bundle)).forEach(path -> {
                if (Files.isSymbolicLink(path)) {
                    throw new BasemapArtifactException("basemap bundle contains a symbolic link");
                }
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    actualFiles.add(bundle.relativize(path).toString().replace('\\', '/'));
                }
            });
        } catch (IOException exception) {
            throw new BasemapArtifactException("could not inventory basemap bundle", exception);
        }
        Set<String> expectedWithManifest = new HashSet<>(expectedFiles);
        expectedWithManifest.add(MANIFEST);
        require(actualFiles.equals(expectedWithManifest),
                "basemap bundle inventory does not match its manifest");
    }

    private static void addExpected(
            Map<String, ExpectedFile> expectedFiles, String relativePath, JsonNode node) {
        requireSafeRelativePath(relativePath);
        long sizeBytes = node.path("sizeBytes").asLong(-1);
        String sha256 = text(node, "sha256");
        require(sizeBytes >= 0, "manifest asset has an invalid size");
        require(sha256.matches("[0-9a-f]{64}"), "manifest asset has an invalid SHA-256");
        require(expectedFiles.putIfAbsent(relativePath, new ExpectedFile(sizeBytes, sha256)) == null,
                "manifest contains a duplicate asset path");
    }

    private static void requireSafeRelativePath(String relativePath) {
        require(relativePath != null && !relativePath.isBlank(), "manifest asset path is blank");
        Path path;
        try {
            path = Path.of(relativePath);
        } catch (RuntimeException exception) {
            throw new BasemapArtifactException("manifest asset path is invalid", exception);
        }
        require(!path.isAbsolute()
                        && !relativePath.contains("\\")
                        && path.normalize().equals(path)
                        && !relativePath.startsWith("."),
                "manifest asset path escapes the bundle");
    }

    private JsonNode readJson(Path path, String message) {
        requireRealFile(path, message);
        try {
            return objectMapper.readTree(path.toFile());
        } catch (IOException exception) {
            throw new BasemapArtifactException(message, exception);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : "";
    }

    private static void requireRealDirectory(Path path, String message) {
        require(!Files.isSymbolicLink(path)
                        && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS), message);
    }

    private static void requireRealFile(Path path, String message) {
        require(!Files.isSymbolicLink(path)
                        && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS), message);
    }

    private static long size(Path path) {
        try {
            return Files.size(path);
        } catch (IOException exception) {
            throw new BasemapArtifactException("could not read basemap asset size", exception);
        }
    }

    private static String sha256(Path path) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        } catch (IOException exception) {
            throw new BasemapArtifactException("could not hash basemap asset", exception);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String contentType(String relativePath) {
        if (relativePath.endsWith(".pmtiles")) {
            return "application/vnd.pmtiles";
        }
        if (relativePath.endsWith(".json")) {
            return "application/json";
        }
        if (relativePath.endsWith(".png")) {
            return "image/png";
        }
        if (relativePath.endsWith(".pbf")) {
            return "application/x-protobuf";
        }
        if (relativePath.endsWith(".md")) {
            return "text/markdown;charset=UTF-8";
        }
        if (relativePath.endsWith(".txt")) {
            return "text/plain;charset=UTF-8";
        }
        return "application/octet-stream";
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new BasemapArtifactException(message);
        }
    }

    private record ExpectedFile(long sizeBytes, String sha256) {
    }
}
