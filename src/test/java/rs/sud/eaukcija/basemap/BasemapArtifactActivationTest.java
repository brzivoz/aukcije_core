package rs.sud.eaukcija.basemap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BasemapArtifactActivationTest {

    @TempDir
    private Path root;

    @Test
    void failedActivationLeavesLastGoodSnapshotAndPointerActive() throws Exception {
        BasemapArtifactValidator validator = new BasemapArtifactValidator(new ObjectMapper());
        BasemapArtifactActivator activator = new BasemapArtifactActivator(validator);
        BasemapTestBundle.Bundle first = BasemapTestBundle.synthetic(root, "version-one", 2048, (byte) 1);
        BasemapTestBundle.Bundle second = BasemapTestBundle.synthetic(root, "version-two", 2048, (byte) 2);
        Path secondStyle = second.directory().resolve("style.json");

        activator.activate(root, first.version());
        BasemapArtifactRegistry registry = new BasemapArtifactRegistry(
                root.toString(), Duration.ofHours(1), validator);
        registry.afterPropertiesSet();
        try {
            assertThat(registry.snapshot().buildId()).isEqualTo("version-one");

            Files.writeString(
                    secondStyle,
                    "corrupt after manifest publication",
                    StandardCharsets.UTF_8);
            assertThatThrownBy(() -> activator.activate(root, second.version()))
                    .isInstanceOf(BasemapArtifactException.class)
                    .hasMessageContaining("mismatch");
            registry.refreshNow();
            assertThat(Files.readString(root.resolve("ACTIVE")).trim()).isEqualTo("version-one");
            assertThat(registry.snapshot().buildId()).isEqualTo("version-one");

            BasemapTestBundle.synthetic(root, "version-two", 2048, (byte) 2);
            activator.activate(root, "version-two");
            registry.refreshNow();
            assertThat(registry.snapshot().buildId()).isEqualTo("version-two");

            activator.activate(root, first.version());
            registry.refreshNow();
            assertThat(registry.snapshot().buildId()).isEqualTo("version-one");
        } finally {
            registry.destroy();
        }
    }

    @Test
    void concurrentReadersObserveOnlyCompleteAtomicPointerValues() throws Exception {
        BasemapArtifactValidator validator = new BasemapArtifactValidator(new ObjectMapper());
        BasemapArtifactActivator activator = new BasemapArtifactActivator(validator);
        BasemapTestBundle.synthetic(root, "atomic-a", 512, (byte) 1);
        BasemapTestBundle.synthetic(root, "atomic-b", 512, (byte) 2);
        activator.activate(root, "atomic-a");

        Set<String> observed = ConcurrentHashMap.newKeySet();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> reader = executor.submit(() -> {
                for (int index = 0; index < 500; index++) {
                    try {
                        observed.add(Files.readString(root.resolve("ACTIVE")).trim());
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                }
            });
            Future<?> writer = executor.submit(() -> {
                for (int index = 0; index < 20; index++) {
                    activator.activate(root, index % 2 == 0 ? "atomic-b" : "atomic-a");
                }
            });
            reader.get();
            writer.get();
        } finally {
            executor.shutdownNow();
        }
        assertThat(observed).isNotEmpty().isSubsetOf("atomic-a", "atomic-b");
    }

    @Test
    void externallyHostedStyleIsRejectedEvenWhenItsUpdatedHashMatchesTheManifest()
            throws Exception {
        BasemapTestBundle.Bundle bundle =
                BasemapTestBundle.synthetic(root, "external-style", 512, (byte) 3);
        Path style = bundle.directory().resolve("style.json");
        Files.writeString(
                style,
                Files.readString(style).replace(
                        "/basemap/sprites/light", "https://cdn.example.invalid/light"),
                StandardCharsets.UTF_8);
        BasemapTestBundle.refreshManifest(bundle);

        BasemapArtifactActivator activator = new BasemapArtifactActivator(
                new BasemapArtifactValidator(new ObjectMapper()));
        assertThatThrownBy(() -> activator.activate(root, bundle.version()))
                .isInstanceOf(BasemapArtifactException.class)
                .hasMessageContaining("same-origin");
        assertThat(root.resolve("ACTIVE")).doesNotExist();
    }
}
