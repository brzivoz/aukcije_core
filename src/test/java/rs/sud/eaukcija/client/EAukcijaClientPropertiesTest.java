package rs.sud.eaukcija.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

class EAukcijaClientPropertiesTest {

    @Test
    void exposesTheDocumentedSourceSafeDefaults() {
        EAukcijaClientProperties properties = new EAukcijaClientProperties();

        properties.validate();

        assertThat(properties.getRootCategoryIds()).containsExactly(7, 8);
        assertThat(properties.getPageSize()).isEqualTo(3_000);
        assertThat(properties.getConnectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.getReadTimeout()).isEqualTo(Duration.ofSeconds(20));
        assertThat(properties.getCallTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.getMaxAttempts()).isEqualTo(3);
        assertThat(properties.getRetryBaseDelay()).isEqualTo(Duration.ofMillis(500));
        assertThat(properties.getRetryMaxDelay()).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.getMaxRetryAfter()).isEqualTo(Duration.ofMinutes(2));
        assertThat(properties.getRequestsPerSecond()).isEqualTo(2.0);
        assertThat(properties.getMaxConcurrency()).isEqualTo(1);
        assertThat(properties.getMaxResponseBytes()).isEqualTo(16L * 1024 * 1024);
        assertThat(properties.requestUserAgent()).isEqualTo(
                "aukcije-core/0.0.1 (+https://github.com/brzivoz/aukcije_core/issues)");
    }

    @Test
    void rejectsUnsafeOriginsHeadersAndBounds() {
        EAukcijaClientProperties properties = new EAukcijaClientProperties();
        properties.setBaseUrl(URI.create("http://example.test/api"));
        assertThatThrownBy(properties::validate).hasMessageContaining("HTTPS");

        properties = new EAukcijaClientProperties();
        properties.setUserAgent("unsafe\r\nInjected: true");
        assertThatThrownBy(properties::validate).hasMessageContaining("user-agent");

        properties = new EAukcijaClientProperties();
        properties.setRootCategoryIds(List.of(7, 7));
        assertThatThrownBy(properties::validate).hasMessageContaining("root-category-ids");

        properties = new EAukcijaClientProperties();
        properties.setRootCategoryIds(java.util.stream.IntStream.rangeClosed(1, 17).boxed().toList());
        assertThatThrownBy(properties::validate).hasMessageContaining("1 to 16");

        properties = new EAukcijaClientProperties();
        properties.setMaxConcurrency(5);
        assertThatThrownBy(properties::validate).hasMessageContaining("max-concurrency");

        properties = new EAukcijaClientProperties();
        properties.setCallTimeout(Duration.ofSeconds(10));
        assertThatThrownBy(properties::validate).hasMessageContaining("call-timeout");
    }
}
