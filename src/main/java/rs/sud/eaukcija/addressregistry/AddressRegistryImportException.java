package rs.sud.eaukcija.addressregistry;

/** A fail-closed import error with a stable operator-facing code. */
public final class AddressRegistryImportException extends RuntimeException {

    private final String code;

    public AddressRegistryImportException(String code, String message) {
        super(message);
        this.code = code;
    }

    public AddressRegistryImportException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
