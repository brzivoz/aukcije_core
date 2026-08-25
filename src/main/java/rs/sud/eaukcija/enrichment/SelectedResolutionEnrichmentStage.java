package rs.sud.eaukcija.enrichment;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import rs.sud.eaukcija.spatial.LocationSelectionSql;

/** Selects the best retained result after every resolver tier has had a chance. */
@Component
public class SelectedResolutionEnrichmentStage implements EnrichmentStage {

    private static final String VERSION = "selected-resolution-v1";

    private final JdbcTemplate jdbc;

    public SelectedResolutionEnrichmentStage(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public EnrichmentStageName name() {
        return EnrichmentStageName.SELECTED_RESOLUTION;
    }

    @Override
    public String implementationVersion() {
        return VERSION;
    }

    @Override
    public String activeDatasetVersion() {
        return "PERSISTED_RESOLUTION_EVIDENCE";
    }

    @Override
    public EnrichmentStageResult process(EnrichmentWorkItem item) {
        String rank = LocationSelectionSql.precisionRank("attempt.location_precision");
        List<Selected> selected = jdbc.query("""
                SELECT attempt.resolution_status, attempt.location_precision,
                       attempt.resolver, attempt.resolver_version,
                       attempt.input_fingerprint, attempt.source_dataset,
                       attempt.source_dataset_version, attempt.source_dataset_sha256,
                       attempt.geometry_id::text
                  FROM property_references reference
                  JOIN current_location_resolutions current
                    ON current.property_reference_id = reference.id
                  JOIN location_resolution_attempts attempt
                    ON attempt.id = current.resolution_attempt_id
                 WHERE reference.auction_id = ?
                 ORDER BY %s DESC, attempt.completed_at DESC, attempt.id DESC
                 LIMIT 1
                """.formatted(rank), (result, row) -> new Selected(
                result.getString("resolution_status"),
                result.getString("location_precision"),
                result.getString("resolver"),
                result.getString("resolver_version"),
                result.getString("input_fingerprint"),
                result.getString("source_dataset"),
                result.getString("source_dataset_version"),
                result.getString("source_dataset_sha256"),
                result.getString("geometry_id")), item.auctionId());

        String koStatus = jdbc.query("""
                SELECT status FROM auction_structured_ko_matches WHERE auction_id = ?
                """, result -> result.next() ? result.getString(1) : null, item.auctionId());
        if (selected.isEmpty() || "NONE".equals(selected.get(0).precision())) {
            EnrichmentStageResult.Disposition disposition = "AMBIGUOUS".equals(koStatus)
                    ? EnrichmentStageResult.Disposition.AMBIGUOUS
                    : EnrichmentStageResult.Disposition.NOT_FOUND;
            return new EnrichmentStageResult(
                    disposition,
                    EnrichmentHashing.sha256(VERSION, Long.toString(item.auctionId()), "NONE", koStatus));
        }
        Selected value = selected.get(0);
        return new EnrichmentStageResult(
                EnrichmentStageResult.Disposition.RESOLVED,
                EnrichmentHashing.sha256(
                        VERSION,
                        value.status(),
                        value.precision(),
                        value.resolver(),
                        value.resolverVersion(),
                        value.inputFingerprint(),
                        value.dataset(),
                        value.datasetVersion(),
                        value.datasetSha256(),
                        value.geometryId()));
    }

    private record Selected(
            String status,
            String precision,
            String resolver,
            String resolverVersion,
            String inputFingerprint,
            String dataset,
            String datasetVersion,
            String datasetSha256,
            String geometryId) {
    }
}
