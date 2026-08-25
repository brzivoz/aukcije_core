package rs.sud.eaukcija.enrichment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

import org.springframework.stereotype.Component;

/** Fixed, deterministic parse-to-selection pipeline. */
@Component
public class EnrichmentPipeline {

    private final List<EnrichmentStage> stages;

    public EnrichmentPipeline(List<EnrichmentStage> stages) {
        List<EnrichmentStage> ordered = new ArrayList<>(stages);
        ordered.sort(Comparator.comparingInt(stage -> stage.name().ordinal()));
        EnumSet<EnrichmentStageName> names = EnumSet.noneOf(EnrichmentStageName.class);
        for (EnrichmentStage stage : ordered) {
            if (!names.add(stage.name())) {
                throw new IllegalStateException("duplicate enrichment stage " + stage.name());
            }
        }
        if (!names.equals(EnumSet.allOf(EnrichmentStageName.class))) {
            throw new IllegalStateException("all five enrichment stages must be configured");
        }
        this.stages = List.copyOf(ordered);
    }

    public EnrichmentVersions activeVersions() {
        String parserVersion = null;
        List<String> resolverComponents = new ArrayList<>();
        List<String> datasetComponents = new ArrayList<>();
        for (EnrichmentStage stage : stages) {
            String implementation = requireVersion(stage.implementationVersion(), stage.name(), "implementation");
            String dataset = requireVersion(stage.activeDatasetVersion(), stage.name(), "dataset");
            if (stage.name() == EnrichmentStageName.PARSE) {
                parserVersion = implementation;
            } else {
                resolverComponents.add(stage.name() + ":" + implementation);
            }
            datasetComponents.add(stage.name() + ":" + dataset);
        }
        return new EnrichmentVersions(
                parserVersion,
                "resolver-set-" + EnrichmentHashing.sha256(resolverComponents.toArray(String[]::new)),
                "dataset-set-" + EnrichmentHashing.sha256(datasetComponents.toArray(String[]::new)));
    }

    public EnrichmentItemResult process(EnrichmentWorkItem item) {
        List<String> outputComponents = new ArrayList<>();
        EnrichmentStageResult last = null;
        EnrichmentStageName lastStage = null;
        for (EnrichmentStage stage : stages) {
            lastStage = stage.name();
            try {
                last = stage.process(item);
            } catch (EnrichmentStageException failure) {
                throw failure.atStage(stage.name());
            } catch (org.springframework.dao.DataAccessException persistenceFailure) {
                throw EnrichmentStageException.retryable(
                        "STAGE_PERSISTENCE_FAILED", persistenceFailure).atStage(stage.name());
            } catch (RuntimeException unexpected) {
                throw EnrichmentStageException.permanent(
                        "STAGE_INTERNAL", unexpected).atStage(stage.name());
            }
            if (last == null) {
                throw EnrichmentStageException.permanent("NULL_STAGE_RESULT", null);
            }
            outputComponents.add(stage.name().name());
            outputComponents.add(last.disposition().name());
            outputComponents.add(last.evidenceSha256());
        }
        String outputHash = EnrichmentHashing.sha256(outputComponents.toArray(String[]::new));
        EnrichmentStateStatus status = switch (last.disposition()) {
            case RESOLVED -> EnrichmentStateStatus.SUCCEEDED;
            case NOT_FOUND -> EnrichmentStateStatus.TERMINAL_NOT_FOUND;
            case AMBIGUOUS -> EnrichmentStateStatus.AMBIGUOUS;
            case CONTINUE -> throw EnrichmentStageException.permanent(
                    "SELECTED_RESOLUTION_INCOMPLETE", null).atStage(lastStage);
        };
        return new EnrichmentItemResult(status, lastStage, outputHash);
    }

    private static String requireVersion(String value, EnrichmentStageName stage, String kind) {
        if (value == null || value.isBlank() || value.length() > 512) {
            throw new IllegalStateException(stage + " has no valid active " + kind + " version");
        }
        return value.trim();
    }
}
