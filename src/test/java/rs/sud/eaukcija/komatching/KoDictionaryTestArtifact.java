package rs.sud.eaukcija.komatching;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import rs.sud.eaukcija.addressregistry.SerbianNameNormalizer;

/** Small reviewed #14-compatible artifact used by issue #37 tests. */
public final class KoDictionaryTestArtifact {

    private static final String SOURCE_DATE = "2026-08-22";
    private static final String SOURCE_HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private KoDictionaryTestArtifact() {
    }

    public static Path create(Path root, ObjectMapper objectMapper) throws Exception {
        Files.createDirectories(root.resolve("versions"));
        Map<String, Object> alias = orderedMap(
                "recordKind", "KO_ALIAS",
                "id", "caribrod-1930",
                "koCode", "200001",
                "name", "Caribrod",
                "normalizedName", "CARIBROD",
                "kind", "HISTORICAL",
                "provenance", "Reviewed historical gazette fixture",
                "sourceReference", "fixture://gazette/1930",
                "reviewer", "fixture-reviewer",
                "reviewedAt", "2026-08-20");
        Map<String, Object> municipalityAliasM1 = municipalityAlias(
                "shared-portal-m1", "M1", "Заједничка-портал", "ZAJEDNICKA PORTAL");
        Map<String, Object> municipalityAliasM2 = municipalityAlias(
                "opstina-b-grad", "M2", "Општина Б-град", "OPSTINA B GRAD");
        Map<String, Object> municipalityAliasOfficialCollision = municipalityAlias(
                "official-name-collision-m2", "M2", "Општина А", "OPSTINA A");
        Map<String, Object> municipalityAliasCollisionM2 = municipalityAlias(
                "shared-portal-m2", "M2", "Заједничка-портал", "ZAJEDNICKA PORTAL");
        List<Map<String, Object>> municipalityAliases = List.of(
                municipalityAliasM1, municipalityAliasOfficialCollision,
                municipalityAliasM2, municipalityAliasCollisionM2);
        Map<String, Object> aliasRoot = orderedMap(
                "formatVersion", 2,
                "datasetVersion", "fixture-2026.1",
                "normalizerContract", SerbianNameNormalizer.CONTRACT_VERSION,
                "koAliases", List.of(alias),
                "municipalityAliases", municipalityAliases);
        byte[] aliasBytes = jsonLine(objectMapper, aliasRoot).getBytes(StandardCharsets.UTF_8);
        String aliasHash = sha256(aliasBytes);
        byte[] municipalityAliasBytes = jsonLine(objectMapper, orderedMap(
                "formatVersion", 1,
                "datasetVersion", "fixture-2026.1",
                "normalizerContract", SerbianNameNormalizer.CONTRACT_VERSION,
                "municipalityAliases", municipalityAliases)).getBytes(StandardCharsets.UTF_8);
        String municipalityAliasHash = sha256(municipalityAliasBytes);
        String version = SOURCE_DATE + "-" + SOURCE_HASH + "-aliases-" + aliasHash;
        Path directory = root.resolve("versions").resolve(version);
        Files.createDirectories(directory);

        List<Map<String, Object>> entries = List.of(
                dictionaryRow(
                        version, "100001", "Чајетина", "Čajetina",
                        "M1", "Општина А", "Opština A",
                        "S1", "Насеље А", "Naselje A", List.of(), List.of("shared-portal-m1")),
                dictionaryRow(
                        version, "200001", "Димитровград", "Dimitrovgrad",
                        "M3", "Димитровград", "Dimitrovgrad",
                        "S3", "Димитровград", "Dimitrovgrad", List.of(alias), List.of()),
                dictionaryRow(
                        version, "300001", "Град", "Grad",
                        "M1", "Општина А", "Opština A",
                        "S4", "Насеље А", "Naselje A", List.of(), List.of("shared-portal-m1")),
                dictionaryRow(
                        version, "300002", "Град", "Grad",
                        "M2", "Општина Б", "Opština B",
                        "S5", "Насеље Б", "Naselje B", List.of(),
                        List.of("official-name-collision-m2", "opstina-b-grad", "shared-portal-m2")),
                // M4 and M5 carry different official spellings that the shared normalizer folds to
                // the same key, so "Општина В" denotes two municipalities without any reviewed alias.
                dictionaryRow(
                        version, "400001", "Поље", "Polje",
                        "M4", "Општина В", "Opština V",
                        "S6", "Насеље В", "Naselje V", List.of(), List.of()),
                dictionaryRow(
                        version, "400002", "Поље", "Polje",
                        "M5", "ОПШТИНА В", "OPŠTINA V",
                        "S7", "Насеље Г", "Naselje G", List.of(), List.of()),
                dictionaryRow(
                        version, "500001", "Сјеница", "Sjenica",
                        "M6", "Сјеница", "Sjenica",
                        "S8", "Сјеница", "Sjenica", List.of(), List.of()),
                dictionaryRow(
                        version, "500002", "Урсуле", "Ursule",
                        "M6", "Сјеница", "Sjenica",
                        "S9", "Урсуле", "Ursule", List.of(), List.of()));
        writeNdjson(directory.resolve("ko-dictionary.ndjson"), entries, objectMapper);

        List<Map<String, Object>> index = List.of(
                indexRow(version, "CAJETINA", List.of(indexCandidate("100001", "M1", true, List.of()))),
                indexRow(version, "CARIBROD", List.of(indexCandidate("200001", "M3", false,
                        List.of("caribrod-1930")))),
                indexRow(version, "DIMITROVGRAD", List.of(indexCandidate("200001", "M3", true, List.of()))),
                indexRow(version, "GRAD", List.of(
                        indexCandidate("300001", "M1", true, List.of()),
                        indexCandidate("300002", "M2", true, List.of()))),
                indexRow(version, "POLJE", List.of(
                        indexCandidate("400001", "M4", true, List.of()),
                        indexCandidate("400002", "M5", true, List.of()))),
                indexRow(version, "SJENICA", List.of(indexCandidate("500001", "M6", true, List.of()))),
                indexRow(version, "URSULE", List.of(indexCandidate("500002", "M6", true, List.of()))));
        writeNdjson(directory.resolve("normalized-index.ndjson"), index, objectMapper);

        Files.write(directory.resolve("alias-overrides.json"), aliasBytes);
        Files.writeString(directory.resolve("report.json"), jsonLine(objectMapper, orderedMap(
                "dictionaryVersion", version,
                "sourceDate", SOURCE_DATE,
                "sourceGpkgSha256", SOURCE_HASH,
                "totalKoEntries", entries.size())), StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("ATTRIBUTION.md"), "Fixture only.\n", StandardCharsets.UTF_8);

        List<Map<String, Object>> evidence = new ArrayList<>();
        for (String name : List.of(
                "ko-dictionary.ndjson", "normalized-index.ndjson", "report.json",
                "alias-overrides.json", "ATTRIBUTION.md")) {
            Path file = directory.resolve(name);
            evidence.add(orderedMap(
                    "name", name,
                    "bytes", Files.size(file),
                    "sha256", sha256(Files.readAllBytes(file))));
        }
        Map<String, Object> manifest = orderedMap(
                "formatVersion", 2,
                "dictionaryVersion", version,
                "normalizerContract", SerbianNameNormalizer.CONTRACT_VERSION,
                "source", orderedMap(
                        "datasetDate", SOURCE_DATE,
                        "gpkgSha256", SOURCE_HASH),
                "aliases", orderedMap(
                        "datasetVersion", "fixture-2026.1",
                        "sha256", aliasHash,
                        "count", 5,
                        "koAliasCount", 1,
                        "municipalityAliasCount", 4),
                "municipalityAliases", orderedMap(
                        "datasetVersion", "fixture-2026.1",
                        "sha256", municipalityAliasHash,
                        "count", 4,
                        "collisionCount", 2),
                "content", orderedMap(
                        "koEntries", entries.size(),
                        "normalizedIndexKeys", index.size(),
                        "duplicateNameGroups", 1),
                "files", evidence);
        Files.writeString(
                directory.resolve("manifest.json"), jsonLine(objectMapper, manifest), StandardCharsets.UTF_8);
        Files.writeString(root.resolve("ACTIVE"), version + "\n", StandardCharsets.UTF_8);
        return root;
    }

