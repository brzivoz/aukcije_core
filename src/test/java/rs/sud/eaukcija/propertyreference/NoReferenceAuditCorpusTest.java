package rs.sud.eaukcija.propertyreference;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/** Failure-selected, agent-reviewed development corpus, NOT an independent quality benchmark. */
class NoReferenceAuditCorpusTest {
    private static final Path ROOT = Path.of("corpus/property-references/no-reference-audit-v1");
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void inventoryHashesAndAllFiftyNineReviewsArePinned() throws Exception {
        var manifest = mapper.readTree(ROOT.resolve("manifest.json").toFile());
        var files = manifest.path("files").fields();
        while (files.hasNext()) {
            var file = files.next();
            assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(ROOT.resolve(file.getKey()))))).isEqualTo(file.getValue().asText());
        }
        var reviews = mapper.readTree(ROOT.resolve("reviews.json").toFile());
        assertThat(reviews).hasSize(59);
        var rows = inputs();
        assertThat(rows).hasSize(59);
        assertThat(rows.stream().map(x -> x.path("auctionId").asLong())).doesNotHaveDuplicates();
        assertThat(Stream.of(PropertyReferenceParser.legacyV1(), PropertyReferenceParser.legacyV2(), new PropertyReferenceParser())
                .map(p -> p.parse(rows.get(0)).parserVersion()))
                .containsExactly("property-reference-v1", "property-reference-v2", "property-reference-v3");
        assertThat(new PropertyReferenceQualityProfile(mapper).profile(PropertyReferenceParser.VERSION).heldOutPrecision()).isNull();
        Map<String, Long> counts = java.util.stream.StreamSupport.stream(reviews.spliterator(), false)
                .collect(Collectors.groupingBy(x -> x.path("classification").asText(), Collectors.counting()));
        assertThat(counts).hasSize(manifest.path("classificationCounts").size());
        counts.forEach((key, value) -> assertThat(manifest.path("classificationCounts").path(key).asLong()).isEqualTo(value));
        assertThat(java.util.stream.StreamSupport.stream(reviews.spliterator(), false).map(x -> x.path("auctionId").asLong()))
                .containsExactlyInAnyOrderElementsOf(rows.stream().map(x -> x.path("auctionId").asLong()).toList());
    }

    @TestFactory Stream<DynamicTest> everyReviewedEvidenceFixtureRetainsCorrectRolesAndEvidence() throws Exception {
        Map<Long, JsonNode> rows = inputs().stream().collect(Collectors.toMap(x -> x.path("auctionId").asLong(), x -> x));
        var reviews = mapper.readTree(ROOT.resolve("reviews.json").toFile());
        return java.util.stream.StreamSupport.stream(reviews.spliterator(), false).map(review ->
                DynamicTest.dynamicTest(review.path("auctionId").asText() + ":" + review.path("classification").asText(), () -> {
                    JsonNode input = rows.get(review.path("auctionId").asLong());
                    assertThat(input.path("sourceSnapshotSha256")).isEqualTo(review.path("sourceSnapshotSha256"));
                    var old = PropertyReferenceParser.legacyV2().parse(input);
                    assertThat(old.references()).noneMatch(r -> r.type() == PropertyReferenceType.PARCEL || r.type() == PropertyReferenceType.ADDRESS);
                    var current = new PropertyReferenceParser().parse(input);
                    assertThat(current.references()).noneMatch(r -> r.type() == PropertyReferenceType.ADDRESS);
                    var parcels = current.references().stream().filter(r -> r.type() == PropertyReferenceType.PARCEL).toList();
                    assertThat(parcels.stream().filter(r -> r.status() == PropertyReferenceExtractionStatus.EXTRACTED)
                            .map(ParsedPropertyReference::canonicalParcelNumber).toList())
                            .isEqualTo(strings(review.path("expectedAutomaticParcels")));
                    assertThat(parcels.stream().filter(r -> r.status() == PropertyReferenceExtractionStatus.NEEDS_REVIEW)
                            .map(ParsedPropertyReference::canonicalParcelNumber).toList())
                            .isEqualTo(strings(review.path("expectedReviewParcels")));
                    assertThat(parcels).hasSize(review.path("expectedAutomaticParcels").size() + review.path("expectedReviewParcels").size());
                    for (var ref : current.references()) {
                        if (ref.type() == PropertyReferenceType.STRUCTURED_LOCATION) continue;
                        String text = input.path(ref.sourceField().equals("detail.Description") ? "description" : "shortDescription").asText();
                        assertThat(text.substring(ref.sourceOffsetStart(), ref.sourceOffsetEnd())).isEqualTo(ref.rawEvidence());
                    }
                    assertThat(new PropertyReferenceParser().parse(input)).isEqualTo(current);
                }));
    }

    private List<JsonNode> inputs() throws Exception {
        var cases = mapper.readTree(ROOT.resolve("evidence.json").toFile());
        List<JsonNode> inputs = new java.util.ArrayList<>();
        for (var evidence : cases) {
            var input = mapper.createObjectNode().put("schemaVersion", rs.sud.eaukcija.enrichment.EnrichmentInputSnapshot.SCHEMA_VERSION);
            input.set("auctionId", evidence.path("auctionId"));
            input.set("sourceSnapshotSha256", evidence.path("sourceSnapshotSha256"));
            input.setAll((com.fasterxml.jackson.databind.node.ObjectNode) evidence.path("structuredContext"));
            input.set("description", evidence.path("descriptionEvidence"));
            input.set("shortDescription", evidence.path("shortDescriptionEvidence"));
            inputs.add(input);
        }
        return inputs;
    }
    private static List<String> strings(JsonNode array) {
        return java.util.stream.StreamSupport.stream(array.spliterator(), false).map(JsonNode::asText).toList();
    }
}
