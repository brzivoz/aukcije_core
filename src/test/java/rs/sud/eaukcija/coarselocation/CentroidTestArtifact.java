package rs.sud.eaukcija.coarselocation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Small immutable #36-compatible artifact shared by #38 tests. */
final class CentroidTestArtifact {

    static final String SOURCE_HASH = "a".repeat(64);
    static final String VERSION = "2026-08-23-" + SOURCE_HASH;

    private CentroidTestArtifact() {
    }

    static Path create(Path root, ObjectMapper objectMapper) throws IOException {
        Path version = root.resolve("versions").resolve(VERSION);
        Files.createDirectories(version);
        Files.writeString(root.resolve("ACTIVE"), VERSION + "\n", StandardCharsets.UTF_8);

        List<Map<String, Object>> rows = List.of(
                row("KO", "K100", "КО ТЕСТ", "KO TEST", List.of("S100"), List.of("M100"), 101, 20.1, 44.1),
                row("KO", "300002", "ГРАД", "GRAD", List.of("S300"), List.of("M200"), 30, 21.2, 44.2),
                row("SETTLEMENT", "S100", "ЧАЈЕТИНА", "ČAJETINA", List.of(), List.of("M100"), 55, 19.7, 43.7),
                row("SETTLEMENT", "S200", "ГРАД", "GRAD", List.of(), List.of("M100"), 20, 20.2, 44.2),
                row("SETTLEMENT", "S300", "ГРАД", "GRAD", List.of(), List.of("M200"), 30, 21.2, 44.2),
                row("MUNICIPALITY", "M100", "ОПШТИНА А", "OPŠTINA A", List.of(), List.of(), 500, 20.3, 44.3),
                row("MUNICIPALITY", "M200", "ОПШТИНА Б", "OPŠTINA B", List.of(), List.of(), 600, 21.3, 44.3));
        StringBuilder centroids = new StringBuilder();
        for (Map<String, Object> row : rows) {
            centroids.append(objectMapper.writeValueAsString(row)).append('\n');
        }
        Path centroidFile = version.resolve("centroids.ndjson");
        Files.writeString(centroidFile, centroids, StandardCharsets.UTF_8);
        Path report = version.resolve("report.json");
        Files.writeString(report, "{}\n", StandardCharsets.UTF_8);
        Path attribution = version.resolve("ATTRIBUTION.md");
        Files.writeString(attribution, "Fixture attribution\n", StandardCharsets.UTF_8);

        Map<String, Object> manifest = Map.of(
                "formatVersion", 1,
                "extractVersion", VERSION,
                "source", Map.of(
                        "datasetDate", "2026-08-23",
                        "gpkgSha256", SOURCE_HASH,
                        "targetCrs", 4326),
                "content", Map.of("centroidCounts", Map.of(
                        "KO", 2,
                        "SETTLEMENT", 3,
                        "MUNICIPALITY", 2)),
                "files", List.of(
                        fileEvidence(centroidFile),
                        fileEvidence(report),
                        fileEvidence(attribution)));
        Files.writeString(
                version.resolve("manifest.json"),
                objectMapper.writeValueAsString(manifest),
                StandardCharsets.UTF_8);
        return root;
    }

    private static Map<String, Object> row(
            String level,
            String code,
            String cyrillic,
            String latin,
            List<String> settlementCodes,
            List<String> municipalityCodes,
            long memberPointCount,
            double longitude,
            double latitude) {
        return Map.ofEntries(
                Map.entry("extractVersion", VERSION),
                Map.entry("sourceDate", "2026-08-23"),
                Map.entry("sourceGpkgSha256", SOURCE_HASH),
                Map.entry("level", level),
                Map.entry("officialCode", code),
                Map.entry("nameCyrillic", cyrillic),
                Map.entry("nameLatin", latin),
                Map.entry("settlementCodes", settlementCodes),
                Map.entry("municipalityCodes", municipalityCodes),
                Map.entry("memberPointCount", memberPointCount),
                Map.entry("longitude", longitude),
                Map.entry("latitude", latitude));
    }

    private static Map<String, Object> fileEvidence(Path file) throws IOException {
        return Map.of(
                "name", file.getFileName().toString(),
                "bytes", Files.size(file),
                "sha256", sha256(file));
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Files.readAllBytes(file));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
