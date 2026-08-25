package rs.sud.eaukcija.enrichment;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Consumes already validated private parcel evidence. Artifact import remains
 * user initiated; this scheduled path never calls RGZ or another network.
 */
@Component
public class ParcelPathEnrichmentStage implements EnrichmentStage {

    private static final String VERSION = "private-parcel-evidence-selection-v1";

    private final JdbcTemplate jdbc;

    public ParcelPathEnrichmentStage(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public EnrichmentStageName name() {
        return EnrichmentStageName.PARCEL_PATH;
    }

    @Override
    public String implementationVersion() {
        return VERSION;
    }

    @Override
    public String activeDatasetVersion() {
        return "PRIVATE_LOCAL_ONLY";
    }

    @Override
    public EnrichmentStageResult process(EnrichmentWorkItem item) {
        List<ParcelEvidence> rows = jdbc.query("""
                SELECT attempt.input_fingerprint, attempt.resolver_version,
                       attempt.source_dataset_version, attempt.source_dataset_sha256,
                       attempt.geometry_id::text
                  FROM property_references reference
                  JOIN current_location_resolutions current
                    ON current.property_reference_id = reference.id
                  JOIN location_resolution_attempts attempt
                    ON attempt.id = current.resolution_attempt_id
                 WHERE reference.auction_id = ?
                   AND attempt.resolution_status = 'RESOLVED'
                   AND attempt.location_precision = 'PARCEL'
                 ORDER BY attempt.completed_at DESC, attempt.id DESC
                 LIMIT 1
                """, (result, row) -> new ParcelEvidence(
                result.getString("input_fingerprint"),
                result.getString("resolver_version"),
                result.getString("source_dataset_version"),
                result.getString("source_dataset_sha256"),
                result.getString("geometry_id")), item.auctionId());
        if (rows.isEmpty()) {
            return EnrichmentStageResult.continuing(
                    EnrichmentHashing.sha256(VERSION, Long.toString(item.auctionId()), "NO_PRIVATE_PARCEL"));
        }
        ParcelEvidence evidence = rows.get(0);
        return new EnrichmentStageResult(
                EnrichmentStageResult.Disposition.RESOLVED,
                EnrichmentHashing.sha256(
                        VERSION,
                        evidence.inputFingerprint(),
                        evidence.resolverVersion(),
                        evidence.datasetVersion(),
                        evidence.datasetSha256(),
                        evidence.geometryId()));
    }

    private record ParcelEvidence(
            String inputFingerprint,
            String resolverVersion,
            String datasetVersion,
            String datasetSha256,
            String geometryId) {
    }
}
