package rs.sud.eaukcija.enrichment;

import org.springframework.stereotype.Component;

import rs.sud.eaukcija.rgz.RgzParcelResolutionService;

/** Automatic, cache-first parcel resolution for every current #33 match. */
@Component
public class ParcelPathEnrichmentStage implements EnrichmentStage {

    private final RgzParcelResolutionService service;

    public ParcelPathEnrichmentStage(RgzParcelResolutionService service) {
        this.service = service;
    }

    @Override
    public EnrichmentStageName name() {
        return EnrichmentStageName.PARCEL_PATH;
    }

    @Override
    public String implementationVersion() {
        return RgzParcelResolutionService.RESOLVER_VERSION;
    }

    @Override
    public String activeDatasetVersion() {
        return service.activeVersion();
    }

    @Override
    public EnrichmentStageResult process(EnrichmentWorkItem item) {
        RgzParcelResolutionService.AuctionResult result = service.resolveAuction(item);
        return result.resolvedCount() > 0
                ? new EnrichmentStageResult(
                        EnrichmentStageResult.Disposition.RESOLVED, result.evidenceSha256())
                : EnrichmentStageResult.continuing(result.evidenceSha256());
    }
}
