package rs.sud.eaukcija.enrichment;

import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

public record EnrichmentWorkItem(
        long auctionId,
        UUID sourceSyncRunId,
        String snapshotSha256,
        String dependencySha256,
        String workKeySha256,
        JsonNode canonicalInput,
        UUID enrichmentRunId) {

    public EnrichmentWorkItem(
            long auctionId,
            UUID sourceSyncRunId,
            String snapshotSha256,
            String dependencySha256,
            String workKeySha256,
            JsonNode canonicalInput) {
        this(auctionId, sourceSyncRunId, snapshotSha256, dependencySha256,
                workKeySha256, canonicalInput, null);
    }

    public EnrichmentWorkItem {
        if (auctionId <= 0) {
            throw new IllegalArgumentException("auctionId must be positive");
        }
        Objects.requireNonNull(sourceSyncRunId, "sourceSyncRunId");
        EnrichmentVersions.requireSha256(snapshotSha256, "snapshotSha256");
        EnrichmentVersions.requireSha256(dependencySha256, "dependencySha256");
        EnrichmentVersions.requireSha256(workKeySha256, "workKeySha256");
        Objects.requireNonNull(canonicalInput, "canonicalInput");
        if (!canonicalInput.isObject()) {
            throw new IllegalArgumentException("canonicalInput must be a JSON object");
        }
        canonicalInput = canonicalInput.deepCopy();
    }

    @Override
    public JsonNode canonicalInput() {
        return canonicalInput.deepCopy();
    }

    public EnrichmentWorkItem forRun(UUID runId) {
        return new EnrichmentWorkItem(
                auctionId, sourceSyncRunId, snapshotSha256, dependencySha256,
                workKeySha256, canonicalInput, Objects.requireNonNull(runId, "runId"));
    }
}
