package rs.sud.eaukcija.refresh;

import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import rs.sud.eaukcija.operations.CorrelationTaskDecorator;

@Configuration
@EnableConfigurationProperties(RefreshProperties.class)
public class RefreshExecutionConfiguration {

    @Bean(name = "refreshCoordinatorExecutor")
    ThreadPoolTaskExecutor refreshCoordinatorExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        // One handoff slot closes the terminal-commit/worker-return race. The
        // database partial unique index remains the one-at-a-time authority.
        executor.setQueueCapacity(1);
        executor.setThreadNamePrefix("eaukcija-refresh-");
        executor.setTaskDecorator(new CorrelationTaskDecorator());
        executor.setStrictEarlyShutdown(true);
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setAwaitTerminationSeconds(20);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        return executor;
    }
}
