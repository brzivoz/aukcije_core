package rs.sud.eaukcija.enrichment;

public final class EnrichmentStageException extends RuntimeException {

    private final boolean retryable;
    private final String safeCode;
    private final EnrichmentStageName stage;

    private EnrichmentStageException(
            boolean retryable,
            String safeCode,
            EnrichmentStageName stage,
            Throwable cause) {
        // Do not copy source/database exception messages into the exception text.
        super(safeCode, cause);
        if (safeCode == null || !safeCode.matches("[A-Z0-9_]{1,64}")) {
            throw new IllegalArgumentException("safeCode must be an uppercase diagnostic code");
        }
        this.retryable = retryable;
        this.safeCode = safeCode;
        this.stage = stage;
    }

    public static EnrichmentStageException retryable(String safeCode, Throwable cause) {
        return new EnrichmentStageException(true, safeCode, null, cause);
    }

    public static EnrichmentStageException permanent(String safeCode, Throwable cause) {
        return new EnrichmentStageException(false, safeCode, null, cause);
    }

    public boolean retryable() {
        return retryable;
    }

    public String safeCode() {
        return safeCode;
    }

    public EnrichmentStageName stage() {
        return stage;
    }

    EnrichmentStageException atStage(EnrichmentStageName value) {
        if (stage != null) {
            return this;
        }
        return new EnrichmentStageException(retryable, safeCode, value, this);
    }
}
