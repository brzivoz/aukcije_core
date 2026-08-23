package rs.sud.eaukcija.basemap;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class FrontendAssetLockTest {

    @Test
    void everyVendoredBrowserByteMatchesTheReviewedLock() throws Exception {
        URL rootResource = getClass().getResource("/static/vendor");
        assertThat(rootResource).isNotNull();
        Path root = Path.of(rootResource.toURI());
        JsonNode lock = new ObjectMapper().readTree(
                root.resolve("frontend-assets.lock.json").toFile());
        assertThat(lock.path("schemaVersion").asInt()).isEqualTo(1);

        Set<String> expected = new HashSet<>();
        expected.add("frontend-assets.lock.json");
        for (JsonNode packageNode : lock.path("packages")) {
            assertThat(packageNode.path("version").asText()).isNotBlank();
            assertThat(packageNode.path("license").asText()).isEqualTo("BSD-3-Clause");
            assertThat(packageNode.path("packageSha256").asText()).matches("[0-9a-f]{64}");
            for (JsonNode file : packageNode.path("files")) {
                String relative = file.path("path").asText();
                assertThat(expected.add(relative)).isTrue();
                Path path = root.resolve(relative).normalize();
                assertThat(path).startsWith(root);
                assertThat(Files.isRegularFile(path)).isTrue();
                assertThat(Files.size(path)).isEqualTo(file.path("sizeBytes").asLong());
                assertThat(sha256(path)).isEqualTo(file.path("sha256").asText());
            }
        }

        Set<String> actual = new HashSet<>();
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                    .map(root::relativize)
                    .map(path -> path.toString().replace('\\', '/'))
                    .forEach(actual::add);
        }
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
