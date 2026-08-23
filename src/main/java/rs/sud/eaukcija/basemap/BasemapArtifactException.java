package rs.sud.eaukcija.basemap;

/** Raised when an immutable basemap bundle or its active pointer is unsafe. */
public final class BasemapArtifactException extends RuntimeException {

    public BasemapArtifactException(String message) {
        super(message);
    }

    public BasemapArtifactException(String message, Throwable cause) {
        super(message, cause);
    }
}
