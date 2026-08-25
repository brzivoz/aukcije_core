package rs.sud.eaukcija.enrichment;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Gives every auction its own transaction so one failure cannot abort the run. */
@Service
public class EnrichmentItemProcessor {

    private final EnrichmentPipeline pipeline;

    public EnrichmentItemProcessor(EnrichmentPipeline pipeline) {
        this.pipeline = pipeline;
    }

    @Transactional
    public EnrichmentItemResult process(EnrichmentWorkItem item) {
        return pipeline.process(item);
    }
}
