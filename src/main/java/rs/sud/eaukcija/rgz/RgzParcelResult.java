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
        Map<String, Object> evidence,
        int geometrySrid) {

    /** Historical results and test producers use explicitly geographic geometry. */
    public RgzParcelResult(Status status, String reason, String rawResponseSha256, String sourceFeatureId,
            String geometryType, String geometryJson, BigDecimal areaSquareMetres, String sourceProjection,
            String scale, int physicalAttempts, Map<String, Object> evidence) {
        this(status, reason, rawResponseSha256, sourceFeatureId, geometryType, geometryJson, areaSquareMetres,
                sourceProjection, scale, physicalAttempts, evidence, 4326);
    }

    public enum Status {
        RESOLVED,
        NOT_FOUND,
        AMBIGUOUS,
        INVALID,
        ERROR
    }

    public RgzParcelResult {
        evidence = evidence == null ? Map.of() : Map.copyOf(evidence);
        if (geometrySrid != 4326 && geometrySrid != 25834) {
            throw new IllegalArgumentException("unsupported RGZ geometry CRS");
        }
    }

    public boolean cacheable() {
        return switch (status) {
            case RESOLVED, NOT_FOUND, AMBIGUOUS, INVALID -> true;
            case ERROR -> false;
        };
    }
}
