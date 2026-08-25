package rs.sud.eaukcija.refresh;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class RefreshExecutionConfigurationTest {

    @Test
    void acceptsOneHandoffAfterThePreviousWorkflowCommitsTerminalState() throws Exception {
        ThreadPoolTaskExecutor executor =
                new RefreshExecutionConfiguration().refreshCoordinatorExecutor();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondCompleted = new CountDownLatch(1);
        executor.initialize();
        try {
            executor.execute(() -> {
                firstStarted.countDown();
                try {
                    releaseFirst.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            assertThat(firstStarted.await(2, TimeUnit.SECONDS)).isTrue();

            executor.execute(secondCompleted::countDown);

            assertThat(executor.getThreadPoolExecutor().getQueue()).hasSize(1);
            releaseFirst.countDown();
            assertThat(secondCompleted.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            releaseFirst.countDown();
            executor.shutdown();
        }
    }
}
