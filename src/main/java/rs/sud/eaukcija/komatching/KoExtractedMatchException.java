package rs.sud.eaukcija.komatching;

/** Fixed-code failure for extracted-KO artifact or reconciliation invariants. */
public final class KoExtractedMatchException extends RuntimeException {

    private final String code;

    KoExtractedMatchException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
