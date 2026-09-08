package rs.sud.eaukcija.rgz;

/** Live, local-only control state; excludes filesystem paths, endpoints and header values. */
public record RgzAccessStatus(
        String state,
        boolean enabled,
        boolean killSwitchEngaged,
        boolean networkAllowed,
        boolean datasetConfigured,
        String decisionVersion,
        double requestsPerSecond,
        int maxConcurrency,
        int maxLogicalLookupsPerRun,
        int maxAttempts,
        boolean autoConfigure,
        boolean sourceContractReady,
        String datasetVersion,
        String datasetVersionPolicy,
        java.time.Instant sourceContractObservedAt,
        boolean warmupEnabled,
        String warmupState) {
}
