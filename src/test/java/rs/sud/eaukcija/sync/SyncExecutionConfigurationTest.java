package rs.sud.eaukcija.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class SyncExecutionConfigurationTest {

    @Test
    void createsOneNamedBoundedWorkerWithNoBacklogQueueAndInterruptsItOnShutdown() throws Exception {
        ThreadPoolTaskExecutor executor = new SyncExecutionConfiguration().syncRunExecutor();
        executor.initialize();
        try {
            assertThat(executor.getCorePoolSize()).isOne();
            assertThat(executor.getMaxPoolSize()).isOne();
            assertThat(executor.getThreadNamePrefix()).isEqualTo("eaukcija-sync-");
            assertThat(executor.getThreadPoolExecutor().getQueue())
                    .isInstanceOf(SynchronousQueue.class);
            assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
                    .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);

            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch interrupted = new CountDownLatch(1);
            AtomicReference<String> threadName = new AtomicReference<>();
            executor.execute(() -> {
                threadName.set(Thread.currentThread().getName());
                started.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException expected) {
                    Thread.currentThread().interrupt();
                    interrupted.countDown();
                }
            });
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();

            executor.shutdown();

            assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(threadName.get()).startsWith("eaukcija-sync-");
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void springShutdownPhaseAdvancesToWorkerInterruptionWithinTheConfiguredBound() throws Exception {
        ConfigurableApplicationContext context = new SpringApplicationBuilder(ShutdownTestConfiguration.class)
                .web(WebApplicationType.NONE)
                .profiles("local-h2")
                .properties(
                        "spring.main.banner-mode=off")
                .run("--spring.lifecycle.timeout-per-shutdown-phase=PT0.2S");
        ThreadPoolTaskExecutor executor = context.getBean("syncRunExecutor", ThreadPoolTaskExecutor.class);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        executor.execute(() -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException expected) {
                Thread.currentThread().interrupt();
                interrupted.countDown();
            }
        });
        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();

        long closingStarted = System.nanoTime();
        context.close();
        long closingMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - closingStarted);

        assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(closingMillis).isLessThan(3_000L);
    }

    @Test
    void propagatesBoundedCorrelationContextIntoTheManagedWorker() throws Exception {
        ThreadPoolTaskExecutor executor = new SyncExecutionConfiguration().syncRunExecutor();
        executor.initialize();
        try {
            CountDownLatch completed = new CountDownLatch(1);
            AtomicReference<String> observed = new AtomicReference<>();
            MDC.put("correlationId", "sync-request-30");
            executor.execute(() -> {
                observed.set(MDC.get("correlationId"));
                completed.countDown();
            });
            MDC.remove("correlationId");

            assertThat(completed.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(observed.get()).isEqualTo("sync-request-30");
        } finally {
            MDC.remove("correlationId");
            executor.shutdown();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import(SyncExecutionConfiguration.class)
    static class ShutdownTestConfiguration {
    }
}
