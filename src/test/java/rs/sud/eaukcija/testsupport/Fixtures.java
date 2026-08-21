package rs.sud.eaukcija.testsupport;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Loads a classpath fixture as text.
 *
 * <p>Every fixture in {@code src/test/resources/fixtures} is a recorded or
 * hand-built payload. No test may reach a live network, so a fixture is the only
 * source of third-party response bodies.
 */
public final class Fixtures {

    private Fixtures() {
    }

    public static String read(String path) {
        try (InputStream stream = Fixtures.class.getClassLoader().getResourceAsStream("fixtures/" + path)) {
            if (stream == null) {
                throw new IllegalArgumentException("missing fixture: fixtures/" + path);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read fixture: fixtures/" + path, e);
        }
    }
}
