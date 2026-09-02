package rs.sud.eaukcija.propertyreference;

import java.util.Objects;

/** One normalized reference with exact source evidence and UTF-16 source offsets. */
public record ParsedPropertyReference(
        int referenceOrder,
        PropertyReferenceType type,
        String rawKo,
        String normalizedKo,
        String koCode,
        String rawParcelNumber,
        String canonicalParcelNumber,
        String landRegisterNumber,
        String addressMunicipality,
        String addressSettlement,
        String addressStreet,
        String addressHouseNumber,
        String sourceField,
        Integer sourceOffsetStart,
        Integer sourceOffsetEnd,
        String rawEvidence,
        PropertyReferenceExtractionStatus status,
        String canonicalKey,
        boolean koConflict) {

    public ParsedPropertyReference {
        if (referenceOrder < 0) {
            throw new IllegalArgumentException("referenceOrder must be non-negative");
        }
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(status, "status");
        if (sourceField == null || sourceField.isBlank()) {
            throw new IllegalArgumentException("sourceField is required");
        }
        if ((sourceOffsetStart == null) != (sourceOffsetEnd == null)
                || (sourceOffsetStart != null
                && (sourceOffsetStart < 0 || sourceOffsetEnd < sourceOffsetStart))) {
            throw new IllegalArgumentException("source offsets must be a valid pair");
        }
        if (canonicalKey == null || canonicalKey.isBlank()) {
            throw new IllegalArgumentException("canonicalKey is required");
        }
    }
}
