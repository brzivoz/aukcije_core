package rs.sud.eaukcija.enrichment;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Processes one auction with short stage-scoped transactions. */
@Service
public class EnrichmentItemProcessor {

    private final EnrichmentPipeline pipeline;
    private final TransactionTemplate transactions;

    public EnrichmentItemProcessor(
            EnrichmentPipeline pipeline,
            PlatformTransactionManager transactionManager) {
        this.pipeline = pipeline;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public EnrichmentItemResult process(EnrichmentWorkItem item) {
        return pipeline.process(item, (stage, workItem) -> {
            if (stage.name() == EnrichmentStageName.PARCEL_PATH) {
                // The RGZ service commits its claim before HTTP and persists afterward.
                return stage.process(workItem);
            }
            EnrichmentStageResult result = transactions.execute(
                    status -> stage.process(workItem));
            if (result == null) {
                throw new IllegalStateException("enrichment stage transaction returned no result");
            }
            return result;
        });
    }
}
