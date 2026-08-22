package rs.sud.eaukcija.komatching;

/** Fail-closed structured-KO matching error with a stable operator-facing code. */
public final class KoStructuredMatchException extends RuntimeException {

    private final String code;

    public KoStructuredMatchException(String code, String message) {
        super(message);
        this.code = code;
    }

    public KoStructuredMatchException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
