package rs.sud.eaukcija.basemap;

import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Offline operator command used for activation and rollback of immutable builds. */
public final class BasemapActivateCli {

    private BasemapActivateCli() {
    }

    public static void main(String[] args) {
        Arguments parsed = Arguments.parse(args);
        BasemapArtifactValidator validator = new BasemapArtifactValidator(new ObjectMapper());
        BasemapSnapshot snapshot = new BasemapArtifactActivator(validator)
                .activate(parsed.directory(), parsed.version());
        System.out.printf(
                "Activated basemap %s (%d bytes, sha256 %s)%n",
                snapshot.buildId(), snapshot.artifactSizeBytes(), snapshot.artifactSha256());
    }

    private record Arguments(Path directory, String version) {

        private static Arguments parse(String[] args) {
            Path directory = Path.of(System.getenv().getOrDefault(
                    "BASEMAP_ASSET_DIRECTORY", "data/basemap"));
            String version = null;
            for (int index = 0; index < args.length; index++) {
                switch (args[index]) {
                    case "--directory" -> {
                        requireValue(args, index, "--directory");
                        directory = Path.of(args[++index]);
                    }
                    case "--version" -> {
                        requireValue(args, index, "--version");
                        version = args[++index];
                    }
                    default -> throw usage("unknown argument: " + args[index]);
                }
            }
            if (version == null) {
                throw usage("--version is required");
            }
            return new Arguments(directory, version);
        }

        private static void requireValue(String[] args, int index, String option) {
            if (index + 1 >= args.length) {
                throw usage(option + " requires a value");
            }
        }

        private static IllegalArgumentException usage(String message) {
            return new IllegalArgumentException(
                    message + System.lineSeparator()
                            + "Usage: --version <immutable-build-id> [--directory <asset-root>]");
        }
    }
}
