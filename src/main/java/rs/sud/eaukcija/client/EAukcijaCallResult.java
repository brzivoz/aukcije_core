package rs.sud.eaukcija.client;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

/**
 * A validated source value, its pre-DTO {@code Data} JSON, and exact physical
 * attempt accounting.
 *
 * <p>The JSON is deliberately carried separately from the normalized DTO so
 * immutable source snapshots never have to reconstruct source field names,
 * numeric types, nulls, or values from application entities.
 */
public record EAukcijaCallResult<T>(T data, JsonNode sourceData, int retries, int attempts) {

    public EAukcijaCallResult {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(sourceData, "sourceData");
        sourceData = sourceData.deepCopy();
        if (attempts < 1 || retries != attempts - 1) {
            throw new IllegalArgumentException("attempt accounting is inconsistent");
        }
    }

    @Override
    public JsonNode sourceData() {
        return sourceData.deepCopy();
    }

    static <T> EAukcijaCallResult<T> success(T data, JsonNode sourceData, int attempts) {
        return new EAukcijaCallResult<>(data, sourceData, attempts - 1, attempts);
    }
}
