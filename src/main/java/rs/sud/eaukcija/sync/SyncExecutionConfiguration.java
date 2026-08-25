package rs.sud.eaukcija.sync;

import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import rs.sud.eaukcija.operations.CorrelationTaskDecorator;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(SyncProperties.class)
public class SyncExecutionConfiguration {

    @Bean(name = "syncRunExecutor")
    ThreadPoolTaskExecutor syncRunExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("eaukcija-sync-");
        executor.setTaskDecorator(new CorrelationTaskDecorator());
        // ContextClosedEvent first cancels any in-flight OkHttp call. Then the
        // managed executor interrupts rate/backoff waits and gives the worker a
        // bounded window to retain terminal run evidence and release its lock.
        executor.setStrictEarlyShutdown(true);
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setAwaitTerminationSeconds(20);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        return executor;
    }
}
