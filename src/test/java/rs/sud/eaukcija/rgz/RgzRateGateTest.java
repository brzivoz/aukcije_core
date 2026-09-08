package rs.sud.eaukcija.rgz;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class RgzRateGateTest {

    @Test
    void spacesPhysicalRequestsAtTheConfiguredRate() throws Exception {
        FakeTiming timing = new FakeTiming();
        RgzRateGate gate = new RgzRateGate(0.2, 1, timing);

        try (RgzRateGate.Permit ignored = gate.acquire()) {
            // first request starts immediately
        }
        try (RgzRateGate.Permit ignored = gate.acquire()) {
            // second request advances fake time by five seconds
        }

        assertThat(timing.sleeps).containsExactly(Duration.ofSeconds(5));
    }

    @Test
    void maxConcurrencyOneBlocksASecondPhysicalRequest() throws Exception {
        RgzRateGate gate = new RgzRateGate(5.0, 1, RgzTiming.system());
        CountDownLatch attempted = new CountDownLatch(1);
        CountDownLatch acquired = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        RgzRateGate.Permit first = gate.acquire();
        try {
            var second = executor.submit(() -> {
                attempted.countDown();
                try (RgzRateGate.Permit ignored = gate.acquire()) {
                    acquired.countDown();
                }
                return null;
            });
            assertThat(attempted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(acquired.await(100, TimeUnit.MILLISECONDS)).isFalse();
            first.close();
            assertThat(acquired.await(1, TimeUnit.SECONDS)).isTrue();
            second.get(1, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    private static final class FakeTiming implements RgzTiming {
        private long now;
        private final List<Duration> sleeps = new ArrayList<>();

        @Override
        public long nanoTime() {
            return now;
        }

        @Override
        public Instant instant() {
            return Instant.EPOCH.plusNanos(now);
        }

        @Override
        public void sleep(Duration duration) {
            sleeps.add(duration);
            now += duration.toNanos();
        }
    }
}
