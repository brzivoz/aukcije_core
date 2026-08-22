package rs.sud.eaukcija;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LegacyH2PreflightTest {

    @TempDir
    Path tempDirectory;

    @Test
    void warnsWithoutChangingTheLegacyDatabase() throws Exception {
        Path database = tempDirectory.resolve("aukcije.mv.db");
        byte[] contents = "legacy-derived-data".getBytes();
        Files.write(database, contents);
        List<String> warnings = new ArrayList<>();

        LegacyH2Preflight.warnIfPresent(List.of(database), warnings::add);

        assertThat(warnings).singleElement().asString()
                .contains(database.toAbsolutePath().toString())
                .contains("clean re-sync")
                .contains("will not be deleted");
        assertThat(Files.readAllBytes(database)).containsExactly(contents);
    }

    @Test
    void anExplicitPathReplacesTheLegacyDefaults() {
        assertThat(LegacyH2Preflight.candidatePaths("/archive/aukcije.mv.db"))
                .containsExactly(Path.of("/archive/aukcije.mv.db"));
    }
}
