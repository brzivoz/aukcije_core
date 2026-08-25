package rs.sud.eaukcija.refresh;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import rs.sud.eaukcija.enrichment.EnrichmentVersions;

public record RefreshRunView(
        UUID workflowId,
        RefreshTriggerKind triggerKind,
        RefreshStatus status,
        RefreshStage stage,
        Instant startedAt,
        Instant heartbeatAt,
        Instant finishedAt,
        UUID sourceSyncRunId,
        UUID enrichmentRunId,
        UUID mapResolutionRunId,
        EnrichmentVersions enrichmentVersions,
        long listingsProcessed,
        long listingsTotal,
        long detailsProcessed,
        long detailsTotal,
        long locationsProcessed,
        long locationsTotal,
        long mappedCount,
        long populationCount,
        Map<String, Long> precisionCounts,
        String mapDataVersion,
        Instant mapReadyAt,
        String failureCode) {

    public RefreshRunView {
        precisionCounts = Map.copyOf(precisionCounts);
    }
}
