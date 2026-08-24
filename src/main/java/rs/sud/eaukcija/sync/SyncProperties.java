package rs.sud.eaukcija.sync;

import java.time.Duration;
import java.time.ZoneId;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.scheduling.support.CronExpression;

/** Fail-fast bounds for durable eAukcija sync orchestration. */
@ConfigurationProperties(prefix = "eaukcija.sync")
public class SyncProperties {

    private boolean enabled = true;
    private Duration detailStaleAfter = Duration.ofDays(1);
    private Duration runningStaleAfter = Duration.ofMinutes(15);
    private int maxPagesPerRoot = 10_000;
    private int maxErrors = 100;
    private String scheduleCron = "-";
    private String scheduleZone = "UTC";

    @PostConstruct
    void validate() {
        requireDuration("detail-stale-after", detailStaleAfter, Duration.ofHours(1), Duration.ofDays(30));
        requireDuration("running-stale-after", runningStaleAfter, Duration.ofMinutes(5), Duration.ofHours(12));
        if (maxPagesPerRoot < 1 || maxPagesPerRoot > 100_000) {
            throw new IllegalStateException("eaukcija.sync.max-pages-per-root must be between 1 and 100000");
        }
        if (maxErrors < 1 || maxErrors > 1_000) {
            throw new IllegalStateException("eaukcija.sync.max-errors must be between 1 and 1000");
        }
        if (scheduleCron == null || scheduleCron.isBlank()) {
            throw new IllegalStateException("eaukcija.sync.schedule-cron must be '-' or a Spring cron expression");
        }
        if (!"-".equals(scheduleCron)) {
            try {
                CronExpression.parse(scheduleCron);
            } catch (RuntimeException invalidCron) {
                // Do not attach the rejected configuration value through the
                // parser's message/cause to startup logs.
                throw new IllegalStateException(
                        "eaukcija.sync.schedule-cron must be '-' or a valid Spring cron expression");
            }
        }
        try {
            ZoneId.of(scheduleZone);
        } catch (RuntimeException invalidZone) {
            throw new IllegalStateException("eaukcija.sync.schedule-zone must be a valid zone id");
        }
    }

    private static void requireDuration(String name, Duration value, Duration minimum, Duration maximum) {
        if (value == null || value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
            throw new IllegalStateException("eaukcija.sync." + name + " must be between " + minimum + " and " + maximum);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getDetailStaleAfter() {
        return detailStaleAfter;
    }

    public void setDetailStaleAfter(Duration detailStaleAfter) {
        this.detailStaleAfter = detailStaleAfter;
    }

    public Duration getRunningStaleAfter() {
        return runningStaleAfter;
    }

    public void setRunningStaleAfter(Duration runningStaleAfter) {
        this.runningStaleAfter = runningStaleAfter;
    }

    public int getMaxPagesPerRoot() {
        return maxPagesPerRoot;
    }

    public void setMaxPagesPerRoot(int maxPagesPerRoot) {
        this.maxPagesPerRoot = maxPagesPerRoot;
    }

    public int getMaxErrors() {
        return maxErrors;
    }

    public void setMaxErrors(int maxErrors) {
        this.maxErrors = maxErrors;
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
