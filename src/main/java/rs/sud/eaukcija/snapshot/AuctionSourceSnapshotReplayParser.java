package rs.sud.eaukcija.snapshot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import rs.sud.eaukcija.client.EAukcijaApiTypes.AuctionDetail;
import rs.sud.eaukcija.client.EAukcijaApiTypes.AuctionSummary;

import java.util.Objects;

/** Offline parser boundary for replaying only retained source JSON. */
@Component
public final class AuctionSourceSnapshotReplayParser {

    private final ObjectMapper objectMapper;

    public AuctionSourceSnapshotReplayParser(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public ReplayedAuction parse(JsonNode canonicalPayload) {
        if (canonicalPayload == null || !canonicalPayload.isObject()
                || !canonicalPayload.path("listing").isObject()
                || !canonicalPayload.path("detail").isObject()) {
            throw new IllegalArgumentException("canonical source payload is incomplete");
        }
        try {
            AuctionSummary listing = objectMapper.treeToValue(
                    canonicalPayload.path("listing"), AuctionSummary.class);
            AuctionDetail detail = objectMapper.treeToValue(
                    canonicalPayload.path("detail"), AuctionDetail.class);
            if (listing.id() < 1 || detail.id() != listing.id()) {
                throw new IllegalArgumentException("listing and detail identities do not match");
            }
            return new ReplayedAuction(listing, detail);
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("canonical source payload cannot be replayed");
        }
    }

    public record ReplayedAuction(AuctionSummary listing, AuctionDetail detail) {
    }
}
