package rs.sud.eaukcija.rgz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RgzParcelPropertiesTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void defaultsExposeTheAutomaticContractWithoutSilentlyActivatingStalePins() {
        RgzParcelProperties properties = new RgzParcelProperties();

        properties.validate();

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getAccessMode())
                .isEqualTo("OWNER_AUTHORIZED_AUTOMATIC_PRIVATE_LOCAL_EXPLICIT_ACTIVATION");
        assertThat(properties.getFeatureType())
                .isEqualTo("dkp:dkp_parcels_weekly_only_utm");
        assertThat(properties.getRequestsPerSecond()).isEqualTo(0.2);
        assertThat(properties.getMaxConcurrency()).isOne();
        assertThat(properties.getMaxLogicalLookupsPerRun()).isEqualTo(100);
        assertThat(properties.getMaxAttempts()).isEqualTo(3);
        assertThat(properties.getRetryDelays())
                .containsExactly(Duration.ofSeconds(5), Duration.ofSeconds(15));
        assertThat(properties.getMaxResponseBytes()).isEqualTo(5_000_000);
        assertThat(properties.getKillSwitchPath()).isEqualTo(Path.of("data/control/rgz.disabled"));
        assertThat(properties.getDatasetVersion()).isEmpty();
        assertThat(properties.getCapabilitiesSha256()).isEmpty();
        assertThat(properties.getSchemaSha256()).isEmpty();
        assertThat(properties.requestUserAgent()).doesNotContain("\r", "\n");
    }

    @Test
    void activationRequiresAnExplicitCurrentDatasetAndSourceFingerprints() {
        RgzParcelProperties properties = new RgzParcelProperties();
        properties.setEnabled(true);

        assertThatThrownBy(properties::validate).hasMessageContaining("dataset-version");

        properties.setDatasetVersion("WFS-current-capture");
        assertThatThrownBy(properties::validate).hasMessageContaining("capabilities-sha256");

        properties.setCapabilitiesSha256("a".repeat(64));
        assertThatThrownBy(properties::validate).hasMessageContaining("schema-sha256");

        properties.setSchemaSha256("b".repeat(64));
        properties.validate();
    }

    @Test
    void rejectsCredentialsNonHttpsHeadersAndInconsistentRetries() {
        RgzParcelProperties properties = new RgzParcelProperties();
        properties.setBaseUrl(URI.create("https://user:pass@example.test/wfs"));
        assertThatThrownBy(properties::validate).hasMessageContaining("credentials");

        properties = new RgzParcelProperties();
        properties.setBaseUrl(URI.create("http://example.test/wfs"));
        assertThatThrownBy(properties::validate).hasMessageContaining("HTTPS");

        properties = new RgzParcelProperties();
        properties.setUserAgent("bad\r\nheader");
        assertThatThrownBy(properties::validate).hasMessageContaining("line breaks");

        properties = new RgzParcelProperties();
        properties.setRetryDelays(List.of(Duration.ofSeconds(5)));
        assertThatThrownBy(properties::validate).hasMessageContaining("max-attempts minus one");
    }

    @Test
    void killSwitchChangesWithoutRestart() throws Exception {
        RgzParcelProperties properties = new RgzParcelProperties();
        properties.setEnabled(true);
        Path killSwitch = temporaryDirectory.resolve("rgz.disabled");
        properties.setKillSwitchPath(killSwitch);

        assertThat(properties.networkAllowed()).isTrue();
        Files.createFile(killSwitch);
        assertThat(properties.networkAllowed()).isFalse();
        Files.delete(killSwitch);
        assertThat(properties.networkAllowed()).isTrue();
    }
}
