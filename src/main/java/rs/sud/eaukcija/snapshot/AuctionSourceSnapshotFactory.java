package rs.sud.eaukcija.snapshot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import rs.sud.eaukcija.sync.persistence.SaleScope;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;

/**
 * Applies the reviewed public-field minimization policy and hashes canonical
 * source JSON before any normalized entity is involved.
 */
@Component
public final class AuctionSourceSnapshotFactory {

    public static final String SCHEMA_VERSION = "eaukcija-listing-detail-v1";
    public static final String MINIMIZATION_POLICY_VERSION = "public-auction-fields-v1";
    public static final String LISTING_ENDPOINT = "GetAuctionsByCategoryId";
    public static final String IMMOVABLE_DETAIL_ENDPOINT = "GetImmovablePropertyDetails";
    public static final String COMMON_DETAIL_ENDPOINT = "GetCommonPropertyDetails";
    public static final int MAX_SOURCE_RECORD_DEPTH = 32;
    public static final int MAX_CANONICAL_BYTES = 64 * 1024;

    private static final List<String> LISTING_FIELDS = List.of(
            "AuctionNumber", "CurrentPrice", "EndDate", "Id", "IsFirstSale",
            "MaxOfferedPrice", "PropertyType", "ShortDescription", "StartDate",
            "StartingPrice", "Status");
    private static final List<String> DETAIL_FIELDS = List.of(
            "AuctionNumber", "BidStep", "Category", "CurrentPrice", "Description",
            "EndDate", "EstimatedPrice", "ExecutorName", "Id", "IsFirstSale",
            "MaxOfferedPrice", "Place", "PropertyType", "PublicationDate",
            "ShortDescription", "StartDate", "StartingPrice", "Status");
    private static final List<String> CATEGORY_FIELDS = List.of("Id", "Name");
    private static final List<String> PLACE_FIELDS = List.of(
            "Cadastral", "Id", "Municipality", "Name", "ZipCode");

    private final ObjectMapper objectMapper;

