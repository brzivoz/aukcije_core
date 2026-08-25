package rs.sud.eaukcija.enrichment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class EnrichmentPipelineTest {

    private static final String SNAPSHOT = "a".repeat(64);
    private static final String DEPENDENCY = "b".repeat(64);
    private static final String WORK_KEY = "c".repeat(64);

    @Test
    void alwaysExecutesTheFiveStagesInContractOrderAndProducesAStableHash() {
        List<EnrichmentStageName> calls = new ArrayList<>();
        EnrichmentPipeline pipeline = new EnrichmentPipeline(List.of(
                stage(EnrichmentStageName.SELECTED_RESOLUTION, calls,
                        EnrichmentStageResult.Disposition.NOT_FOUND),
                stage(EnrichmentStageName.PARCEL_PATH, calls,
                        EnrichmentStageResult.Disposition.CONTINUE),
                stage(EnrichmentStageName.PARSE, calls,
                        EnrichmentStageResult.Disposition.CONTINUE),
                stage(EnrichmentStageName.ADDRESS_FALLBACK, calls,
                        EnrichmentStageResult.Disposition.RESOLVED),
                stage(EnrichmentStageName.KO_MATCHING, calls,
                        EnrichmentStageResult.Disposition.CONTINUE)));

        EnrichmentItemResult first = pipeline.process(item());
        EnrichmentItemResult second = pipeline.process(item());

        assertThat(calls).containsExactly(
                EnrichmentStageName.PARSE,
                EnrichmentStageName.KO_MATCHING,
                EnrichmentStageName.PARCEL_PATH,
                EnrichmentStageName.ADDRESS_FALLBACK,
                EnrichmentStageName.SELECTED_RESOLUTION,
                EnrichmentStageName.PARSE,
                EnrichmentStageName.KO_MATCHING,
                EnrichmentStageName.PARCEL_PATH,
                EnrichmentStageName.ADDRESS_FALLBACK,
                EnrichmentStageName.SELECTED_RESOLUTION);
        assertThat(first.status()).isEqualTo(EnrichmentStateStatus.TERMINAL_NOT_FOUND);
        assertThat(first.lastStage()).isEqualTo(EnrichmentStageName.SELECTED_RESOLUTION);
        assertThat(second).isEqualTo(first);
    }

    @Test
    void composesOneStableVersionSetFromEveryStage() {
        EnrichmentPipeline pipeline = pipeline(new ArrayList<>());

        EnrichmentVersions first = pipeline.activeVersions();
        EnrichmentVersions second = pipeline.activeVersions();

        assertThat(first).isEqualTo(second);
        assertThat(first.parserVersion()).isEqualTo("impl-PARSE");
        assertThat(first.resolverVersion()).startsWith("resolver-set-");
        assertThat(first.datasetVersion()).startsWith("dataset-set-");
        assertThat(first.resolverVersion()).hasSize("resolver-set-".length() + 64);
        assertThat(first.datasetVersion()).hasSize("dataset-set-".length() + 64);
    }

    @Test
    void refusesMissingDuplicateAndUnversionedStagesAtStartup() {
        List<EnrichmentStage> missing = new ArrayList<>();
        for (EnrichmentStageName name : EnrichmentStageName.values()) {
            if (name != EnrichmentStageName.PARCEL_PATH) {
                missing.add(stage(name, new ArrayList<>(), EnrichmentStageResult.Disposition.CONTINUE));
            }
        }
        assertThatThrownBy(() -> new EnrichmentPipeline(missing))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("all five enrichment stages");

        List<EnrichmentStage> duplicate = new ArrayList<>(missing);
        duplicate.add(stage(EnrichmentStageName.PARSE, new ArrayList<>(),
                EnrichmentStageResult.Disposition.CONTINUE));
        assertThatThrownBy(() -> new EnrichmentPipeline(duplicate))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate enrichment stage PARSE");

        EnrichmentStage invalid = new StubStage(
                EnrichmentStageName.PARSE,
                " ",
                "dataset",
                new ArrayList<>(),
                EnrichmentStageResult.continuing(EnrichmentHashing.sha256("invalid")));
        List<EnrichmentStage> invalidSet = new ArrayList<>();
        invalidSet.add(invalid);
        for (EnrichmentStageName name : EnrichmentStageName.values()) {
            if (name != EnrichmentStageName.PARSE) {
                invalidSet.add(stage(name, new ArrayList<>(), EnrichmentStageResult.Disposition.CONTINUE));
            }
        }
        assertThatThrownBy(() -> new EnrichmentPipeline(invalidSet).activeVersions())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PARSE has no valid active implementation version");
    }

    @Test
    void classifiesStageFailuresAndNeverCopiesTheCauseMessage() {
        String sentinel = "password=secret raw-payload";
        List<EnrichmentStage> stages = new ArrayList<>();
        for (EnrichmentStageName name : EnrichmentStageName.values()) {
            if (name == EnrichmentStageName.PARCEL_PATH) {
                stages.add(new EnrichmentStage() {
                    @Override
                    public EnrichmentStageName name() {
                        return name;
                    }

                    @Override
                    public String implementationVersion() {
                        return "impl-" + name;
                    }

                    @Override
                    public String activeDatasetVersion() {
                        return "data-" + name;
                    }

                    @Override
                    public EnrichmentStageResult process(EnrichmentWorkItem item) {
                        throw new IllegalStateException(sentinel);
                    }
                });
            } else {
                stages.add(stage(name, new ArrayList<>(), EnrichmentStageResult.Disposition.CONTINUE));
            }
        }

        assertThatThrownBy(() -> new EnrichmentPipeline(stages).process(item()))
                .isInstanceOf(EnrichmentStageException.class)
                .satisfies(failure -> {
                    EnrichmentStageException stageFailure = (EnrichmentStageException) failure;
                    assertThat(stageFailure.retryable()).isFalse();
                    assertThat(stageFailure.stage()).isEqualTo(EnrichmentStageName.PARCEL_PATH);
                    assertThat(stageFailure.safeCode()).isEqualTo("STAGE_INTERNAL");
                    assertThat(stageFailure.getMessage()).doesNotContain(sentinel);
                });
    }

    @Test
    void refusesASelectedResolutionStageThatDoesNotChooseATerminalDisposition() {
        assertThatThrownBy(() -> pipeline(new ArrayList<>()).process(item()))
                .isInstanceOf(EnrichmentStageException.class)
                .satisfies(failure -> {
                    EnrichmentStageException stageFailure = (EnrichmentStageException) failure;
                    assertThat(stageFailure.safeCode()).isEqualTo("SELECTED_RESOLUTION_INCOMPLETE");
                    assertThat(stageFailure.stage())
                            .isEqualTo(EnrichmentStageName.SELECTED_RESOLUTION);
                });
    }

    private static EnrichmentPipeline pipeline(List<EnrichmentStageName> calls) {
        List<EnrichmentStage> stages = new ArrayList<>();
        for (EnrichmentStageName name : EnrichmentStageName.values()) {
            stages.add(stage(name, calls, EnrichmentStageResult.Disposition.CONTINUE));
        }
        return new EnrichmentPipeline(stages);
    }

    private static EnrichmentStage stage(
            EnrichmentStageName name,
            List<EnrichmentStageName> calls,
            EnrichmentStageResult.Disposition disposition) {
        return new StubStage(
                name,
                "impl-" + name,
                "data-" + name,
                calls,
                new EnrichmentStageResult(disposition, EnrichmentHashing.sha256(name.name())));
    }

    private static EnrichmentWorkItem item() {
        return new EnrichmentWorkItem(
                29L,
                UUID.fromString("11111111-1111-4111-8111-111111111111"),
                SNAPSHOT,
                DEPENDENCY,
                WORK_KEY,
                new ObjectMapper().createObjectNode().put("auctionId", 29));
    }

    private record StubStage(
            EnrichmentStageName name,
            String implementationVersion,
            String activeDatasetVersion,
            List<EnrichmentStageName> calls,
            EnrichmentStageResult result) implements EnrichmentStage {

        @Override
        public EnrichmentStageResult process(EnrichmentWorkItem item) {
            calls.add(name);
            return result;
        }
    }
}
