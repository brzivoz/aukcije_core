package rs.sud.eaukcija.refresh;

import java.time.Duration;
import java.time.ZoneId;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.scheduling.support.CronExpression;

@ConfigurationProperties(prefix = "eaukcija.refresh")
public class RefreshProperties {

    private boolean enabled = true;
    private String scheduleCron = "0 0 3 * * *";
    private String scheduleZone = "Europe/Belgrade";
    private Duration pollInterval = Duration.ofSeconds(1);
    private Duration runningStaleAfter = Duration.ofMinutes(15);

    @PostConstruct
    void validate() {
        if (scheduleCron == null || scheduleCron.isBlank()) {
            throw new IllegalStateException(
                    "eaukcija.refresh.schedule-cron must be '-' or a Spring cron expression");
        }
        if (!"-".equals(scheduleCron)) {
            try {
                CronExpression.parse(scheduleCron);
            } catch (RuntimeException invalid) {
                throw new IllegalStateException(
                        "eaukcija.refresh.schedule-cron must be '-' or a valid Spring cron expression");
            }
        }
        try {
            ZoneId.of(scheduleZone);
        } catch (RuntimeException invalid) {
            throw new IllegalStateException(
                    "eaukcija.refresh.schedule-zone must be a valid zone id");
        }
        if (pollInterval == null || pollInterval.isNegative() || pollInterval.isZero()
                || pollInterval.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalStateException(
                    "eaukcija.refresh.poll-interval must be greater than PT0S and at most PT30S");
        }
        if (runningStaleAfter == null
                || runningStaleAfter.compareTo(Duration.ofMinutes(5)) < 0
                || runningStaleAfter.compareTo(Duration.ofHours(12)) > 0) {
            throw new IllegalStateException(
                    "eaukcija.refresh.running-stale-after must be between PT5M and PT12H");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
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

    public Duration getPollInterval() {
        return pollInterval;
    }

    public void setPollInterval(Duration pollInterval) {
        this.pollInterval = pollInterval;
    }

    public Duration getRunningStaleAfter() {
        return runningStaleAfter;
    }

    public void setRunningStaleAfter(Duration runningStaleAfter) {
        this.runningStaleAfter = runningStaleAfter;
    }
}
