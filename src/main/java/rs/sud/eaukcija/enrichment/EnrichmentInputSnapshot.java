package rs.sud.eaukcija.enrichment;

import java.math.BigDecimal;
import java.time.Instant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import rs.sud.eaukcija.model.Auction;

/** Canonical, minimized local input accepted at the successful-sync boundary. */
public record EnrichmentInputSnapshot(String sha256, JsonNode canonicalInput) {

    public static final String SCHEMA_VERSION = "enrichment-input-v1";

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
        put(root, "auctionNumber", auction.getAuctionNumber());
        put(root, "startDate", auction.getStartDate());
        put(root, "endDate", auction.getEndDate());
        put(root, "publicationDate", auction.getPublicationDate());
        put(root, "startingPrice", auction.getStartingPrice());
        put(root, "estimatedPrice", auction.getEstimatedPrice());
        put(root, "currentPrice", auction.getCurrentPrice());
        put(root, "maxOfferedPrice", auction.getMaxOfferedPrice());
        put(root, "bidStep", auction.getBidStep());
        put(root, "shortDescription", auction.getShortDescription());
        put(root, "description", auction.getDescription());
        put(root, "status", auction.getStatus());
        root.put("firstSale", auction.isFirstSale());
        put(root, "propertyType", auction.getPropertyType());
        put(root, "executorName", auction.getExecutorName());
        put(root, "categoryName", auction.getCategoryName());
        put(root, "placeName", auction.getPlaceName());
        put(root, "placeZipCode", auction.getPlaceZipCode());
        put(root, "municipality", auction.getMunicipality());
        put(root, "cadastral", auction.getCadastral());
        root.put("detailsFetched", auction.isDetailsFetched());
        put(root, "listingFingerprint", auction.getListingFingerprint());
        if (auction.getSourceDetailCategoryId() == null) {
            root.putNull("sourceDetailCategoryId");
        } else {
            root.put("sourceDetailCategoryId", auction.getSourceDetailCategoryId());
        }
        put(root, "saleScope", auction.getSaleScope() == null ? null : auction.getSaleScope().name());
        put(root, "normalizedPropertyKind", auction.getNormalizedPropertyKind() == null
                ? null : auction.getNormalizedPropertyKind().name());
        put(root, "taxonomySha256", auction.getTaxonomySha256());
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

    private static void put(ObjectNode root, String field, Instant value) {
        put(root, field, value == null ? null : value.toString());
    }

    private static void put(ObjectNode root, String field, BigDecimal value) {
        put(root, field, value == null ? null : normalizedDecimal(value));
    }

    private static String normalizedDecimal(BigDecimal value) {
        BigDecimal normalized = value.stripTrailingZeros();
        if (normalized.scale() < 0) {
            normalized = normalized.setScale(0);
        }
        return normalized.toPlainString();
    }
}
