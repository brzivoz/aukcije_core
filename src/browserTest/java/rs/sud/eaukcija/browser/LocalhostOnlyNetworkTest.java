package rs.sud.eaukcija.browser;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Pure policy checks that do not launch Spring, PostGIS, or Chromium. */
class LocalhostOnlyNetworkTest {

    @Test
    void browserLocalSchemesAreClassifiedBeforeJdkProtocolHandlers() {
        assertThat(LocalhostOnlyNetwork.hasBrowserLocalScheme(
                "blob:http://localhost:8081/4e353ba0-5f80-4af7-a399-76c81322b547")).isTrue();
        assertThat(LocalhostOnlyNetwork.hasBrowserLocalScheme(
                "data:image/svg+xml,%3Csvg%3E%3C/svg%3E")).isTrue();

        assertThat(LocalhostOnlyNetwork.hasBrowserLocalScheme("ws://localhost:8081/socket")).isFalse();
        assertThat(LocalhostOnlyNetwork.hasBrowserLocalScheme("wss://example.invalid/socket")).isFalse();
        assertThat(LocalhostOnlyNetwork.hasBrowserLocalScheme(
                "https://cdn.example.invalid/a[1]^x|y.png")).isFalse();
        assertThat(LocalhostOnlyNetwork.hasBrowserLocalScheme("not an absolute URL")).isFalse();
    }
}
