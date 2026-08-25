package rs.sud.eaukcija.enrichment;

import java.util.Objects;

public record EnrichmentItemResult(
        EnrichmentStateStatus status,
        EnrichmentStageName lastStage,
        String outputSha256) {

    public EnrichmentItemResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(lastStage, "lastStage");
        if (status != EnrichmentStateStatus.SUCCEEDED
                && status != EnrichmentStateStatus.TERMINAL_NOT_FOUND
                && status != EnrichmentStateStatus.AMBIGUOUS) {
            throw new IllegalArgumentException("pipeline result must be a deterministic terminal outcome");
        }
        EnrichmentVersions.requireSha256(outputSha256, "outputSha256");
    }
}