    public AuctionSourceSnapshotFactory(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public AuctionSourceSnapshot create(
            long auctionId,
            JsonNode listingSource,
            JsonNode detailSource,
            SaleScope saleScope,
            Instant listingFetchedAt,
            Instant detailFetchedAt) {
        String detailEndpoint = switch (Objects.requireNonNull(saleScope, "saleScope")) {
            case IMMOVABLE -> IMMOVABLE_DETAIL_ENDPOINT;
            case COMMON -> COMMON_DETAIL_ENDPOINT;
        };
        return create(
                auctionId,
                minimizeListing(auctionId, listingSource),
                minimizeDetail(auctionId, detailSource),
                detailEndpoint,
                listingFetchedAt, detailFetchedAt);
    }

    /** Canonical minimized listing used to detect source conflicts across roots. */
    public MinimizedListing minimizeListing(long auctionId, JsonNode listingSource) {
        requireSourceObject(listingSource, "listingSource");
        requireDepth(listingSource, 1);
        ObjectNode listing = minimize(listingSource, LISTING_FIELDS);
        requireAuctionId(listing, auctionId, "listing");
        if (AuctionSourceCanonicalJson.bytes(listing).length > MAX_CANONICAL_BYTES) {
            throw new IllegalArgumentException("canonical listing exceeds maximum bytes");
        }
        return new MinimizedListing(auctionId, listing);
    }

    /** Sanitizes a valid detail immediately so excluded binary is never staged. */
    public MinimizedDetail minimizeDetail(long auctionId, JsonNode detailSource) {
        requireSourceObject(detailSource, "detailSource");
        requireDepth(detailSource, 1);
        ObjectNode detail = minimizeDetailFields(detailSource);
        requireAuctionId(detail, auctionId, "detail");
        if (AuctionSourceCanonicalJson.bytes(detail).length > MAX_CANONICAL_BYTES) {
            throw new IllegalArgumentException("canonical detail exceeds maximum bytes");
        }
        return new MinimizedDetail(auctionId, detail);
    }

    public AuctionSourceSnapshot combineWithCurrentDetail(
            long auctionId,
            MinimizedListing listing,
            CurrentAuctionSourceSnapshot current,
            Instant listingFetchedAt) {
        Objects.requireNonNull(current, "current");
        if (current.auctionId() != auctionId) {
            throw new IllegalArgumentException("current source snapshot belongs to another auction");
        }
        return create(
                auctionId,
                listing,
                minimizeDetail(auctionId, current.canonicalPayload().path("detail")),
                current.detailEndpoint(),
                listingFetchedAt,
                current.detailFetchedAt());
    }

    public AuctionSourceSnapshot create(
            long auctionId,
            MinimizedListing listing,
            MinimizedDetail detail,
            SaleScope saleScope,
            Instant listingFetchedAt,
            Instant detailFetchedAt) {
        String detailEndpoint = switch (Objects.requireNonNull(saleScope, "saleScope")) {
            case IMMOVABLE -> IMMOVABLE_DETAIL_ENDPOINT;
            case COMMON -> COMMON_DETAIL_ENDPOINT;
        };
        return create(
                auctionId, listing, detail, detailEndpoint,
                listingFetchedAt, detailFetchedAt);
    }

    private AuctionSourceSnapshot create(
            long auctionId,
            MinimizedListing minimizedListing,
            MinimizedDetail minimizedDetail,
            String detailEndpoint,
            Instant listingFetchedAt,
            Instant detailFetchedAt) {
        Objects.requireNonNull(minimizedListing, "minimizedListing");
        Objects.requireNonNull(minimizedDetail, "minimizedDetail");
        if (minimizedListing.auctionId() != auctionId
                || minimizedDetail.auctionId() != auctionId) {
            throw new IllegalArgumentException("minimized source belongs to another auction");
        }
        ObjectNode listing = (ObjectNode) minimizedListing.canonicalJson();
        ObjectNode detail = (ObjectNode) minimizedDetail.canonicalJson();
        requireAuctionId(listing, auctionId, "listing");
        requireAuctionId(detail, auctionId, "detail");

        ObjectNode canonical = objectMapper.createObjectNode();
        canonical.set("detail", detail);
        canonical.set("listing", listing);
        byte[] canonicalBytes = AuctionSourceCanonicalJson.bytes(canonical);
        if (canonicalBytes.length > MAX_CANONICAL_BYTES) {
            throw new IllegalArgumentException("canonical source snapshot exceeds maximum bytes");
        }
        Instant sourceStartAt = sourceInstant(listing, "StartDate", false);
        Instant sourceEndAt = sourceInstant(listing, "EndDate", false);
        Instant sourcePublicationAt = sourceInstant(detail, "PublicationDate", true);
        Instant fetchedAt = listingFetchedAt.isAfter(detailFetchedAt)
                ? listingFetchedAt : detailFetchedAt;
        return new AuctionSourceSnapshot(
                auctionId,
                AuctionSourceCanonicalJson.sha256(canonical),
                canonical,
                SCHEMA_VERSION,
                MINIMIZATION_POLICY_VERSION,
                LISTING_ENDPOINT,
                detailEndpoint,
                fetchedAt,
                listingFetchedAt,
                detailFetchedAt,
                sourceStartAt,
                sourceEndAt,
                sourcePublicationAt);
    }

    private ObjectNode minimize(JsonNode source, List<String> fields) {
        ObjectNode minimized = objectMapper.createObjectNode();
        for (String field : fields) {
            copyScalar(source, minimized, field);
        }
        return minimized;
    }

    private ObjectNode minimizeDetailFields(JsonNode source) {
        ObjectNode minimized = objectMapper.createObjectNode();
        for (String field : DETAIL_FIELDS) {
            if ("Category".equals(field)) {
                copyNestedObject(source, minimized, field, CATEGORY_FIELDS);
            } else if ("Place".equals(field)) {
                copyNestedObject(source, minimized, field, PLACE_FIELDS);
            } else {
                copyScalar(source, minimized, field);
            }
        }
        return minimized;
    }

    private static void copyScalar(JsonNode source, ObjectNode target, String field) {
        JsonNode value = source.get(field);
        if (value == null) {
            return;
        }
        if (value.isContainerNode()) {
            throw new IllegalArgumentException(field + " must be a scalar or null");
        }
        target.set(field, value.deepCopy());
    }

    private void copyNestedObject(
            JsonNode source,
            ObjectNode target,
            String field,
            List<String> allowedFields) {
        JsonNode value = source.get(field);
        if (value == null) {
            return;
        }
        if (value.isNull()) {
            target.putNull(field);
            return;
        }
        if (!value.isObject()) {
            throw new IllegalArgumentException(field + " must be an object or null");
        }
        target.set(field, minimize(value, allowedFields));
    }

    private static void requireSourceObject(JsonNode source, String name) {
        if (source == null || source.isNull() || !source.isObject()) {
            throw new IllegalArgumentException(name + " must be a non-null JSON object");
        }
    }

    private static void requireAuctionId(ObjectNode source, long auctionId, String name) {
        JsonNode id = source.get("Id");
        if (auctionId < 1 || id == null || !id.isIntegralNumber() || !id.canConvertToLong()
                || id.longValue() != auctionId) {
            throw new IllegalArgumentException(name + " Id does not match the auction");
        }
    }

    private static Instant sourceInstant(ObjectNode source, String field, boolean nullable) {
        JsonNode value = source.get(field);
        if (nullable && (value == null || value.isNull()
                || (value.isTextual() && value.textValue().isBlank()))) {
            return null;
        }
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException(field + " must be an ISO-8601 string");
        }
        try {
            return Instant.parse(value.textValue());
        } catch (DateTimeParseException invalid) {
            throw new IllegalArgumentException(field + " must be an ISO-8601 instant");
        }
    }