    private static Map<String, Object> dictionaryRow(
            String version,
            String code,
            String cyrillic,
            String latin,
            String municipalityCode,
            String municipalityCyrillic,
            String municipalityLatin,
            String settlementCode,
            String settlementCyrillic,
            String settlementLatin,
            List<Map<String, Object>> aliases,
            List<String> municipalityAliasIds) {
        return orderedMap(
                "dictionaryVersion", version,
                "sourceDate", SOURCE_DATE,
                "sourceGpkgSha256", SOURCE_HASH,
                "koCode", code,
                "officialNameCyrillic", cyrillic,
                "officialNameLatin", latin,
                "normalizedNames", normalized(cyrillic, latin),
                "municipalities", List.of(orderedMap(
                        "code", municipalityCode,
                        "nameCyrillic", municipalityCyrillic,
                        "nameLatin", municipalityLatin,
                        "aliasIds", municipalityAliasIds)),
                "settlements", List.of(orderedMap(
                        "code", settlementCode,
                        "nameCyrillic", settlementCyrillic,
                        "nameLatin", settlementLatin,
                        "municipalityCodes", List.of(municipalityCode))),
                "aliases", aliases);
    }

    private static Map<String, Object> municipalityAlias(
            String id, String municipalityCode, String name, String normalizedName) {
        return orderedMap(
                "recordKind", "MUNICIPALITY_ALIAS",
                "id", id,
                "municipalityCode", municipalityCode,
                "name", name,
                "normalizedName", normalizedName,
                "provenance", "Reviewed municipality fixture",
                "sourceReference", "fixture://municipality/" + id,
                "reviewer", "fixture-reviewer",
                "reviewedAt", "2026-08-20");
    }

