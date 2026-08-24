package rs.sud.eaukcija.client;

import java.time.Duration;
import java.time.Instant;

interface EAukcijaTiming {

    long nanoTime();

    Instant now();

    void sleep(Duration duration) throws InterruptedException;

    static EAukcijaTiming system() {
        return SystemTiming.INSTANCE;
    }

    enum SystemTiming implements EAukcijaTiming {
        INSTANCE;

        @Override
        public long nanoTime() {
            return System.nanoTime();
        }

        @Override
        public Instant now() {
            return Instant.now();
        }

        @Override
        public void sleep(Duration duration) throws InterruptedException {
            long millis = duration.toMillis();
            int nanos = duration.minusMillis(millis).getNano();
            Thread.sleep(millis, nanos);
        }
    }
}
