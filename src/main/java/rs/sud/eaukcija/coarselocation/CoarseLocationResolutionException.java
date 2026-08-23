package rs.sud.eaukcija.coarselocation;

/** Fail-closed operator error raised before a coarse-resolution transaction can publish partial state. */
public final class CoarseLocationResolutionException extends RuntimeException {

    private final String code;

    CoarseLocationResolutionException(String code, String message) {
        super(message);
        this.code = code;
    }

    CoarseLocationResolutionException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
