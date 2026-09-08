package rs.sud.eaukcija.enrichment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.UUID;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class EnrichmentItemProcessorTest {

    @Test
    void keepsDatabaseStagesTransactionalButLeavesParcelNetworkStageUnwrapped() {
        EnrichmentPipeline pipeline = new EnrichmentPipeline(Arrays.stream(EnrichmentStageName.values())
                .map(ProbeStage::new)
                .map(EnrichmentStage.class::cast)
                .toList());
        EnrichmentItemProcessor processor = new EnrichmentItemProcessor(
                pipeline, new ProbeTransactionManager());
        String hash = "a".repeat(64);
        EnrichmentWorkItem item = new EnrichmentWorkItem(
                41L, UUID.randomUUID(), hash, hash, hash,
                JsonNodeFactory.instance.objectNode());

        EnrichmentItemResult result = processor.process(item);

        assertThat(result.status()).isEqualTo(EnrichmentStateStatus.SUCCEEDED);
    }

    private record ProbeStage(EnrichmentStageName name) implements EnrichmentStage {

        @Override
        public String implementationVersion() {
            return "test-v1";
        }

        @Override
        public String activeDatasetVersion() {
            return "test-dataset-v1";
        }

        @Override
        public EnrichmentStageResult process(EnrichmentWorkItem item) {
            boolean expectedTransaction = name != EnrichmentStageName.PARCEL_PATH;
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .as("transaction state for %s", name)
                    .isEqualTo(expectedTransaction);
            String evidence = EnrichmentHashing.sha256("probe", name.name());
            return name == EnrichmentStageName.SELECTED_RESOLUTION
                    ? new EnrichmentStageResult(EnrichmentStageResult.Disposition.RESOLVED, evidence)
                    : EnrichmentStageResult.continuing(evidence);
        }
    }

    private static final class ProbeTransactionManager extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            // AbstractPlatformTransactionManager marks the thread transaction active.
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            // No backing resource is needed for the transaction-boundary assertion.
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            // No backing resource is needed for the transaction-boundary assertion.
        }
    }
}
