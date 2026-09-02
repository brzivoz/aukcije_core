package rs.sud.eaukcija.enrichment;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import rs.sud.eaukcija.propertyreference.PropertyReferenceExtractionRepository;
import rs.sud.eaukcija.propertyreference.PropertyReferenceParser;

/** Issue-19 parser stage over the immutable v2 enrichment input. */
@Component
public final class PropertyReferenceExtractionStage implements EnrichmentStage {

    private final PropertyReferenceParser parser;
    private final PropertyReferenceExtractionRepository references;

    public PropertyReferenceExtractionStage(
            PropertyReferenceParser parser,
            PropertyReferenceExtractionRepository references) {
        this.parser = parser;
        this.references = references;
    }

    @Override
    public EnrichmentStageName name() {
        return EnrichmentStageName.PARSE;
    }

    @Override
    public String implementationVersion() {
        return PropertyReferenceParser.VERSION;
    }

    @Override
    public String activeDatasetVersion() {
        return "property-reference-corpus-2026-09-02.2";
    }

    @Override
    public EnrichmentStageResult process(EnrichmentWorkItem item) {
        try {
            var parsed = parser.parse(item.canonicalInput());
            var persisted = references.replace(item, parsed);
            return EnrichmentStageResult.continuing(persisted.resultSha256());
        } catch (PropertyReferenceParser.PropertyReferenceParseException invalid) {
            throw EnrichmentStageException.permanent(invalid.safeCode(), invalid);
        } catch (DataAccessException persistenceFailure) {
            throw EnrichmentStageException.retryable(
                    "PARSE_PERSISTENCE_FAILED", persistenceFailure);
        }
    }
}
