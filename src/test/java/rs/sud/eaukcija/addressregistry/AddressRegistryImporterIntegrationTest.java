package rs.sud.eaukcija.addressregistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.nio.file.Files;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;

import rs.sud.eaukcija.testsupport.PostgisTestContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class AddressRegistryImporterIntegrationTest {

    @ServiceConnection(name = "postgresql")
    static final PostgreSQLContainer<?> POSTGIS = PostgisTestContainer.shared();

    @TempDir
    Path tempDirectory;

    @Autowired
    private AddressRegistryImporter importer;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    @BeforeEach
    void clearSnapshots() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                TRUNCATE address_registry_import_runs,
                         address_registry_active_snapshot,
                         address_registry_centroids,
                         address_registry_points,
                         address_registry_snapshots
                """);
    }

    @Test
    void cleanAndUnchangedImportsAreReproducibleAndPreserveOfficialValues() throws Exception {
        Path gpkg = AddressRegistryGpkgFixture.create(
                tempDirectory.resolve("clean"), 0, AddressRegistryGpkgFixture.Fault.NONE);
        AddressRegistryImportProperties properties = properties(gpkg, 2);

        AddressRegistryImporter.ImportResult first = importer.importSnapshot(properties);

        assertThat(first.outcome()).isEqualTo("SUCCEEDED");
        assertThat(first.sourceRows()).isEqualTo(3);
        assertThat(first.gpkgBytes()).isPositive();
        assertThat(first.importedRows()).isEqualTo(2);
        assertThat(first.inactiveRows()).isEqualTo(1);
        assertThat(first.retiredRows()).isEqualTo(1);
        assertThat(first.centroidRows()).isEqualTo(6);
        assertThat(first.previousSnapshotId()).isNull();

        Map<String, Object> source = jdbc.queryForMap("""
                SELECT house_number_id, house_number, house_number_latin,
                       parcel_number, parcel_number_normalized, parcel_part,
                       ko_id, ko_name, ko_name_latin,
                       settlement_id, municipality_id, ko_name_normalized,
                       ST_X(location) AS lon, ST_Y(location) AS lat
                FROM address_registry_points
                WHERE snapshot_id = ? AND source_primary_key = 1001
                """, first.snapshotId());
        assertThat(source)
                .containsEntry("house_number_id", "500001")
                .containsEntry("house_number", "23")
                .containsEntry("house_number_latin", "23")
                .containsEntry("parcel_number", "1572")
                .containsEntry("parcel_number_normalized", "1572")
                .containsEntry("parcel_part", "1")
                .containsEntry("ko_id", "702013")
                .containsEntry("ko_name", "ДИМИТРОВГРАД")
                .containsEntry("ko_name_latin", "DIMITROVGRAD")
                .containsEntry("settlement_id", "704156")
                .containsEntry("municipality_id", "70201")
                .containsEntry("ko_name_normalized", "DIMITROVGRAD");
        assertThat((Double) source.get("lon")).isCloseTo(22.780484, within(0.0000001));
        assertThat((Double) source.get("lat")).isCloseTo(43.013322, within(0.0000001));

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM address_registry_points
                WHERE snapshot_id = ? AND source_primary_key = 1003
                """, Long.class, first.snapshotId())).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT member_point_count FROM address_registry_centroids
                WHERE snapshot_id = ? AND level = 'KO' AND official_id = '702013'
                """, Long.class, first.snapshotId())).isEqualTo(1);

        AddressRegistryImporter.ImportResult unchanged = importer.importSnapshot(properties);

        assertThat(unchanged.outcome()).isEqualTo("UNCHANGED");
        assertThat(unchanged.snapshotId()).isEqualTo(first.snapshotId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM address_registry_snapshots", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForList(
                "SELECT outcome FROM address_registry_import_runs ORDER BY started_at", String.class))
                .containsExactly("SUCCEEDED", "UNCHANGED");
    }

    @Test
    void checksumSchemaCrsAndRowCountFailuresNeverPromote() throws Exception {
        Path valid = AddressRegistryGpkgFixture.create(
                tempDirectory.resolve("negative"), 0, AddressRegistryGpkgFixture.Fault.NONE);

        AddressRegistryImportProperties badChecksum = properties(valid, 2);
        badChecksum.setExpectedSha256("0".repeat(64));
        assertFailure(badChecksum, "CHECKSUM_MISMATCH");

        Path missing = AddressRegistryGpkgFixture.create(
                tempDirectory.resolve("negative"), 1, AddressRegistryGpkgFixture.Fault.MISSING_REQUIRED_COLUMN);
        assertFailure(properties(missing, 2), "SCHEMA_MISMATCH");

        Path wrongCrs = AddressRegistryGpkgFixture.create(
                tempDirectory.resolve("negative"), 2, AddressRegistryGpkgFixture.Fault.WRONG_CRS);
        assertFailure(properties(wrongCrs, 2), "CRS_MISMATCH");

        AddressRegistryImportProperties badRows = properties(valid, 2);
        badRows.setMaximumRows(2);
        assertFailure(badRows, "ROW_COUNT_SANITY");

        AddressRegistryImportProperties badFingerprint = properties(valid, 2);
        badFingerprint.setExpectedSchemaSha256("0".repeat(64));
        assertFailure(badFingerprint, "SCHEMA_FINGERPRINT_MISMATCH");

        Path badGeometry = AddressRegistryGpkgFixture.create(
                tempDirectory.resolve("negative"), 3, AddressRegistryGpkgFixture.Fault.INVALID_GEOMETRY);
        assertFailure(properties(badGeometry, 2), "INVALID_GEOMETRY");

        Path duplicate = AddressRegistryGpkgFixture.create(
                tempDirectory.resolve("negative"), 4, AddressRegistryGpkgFixture.Fault.DUPLICATE_PRIMARY_KEY);
        assertFailure(properties(duplicate, 2), "DATABASE_IMPORT");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM address_registry_snapshots", Long.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM address_registry_import_runs WHERE outcome = 'FAILED'", Long.class)).isEqualTo(7);
    }

    @Test
    void failedRefreshLeavesThePreviousSnapshotActive() throws Exception {
        Path valid = AddressRegistryGpkgFixture.create(
                tempDirectory.resolve("atomic"), 0, AddressRegistryGpkgFixture.Fault.NONE);
        UUID good = importer.importSnapshot(properties(valid, 2)).snapshotId();

        Path invalid = AddressRegistryGpkgFixture.create(
                tempDirectory.resolve("atomic"), 1, AddressRegistryGpkgFixture.Fault.OUTSIDE_SERBIA);
        assertFailure(properties(invalid, 2), "GEOMETRY_OUTSIDE_SERBIA");

        AddressRegistryImporter.Status status = importer.status();
        assertThat(status.activeSnapshotId()).isEqualTo(good);
        assertThat(status.previousSnapshotId()).isNull();
        assertThat(status.retainedSnapshots()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM address_registry_points", Long.class)).isEqualTo(2);
    }

    @Test
    void officialZipShapeIsExtractedAndBothHashesAreRetained() throws Exception {
        Path gpkg = AddressRegistryGpkgFixture.create(
                tempDirectory.resolve("zip"), 0, AddressRegistryGpkgFixture.Fault.NONE);
        Path zip = tempDirectory.resolve("zip").resolve("kucni_br_gpkg.zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zip))) {
            output.putNextEntry(new ZipEntry("kucni_broj.gpkg"));
            Files.copy(gpkg, output);
            output.closeEntry();
        }
        AddressRegistryImportProperties properties = properties(zip, 2);
        properties.setExpectedGpkgSha256(AddressRegistryArtifactStager.sha256(gpkg));

        AddressRegistryImporter.ImportResult imported = importer.importSnapshot(properties);

        assertThat(imported.outcome()).isEqualTo("SUCCEEDED");
        assertThat(imported.sourceSha256()).isEqualTo(AddressRegistryArtifactStager.sha256(zip));
        assertThat(imported.gpkgSha256()).isEqualTo(AddressRegistryArtifactStager.sha256(gpkg));
        assertThat(imported.sourceSha256()).isNotEqualTo(imported.gpkgSha256());
        assertThat(jdbc.queryForObject("""
                SELECT archive_member FROM address_registry_snapshots WHERE id = ?
                """, String.class, imported.snapshotId())).isEqualTo("kucni_broj.gpkg");
    }

    @Test
    void ambiguousKoParentIsPreservedWithoutChoosingAnArbitraryMunicipality() throws Exception {
        Path gpkg = AddressRegistryGpkgFixture.create(
                tempDirectory.resolve("parent-conflict"), 0, AddressRegistryGpkgFixture.Fault.PARENT_CONFLICT);

        AddressRegistryImporter.ImportResult imported = importer.importSnapshot(properties(gpkg, 2));

        assertThat(imported.outcome()).isEqualTo("SUCCEEDED");
        assertThat(imported.ambiguousParentIdentities()).isEqualTo(1);
        Map<String, Object> centroid = jdbc.queryForMap("""
                SELECT municipality_id, parent_variant_count, member_point_count
                FROM address_registry_centroids
                WHERE snapshot_id = ? AND level = 'KO' AND official_id = '702013'
                """, imported.snapshotId());
        assertThat(centroid.get("municipality_id")).isNull();
        assertThat(centroid)
                .containsEntry("parent_variant_count", 2L)
                .containsEntry("member_point_count", 2L);
    }

    @Test
    void retentionProtectsCurrentAndPreviousAndRollbackSwapsThem() throws Exception {
        UUID first = importer.importSnapshot(properties(AddressRegistryGpkgFixture.create(
                tempDirectory.resolve("retention"), 1, AddressRegistryGpkgFixture.Fault.NONE), 2)).snapshotId();
        UUID second = importer.importSnapshot(properties(AddressRegistryGpkgFixture.create(
                tempDirectory.resolve("retention"), 2, AddressRegistryGpkgFixture.Fault.NONE), 2)).snapshotId();
        AddressRegistryImporter.ImportResult thirdResult = importer.importSnapshot(properties(
                AddressRegistryGpkgFixture.create(
                        tempDirectory.resolve("retention"), 3, AddressRegistryGpkgFixture.Fault.NONE), 2));
        UUID third = thirdResult.snapshotId();

        assertThat(thirdResult.previousSnapshotId()).isEqualTo(second);
        assertThat(thirdResult.retainedSnapshots()).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM address_registry_snapshots WHERE id = ?)", Boolean.class, first)).isFalse();

        AddressRegistryImporter.ImportResult rollback = importer.rollback();

        assertThat(rollback.outcome()).isEqualTo("ROLLED_BACK");
        assertThat(rollback.snapshotId()).isEqualTo(second);
        assertThat(rollback.previousSnapshotId()).isEqualTo(third);
        assertThat(importer.status().activeSnapshotId()).isEqualTo(second);
        assertThat(importer.status().previousSnapshotId()).isEqualTo(third);
        assertThat(importer.status().retainedSnapshots()).isEqualTo(2);
    }

    private AddressRegistryImportProperties properties(Path gpkg, int retainedSnapshots) throws Exception {
        AddressRegistryImportProperties properties = new AddressRegistryImportProperties();
        properties.setSourceUri(gpkg.toUri());
        properties.setCanonicalUrl(AddressRegistryImportProperties.OFFICIAL_RESOURCE_URL);
        properties.setSourceDate(LocalDate.of(2026, 8, 21));
        properties.setExpectedSha256(AddressRegistryArtifactStager.sha256(gpkg));
        properties.setMinimumRows(1);
        properties.setMaximumRows(10);
        properties.setBatchSize(2);
        properties.setRetainedSnapshots(retainedSnapshots);
        properties.setMinimumFreeBytes(0);
        properties.setMaximumGpkgBytes(10 * 1024 * 1024);
        properties.setWorkDirectory(tempDirectory.resolve("work"));
        return properties;
    }

    private void assertFailure(AddressRegistryImportProperties properties, String code) {
        assertThatThrownBy(() -> importer.importSnapshot(properties))
                .isInstanceOfSatisfying(AddressRegistryImportException.class,
                        error -> assertThat(error.code()).isEqualTo(code));
    }

    private static org.assertj.core.data.Offset<Double> within(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }
}