    private static void requireDepth(JsonNode value, int depth) {
        if (depth > MAX_SOURCE_RECORD_DEPTH) {
            throw new IllegalArgumentException("source record exceeds maximum JSON depth");
        }
        if (value.isContainerNode()) {
            value.forEach(child -> requireDepth(child, depth + 1));
        }
    }

    public static final class MinimizedListing {
        private final long auctionId;
        private final JsonNode canonicalJson;

        private MinimizedListing(long auctionId, JsonNode canonicalJson) {
            requireMinimized(auctionId, canonicalJson, "listing");
            this.auctionId = auctionId;
            this.canonicalJson = canonicalJson.deepCopy();
        }

        public long auctionId() {
            return auctionId;
        }

        public JsonNode canonicalJson() {
            return canonicalJson.deepCopy();
        }

        @Override
        public boolean equals(Object other) {
            return this == other
                    || (other instanceof MinimizedListing listing
                    && auctionId == listing.auctionId
                    && canonicalJson.equals(listing.canonicalJson));
        }

        @Override
        public int hashCode() {
            return Objects.hash(auctionId, canonicalJson);
        }
    }

    public static final class MinimizedDetail {
        private final long auctionId;
        private final JsonNode canonicalJson;

        private MinimizedDetail(long auctionId, JsonNode canonicalJson) {
            requireMinimized(auctionId, canonicalJson, "detail");
            this.auctionId = auctionId;
            this.canonicalJson = canonicalJson.deepCopy();
        }

        public long auctionId() {
            return auctionId;
        }

        public JsonNode canonicalJson() {
            return canonicalJson.deepCopy();
        }
    }

    private static void requireMinimized(long auctionId, JsonNode value, String name) {
        if (auctionId < 1 || value == null || !value.isObject()) {
            throw new IllegalArgumentException(name + " must be a minimized source object");
        }
    }
}
