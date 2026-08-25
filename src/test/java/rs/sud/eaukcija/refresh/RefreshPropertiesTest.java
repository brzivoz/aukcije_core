package rs.sud.eaukcija.refresh;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class RefreshPropertiesTest {

    @Test
    void defaultsToOneDailyBelgradeRunWithAnExplicitDisableSwitch() {
        RefreshProperties properties = new RefreshProperties();

        properties.validate();

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getScheduleCron()).isEqualTo("0 0 3 * * *");
        assertThat(properties.getScheduleZone()).isEqualTo("Europe/Belgrade");
        assertThat(properties.getRunningStaleAfter()).isEqualTo(Duration.ofMinutes(15));
        properties.setScheduleCron("-");
        properties.validate();
    }

    @Test
    void rejectsUnsafePollingCronAndZoneConfiguration() {
        RefreshProperties properties = new RefreshProperties();
        properties.setPollInterval(Duration.ZERO);
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class);

        properties = new RefreshProperties();
        properties.setScheduleCron("not-a-cron");
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class);

        properties = new RefreshProperties();
        properties.setScheduleZone("Not/AZone");
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class);

        properties = new RefreshProperties();
        properties.setRunningStaleAfter(Duration.ofMinutes(4));
        assertThatThrownBy(properties::validate)
                .hasMessageContaining("running-stale-after");

        properties = new RefreshProperties();
        properties.setRunningStaleAfter(Duration.ofHours(13));
        assertThatThrownBy(properties::validate)
                .hasMessageContaining("running-stale-after");
    }
}
