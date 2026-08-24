package rs.sud.eaukcija.client;

import java.util.Objects;

/** A validated source value plus exact physical-attempt accounting. */
public record EAukcijaCallResult<T>(T data, int retries, int attempts) {

    public EAukcijaCallResult {
        Objects.requireNonNull(data, "data");
        if (attempts < 1 || retries != attempts - 1) {
            throw new IllegalArgumentException("attempt accounting is inconsistent");
        }
    }

    static <T> EAukcijaCallResult<T> success(T data, int attempts) {
        return new EAukcijaCallResult<>(data, attempts - 1, attempts);
    }
}
