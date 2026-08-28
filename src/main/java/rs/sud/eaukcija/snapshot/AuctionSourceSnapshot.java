package rs.sud.eaukcija.snapshot;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Objects;

/** One immutable, minimized listing plus valid detail source record. */
public record AuctionSourceSnapshot(
        long auctionId,
        String contentSha256,
        JsonNode canonicalPayload,
        String schemaVersion,
        String minimizationPolicyVersion,
        String listingEndpoint,
        String detailEndpoint,
        Instant fetchedAt,
        Instant listingFetchedAt,
        Instant detailFetchedAt,
        Instant sourceStartAt,
        Instant sourceEndAt,
        Instant sourcePublicationAt) {

    public AuctionSourceSnapshot {
        if (auctionId < 1) {
            throw new IllegalArgumentException("auctionId must be positive");
        }
        requireSha256(contentSha256);
        if (canonicalPayload == null || !canonicalPayload.isObject()
                || !canonicalPayload.path("listing").isObject()
                || !canonicalPayload.path("detail").isObject()) {
            throw new IllegalArgumentException("canonicalPayload must contain listing and detail objects");
        }
        canonicalPayload = canonicalPayload.deepCopy();
        if (!contentSha256.equals(AuctionSourceCanonicalJson.sha256(canonicalPayload))) {
            throw new IllegalArgumentException("contentSha256 does not address canonicalPayload");
        }
        requireText(schemaVersion, "schemaVersion");
        requireText(minimizationPolicyVersion, "minimizationPolicyVersion");
        requireText(listingEndpoint, "listingEndpoint");
        requireText(detailEndpoint, "detailEndpoint");
        Objects.requireNonNull(fetchedAt, "fetchedAt");
        Objects.requireNonNull(listingFetchedAt, "listingFetchedAt");
        Objects.requireNonNull(detailFetchedAt, "detailFetchedAt");
        Objects.requireNonNull(sourceStartAt, "sourceStartAt");
        Objects.requireNonNull(sourceEndAt, "sourceEndAt");
        if (sourceEndAt.isBefore(sourceStartAt)) {
            throw new IllegalArgumentException("sourceEndAt must not precede sourceStartAt");
        }
        if (fetchedAt.isBefore(listingFetchedAt) || fetchedAt.isBefore(detailFetchedAt)) {
            throw new IllegalArgumentException("fetchedAt must cover listing and detail acquisition");
        }
    }

    @Override
    public JsonNode canonicalPayload() {
        return canonicalPayload.deepCopy();
    }

    private static void requireSha256(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("contentSha256 must be lowercase SHA-256");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 160) {
            throw new IllegalArgumentException(name + " must be nonblank and at most 160 characters");
        }
    }
}
