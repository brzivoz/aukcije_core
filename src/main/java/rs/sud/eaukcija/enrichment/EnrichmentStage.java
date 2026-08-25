package rs.sud.eaukcija.enrichment;

public interface EnrichmentStage {

    EnrichmentStageName name();

    /** Code/configuration version that changes this stage's deterministic output. */
    String implementationVersion();

    /** Active local dataset identity, or a stable NONE marker for dataset-free stages. */
    String activeDatasetVersion();

    EnrichmentStageResult process(EnrichmentWorkItem item);
}
