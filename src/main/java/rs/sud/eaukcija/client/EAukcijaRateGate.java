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

    EAukcijaRateGate(double requestsPerSecond, int maxConcurrency, EAukcijaTiming timing) {
        this.concurrentRequests = new Semaphore(maxConcurrency, true);
        this.minimumIntervalNanos = Math.max(1L, (long) Math.ceil(1_000_000_000d / requestsPerSecond));
        this.timing = timing;
        this.nextStartNanos = timing.nanoTime();
    }

    Permit acquire() throws InterruptedException {
        concurrentRequests.acquire();
        boolean accepted = false;
        try {
            awaitStartSlot();
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
            long pausedUntil = saturatingAdd(timing.nanoTime(), delay.toNanos());
            nextStartNanos = Math.max(nextStartNanos, pausedUntil);
        }
    }

    private void awaitStartSlot() throws InterruptedException {
        while (true) {
            Duration wait;
            synchronized (rateLock) {
                long now = timing.nanoTime();
                long remaining = nextStartNanos - now;
                if (remaining <= 0) {
                    nextStartNanos = saturatingAdd(now, minimumIntervalNanos);
                    return;
                }
                wait = Duration.ofNanos(remaining);
            }
            timing.sleep(wait);
        }
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
}
