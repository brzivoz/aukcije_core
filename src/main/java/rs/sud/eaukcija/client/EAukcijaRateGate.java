package rs.sud.eaukcija.client;

import java.time.Duration;
import java.util.concurrent.Semaphore;

/** Shared physical-request rate, concurrency, and Retry-After gate. */
final class EAukcijaRateGate {

    private final Semaphore concurrentRequests;
    private final long minimumIntervalNanos;
    private final EAukcijaTiming timing;
    private final Object rateLock = new Object();

    private long nextStartNanos;
    private long pauseStartedNanos;
    private Duration pauseDuration = Duration.ZERO;

    EAukcijaRateGate(double requestsPerSecond, int maxConcurrency, EAukcijaTiming timing) {
        this.concurrentRequests = new Semaphore(maxConcurrency, true);
        this.minimumIntervalNanos = Math.max(1L, (long) Math.ceil(1_000_000_000d / requestsPerSecond));
        this.timing = timing;
        this.nextStartNanos = timing.nanoTime();
        this.pauseStartedNanos = this.nextStartNanos;
    }

    Permit acquire(Duration maximumPauseWait)
            throws InterruptedException, PauseBeyondBudgetException {
        // Reject a source cool-down that this caller cannot afford before it
        // queues for a physical-call permit. This keeps orchestration locks out
        // of an unbounded sleep while leaving the shared pause intact.
        rejectPauseBeyondBudget(maximumPauseWait);
        concurrentRequests.acquire();
        boolean accepted = false;
        try {
            awaitStartSlot(maximumPauseWait);
            accepted = true;
            return concurrentRequests::release;
        } finally {
            if (!accepted) {
                concurrentRequests.release();
            }
        }
    }

    void pause(Duration delay) {
        if (delay == null || delay.isNegative() || delay.isZero()) {
            return;
        }
        synchronized (rateLock) {
            long now = timing.nanoTime();
            Duration remainingPause = remainingPause(now);
            pauseStartedNanos = now;
            pauseDuration = remainingPause.compareTo(delay) >= 0 ? remainingPause : delay;
        }
    }

    private void awaitStartSlot(Duration maximumPauseWait)
            throws InterruptedException, PauseBeyondBudgetException {
        while (true) {
            Duration wait;
            synchronized (rateLock) {
                long now = timing.nanoTime();
                Duration remainingPause = remainingPause(now);
                if (remainingPause.compareTo(maximumPauseWait) > 0) {
                    throw new PauseBeyondBudgetException();
                }
                Duration remainingRate = Duration.ofNanos(remainingRateNanos(now));
                wait = remainingPause.compareTo(remainingRate) >= 0
                        ? remainingPause
                        : remainingRate;
                if (wait.isZero()) {
                    nextStartNanos = saturatingAdd(now, minimumIntervalNanos);
                    return;
                }
            }
            timing.sleep(wait);
        }
    }

    private void rejectPauseBeyondBudget(Duration maximumPauseWait)
            throws PauseBeyondBudgetException {
        synchronized (rateLock) {
            if (remainingPause(timing.nanoTime()).compareTo(maximumPauseWait) > 0) {
                throw new PauseBeyondBudgetException();
            }
        }
    }

    private Duration remainingPause(long now) {
        if (pauseDuration.isZero()) {
            return Duration.ZERO;
        }
        long elapsed = now - pauseStartedNanos;
        if (elapsed <= 0) {
            return pauseDuration;
        }
        Duration elapsedDuration = Duration.ofNanos(elapsed);
        return elapsedDuration.compareTo(pauseDuration) >= 0
                ? Duration.ZERO
                : pauseDuration.minus(elapsedDuration);
    }

    private long remainingRateNanos(long now) {
        long remaining = nextStartNanos - now;
        return remaining <= 0 ? 0 : remaining;
    }

    private static long saturatingAdd(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    @FunctionalInterface
    interface Permit extends AutoCloseable {
        @Override
        void close();
    }

    static final class PauseBeyondBudgetException extends Exception {
        private static final long serialVersionUID = 1L;
    }
}
