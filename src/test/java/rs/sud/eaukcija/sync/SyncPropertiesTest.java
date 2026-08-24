package rs.sud.eaukcija.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class SyncPropertiesTest {

    @Test
    void exposesAndAcceptsTheDocumentedDefaults() {
        SyncProperties properties = new SyncProperties();

        properties.validate();

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getDetailStaleAfter()).isEqualTo(Duration.ofDays(1));
        assertThat(properties.getRunningStaleAfter()).isEqualTo(Duration.ofMinutes(15));
        assertThat(properties.getMaxPagesPerRoot()).isEqualTo(10_000);
        assertThat(properties.getMaxErrors()).isEqualTo(100);
        assertThat(properties.getScheduleCron()).isEqualTo("-");
        assertThat(properties.getScheduleZone()).isEqualTo("UTC");
    }

    @Test
    void rejectsUnsafeOrUnboundedOverrides() {
        SyncProperties properties = new SyncProperties();
        properties.setDetailStaleAfter(Duration.ofMinutes(59));
        assertThatThrownBy(properties::validate).hasMessageContaining("detail-stale-after");

        properties = new SyncProperties();
        properties.setRunningStaleAfter(Duration.ofHours(13));
        assertThatThrownBy(properties::validate).hasMessageContaining("running-stale-after");

        properties = new SyncProperties();
        properties.setMaxPagesPerRoot(100_001);
        assertThatThrownBy(properties::validate).hasMessageContaining("max-pages-per-root");

        properties = new SyncProperties();
        properties.setMaxErrors(0);
        assertThatThrownBy(properties::validate).hasMessageContaining("max-errors");

        properties = new SyncProperties();
        properties.setScheduleCron(" ");
        assertThatThrownBy(properties::validate).hasMessageContaining("schedule-cron");

        properties = new SyncProperties();
        properties.setScheduleCron("password=not-a-cron");
        assertThatThrownBy(properties::validate)
                .hasMessageContaining("schedule-cron")
                .hasMessageNotContaining("password")
                .satisfies(failure -> assertThat(failure.getCause()).isNull());

        properties = new SyncProperties();
        properties.setScheduleZone("password/not-a-zone");
        assertThatThrownBy(properties::validate)
                .hasMessageContaining("schedule-zone")
                .hasMessageNotContaining("password")
                .satisfies(failure -> assertThat(failure.getCause()).isNull());
    }
}
