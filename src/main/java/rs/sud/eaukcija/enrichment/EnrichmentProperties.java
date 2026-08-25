package rs.sud.eaukcija.enrichment;

import java.time.ZoneId;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.scheduling.support.CronExpression;

/** Bounded, queue-free coordinator configuration. */
@ConfigurationProperties(prefix = "eaukcija.enrichment")
public class EnrichmentProperties {

    private boolean enabled = true;
    private int maxAttempts = 3;
    private int maxItemsPerRun = 1_000;
    private int maxReplayItems = 1_000;
    private String scheduleCron = "-";
    private String scheduleZone = "UTC";

    @PostConstruct
    void validate() {
        if (maxAttempts < 1 || maxAttempts > 20) {
            throw new IllegalStateException("eaukcija.enrichment.max-attempts must be between 1 and 20");
        }
        if (maxItemsPerRun < 1 || maxItemsPerRun > 1_000) {
            throw new IllegalStateException(
                    "eaukcija.enrichment.max-items-per-run must be between 1 and 1000");
        }
        if (maxReplayItems < 1 || maxReplayItems > 1_000) {
            throw new IllegalStateException(
                    "eaukcija.enrichment.max-replay-items must be between 1 and 1000");
        }
        if (scheduleCron == null || scheduleCron.isBlank()) {
            throw new IllegalStateException(
                    "eaukcija.enrichment.schedule-cron must be '-' or a Spring cron expression");
        }
        if (!"-".equals(scheduleCron)) {
            try {
                CronExpression.parse(scheduleCron);
            } catch (RuntimeException invalid) {
                throw new IllegalStateException(
                        "eaukcija.enrichment.schedule-cron must be '-' or a valid Spring cron expression");
            }
        }
        try {
            ZoneId.of(scheduleZone);
        } catch (RuntimeException invalid) {
            throw new IllegalStateException(
                    "eaukcija.enrichment.schedule-zone must be a valid zone id");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public int getMaxItemsPerRun() {
        return maxItemsPerRun;
    }

    public void setMaxItemsPerRun(int maxItemsPerRun) {
        this.maxItemsPerRun = maxItemsPerRun;
    }

    public int getMaxReplayItems() {
        return maxReplayItems;
    }

    public void setMaxReplayItems(int maxReplayItems) {
        this.maxReplayItems = maxReplayItems;
    }

    public String getScheduleCron() {
        return scheduleCron;
    }

    public void setScheduleCron(String scheduleCron) {
        this.scheduleCron = scheduleCron;
    }

    public String getScheduleZone() {
        return scheduleZone;
    }

    public void setScheduleZone(String scheduleZone) {
        this.scheduleZone = scheduleZone;
    }
}
