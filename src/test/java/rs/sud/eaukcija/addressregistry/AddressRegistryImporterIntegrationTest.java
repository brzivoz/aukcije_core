package rs.sud.eaukcija.addressregistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.sql.DataSource;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
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
    private AddressRegistryImportRecovery recovery;

    @Autowired
    private AddressRegistryImportLock importLock;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private JdbcTemplate jdbc;

    @BeforeEach
    void clearSnapshots() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                TRUNCATE address_registry_retention_jobs,
                         address_registry_import_runs,
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
        assertThat(first.sourceRows()).isEqualTo(4);
        assertThat(first.gpkgBytes()).isPositive();
        assertThat(first.importedRows()).isEqualTo(3);
        assertThat(first.inactiveRows()).isEqualTo(1);
        assertThat(first.retiredRows()).isEqualTo(1);
        assertThat(first.unnormalizedParcelRows()).isZero();
        assertThat(first.centroidRows()).isEqualTo(9);
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
        assertThat(jdbc.queryForMap("""
                SELECT house_number_normalized, street_name_normalized,
                       ko_name_normalized, settlement_name_normalized,
                       municipality_name_normalized
                FROM address_registry_points
                WHERE snapshot_id = ? AND source_primary_key = 7312319
                """, first.snapshotId()))
                .containsEntry("house_number_normalized", "88DJ")
                .containsEntry("street_name_normalized", "DJURE JAKSICA")
                .containsEntry("ko_name_normalized", "BECMEN")
                .containsEntry("settlement_name_normalized", "BECMEN")
                .containsEntry("municipality_name_normalized", "SURCIN");

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

        Path blankNormalizedName = AddressRegistryGpkgFixture.create(
                tempDirectory.resolve("negative"), 5,
                AddressRegistryGpkgFixture.Fault.REQUIRED_NAME_NORMALIZES_EMPTY);
        assertFailure(properties(blankNormalizedName, 2), "REQUIRED_VALUE_MISSING");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM address_registry_snapshots", Long.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM address_registry_import_runs WHERE outcome = 'FAILED'", Long.class)).isEqualTo(8);
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
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM address_registry_points", Long.class)).isEqualTo(3);
    }

    @Test
    void changedStatusOrRetiredVocabularyCannotPromoteAnEmptySnapshot() throws Exception {
        Path valid = AddressRegistryGpkgFixture.create(
                tempDirectory.resolve("status-vocabulary"), 0, AddressRegistryGpkgFixture.Fault.NONE);
        UUID good = importer.importSnapshot(properties(valid, 2)).snapshotId();

        Path unknownStatuses = AddressRegistryGpkgFixture.create(
                tempDirectory.resolve("status-vocabulary"), 1,
                AddressRegistryGpkgFixture.Fault.UNKNOWN_ACTIVE_STATUS);
        assertFailure(properties(unknownStatuses, 2), "ACTIVE_ROW_COUNT_SANITY");

        Path booleanRetired = AddressRegistryGpkgFixture.create(
                tempDirectory.resolve("status-vocabulary"), 2,
                AddressRegistryGpkgFixture.Fault.BOOLEAN_RETIRED);
        assertFailure(properties(booleanRetired, 2), "ACTIVE_ROW_COUNT_SANITY");

        assertThat(importer.status().activeSnapshotId()).isEqualTo(good);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM address_registry_snapshots", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForList(
                "SELECT error_code FROM address_registry_import_runs WHERE outcome = 'FAILED' ORDER BY started_at",
                String.class)).containsExactly("ACTIVE_ROW_COUNT_SANITY", "ACTIVE_ROW_COUNT_SANITY");
    }

    @Test
    void parcelValuesThatCannotNormalizeAreCountedAndRetainedAsSourceEvidence() throws Exception {
        Path gpkg = AddressRegistryGpkgFixture.create(
                tempDirectory.resolve("parcel-normalization"), 0,
                AddressRegistryGpkgFixture.Fault.UNNORMALIZED_PARCEL);

        AddressRegistryImporter.ImportResult imported = importer.importSnapshot(properties(gpkg, 2));

        assertThat(imported.unnormalizedParcelRows()).isEqualTo(1);
        assertThat(jdbc.queryForMap("""
                SELECT parcel_number, parcel_number_normalized
                FROM address_registry_points
                WHERE snapshot_id = ? AND source_primary_key = 1001
                """, imported.snapshotId()))
                .containsEntry("parcel_number", "1572-А")
                .containsEntry("parcel_number_normalized", null);
        assertThat(jdbc.queryForObject("""
                SELECT unnormalized_parcel_rows FROM address_registry_import_runs WHERE id = ?
                """, Long.class, imported.runId())).isEqualTo(1);
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
        assertThat(jdbc.queryForObject("""
                SELECT duration_millis FROM address_registry_retention_jobs WHERE import_run_id = ?
                """, Long.class, thirdResult.runId())).isEqualTo(thirdResult.retentionMillis());
        Map<String, Object> durationEvidence = jdbc.queryForMap("""
                SELECT run.retention_millis, run.total_millis AS import_millis,
                       retention.duration_millis AS retention_millis_separate
                  FROM address_registry_import_runs run
                  JOIN address_registry_retention_jobs retention
                    ON retention.import_run_id = run.id
                 WHERE run.id = ?
                """, thirdResult.runId());
        assertThat(durationEvidence.get("retention_millis")).isNull();
        assertThat(((Number) durationEvidence.get("import_millis")).longValue()
                + ((Number) durationEvidence.get("retention_millis_separate")).longValue())
                .isEqualTo(thirdResult.totalMillis());
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

    @Test
    void startupRecoveryFinalizesAnImportAbandonedByItsOwningProcess() {
        UUID runId = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        jdbc.update("""
                INSERT INTO address_registry_import_runs (
                    id, action, outcome, started_at, source_date, canonical_url
                ) VALUES (?, 'IMPORT', 'RUNNING', CURRENT_TIMESTAMP - INTERVAL '1 minute',
                          '2026-08-25', 'https://example.invalid/address.gpkg')
                """, runId);

        assertThat(recovery.reconcileAbandonedRuns()).isEqualTo(1);
        assertThat(jdbc.queryForMap("""
                SELECT outcome, error_code, error_message, finished_at IS NOT NULL AS finished
                  FROM address_registry_import_runs WHERE id = ?
                """, runId))
                .containsEntry("outcome", "FAILED")
                .containsEntry("error_code", "IMPORT_PROCESS_RESTARTED")
                .containsEntry("finished", true)
                .containsEntry("error_message", null);
    }

    @Test
    void liveStagingLeaseBlocksRecoveryAndRejectsASecondJvmStyleInvocation() throws Exception {
        Path gpkg = AddressRegistryGpkgFixture.create(
                tempDirectory.resolve("concurrent-staging"), 0,
                AddressRegistryGpkgFixture.Fault.NONE);
        AddressRegistryImportProperties properties = properties(gpkg, 2);
        AddressRegistryArtifactStager blockingStager = mock(AddressRegistryArtifactStager.class);
        GeoPackageInspector unusedInspector = mock(GeoPackageInspector.class);
        CountDownLatch stagingStarted = new CountDownLatch(1);
        CountDownLatch releaseStaging = new CountDownLatch(1);
        when(blockingStager.stage(any())).thenAnswer(invocation -> {
            stagingStarted.countDown();
            try {
                if (!releaseStaging.await(10, TimeUnit.SECONDS)) {
                    throw new AddressRegistryImportException(
                            "TEST_TIMEOUT", "test staging release timed out");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AddressRegistryImportException(
                        "TEST_INTERRUPTED", "test staging wait was interrupted", interrupted);
            }
            throw new AddressRegistryImportException(
                    "STAGING_ABORTED", "synthetic stop after concurrency assertions");
        });
        AddressRegistryImporter concurrentImporter = new AddressRegistryImporter(
                dataSource, transactionManager, blockingStager, unusedInspector, importLock);

        CompletableFuture<String> first = CompletableFuture.supplyAsync(() -> {
            try {
                concurrentImporter.importSnapshot(properties);
                return "UNEXPECTED_SUCCESS";
            } catch (AddressRegistryImportException failure) {
                return failure.code();
            }
        });
        try {
            assertThat(stagingStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM address_registry_import_runs WHERE outcome = 'RUNNING'
                    """, Long.class)).isEqualTo(1);

            assertThat(recovery.reconcileAbandonedRuns()).isZero();
            assertThatThrownBy(() -> concurrentImporter.importSnapshot(properties))
                    .isInstanceOfSatisfying(AddressRegistryImportException.class,
                            failure -> assertThat(failure.code()).isEqualTo("IMPORT_ALREADY_RUNNING"));

            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM address_registry_import_runs WHERE outcome = 'RUNNING'
                    """, Long.class)).isEqualTo(1);
            assertThat(jdbc.queryForList("""
                    SELECT error_code FROM address_registry_import_runs
                     WHERE outcome = 'FAILED' ORDER BY started_at
                    """, String.class)).containsExactly("IMPORT_ALREADY_RUNNING");
            verify(unusedInspector, never()).inspect(any(), any());
        } finally {
            releaseStaging.countDown();
        }

        assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo("STAGING_ABORTED");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM address_registry_import_runs WHERE outcome = 'RUNNING'
                """, Long.class)).isZero();
    }

    @Test
    void committedImportRemainsSuccessfulWhenSessionLeaseReleaseFails() throws Exception {
        String sensitiveFailure = "password=hunter2 raw SQLException detail";
        AddressRegistryImportLock failingReleaseLock = mock(AddressRegistryImportLock.class);
        AddressRegistryImportLock.Lease failingLease = mock(AddressRegistryImportLock.Lease.class);
        when(failingReleaseLock.tryAcquire()).thenReturn(Optional.of(failingLease));
        doThrow(new IllegalStateException(sensitiveFailure)).when(failingLease).close();
        AddressRegistryImporter importerWithFailingRelease = new AddressRegistryImporter(
                dataSource,
                transactionManager,
                new AddressRegistryArtifactStager(),
                new GeoPackageInspector(),
                failingReleaseLock);
        Path gpkg = AddressRegistryGpkgFixture.create(
                tempDirectory.resolve("release-failure"), 0,
                AddressRegistryGpkgFixture.Fault.NONE);
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(AddressRegistryImporter.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.addAppender(events);

        try {
            AddressRegistryImporter.ImportResult imported =
                    importerWithFailingRelease.importSnapshot(properties(gpkg, 2));

            assertThat(imported.outcome()).isEqualTo("SUCCEEDED");
            assertThat(jdbc.queryForObject("""
                    SELECT outcome FROM address_registry_import_runs WHERE id = ?
                    """, String.class, imported.runId())).isEqualTo("SUCCEEDED");
            assertThat(jdbc.queryForObject("""
                    SELECT outcome FROM address_registry_retention_jobs WHERE import_run_id = ?
                    """, String.class, imported.runId())).isEqualTo("SUCCEEDED");
            assertThat(events.list)
                    .filteredOn(event -> event.getFormattedMessage()
                            .contains("IMPORT_LOCK_RELEASE_FAILED"))
                    .singleElement()
                    .satisfies(event -> {
                        assertThat(event.getFormattedMessage()).doesNotContain(sensitiveFailure);
                        assertThat(event.getThrowableProxy()).isNull();
                    });
            assertThat(events.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .anyMatch(message -> message.contains(
                            "outcome=SUCCEEDED"));
        } finally {
            logger.detachAppender(events);
            events.stop();
        }
    }

    @Test
    void retentionFailureCannotRollBackAnAlreadyPromotedSnapshot() throws Exception {
        importer.importSnapshot(properties(AddressRegistryGpkgFixture.create(
                tempDirectory.resolve("retention-failure"), 1,
                AddressRegistryGpkgFixture.Fault.NONE), 2));
        UUID second = importer.importSnapshot(properties(AddressRegistryGpkgFixture.create(
                tempDirectory.resolve("retention-failure"), 2,
                AddressRegistryGpkgFixture.Fault.NONE), 2)).snapshotId();
        jdbc.execute("""
                CREATE FUNCTION fail_address_registry_retention() RETURNS trigger
                LANGUAGE plpgsql AS $$
                BEGIN
                  RAISE EXCEPTION 'forced retention failure';
                END
                $$
                """);
        jdbc.execute("""
                CREATE TRIGGER fail_address_registry_retention
                BEFORE DELETE ON address_registry_snapshots
                FOR EACH ROW EXECUTE FUNCTION fail_address_registry_retention()
                """);
        try {
            AddressRegistryImporter.ImportResult third = importer.importSnapshot(properties(
                    AddressRegistryGpkgFixture.create(
                            tempDirectory.resolve("retention-failure"), 3,
                            AddressRegistryGpkgFixture.Fault.NONE), 2));

            assertThat(third.outcome()).isEqualTo("SUCCEEDED");
            assertThat(third.previousSnapshotId()).isEqualTo(second);
            assertThat(importer.status().activeSnapshotId()).isEqualTo(third.snapshotId());
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM address_registry_snapshots", Long.class)).isEqualTo(3);
            assertThat(jdbc.queryForObject("""
                    SELECT outcome FROM address_registry_import_runs WHERE id = ?
                    """, String.class, third.runId())).isEqualTo("SUCCEEDED");
            assertThat(jdbc.queryForObject("""
                    SELECT error_code FROM address_registry_retention_jobs WHERE import_run_id = ?
                    """, String.class, third.runId())).isEqualTo("RETENTION_FAILED");
        } finally {
            jdbc.execute("DROP TRIGGER fail_address_registry_retention ON address_registry_snapshots");
            jdbc.execute("DROP FUNCTION fail_address_registry_retention()");
        }
    }

    private AddressRegistryImportProperties properties(Path gpkg, int retainedSnapshots) throws Exception {
        AddressRegistryImportProperties properties = new AddressRegistryImportProperties();
        properties.setSourceUri(gpkg.toUri());
        properties.setCanonicalUrl(AddressRegistryImportProperties.OFFICIAL_RESOURCE_URL);
        properties.setSourceDate(LocalDate.of(2026, 8, 21));
        properties.setExpectedSha256(AddressRegistryArtifactStager.sha256(gpkg));
        properties.setMinimumRows(1);
        properties.setMaximumRows(10);
        properties.setMinimumActiveFraction(0.5);
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
