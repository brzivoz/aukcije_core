package rs.sud.eaukcija.enrichment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import rs.sud.eaukcija.model.Auction;

/** Canonical, minimized local input accepted at the successful-sync boundary. */
public record EnrichmentInputSnapshot(String sha256, JsonNode canonicalInput) {

    public static final String SCHEMA_VERSION = "enrichment-location-input-v1";

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

    public static EnrichmentInputSnapshot from(Auction auction, ObjectMapper objectMapper) {
        if (auction == null || auction.getId() == null || auction.getId() <= 0) {
            throw new IllegalArgumentException("auction with a positive id is required");
        }
        ObjectNode root = objectMapper.createObjectNode();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("auctionId", auction.getId());
        put(root, "placeName", auction.getPlaceName());
        put(root, "municipality", auction.getMunicipality());
        put(root, "cadastral", auction.getCadastral());
        try {
            String canonicalJson = objectMapper.writeValueAsString(root);
            return new EnrichmentInputSnapshot(
                    EnrichmentHashing.sha256(canonicalJson), root);
        } catch (JsonProcessingException serializationFailure) {
            throw new IllegalStateException("could not canonicalize enrichment input", serializationFailure);
        }
    }

    private static void put(ObjectNode root, String field, String value) {
        if (value == null) {
            root.putNull(field);
        } else {
            root.put(field, value);
        }
    }

}
