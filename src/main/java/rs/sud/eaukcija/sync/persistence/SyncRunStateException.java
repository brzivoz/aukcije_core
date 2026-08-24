package rs.sud.eaukcija.sync.persistence;

public final class SyncRunStateException extends RuntimeException {
    public SyncRunStateException(String message) {
        super(message);
    }

    public SyncRunStateException(String message, Throwable cause) {
        super(message, cause);
    }
}
