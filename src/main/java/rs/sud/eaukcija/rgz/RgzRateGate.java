package rs.sud.eaukcija.rgz;

import java.time.Duration;
import java.util.concurrent.Semaphore;

/** Shared physical-request rate and concurrency gate for the RGZ client. */
final class RgzRateGate {

    private final Semaphore concurrency;
    private final long minimumIntervalNanos;
    private final RgzTiming timing;
    private final Object lock = new Object();
    private long nextStartNanos;

    RgzRateGate(double requestsPerSecond, int maxConcurrency, RgzTiming timing) {
        concurrency = new Semaphore(maxConcurrency, true);
        minimumIntervalNanos = Math.max(1L, (long) Math.ceil(1_000_000_000d / requestsPerSecond));
        this.timing = timing;
        nextStartNanos = timing.nanoTime();
    }

    Permit acquire() throws InterruptedException {
        concurrency.acquire();
        boolean accepted = false;
        try {
            awaitRateSlot();
            accepted = true;
            return concurrency::release;
        } finally {
            if (!accepted) {
                concurrency.release();
            }
        }
    }

    private void awaitRateSlot() throws InterruptedException {
        while (true) {
            Duration wait;
            synchronized (lock) {
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
        return right > 0 && left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    @FunctionalInterface
    interface Permit extends AutoCloseable {
        @Override
        void close();
    }
}
