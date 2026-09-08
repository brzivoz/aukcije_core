package rs.sud.eaukcija.rgz;

import java.math.BigDecimal;
import java.util.Map;

/** Sanitized outcome of one logical RGZ parcel lookup, including all retries. */
public record RgzParcelResult(
        Status status,
        String reason,
        String rawResponseSha256,
        String sourceFeatureId,
        String geometryType,
        String geometryJson,
        BigDecimal areaSquareMetres,
        String sourceProjection,
        String scale,
        int physicalAttempts,
        Map<String, Object> evidence) {

    public enum Status {
        RESOLVED,
        NOT_FOUND,
        AMBIGUOUS,
        INVALID,
        ERROR
    }

    public RgzParcelResult {
        evidence = evidence == null ? Map.of() : Map.copyOf(evidence);
    }

    public boolean cacheable() {
        return switch (status) {
            case RESOLVED, NOT_FOUND, AMBIGUOUS, INVALID -> true;
            case ERROR -> false;
        };
    }
}
