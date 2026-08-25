package rs.sud.eaukcija.refresh;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record RefreshWorkflowState(
        boolean enabled,
        UUID workflowId,
        String trigger,
        String status,
        String stage,
        Instant startedAt,
        Instant finishedAt,
        long elapsedSeconds,
        long listingsProcessed,
        long listingsTotal,
        long detailsProcessed,
        long detailsTotal,
        long locationsProcessed,
        long locationsTotal,
        long mappedCount,
        long populationCount,
        Map<String, Long> precisionSummary,
        UUID sourceSyncRunId,
        UUID enrichmentRunId,
        UUID mapResolutionRunId,
        String mapDataVersion,
        Instant mapReadyAt,
        String failureCode,
        String failureMessage,
        UUID lastSuccessfulWorkflowId,
        Instant lastSuccessfulCompleteRefresh,
        boolean scheduleEnabled,
        String scheduleZone,
        Instant nextScheduledRun) {

    public RefreshWorkflowState {
        precisionSummary = Map.copyOf(precisionSummary);
    }
}
