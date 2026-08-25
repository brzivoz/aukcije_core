package rs.sud.eaukcija.enrichment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class EnrichmentPropertiesTest {

    @Test
    void exposesSafeQueueFreeDefaults() {
        EnrichmentProperties properties = new EnrichmentProperties();

        properties.validate();

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getMaxAttempts()).isEqualTo(3);
        assertThat(properties.getMaxInterruptions()).isEqualTo(3);
        assertThat(properties.getRunningStaleAfter()).isEqualTo(Duration.ofMinutes(15));
        assertThat(properties.getMaxItemsPerRun()).isEqualTo(1_000);
        assertThat(properties.getMaxReplayItems()).isEqualTo(1_000);
        assertThat(properties.getScheduleCron()).isEqualTo("-");
        assertThat(properties.getScheduleZone()).isEqualTo("Europe/Belgrade");
    }

    @Test
    void rejectsUnboundedOrSecretBearingOverridesWithoutEchoingThem() {
        EnrichmentProperties properties = new EnrichmentProperties();
        properties.setMaxAttempts(0);
        assertThatThrownBy(properties::validate).hasMessageContaining("max-attempts");

        properties = new EnrichmentProperties();
        properties.setMaxInterruptions(21);
        assertThatThrownBy(properties::validate).hasMessageContaining("max-interruptions");

        properties = new EnrichmentProperties();
        properties.setRunningStaleAfter(Duration.ofMinutes(4));
        assertThatThrownBy(properties::validate).hasMessageContaining("running-stale-after");

        properties = new EnrichmentProperties();
        properties.setMaxItemsPerRun(1_001);
        assertThatThrownBy(properties::validate).hasMessageContaining("max-items-per-run");

        properties = new EnrichmentProperties();
        properties.setMaxReplayItems(0);
        assertThatThrownBy(properties::validate).hasMessageContaining("max-replay-items");

        properties = new EnrichmentProperties();
        properties.setScheduleCron("password=not-a-cron");
        assertThatThrownBy(properties::validate)
                .hasMessageContaining("schedule-cron")
                .hasMessageNotContaining("password")
                .satisfies(failure -> assertThat(failure.getCause()).isNull());

        properties = new EnrichmentProperties();
        properties.setScheduleZone("password/not-a-zone");
        assertThatThrownBy(properties::validate)
                .hasMessageContaining("schedule-zone")
                .hasMessageNotContaining("password")
                .satisfies(failure -> assertThat(failure.getCause()).isNull());
    }
}
