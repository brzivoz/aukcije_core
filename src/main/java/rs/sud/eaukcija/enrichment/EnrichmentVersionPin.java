package rs.sud.eaukcija.enrichment;

/** Thread-bound immutable artifact selection held for one enrichment run. */
@FunctionalInterface
public interface EnrichmentVersionPin extends AutoCloseable {

    @Override
    void close();
}
