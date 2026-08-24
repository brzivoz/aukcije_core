package rs.sud.eaukcija.client;

/** Stable, payload-free failure classes safe for logs and retained sync evidence. */
public enum EAukcijaErrorCode {
    TIMEOUT,
    IO,
    INTERRUPTED,
    HTTP_STATUS,
    RATE_LIMITED,
    BODY_TOO_LARGE,
    INVALID_CONTENT_TYPE,
    INVALID_JSON,
    INVALID_ENVELOPE,
    APPLICATION_ERROR,
    INVALID_DATA,
    INTERNAL
}
