package rs.sud.eaukcija.komatching;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.fasterxml.jackson.databind.ObjectMapper;

class LocationCoverageAuditCliTest {
    @TempDir Path root;
    @Test void offlineAuditPinsOneInputFrameAndDictionaryAndDoesNotExportDescriptions() throws Exception {
        var mapper = new ObjectMapper();
        Path dictionary = KoDictionaryTestArtifact.create(root.resolve("dictionary"), mapper);
        var input = mapper.createObjectNode().put("schemaVersion", rs.sud.eaukcija.enrichment.EnrichmentInputSnapshot.SCHEMA_VERSION)
                .put("auctionId", 55).put("sourceSnapshotSha256", "a".repeat(64))
                .put("cadastral", "Димитровград").put("placeName", "Димитровград").put("municipality", "Димитровград")
                .put("description", "КО Caribrod; парцела 12. PRIVATE_DESCRIPTION_SENTINEL");
        Path source = root.resolve("input.ndjson"); Files.writeString(source, input + "\n");
        Path first = root.resolve("first.json"), second = root.resolve("second.json");
        LocationCoverageAuditCli.audit(source, dictionary, first);
        LocationCoverageAuditCli.audit(source, dictionary, second);
        assertThat(Files.readString(first)).isEqualTo(Files.readString(second)).doesNotContain("PRIVATE_DESCRIPTION_SENTINEL");
        var report = mapper.readTree(first.toFile());
        assertThat(report.path("inputFrameSha256").asText()).hasSize(64);
        assertThat(report.path("population").asInt()).isEqualTo(1);
        assertThat(report.path("measurement").asText()).contains("not independent accuracy");
        assertThat(report.path("parsers").path("property-reference-v1").path("auctionsWithEligibleParcel").asInt()).isZero();
        assertThat(report.path("parsers").path("property-reference-v2").path("auctionsWithEligibleParcel").asInt()).isOne();
    }
}
