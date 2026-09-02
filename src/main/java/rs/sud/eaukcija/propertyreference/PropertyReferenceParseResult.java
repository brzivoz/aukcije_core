package rs.sud.eaukcija.propertyreference;

import java.util.List;
import java.util.Objects;

/** Deterministic parser output before persistence adds run lineage. */
public record PropertyReferenceParseResult(
        String parserVersion,
        List<ParsedPropertyReference> references,
        String outputSha256,
        int textReferenceCount,
        int noStructuredReferenceCount,
        int koConflictCount) {

    public PropertyReferenceParseResult {
        if (parserVersion == null || parserVersion.isBlank()) {
            throw new IllegalArgumentException("parserVersion is required");
        }
        references = List.copyOf(Objects.requireNonNull(references, "references"));
        if (outputSha256 == null || !outputSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("outputSha256 must be lowercase SHA-256");
        }
        if (textReferenceCount < 0 || noStructuredReferenceCount < 0 || koConflictCount < 0) {
            throw new IllegalArgumentException("parser counts must be non-negative");
        }
    }
}
