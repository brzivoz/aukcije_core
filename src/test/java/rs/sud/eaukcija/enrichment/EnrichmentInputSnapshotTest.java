package rs.sud.eaukcija.enrichment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import rs.sud.eaukcija.snapshot.AuctionSourceCanonicalJson;
import rs.sud.eaukcija.snapshot.CurrentAuctionSourceSnapshot;

class EnrichmentInputSnapshotTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void derivesAllParserFieldsAndLineageFromTheImmutableSourceSnapshot() {
        CurrentAuctionSourceSnapshot source = source(
                "парцела број 1572", "само кратко", "ГРАД", "Насеље Б", "Општина Б-град");

        EnrichmentInputSnapshot first = EnrichmentInputSnapshot.from(source, objectMapper);
        EnrichmentInputSnapshot replay = EnrichmentInputSnapshot.from(source, objectMapper);

        assertThat(replay).isEqualTo(first);
        assertThat(first.canonicalInput().fieldNames()).toIterable().containsExactly(
                "schemaVersion", "sourceSnapshotSha256", "auctionId", "placeName",
                "municipality", "cadastral", "description", "shortDescription");
        assertThat(first.canonicalInput().path("schemaVersion").asText())
                .isEqualTo("enrichment-location-input-v2");
        assertThat(first.canonicalInput().path("sourceSnapshotSha256").asText())
                .isEqualTo(source.contentSha256());
        assertThat(first.canonicalInput().path("description").asText())
                .isEqualTo("парцела број 1572");
        assertThat(first.canonicalInput().path("shortDescription").asText())
                .isEqualTo("само кратко");

        ((ObjectNode) first.canonicalInput()).put("cadastral", "mutated by caller");
        assertThat(first.canonicalInput().path("cadastral").asText()).isEqualTo("ГРАД");
    }

    @Test
    void descriptionShortDescriptionAndStructuredChangesProduceNewInputs() {
        EnrichmentInputSnapshot original = EnrichmentInputSnapshot.from(
                source("опис", "кратко", "ГРАД", "Насеље", "Општина"), objectMapper);

        assertThat(EnrichmentInputSnapshot.from(
                source("други опис", "кратко", "ГРАД", "Насеље", "Општина"), objectMapper)
                .sha256()).isNotEqualTo(original.sha256());
        assertThat(EnrichmentInputSnapshot.from(
                source("опис", "друго кратко", "ГРАД", "Насеље", "Општина"), objectMapper)
                .sha256()).isNotEqualTo(original.sha256());
        assertThat(EnrichmentInputSnapshot.from(
                source("опис", "кратко", "НОВИ КО", "Насеље", "Општина"), objectMapper)
                .sha256()).isNotEqualTo(original.sha256());
    }

    private CurrentAuctionSourceSnapshot source(
            String description,
            String shortDescription,
            String cadastral,
            String placeName,
            String municipality) {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode listing = root.putObject("listing");
        listing.put("Id", 29);
        listing.put("ShortDescription", "listing fallback");
        ObjectNode detail = root.putObject("detail");
        detail.put("Id", 29);
        detail.put("Description", description);
        detail.put("ShortDescription", shortDescription);
        ObjectNode place = detail.putObject("Place");
        place.put("Cadastral", cadastral);
        place.put("Name", placeName);
        place.put("Municipality", municipality);
        String hash = sha256(root);
        return new CurrentAuctionSourceSnapshot(
                29, hash, root, "GetImmovablePropertyDetails",
                Instant.parse("2026-09-02T10:00:00Z"));
    }

    private static String sha256(ObjectNode value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(AuctionSourceCanonicalJson.write(value)
                            .getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
