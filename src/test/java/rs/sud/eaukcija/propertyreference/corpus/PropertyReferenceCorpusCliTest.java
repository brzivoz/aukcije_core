package rs.sud.eaukcija.propertyreference.corpus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PropertyReferenceCorpusCliTest {

    private static final Path CORPUS = Path.of("corpus/property-references/v1");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path temporaryDirectory;

    @Test
    void committedCorpusAndBaselinePassTheSameGateAsCi() throws Exception {
        Path report = temporaryDirectory.resolve("metrics.json");
        PropertyReferenceCorpusCli.main(new String[]{
                "--corpus-dir", CORPUS.toString(),
                "--report", report.toString(),
                "--verify-committed"
        });

        JsonNode metrics = objectMapper.readTree(report.toFile());
        JsonNode expectedPatterns = metrics.path("overall").path("byExpectedPattern");
        assertThat(expectedPatterns.path("PARCEL_LABELED").path("truePositives").asInt())
                .isEqualTo(19);
        assertThat(expectedPatterns.path("LAND_REGISTER_LABELED")
                .path("truePositives").asInt()).isEqualTo(13);
        assertThat(expectedPatterns.path("PARCEL_LABELED").has("precision")).isFalse();
        assertThat(metrics.path("overall").path("byDetector").path("PARCEL_LABELED")
                .has("recall")).isFalse();
    }

    @Test
    void artifactHashRejectsAnUnreviewedFixtureEdit() throws Exception {
        Path copy = copyCorpus();
        Path development = copy.resolve("development.json");
        Files.writeString(development, Files.readString(development) + " ");

        assertThatThrownBy(() -> PropertyReferenceCorpusCli.main(new String[]{
                "--corpus-dir", copy.toString(),
                "--report", temporaryDirectory.resolve("tampered-metrics.json").toString()
        })).isInstanceOf(PropertyReferenceCorpusCli.CorpusValidationException.class)
                .hasMessageContaining("artifact hash changed: development.json");
    }

    @Test
    void reviewedArtifactStillRejectsPersonalDataEvidence() throws Exception {
        Path copy = copyCorpus();
        Path development = copy.resolve("development.json");
        ObjectNode file = (ObjectNode) objectMapper.readTree(development.toFile());
        ArrayNode auctions = (ArrayNode) file.path("auctions");
        ObjectNode negative = null;
        for (JsonNode auction : auctions) {
            if (auction.path("caseStatus").asText().equals("NO_DESCRIPTION_REFERENCE")) {
                negative = (ObjectNode) auction;
                break;
            }
        }
        if (negative == null) {
            throw new AssertionError("development fixture has no negative auction");
        }
        ObjectNode evidence = (ObjectNode) negative.withArray("evidence").get(0);
        evidence.put("text", "ЈМБГ 0101990710006");
        objectMapper.writeValue(development.toFile(), file);
        updateArtifactHash(copy, "development.json", sha256(development));

        assertThatThrownBy(() -> PropertyReferenceCorpusCli.main(new String[]{
                "--corpus-dir", copy.toString(),
                "--report", temporaryDirectory.resolve("personal-data-metrics.json").toString()
        })).isInstanceOf(PropertyReferenceCorpusCli.CorpusValidationException.class)
                .hasMessageContaining("personal data is forbidden in evidence");
    }

    @Test
    void publishedSchemaRejectsAReviewedFixtureWithAMissingRequiredField() throws Exception {
        Path copy = copyCorpus();
        Path development = copy.resolve("development.json");
        ObjectNode file = (ObjectNode) objectMapper.readTree(development.toFile());
        ((ObjectNode) file.withArray("auctions").get(0)).remove("evidence");
        objectMapper.writeValue(development.toFile(), file);
        updateArtifactHash(copy, "development.json", sha256(development));

        assertThatThrownBy(() -> PropertyReferenceCorpusCli.main(new String[]{
                "--corpus-dir", copy.toString(),
                "--report", temporaryDirectory.resolve("schema-metrics.json").toString()
        })).isInstanceOf(PropertyReferenceCorpusCli.CorpusValidationException.class)
                .hasMessageContaining("violates corpus.schema.json");
    }

    @Test
    void verifyCommittedRejectsReviewedBaselineMetricDrift() throws Exception {
        Path copy = copyCorpus();
        Path baseline = copy.resolve("baseline-metrics.json");
        ObjectNode metrics = (ObjectNode) objectMapper.readTree(baseline.toFile());
        ((ObjectNode) metrics.path("overall")).put("truePositives",
                metrics.path("overall").path("truePositives").asInt() - 1);
        objectMapper.writeValue(baseline.toFile(), metrics);
        updateArtifactHash(copy, "baseline-metrics.json", sha256(baseline));

        assertThatThrownBy(() -> PropertyReferenceCorpusCli.main(new String[]{
                "--corpus-dir", copy.toString(),
                "--report", temporaryDirectory.resolve("drifted-metrics.json").toString(),
                "--verify-committed"
        })).isInstanceOf(PropertyReferenceCorpusCli.CorpusValidationException.class)
                .hasMessageContaining("baseline metrics changed");
    }

    @Test
    void reviewedKoCodeMustExistInThePinnedOfficialAuthorityExtract() throws Exception {
        Path copy = copyCorpus();
        Path development = copy.resolve("development.json");
        ObjectNode file = (ObjectNode) objectMapper.readTree(development.toFile());
        ObjectNode reference = (ObjectNode) file.withArray("auctions").get(0)
                .withArray("expectedReferences").get(0);
        reference.put("koCode", "999999");
        objectMapper.writeValue(development.toFile(), file);
        updateArtifactHash(copy, "development.json", sha256(development));

        assertThatThrownBy(() -> PropertyReferenceCorpusCli.main(new String[]{
                "--corpus-dir", copy.toString(),
                "--report", temporaryDirectory.resolve("invalid-ko-metrics.json").toString()
        })).isInstanceOf(PropertyReferenceCorpusCli.CorpusValidationException.class)
                .hasMessageContaining("KO code is absent from the authority extract");
    }

    @Test
    void latinCoverageExcludesAStandaloneRomanNumeral() throws Exception {
        int latinAuctions = 0;
        boolean incidentalRomanNumeralIsTagged = false;
        for (String fixture : new String[]{"development.json", "held-out.json"}) {
            JsonNode file = objectMapper.readTree(CORPUS.resolve(fixture).toFile());
            for (JsonNode auction : file.path("auctions")) {
                boolean tagged = false;
                for (JsonNode tag : auction.path("patternTags")) {
                    tagged |= "LATIN".equals(tag.asText());
                }
                latinAuctions += tagged ? 1 : 0;
                if (auction.path("auctionId").asLong() == 181104L) {
                    incidentalRomanNumeralIsTagged = tagged;
                }
            }
        }

        assertThat(latinAuctions).isEqualTo(6);
        assertThat(incidentalRomanNumeralIsTagged).isFalse();
    }

    private Path copyCorpus() throws IOException {
        Path target = temporaryDirectory.resolve("corpus-" + System.nanoTime());
        Files.createDirectories(target);
        try (var files = Files.list(CORPUS)) {
            for (Path source : files.toList()) {
                if (Files.isRegularFile(source)) {
                    Files.copy(source, target.resolve(source.getFileName()));
                }
            }
        }
        return target;
    }

    private void updateArtifactHash(Path directory, String artifactPath, String hash)
            throws IOException {
        Path manifestPath = directory.resolve("manifest.json");
        ObjectNode manifest = (ObjectNode) objectMapper.readTree(manifestPath.toFile());
        for (JsonNode artifact : manifest.withArray("artifacts")) {
            if (artifact.path("path").asText().equals(artifactPath)) {
                ((ObjectNode) artifact).put("sha256", hash);
                objectMapper.writeValue(manifestPath.toFile(), manifest);
                return;
            }
        }
        throw new AssertionError("manifest artifact is missing: " + artifactPath);
    }

    private static String sha256(Path path) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(path)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
