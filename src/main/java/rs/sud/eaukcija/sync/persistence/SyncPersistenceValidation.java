package rs.sud.eaukcija.sync.persistence;

import java.util.Objects;
import java.util.regex.Pattern;

final class SyncPersistenceValidation {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern ERROR_CODE = Pattern.compile("[A-Z0-9_]{1,64}");

    private SyncPersistenceValidation() {
    }

    static String sha256(String value, String field) {
        if (value == null || !SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be a lowercase SHA-256 value");
        }
        return value;
    }

    static String nonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    static String errorCode(String value) {
        if (value == null || !ERROR_CODE.matcher(value).matches()) {
            throw new IllegalArgumentException("errorCode must contain only A-Z, 0-9, and underscore");
        }
        return value;
    }

    static long nonNegative(long value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
        return value;
    }

    static <T> T required(T value, String field) {
        return Objects.requireNonNull(value, field + " must not be null");
    }
}