    private static Map<String, Object> indexRow(
            String version, String normalizedName, List<Map<String, Object>> candidates) {
        return orderedMap(
                "dictionaryVersion", version,
                "sourceDate", SOURCE_DATE,
                "sourceGpkgSha256", SOURCE_HASH,
                "normalizedName", normalizedName,
                "candidates", candidates);
    }

    private static Map<String, Object> indexCandidate(
            String code, String municipalityCode, boolean officialName, List<String> aliases) {
        return orderedMap(
                "koCode", code,
                "municipalityCodes", List.of(municipalityCode),
                "officialName", officialName,
                "aliasIds", aliases);
    }

    private static List<String> normalized(String... names) {
        return java.util.Arrays.stream(names)
                .map(SerbianNameNormalizer::normalize)
                .distinct()
                .sorted()
                .toList();
    }

    private static void writeNdjson(Path file, List<Map<String, Object>> rows, ObjectMapper objectMapper)
            throws IOException {
        StringBuilder content = new StringBuilder();
        for (Map<String, Object> row : rows) {
            content.append(objectMapper.writeValueAsString(row)).append('\n');
        }
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static String jsonLine(ObjectMapper objectMapper, Map<String, Object> value) throws IOException {
        return objectMapper.writeValueAsString(value) + "\n";
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    @SuppressWarnings("unchecked")
    private static <K, V> Map<K, V> orderedMap(Object... values) {
        LinkedHashMap<K, V> map = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            map.put((K) values[index], (V) values[index + 1]);
        }
        return map;
    }
}
