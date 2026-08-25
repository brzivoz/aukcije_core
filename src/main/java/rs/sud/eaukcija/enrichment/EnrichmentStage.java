package rs.sud.eaukcija.enrichment;

public interface EnrichmentStage {

    EnrichmentStageName name();

    /** Code/configuration version that changes this stage's deterministic output. */
    String implementationVersion();

    /** Active local dataset identity, or a stable NONE marker for dataset-free stages. */
    String activeDatasetVersion();

    /** Pins any mutable ACTIVE pointer to one immutable snapshot for this worker thread. */
    default EnrichmentVersionPin pinActiveVersion() {
        return () -> { };
    }

    EnrichmentStageResult process(EnrichmentWorkItem item);
}
