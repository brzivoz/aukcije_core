package rs.sud.eaukcija.addressregistry;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.LocalDate;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;

import rs.sud.eaukcija.testsupport.PostgisTestContainer;

/**
 * Opt-in proof against the retained full official artifact. CI uses the small
 * committed fixture and never downloads a gigabyte; operators can reproduce
 * this test by naming their reviewed local GPKG and checksum.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "ADDRESS_REGISTRY_FULL_GPKG", matches = ".+")
class AddressRegistryFullImportIntegrationTest {

    @ServiceConnection(name = "postgresql")
    static final PostgreSQLContainer<?> POSTGIS = PostgisTestContainer.shared();

    @Autowired
    private AddressRegistryImporter importer;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void clearSnapshots() {
        new JdbcTemplate(dataSource).execute("""
                TRUNCATE address_registry_import_runs,
                         address_registry_active_snapshot,
                         address_registry_centroids,
                         address_registry_points,
                         address_registry_snapshots
                """);
    }

    @Test
    void importsAndReplaysTheCompleteOfficialSnapshot() {
        Path gpkg = Path.of(requiredEnvironment("ADDRESS_REGISTRY_FULL_GPKG"));
        String sha256 = requiredEnvironment("ADDRESS_REGISTRY_FULL_GPKG_SHA256");
        String sourceDate = requiredEnvironment("ADDRESS_REGISTRY_FULL_SOURCE_DATE");

        AddressRegistryImportProperties properties = new AddressRegistryImportProperties();
        properties.setSourceUri(gpkg.toUri());
        properties.setCanonicalUrl(AddressRegistryImportProperties.OFFICIAL_RESOURCE_URL);
        properties.setSourceDate(LocalDate.parse(sourceDate));
        properties.setExpectedSha256(sha256);
        properties.setExpectedGpkgSha256(sha256);
        properties.setWorkDirectory(Path.of(
                System.getenv().getOrDefault("ADDRESS_REGISTRY_FULL_WORK_DIR", System.getProperty("java.io.tmpdir"))));

        AddressRegistryImporter.ImportResult imported = importer.importSnapshot(properties);

        assertThat(imported.outcome()).isEqualTo("SUCCEEDED");
        assertThat(imported.sourceRows()).isEqualTo(2_488_492);
        assertThat(imported.importedRows()).isEqualTo(2_488_492);
        assertThat(imported.inactiveRows()).isZero();
        assertThat(imported.retiredRows()).isZero();
        assertThat(imported.unnormalizedParcelRows()).isZero();
        assertThat(imported.duplicateParcelIdentities()).isEqualTo(182_989);
        assertThat(imported.ambiguousParentIdentities()).isEqualTo(4);
        assertThat(imported.centroidRows()).isGreaterThan(1_000);
        assertThat(imported.gpkgSha256()).isEqualTo(sha256);

        AddressRegistryImporter.ImportResult unchanged = importer.importSnapshot(properties);

        assertThat(unchanged.outcome()).isEqualTo("UNCHANGED");
        assertThat(unchanged.snapshotId()).isEqualTo(imported.snapshotId());
        assertThat(importer.status().activeSnapshotId()).isEqualTo(imported.snapshotId());
        System.out.println("FULL_ADDRESS_REGISTRY_IMPORT=" + imported);
        System.out.println("FULL_ADDRESS_REGISTRY_REPLAY=" + unchanged);
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required when the full import test is enabled");
        }
        return value;
    }
}
