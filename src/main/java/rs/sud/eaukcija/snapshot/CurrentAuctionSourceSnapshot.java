package rs.sud.eaukcija.snapshot;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/** Current immutable source snapshot loaded for a listing-only observation. */
public record CurrentAuctionSourceSnapshot(
        long auctionId,
        String contentSha256,
        JsonNode canonicalPayload,
        String detailEndpoint,
        Instant detailFetchedAt) {

    public CurrentAuctionSourceSnapshot {
        if (auctionId < 1) {
            throw new IllegalArgumentException("auctionId must be positive");
        }
        if (contentSha256 == null || !contentSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("contentSha256 must be lowercase SHA-256");
        }
        if (canonicalPayload == null || !canonicalPayload.isObject()
                || !canonicalPayload.path("detail").isObject()) {
            throw new IllegalArgumentException("canonicalPayload must contain a detail object");
        }
        canonicalPayload = canonicalPayload.deepCopy();
        if (!contentSha256.equals(AuctionSourceCanonicalJson.sha256(canonicalPayload))) {
            throw new IllegalArgumentException(
                    "contentSha256 does not address canonicalPayload");
        }
        if (detailEndpoint == null || detailEndpoint.isBlank()) {
            throw new IllegalArgumentException("detailEndpoint must be nonblank");
        }
        if (detailFetchedAt == null) {
            throw new IllegalArgumentException("detailFetchedAt is required");
        }
    }

    @Override
    public JsonNode canonicalPayload() {
        return canonicalPayload.deepCopy();
    }
}
