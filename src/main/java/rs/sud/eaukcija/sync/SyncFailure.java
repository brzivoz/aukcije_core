package rs.sud.eaukcija.sync;

import rs.sud.eaukcija.client.EAukcijaClientException;
import rs.sud.eaukcija.sync.persistence.SyncRunErrorEvidence;
import rs.sud.eaukcija.sync.persistence.SyncRunStage;

/** Payload-free contextual failure retained by a terminal sync run. */
public final class SyncFailure extends RuntimeException {

    private final SyncRunStage stage;
    private final Integer rootCategoryId;
    private final Integer childCategoryId;
    private final Integer pageNumber;
    private final Long auctionId;
    private final Integer httpStatus;
    private final String code;
    private final boolean retryable;
    private final int attempts;
    private final int retries;

    private SyncFailure(
            SyncRunStage stage,
            Integer rootCategoryId,
            Integer childCategoryId,
            Integer pageNumber,
            Long auctionId,
            Integer httpStatus,
            String code,
            boolean retryable,
            int attempts,
            int retries) {
        super("sync failed with " + code + " during " + stage);
        this.stage = stage;
        this.rootCategoryId = rootCategoryId;
        this.childCategoryId = childCategoryId;
        this.pageNumber = pageNumber;
        this.auctionId = auctionId;
        this.httpStatus = httpStatus;
        this.code = code;
        this.retryable = retryable;
        this.attempts = attempts;
        this.retries = retries;
    }

    public static SyncFailure client(
            SyncRunStage stage,
            Integer rootCategoryId,
            Integer pageNumber,
            Long auctionId,
            EAukcijaClientException failure) {
        return new SyncFailure(
                stage,
                rootCategoryId,
                null,
                pageNumber,
                auctionId,
                failure.httpStatus(),
                failure.code().name(),
                retryable(failure),
                Math.max(1, failure.attempts()),
                failure.retries());
    }

    public static SyncFailure childClient(
            SyncRunStage stage,
            Integer rootCategoryId,
            Integer childCategoryId,
            Integer pageNumber,
            Long auctionId,
            EAukcijaClientException failure) {
        return new SyncFailure(
                stage,
                rootCategoryId,
                childCategoryId,
                pageNumber,
                auctionId,
                failure.httpStatus(),
                failure.code().name(),
                retryable(failure),
                Math.max(1, failure.attempts()),
                failure.retries());
    }

    public static SyncFailure contract(
            SyncRunStage stage,
            String code,
            Integer rootCategoryId,
            Integer pageNumber,
            Long auctionId) {
        return new SyncFailure(stage, rootCategoryId, null, pageNumber, auctionId,
                null, code, false, 1, 0);
    }

    public static SyncFailure childContract(
            SyncRunStage stage,
            String code,
            Integer rootCategoryId,
            Integer childCategoryId,
            Integer pageNumber,
            Long auctionId) {
        return new SyncFailure(stage, rootCategoryId, childCategoryId, pageNumber, auctionId,
                null, code, false, 1, 0);
    }

    public SyncRunErrorEvidence evidence() {
        return new SyncRunErrorEvidence(
                stage,
                rootCategoryId,
                childCategoryId,
                pageNumber,
                auctionId,
                httpStatus,
                code,
                retryable,
                attempts);
    }

    public int retries() {
        return retries;
    }

    public SyncRunStage stage() {
        return stage;
    }

    private static boolean retryable(EAukcijaClientException failure) {
        return switch (failure.code()) {
            case TIMEOUT, IO, RATE_LIMITED -> true;
            case HTTP_STATUS -> failure.httpStatus() != null
                    && (failure.httpStatus() == 408
                    || failure.httpStatus() == 429
                    || failure.httpStatus() == 500
                    || failure.httpStatus() == 502
                    || failure.httpStatus() == 503
                    || failure.httpStatus() == 504);
            default -> false;
        };
    }
}
