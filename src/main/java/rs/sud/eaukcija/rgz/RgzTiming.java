package rs.sud.eaukcija.rgz;

import java.time.Duration;
import java.time.Instant;

interface RgzTiming {

    long nanoTime();

    Instant instant();

    void sleep(Duration duration) throws InterruptedException;

    static RgzTiming system() {
        return new RgzTiming() {
            @Override
            public long nanoTime() {
                return System.nanoTime();
            }

            @Override
            public Instant instant() {
                return Instant.now();
            }

            @Override
            public void sleep(Duration duration) throws InterruptedException {
                long millis = duration.toMillis();
                int nanos = duration.minusMillis(millis).getNano();
                Thread.sleep(millis, nanos);
            }
        };
    }
}
