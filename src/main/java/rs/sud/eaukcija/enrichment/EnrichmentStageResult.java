package rs.sud.eaukcija.enrichment;

import java.util.Objects;

public record EnrichmentStageResult(Disposition disposition, String evidenceSha256) {

    public enum Disposition {
        CONTINUE,
        RESOLVED,
        NOT_FOUND,
        AMBIGUOUS
    }

    public EnrichmentStageResult {
        Objects.requireNonNull(disposition, "disposition");
        EnrichmentVersions.requireSha256(evidenceSha256, "evidenceSha256");
    }

    public static EnrichmentStageResult continuing(String evidenceSha256) {
        return new EnrichmentStageResult(Disposition.CONTINUE, evidenceSha256);
    }
}
