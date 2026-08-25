package rs.sud.eaukcija.coarselocation;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import rs.sud.eaukcija.enrichment.EnrichmentHashing;
import rs.sud.eaukcija.enrichment.EnrichmentStage;
import rs.sud.eaukcija.enrichment.EnrichmentStageException;
import rs.sud.eaukcija.enrichment.EnrichmentStageName;
import rs.sud.eaukcija.enrichment.EnrichmentStageResult;
import rs.sud.eaukcija.enrichment.EnrichmentVersionPin;
import rs.sud.eaukcija.enrichment.EnrichmentWorkItem;

/** Per-auction local fallback through KO, settlement, municipality, then NONE. */
@Component
public class CoarseLocationEnrichmentStage implements EnrichmentStage {

    private final CoarseLocationResolutionService service;

    public CoarseLocationEnrichmentStage(CoarseLocationResolutionService service) {
        this.service = service;
    }

    @Override
    public EnrichmentStageName name() {
        return EnrichmentStageName.ADDRESS_FALLBACK;
    }

    @Override
    public String implementationVersion() {
        return CoarseLocationResolver.RESOLVER_VERSION;
    }

    @Override
    public String activeDatasetVersion() {
        CoarseLocationResolutionService.ActiveVersion active = service.activeVersion();
        return active.version() + ":" + active.sourceSha256();
    }

    @Override
    public EnrichmentVersionPin pinActiveVersion() {
        return service.pinActiveVersion();
    }

    @Override
    public EnrichmentStageResult process(EnrichmentWorkItem item) {
        try {
            CoarseLocationResolutionService.AuctionResult result =
                    service.resolveAuction(item.auctionId());
            return EnrichmentStageResult.continuing(EnrichmentHashing.sha256(
                    implementationVersion(),
                    result.status(),
                    result.precision(),
                    result.inputFingerprint(),
                    result.datasetVersion(),
                    result.datasetSha256()));
        } catch (CoarseLocationResolutionException resolutionFailure) {
            throw EnrichmentStageException.permanent(
                    safeCode(resolutionFailure.getCode()), resolutionFailure);
        } catch (DataAccessException persistenceFailure) {
            throw EnrichmentStageException.retryable(
                    "ADDRESS_FALLBACK_PERSISTENCE_FAILED", persistenceFailure);
        }
    }

    private static String safeCode(String code) {
        return code != null && code.matches("[A-Z0-9_]{1,64}")
                ? code : "ADDRESS_FALLBACK_FAILED";
    }
}
