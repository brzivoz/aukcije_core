package rs.sud.eaukcija.basemap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Creates production-shaped immutable bundles for HTTP and browser tests. */
public final class BasemapTestBundle {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String STYLE = """
            {
              "version": 8,
              "glyphs": "/basemap/glyphs/{fontstack}/{range}.pbf",
              "sprite": "/basemap/sprites/light",
              "sources": {
                "serbia": {
                  "type": "vector",
                  "url": "pmtiles:///basemap/serbia.pmtiles",
                  "attribution": "© <a href=\\\"https://www.openstreetmap.org/copyright\\\" target=\\\"_blank\\\" rel=\\\"noopener noreferrer\\\">OpenStreetMap contributors</a>"
                }
              },
              "layers": [{"id": "background", "type": "background"}]
            }
            """;

    private BasemapTestBundle() {
    }

    public static Bundle synthetic(Path root, String version, int archiveSize, byte fill) {
        if (archiveSize < 8) {
            throw new IllegalArgumentException("synthetic PMTiles fixture needs an eight-byte header");
        }
        byte[] archive = new byte[archiveSize];
        java.util.Arrays.fill(archive, fill);
        System.arraycopy("PMTiles".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                0, archive, 0, 7);
        archive[7] = 3;

        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("serbia.pmtiles", archive);
        files.put("style.json", STYLE.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        files.put("THIRD_PARTY_NOTICES.md",
                "OpenStreetMap ODbL; Noto OFL; Tangram MIT.\n"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        files.put("licenses/Noto-OFL-1.1.txt",
                "OFL test notice\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        files.put("licenses/Tangram-Icons-MIT.md",
                "MIT test notice\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        files.put("sprites/light.json", "{}\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        files.put("sprites/light.png", new byte[] {(byte) 0x89, 'P', 'N', 'G'});
        files.put("sprites/light@2x.json", "{}\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        files.put("sprites/light@2x.png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 2});
        for (String range : List.of(
                "0-255", "256-511", "512-767", "768-1023", "1024-1279", "8192-8447")) {
            files.put("glyphs/Noto Sans Regular/" + range + ".pbf", new byte[] {1, 2, 3, 4});
        }
        return write(root, version, files);
    }

    public static Bundle fromDirectory(Path root, String version, Path sourceDirectory) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (Stream<Path> paths = Files.walk(sourceDirectory)) {
            paths.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> sourceDirectory.relativize(path).toString()))
                    .forEach(path -> {
                        try {
                            String relative = sourceDirectory.relativize(path)
                                    .toString().replace('\\', '/');
                            files.put(relative, Files.readAllBytes(path));
                        } catch (IOException exception) {
                            throw new IllegalStateException(exception);
                        }
                    });
        } catch (IOException exception) {
            throw new IllegalStateException("could not read basemap test fixture", exception);
        }
        return write(root, version, files);
    }

    public static BasemapSnapshot activate(Path root, String version) {
        BasemapArtifactValidator validator = new BasemapArtifactValidator(OBJECT_MAPPER);
        return new BasemapArtifactActivator(validator).activate(root, version);
    }

    /** Re-hashes an intentionally modified test bundle to exercise semantic gates. */
    public static Bundle refreshManifest(Bundle bundle) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (Stream<Path> paths = Files.walk(bundle.directory())) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().equals("build-manifest.json"))
                    .sorted(Comparator.comparing(
                            path -> bundle.directory().relativize(path).toString()))
                    .forEach(path -> {
                        try {
                            String relative = bundle.directory().relativize(path)
                                    .toString().replace('\\', '/');
                            files.put(relative, Files.readAllBytes(path));
                        } catch (IOException exception) {
                            throw new IllegalStateException(exception);
                        }
                    });
        } catch (IOException exception) {
            throw new IllegalStateException("could not refresh basemap test manifest", exception);
        }
        return write(bundle.root(), bundle.version(), files);
    }

    private static Bundle write(Path root, String version, Map<String, byte[]> files) {
        BasemapArtifactValidator.requireBuildId(version);
        if (!files.containsKey("serbia.pmtiles")) {
            throw new IllegalArgumentException("test bundle is missing serbia.pmtiles");
        }
        Path bundle = root.resolve("builds").resolve(version);
        try {
            Files.createDirectories(bundle);
            for (Map.Entry<String, byte[]> entry : files.entrySet()) {
                Path target = bundle.resolve(entry.getKey());
                Files.createDirectories(target.getParent());
                Files.write(target, entry.getValue());
            }

            List<Map<String, Object>> bundleFiles = new ArrayList<>();
            files.entrySet().stream()
                    .filter(entry -> !entry.getKey().equals("serbia.pmtiles"))
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> bundleFiles.add(fileRecord(entry.getKey(), entry.getValue())));
            byte[] archive = files.get("serbia.pmtiles");
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("schemaVersion", 1);
            manifest.put("buildId", version);
            manifest.put("artifact", Map.of(
                    "filename", "serbia.pmtiles",
                    "sizeBytes", archive.length,
                    "sha256", sha256(archive)));
            manifest.put("attribution", Map.of(
                    "requiredText", "© OpenStreetMap contributors",
                    "copyrightUrl", "https://www.openstreetmap.org/copyright",
                    "visibleInStyle", true));
            manifest.put("bundleFiles", bundleFiles);
            OBJECT_MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValue(bundle.resolve("build-manifest.json").toFile(), manifest);
            return new Bundle(root, version, bundle, archive.clone());
        } catch (IOException exception) {
            throw new IllegalStateException("could not create basemap test bundle", exception);
        }
    }

    private static Map<String, Object> fileRecord(String path, byte[] bytes) {
        return Map.of(
                "path", path,
                "sizeBytes", bytes.length,
                "sha256", sha256(bytes));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record Bundle(Path root, String version, Path directory, byte[] archive) {

        public Bundle {
            archive = archive.clone();
        }

        @Override
        public byte[] archive() {
            return archive.clone();
        }
    }
}
