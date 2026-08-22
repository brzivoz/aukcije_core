package rs.sud.eaukcija;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Warns before Spring tries to connect to PostgreSQL when an old H2 database is
 * still present. The transition is intentionally operator-controlled: this
 * class never moves, edits, or deletes the file.
 */
final class LegacyH2Preflight {

    private static final Logger log = LoggerFactory.getLogger(LegacyH2Preflight.class);
    private static final String LEGACY_PATH_ENV = "AUKCIJE_LEGACY_H2_PATH";
    private static final List<Path> DEFAULT_PATHS = List.of(
            Path.of("data/aukcije.mv.db"),
            Path.of("data/aukcije.h2.db"));

    private LegacyH2Preflight() {
    }

    static void warnIfPresent() {
        warnIfPresent(candidatePaths(System.getenv(LEGACY_PATH_ENV)), log::warn);
    }

    static List<Path> candidatePaths(String configuredPath) {
        if (configuredPath == null || configuredPath.isBlank()) {
            return DEFAULT_PATHS;
        }
        return List.of(Path.of(configuredPath));
    }

    static void warnIfPresent(List<Path> candidates, Consumer<String> warningSink) {
        candidates.stream()
                .filter(Files::isRegularFile)
                .forEach(path -> warningSink.accept(
                        "Legacy H2 database found at " + path.toAbsolutePath().normalize()
                                + ". PostgreSQL migration uses a clean re-sync; archive this file first. "
                                + "It will not be deleted automatically."));
    }
}
