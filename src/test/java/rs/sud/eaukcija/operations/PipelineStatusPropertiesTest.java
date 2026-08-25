package rs.sud.eaukcija.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class PipelineStatusPropertiesTest {

    @Test
    void defaultsMatchTheDocumentedDailyPrivateRuntimePolicy() {
        PipelineStatusProperties properties = new PipelineStatusProperties();

        assertThat(properties.getSyncStaleAfter()).isEqualTo(Duration.ofHours(26));
        assertThat(properties.getBacklogMaxDepth()).isEqualTo(100);
        assertThat(properties.getBacklogMaxAge()).isEqualTo(Duration.ofHours(2));
        assertThat(properties.getReadinessCacheTtl()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void freshnessAndBacklogBoundsFailFast() {
        PipelineStatusProperties properties = new PipelineStatusProperties();

        assertThatThrownBy(() -> properties.setSyncStaleAfter(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setBacklogMaxAge(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setBacklogMaxDepth(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setReadinessCacheTtl(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
