package rs.sud.eaukcija.enrichment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import rs.sud.eaukcija.snapshot.AuctionSourceSnapshot;
import rs.sud.eaukcija.snapshot.CurrentAuctionSourceSnapshot;

/** Canonical, minimized local input accepted at the successful-sync boundary. */
public record EnrichmentInputSnapshot(String sha256, JsonNode canonicalInput) {

    public static final String SCHEMA_VERSION = "enrichment-location-input-v2";

    public EnrichmentInputSnapshot {
        EnrichmentVersions.requireSha256(sha256, "sha256");
        if (canonicalInput == null || !canonicalInput.isObject()) {
            throw new IllegalArgumentException("canonicalInput must be a JSON object");
        }
        canonicalInput = canonicalInput.deepCopy();
    }

    @Override
    public JsonNode canonicalInput() {
        return canonicalInput.deepCopy();
    }

    public static EnrichmentInputSnapshot from(
            AuctionSourceSnapshot sourceSnapshot,
            ObjectMapper objectMapper) {
        if (sourceSnapshot == null) {
            throw new IllegalArgumentException("sourceSnapshot is required");
        }
        return from(
                sourceSnapshot.auctionId(),
                sourceSnapshot.contentSha256(),
                sourceSnapshot.canonicalPayload(),
                objectMapper);
    }

    public static EnrichmentInputSnapshot from(
            CurrentAuctionSourceSnapshot sourceSnapshot,
            ObjectMapper objectMapper) {
        if (sourceSnapshot == null) {
            throw new IllegalArgumentException("sourceSnapshot is required");
        }
        return from(
                sourceSnapshot.auctionId(),
                sourceSnapshot.contentSha256(),
                sourceSnapshot.canonicalPayload(),
                objectMapper);
    }

    private static EnrichmentInputSnapshot from(
            long auctionId,
            String sourceSnapshotSha256,
            JsonNode canonicalSource,
            ObjectMapper objectMapper) {
        if (auctionId <= 0 || canonicalSource == null
                || !canonicalSource.path("detail").isObject()
                || !canonicalSource.path("listing").isObject()) {
            throw new IllegalArgumentException("complete source snapshot is required");
        }
        JsonNode detail = canonicalSource.path("detail");
        JsonNode listing = canonicalSource.path("listing");
        JsonNode place = detail.path("Place");
        ObjectNode root = objectMapper.createObjectNode();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("sourceSnapshotSha256", sourceSnapshotSha256);
        root.put("auctionId", auctionId);
        put(root, "placeName", text(place, "Name"));
        put(root, "municipality", text(place, "Municipality"));
        put(root, "cadastral", text(place, "Cadastral"));
        put(root, "description", text(detail, "Description"));
        String shortDescription = text(detail, "ShortDescription");
        put(root, "shortDescription", shortDescription == null
                ? text(listing, "ShortDescription") : shortDescription);
        try {
            String canonicalJson = objectMapper.writeValueAsString(root);
            return new EnrichmentInputSnapshot(
                    EnrichmentHashing.sha256(canonicalJson), root);
        } catch (JsonProcessingException serializationFailure) {
            throw new IllegalStateException("could not canonicalize enrichment input", serializationFailure);
        }
    }

    private static String text(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new IllegalArgumentException(field + " must be textual or null");
        }
        return value.textValue();
    }

    private static void put(ObjectNode root, String field, String value) {
        if (value == null) {
            root.putNull(field);
        } else {
            root.put(field, value);
        }
    }

}
