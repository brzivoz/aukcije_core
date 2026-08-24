package rs.sud.eaukcija.client;

/**
 * A deliberately redacted source failure.
 *
 * <p>The message contains only stable classifications. Response bodies, source
 * messages, request bodies, URLs, and nested exception messages are never
 * attached, so ordinary exception logging cannot disclose them accidentally.
 */
public final class EAukcijaClientException extends RuntimeException {

    private final EAukcijaErrorCode code;
    private final String endpoint;
    private final Integer httpStatus;
    private final int attempts;
    private final Long bodyBytes;
    private final String bodySha256;

    EAukcijaClientException(
            EAukcijaErrorCode code,
            String endpoint,
            Integer httpStatus,
            int attempts,
            Long bodyBytes,
            String bodySha256) {
        super(safeMessage(code, endpoint, httpStatus, attempts));
        this.code = code;
        this.endpoint = endpoint;
        this.httpStatus = httpStatus;
        this.attempts = attempts;
        this.bodyBytes = bodyBytes;
        this.bodySha256 = bodySha256;
    }

    public EAukcijaErrorCode code() {
        return code;
    }

    public String endpoint() {
        return endpoint;
    }

    public Integer httpStatus() {
        return httpStatus;
    }

    public int attempts() {
        return attempts;
    }

    public int retries() {
        return Math.max(0, attempts - 1);
    }

    public Long bodyBytes() {
        return bodyBytes;
    }

    public String bodySha256() {
        return bodySha256;
    }

    private static String safeMessage(
            EAukcijaErrorCode code, String endpoint, Integer httpStatus, int attempts) {
        StringBuilder message = new StringBuilder("eAukcija ")
                .append(code)
                .append(" at ")
                .append(endpoint)
                .append(" after ")
                .append(attempts)
                .append(attempts == 1 ? " attempt" : " attempts");
        if (httpStatus != null) {
            message.append(" (HTTP ").append(httpStatus).append(')');
        }
        return message.toString();
    }
}
