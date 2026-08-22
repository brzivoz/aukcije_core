package rs.sud.eaukcija.addressregistry;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Streaming, fail-closed, atomic importer for official Address Registry snapshots. */
@Service
@Profile("!local-h2")
public class AddressRegistryImporter {

    private static final Logger log = LoggerFactory.getLogger(AddressRegistryImporter.class);
    private static final long IMPORT_ADVISORY_LOCK = 220_258_344L;

    private static final String POINT_INSERT = """
            INSERT INTO address_registry_points (
              snapshot_id, source_fid, source_primary_key,
              house_number_id, house_number, house_number_latin, house_number_normalized,
              status_name, status_name_latin, source_created, source_modified,
              type_name, type_name_latin,
              street_id, street_name, street_name_latin, street_name_normalized,
              parcel_number, parcel_number_normalized, parcel_part,
              ko_id, ko_name, ko_name_latin, ko_name_normalized,
              settlement_id, settlement_name, settlement_name_latin, settlement_name_normalized,
              municipality_id, municipality_name, municipality_name_latin, municipality_name_normalized,
              location
            ) VALUES (
              ?, ?, ?,
              ?, ?, ?, ?,
              ?, ?, ?, ?,
              ?, ?,
              ?, ?, ?, ?,
              ?, ?, ?,
              ?, ?, ?, ?,
              ?, ?, ?, ?,
              ?, ?, ?, ?,
              ST_Transform(ST_SetSRID(ST_MakePoint(?, ?), 25834), 4326)
            )
            """;

    private static final String SOURCE_SELECT = """
            SELECT fid, geom, kucni_broj_id, kucni_broj, kucni_broj_lat,
                   vrsta_stanja, vrsta_stanja_lat, created, modificationdate, retired,
                   tip, tip_lat, ulica_maticni_broj, ulica_ime, ulica_ime_lat,
                   broj_parcele, broj_dela_parcele, ko_maticni_broj,
                   kat_opstina_ime, kat_opstina_ime_lat,
                   naselje_maticni_broj, naselje_ime, naselje_ime_lat,
                   opstina_maticni_broj, opstina_ime, opstina_ime_lat, primary_key
            FROM kucni_broj
            ORDER BY fid
            """;

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final AddressRegistryArtifactStager stager;
    private final GeoPackageInspector inspector;

    public AddressRegistryImporter(
            DataSource dataSource,
            PlatformTransactionManager transactionManager,
            AddressRegistryArtifactStager stager,
            GeoPackageInspector inspector) {
        this.dataSource = dataSource;
        this.jdbc = new JdbcTemplate(dataSource);
        this.transactions = new TransactionTemplate(transactionManager);
        this.stager = stager;
        this.inspector = inspector;
    }

    public ImportResult importSnapshot(AddressRegistryImportProperties properties) {
        properties.validateForImport();
        UUID runId = UUID.randomUUID();
        Instant started = Instant.now();
        startRun(runId, "IMPORT", properties);
        AddressRegistryArtifactStager.Artifact artifact = null;
        long validationMillis = 0;
        try {
            artifact = stager.stage(properties);
            Instant validationStarted = Instant.now();
            GeoPackageInspector.Schema schema = inspector.inspect(artifact.gpkg(), properties);
            validationMillis = Duration.between(validationStarted, Instant.now()).toMillis();
            AddressRegistryArtifactStager.Artifact validatedArtifact = artifact;
            long completedValidationMillis = validationMillis;

            ImportResult result = transactions.execute(status -> {
                ImportResult imported = importValidated(
                        runId, properties, validatedArtifact, schema, started, completedValidationMillis);
                finishSuccessfulRun(imported);
                return imported;
            });
            if (result == null) {
                throw new AddressRegistryImportException("IMPORT_FAILED", "transaction returned no import result");
            }
            if ("SUCCEEDED".equals(result.outcome())) {
                result = cleanupAfterPromotion(result, properties.getRetainedSnapshots());
            }
            return result;
        } catch (RuntimeException e) {
            AddressRegistryImportException failure = classify(e);
            finishFailedRun(runId, started, artifact, validationMillis, failure);
            throw failure;
        } finally {
            if (artifact != null) {
                artifact.close();
            }
        }
    }

    public ImportResult rollback() {
        UUID runId = UUID.randomUUID();
        Instant started = Instant.now();
        startRun(runId, "ROLLBACK", null);
        try {
            ImportResult result = transactions.execute(status -> {
                acquireImportLock();
                ActivePointer pointer = activePointer(true);
                if (pointer == null || pointer.previousSnapshotId() == null) {
                    throw new AddressRegistryImportException(
                            "NO_ROLLBACK_SNAPSHOT", "no previous good Address Registry snapshot is retained");
                }
                jdbc.update("""
                        UPDATE address_registry_active_snapshot
                        SET snapshot_id = previous_snapshot_id,
                            previous_snapshot_id = ?,
                            activated_at = CURRENT_TIMESTAMP
                        WHERE singleton = TRUE
                        """, pointer.snapshotId());
                SnapshotSummary active = snapshot(pointer.previousSnapshotId());
                ImportResult rolledBack = result(
                        "ROLLED_BACK", runId, active, pointer.snapshotId(), 0, 0, 0, 0,
                        0, Duration.between(started, Instant.now()).toMillis(), retainedSnapshotCount());
                finishSuccessfulRun(rolledBack);
                return rolledBack;
            });
            return result;
        } catch (RuntimeException e) {
            AddressRegistryImportException failure = classify(e);
            finishFailedRun(runId, started, null, 0, failure);
            throw failure;
        }
    }

    public Status status() {
        ActivePointer pointer = activePointer(false);
        if (pointer == null) {
            return new Status(null, null, null, null, 0, 0, retainedSnapshotCount());
        }
        SnapshotSummary current = snapshot(pointer.snapshotId());
        SnapshotSummary previous = pointer.previousSnapshotId() == null ? null : snapshot(pointer.previousSnapshotId());
        return new Status(
                current.id(),
                current.sourceDate().toString(),
                current.gpkgSha256(),
                previous == null ? null : previous.id(),
                current.importedRows(),
                centroidCount(current.id()),
                retainedSnapshotCount());
    }

    private ImportResult importValidated(
            UUID runId,
            AddressRegistryImportProperties properties,
            AddressRegistryArtifactStager.Artifact artifact,
            GeoPackageInspector.Schema schema,
            Instant started,
            long validationMillis) {
        acquireImportLock();

        UUID existingId = jdbc.query(
                "SELECT id FROM address_registry_snapshots WHERE gpkg_sha256 = ?",
                result -> result.next() ? result.getObject(1, UUID.class) : null,
                artifact.gpkgSha256());
        if (existingId != null) {
            ActivePointer pointer = activePointer(true);
            if (pointer != null && existingId.equals(pointer.snapshotId())) {
                SnapshotSummary existing = snapshot(existingId);
                return result(
                        "UNCHANGED", runId, existing, pointer.previousSnapshotId(), artifact.downloadMillis(),
                        validationMillis, 0, 0, 0, Duration.between(started, Instant.now()).toMillis(),
                        retainedSnapshotCount());
            }
            throw new AddressRegistryImportException(
                    "SNAPSHOT_ALREADY_RETAINED",
                    "this GPKG is already retained but is not active; use the explicit rollback action instead");
        }

        UUID snapshotId = UUID.randomUUID();
        insertPlaceholderSnapshot(snapshotId, properties, artifact, schema);

        Instant loadStarted = Instant.now();
        SourceCounts counts = streamPoints(artifact.gpkg(), snapshotId, schema.rowCount(), properties.getBatchSize());
        long loadMillis = Duration.between(loadStarted, Instant.now()).toMillis();
        validateLoadedSnapshot(snapshotId, schema.rowCount(), counts, properties.getMinimumActiveFraction());
        long duplicateIdentities = duplicateParcelIdentityCount(snapshotId);
        long unnormalizedParcels = unnormalizedParcelRowCount(snapshotId);

        Instant centroidStarted = Instant.now();
        validateOfficialNameConsistency(snapshotId);
        long ambiguousParents = ambiguousParentIdentityCount(snapshotId);
        buildCentroids(snapshotId);
        long centroidMillis = Duration.between(centroidStarted, Instant.now()).toMillis();

        jdbc.update("""
                UPDATE address_registry_snapshots
                SET imported_row_count = ?, active_source_row_count = ?,
                    inactive_source_row_count = ?, retired_source_row_count = ?,
                    duplicate_parcel_identities = ?, unnormalized_parcel_rows = ?,
                    ambiguous_parent_identities = ?
                WHERE id = ?
                """,
                counts.active(), counts.active(), counts.inactive(), counts.retired(),
                duplicateIdentities, unnormalizedParcels, ambiguousParents, snapshotId);

        ActivePointer pointer = activePointer(true);
        UUID previous = pointer == null ? null : pointer.snapshotId();
        if (pointer == null) {
            jdbc.update("""
                    INSERT INTO address_registry_active_snapshot (
                      singleton, snapshot_id, previous_snapshot_id, activated_at
                    ) VALUES (TRUE, ?, NULL, CURRENT_TIMESTAMP)
                    """, snapshotId);
        } else {
            jdbc.update("""
                    UPDATE address_registry_active_snapshot
                    SET previous_snapshot_id = snapshot_id,
                        snapshot_id = ?, activated_at = CURRENT_TIMESTAMP
                    WHERE singleton = TRUE
                    """, snapshotId);
        }

        SnapshotSummary summary = snapshot(snapshotId);
        return result(
                "SUCCEEDED", runId, summary, previous, artifact.downloadMillis(), validationMillis,
                loadMillis, centroidMillis, 0, Duration.between(started, Instant.now()).toMillis(),
                retainedSnapshotCount());
    }

    private SourceCounts streamPoints(Path gpkg, UUID snapshotId, long expectedRows, int batchSize) {
        long seen = 0;
        long active = 0;
        long retired = 0;
        Connection postgres = DataSourceUtils.getConnection(dataSource);
        try (Connection sqlite = GeoPackageInspector.openReadOnly(gpkg);
             PreparedStatement source = sqlite.prepareStatement(SOURCE_SELECT);
             PreparedStatement target = postgres.prepareStatement(POINT_INSERT)) {
            source.setFetchSize(batchSize);
            try (ResultSet rows = source.executeQuery()) {
                int pending = 0;
                while (rows.next()) {
                    seen++;
                    GeoPackagePointReader.Point point = GeoPackagePointReader.read(rows, "geom");
                    String statusCyrillic = text(rows, "vrsta_stanja");
                    String statusLatin = text(rows, "vrsta_stanja_lat");
                    String retiredValue = text(rows, "retired");
                    if (retiredValue != null) {
                        retired++;
                    }
                    if (!AddressRegistryNormalizer.isActive(statusCyrillic, statusLatin, retiredValue)) {
                        continue;
                    }
                    bindPoint(target, snapshotId, rows, point, statusCyrillic, statusLatin);
                    target.addBatch();
                    pending++;
                    active++;
                    if (pending >= batchSize) {
                        target.executeBatch();
                        pending = 0;
                    }
                    if (seen % 100_000 == 0) {
                        log.info("Address Registry import progress: {}/{} source rows, {} active rows", seen, expectedRows, active);
                    }
                }
                if (pending > 0) {
                    target.executeBatch();
                }
            }
        } catch (AddressRegistryImportException e) {
            throw e;
        } catch (SQLException e) {
            throw new AddressRegistryImportException("DATABASE_IMPORT", "could not stream GPKG rows into PostGIS", e);
        } finally {
            DataSourceUtils.releaseConnection(postgres, dataSource);
        }
        if (seen != expectedRows) {
            throw new AddressRegistryImportException(
                    "ROW_COUNT_CHANGED",
                    "GPKG changed while being read: inspected " + expectedRows + " rows but streamed " + seen);
        }
        return new SourceCounts(seen, active, seen - active, retired);
    }

    private static void bindPoint(
            PreparedStatement target,
            UUID snapshotId,
            ResultSet row,
            GeoPackagePointReader.Point point,
            String statusCyrillic,
            String statusLatin) throws SQLException {
        long sourceFid = requiredLong(row, "fid");
        long sourcePrimaryKey = requiredLong(row, "primary_key");
        String koId = required(row, "ko_maticni_broj");
        String koName = required(row, "kat_opstina_ime");
        String settlementId = required(row, "naselje_maticni_broj");
        String settlementName = required(row, "naselje_ime");
        String municipalityId = required(row, "opstina_maticni_broj");
        String municipalityName = required(row, "opstina_ime");
        String koLatin = text(row, "kat_opstina_ime_lat");
        String settlementLatin = text(row, "naselje_ime_lat");
        String municipalityLatin = text(row, "opstina_ime_lat");
        String houseNumber = text(row, "kucni_broj");
        String houseNumberLatin = text(row, "kucni_broj_lat");
        String street = text(row, "ulica_ime");
        String streetLatin = text(row, "ulica_ime_lat");
        String parcel = text(row, "broj_parcele");

        int index = 1;
        target.setObject(index++, snapshotId);
        target.setLong(index++, sourceFid);
        target.setLong(index++, sourcePrimaryKey);
        nullable(target, index++, text(row, "kucni_broj_id"));
        nullable(target, index++, houseNumber);
        nullable(target, index++, houseNumberLatin);
        nullable(target, index++, AddressRegistryNormalizer.houseNumber(
                houseNumberLatin == null ? houseNumber : houseNumberLatin));
        nullable(target, index++, statusCyrillic);
        nullable(target, index++, statusLatin);
        nullable(target, index++, text(row, "created"));
        nullable(target, index++, text(row, "modificationdate"));
        nullable(target, index++, text(row, "tip"));
        nullable(target, index++, text(row, "tip_lat"));
        nullable(target, index++, text(row, "ulica_maticni_broj"));
        nullable(target, index++, street);
        nullable(target, index++, streetLatin);
        nullable(target, index++, AddressRegistryNormalizer.name(streetLatin == null ? street : streetLatin));
        nullable(target, index++, parcel);
        nullable(target, index++, AddressRegistryNormalizer.parcel(parcel));
        nullable(target, index++, text(row, "broj_dela_parcele"));
        target.setString(index++, koId);
        target.setString(index++, koName);
        nullable(target, index++, koLatin);
        target.setString(index++, requiredNormalized(koLatin == null ? koName : koLatin, "kat_opstina_ime"));
        target.setString(index++, settlementId);
        target.setString(index++, settlementName);
        nullable(target, index++, settlementLatin);
        target.setString(index++, requiredNormalized(
                settlementLatin == null ? settlementName : settlementLatin, "naselje_ime"));
        target.setString(index++, municipalityId);
        target.setString(index++, municipalityName);
        nullable(target, index++, municipalityLatin);
        target.setString(index++, requiredNormalized(
                municipalityLatin == null ? municipalityName : municipalityLatin, "opstina_ime"));
        target.setDouble(index++, point.easting());
        target.setDouble(index, point.northing());
    }

    private void validateLoadedSnapshot(
            UUID snapshotId,
            long expectedRows,
            SourceCounts counts,
            double minimumActiveFraction) {
        if (counts.seen() != expectedRows || counts.active() + counts.inactive() != expectedRows) {
            throw new AddressRegistryImportException("ROW_ACCOUNTING", "source row accounting is inconsistent");
        }
        long minimumActiveRows = Math.max(1, (long) Math.ceil(expectedRows * minimumActiveFraction));
        if (counts.active() < minimumActiveRows) {
            throw new AddressRegistryImportException(
                    "ACTIVE_ROW_COUNT_SANITY",
                    "only " + counts.active() + " of " + expectedRows + " source rows are active; at least "
                            + minimumActiveRows + " (minimum-active-fraction=" + minimumActiveFraction
                            + ") are required before promotion");
        }
        Long loaded = jdbc.queryForObject(
                "SELECT COUNT(*) FROM address_registry_points WHERE snapshot_id = ?", Long.class, snapshotId);
        if (loaded == null || loaded != counts.active()) {
            throw new AddressRegistryImportException(
                    "ROW_ACCOUNTING", "expected " + counts.active() + " imported active rows, got " + loaded);
        }
        Long outside = jdbc.queryForObject("""
                SELECT COUNT(*) FROM address_registry_points
                WHERE snapshot_id = ?
                  AND NOT ST_CoveredBy(location, ST_MakeEnvelope(18.0, 41.5, 23.5, 46.5, 4326))
                """, Long.class, snapshotId);
        if (outside != null && outside > 0) {
            throw new AddressRegistryImportException(
                    "GEOMETRY_OUTSIDE_SERBIA", outside + " transformed points fall outside the Serbia sanity envelope");
        }
    }

    private long duplicateParcelIdentityCount(UUID snapshotId) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM (
                  SELECT 1
                  FROM address_registry_points
                  WHERE snapshot_id = ? AND parcel_number_normalized IS NOT NULL
                  GROUP BY ko_id, parcel_number_normalized
                  HAVING COUNT(*) > 1
                ) duplicates
                """, Long.class, snapshotId);
        return count == null ? 0 : count;
    }

    private long unnormalizedParcelRowCount(UUID snapshotId) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM address_registry_points
                WHERE snapshot_id = ?
                  AND parcel_number IS NOT NULL
                  AND parcel_number_normalized IS NULL
                """, Long.class, snapshotId);
        return count == null ? 0 : count;
    }

    private void validateOfficialNameConsistency(UUID snapshotId) {
        long conflicts = identityNameConflicts(snapshotId, "ko_id", "ko_name", "ko_name_latin")
                + identityNameConflicts(snapshotId, "settlement_id", "settlement_name", "settlement_name_latin")
                + identityNameConflicts(snapshotId, "municipality_id", "municipality_name", "municipality_name_latin");
        if (conflicts > 0) {
            throw new AddressRegistryImportException(
                    "IDENTIFIER_NAME_CONFLICT", conflicts + " official identifiers map to conflicting names");
        }
    }

    private long identityNameConflicts(UUID snapshotId, String id, String name, String latin) {
        String identity = "COALESCE(" + name + ", '') || '|' || COALESCE(" + latin + ", '')";
        String sql = "SELECT COUNT(*) FROM (SELECT " + id
                + " FROM address_registry_points WHERE snapshot_id = ? GROUP BY " + id
                + " HAVING COUNT(DISTINCT " + identity + ") > 1) conflicts";
        Long count = jdbc.queryForObject(sql, Long.class, snapshotId);
        return count == null ? 0 : count;
    }

    private long ambiguousParentIdentityCount(UUID snapshotId) {
        return parentConflicts(snapshotId, "ko_id") + parentConflicts(snapshotId, "settlement_id");
    }

    private long parentConflicts(UUID snapshotId, String id) {
        String sql = "SELECT COUNT(*) FROM (SELECT " + id
                + " FROM address_registry_points WHERE snapshot_id = ? GROUP BY " + id
                + " HAVING COUNT(DISTINCT municipality_id) > 1) conflicts";
        Long count = jdbc.queryForObject(sql, Long.class, snapshotId);
        return count == null ? 0 : count;
    }

    private void buildCentroids(UUID snapshotId) {
        insertCentroids(snapshotId, "KO", "ko_id", "ko_name", "ko_name_latin", "ko_name_normalized", "municipality_id");
        insertCentroids(snapshotId, "SETTLEMENT", "settlement_id", "settlement_name", "settlement_name_latin",
                "settlement_name_normalized", "municipality_id");
        insertCentroids(snapshotId, "MUNICIPALITY", "municipality_id", "municipality_name", "municipality_name_latin",
                "municipality_name_normalized", null);
    }

    private void insertCentroids(
            UUID snapshotId,
            String level,
            String id,
            String name,
            String latin,
            String normalized,
            String municipalityId) {
        String municipality = municipalityId == null
                ? "NULL"
                : "CASE WHEN COUNT(DISTINCT " + municipalityId + ") = 1 THEN MIN(" + municipalityId + ") ELSE NULL END";
        String parentVariants = municipalityId == null ? "0" : "COUNT(DISTINCT " + municipalityId + ")";
        String sql = """
                INSERT INTO address_registry_centroids (
                  snapshot_id, level, official_id, name, name_latin,
                  name_normalized, municipality_id, parent_variant_count,
                  member_point_count, location
                )
                SELECT ?, ?, %s, MIN(%s), MIN(%s), MIN(%s), %s, %s, COUNT(*),
                       ST_SetSRID(ST_MakePoint(AVG(ST_X(location)), AVG(ST_Y(location))), 4326)::geometry(Point, 4326)
                FROM address_registry_points
                WHERE snapshot_id = ?
                GROUP BY %s
                """.formatted(id, name, latin, normalized, municipality, parentVariants, id);
        jdbc.update(sql, snapshotId, level, snapshotId);
    }

    private void insertPlaceholderSnapshot(
            UUID snapshotId,
            AddressRegistryImportProperties properties,
            AddressRegistryArtifactStager.Artifact artifact,
            GeoPackageInspector.Schema schema) {
        jdbc.update("""
                INSERT INTO address_registry_snapshots (
                  id, canonical_url, download_uri, downloaded_at, source_date,
                  source_bytes, source_sha256, archive_member, gpkg_bytes, gpkg_sha256,
                  schema_sha256, source_table, geometry_column, source_crs, target_crs,
                  source_row_count, imported_row_count, active_source_row_count,
                  inactive_source_row_count, retired_source_row_count, rejected_row_count,
                  duplicate_parcel_identities, unnormalized_parcel_rows,
                  ambiguous_parent_identities
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 4326, ?, 0, 0, ?, 0, 0, 0, 0, 0)
                """,
                snapshotId,
                properties.getCanonicalUrl(),
                properties.getSourceUri().toString(),
                Timestamp.from(artifact.downloadedAt()),
                properties.getSourceDate(),
                artifact.sourceBytes(),
                artifact.sourceSha256(),
                artifact.archiveMember(),
                artifact.gpkgBytes(),
                artifact.gpkgSha256(),
                schema.fingerprint(),
                schema.table(),
                schema.geometryColumn(),
                schema.sourceSrid(),
                schema.rowCount(),
                schema.rowCount());
    }

    private ImportResult cleanupAfterPromotion(ImportResult imported, int retainedSnapshots) {
        Instant started = Instant.now();
        try {
            RetentionResult cleanup = transactions.execute(status -> {
                acquireImportLock();
                cleanupRetainedSnapshots(retainedSnapshots);
                long retentionMillis = Duration.between(started, Instant.now()).toMillis();
                long totalMillis = imported.totalMillis() + retentionMillis;
                jdbc.update("""
                        UPDATE address_registry_import_runs
                        SET retention_millis = ?, total_millis = ?
                        WHERE id = ?
                        """, retentionMillis, totalMillis, imported.runId());
                return new RetentionResult(retainedSnapshotCount(), retentionMillis);
            });
            if (cleanup == null) {
                log.warn("Address Registry snapshot {} was promoted, but retention returned no result", imported.snapshotId());
                return imported;
            }
            return withRetention(imported, cleanup.retainedSnapshots(), cleanup.retentionMillis());
        } catch (RuntimeException cleanupFailure) {
            log.warn(
                    "Address Registry snapshot {} was promoted, but post-commit retention failed; "
                            + "a later import or operator cleanup can retry it",
                    imported.snapshotId(), cleanupFailure);
            return imported;
        }
    }

    private void cleanupRetainedSnapshots(int retainedSnapshots) {
        ActivePointer pointer = activePointer(true);
        Set<UUID> keep = new LinkedHashSet<>();
        keep.add(pointer.snapshotId());
        if (pointer.previousSnapshotId() != null) {
            keep.add(pointer.previousSnapshotId());
        }
        List<UUID> snapshots = jdbc.query(
                "SELECT id FROM address_registry_snapshots ORDER BY imported_at DESC, id",
                (result, row) -> result.getObject(1, UUID.class));
        for (UUID candidate : snapshots) {
            if (keep.size() >= retainedSnapshots) {
                break;
            }
            keep.add(candidate);
        }
        for (UUID candidate : snapshots) {
            if (!keep.contains(candidate)) {
                jdbc.update("DELETE FROM address_registry_snapshots WHERE id = ?", candidate);
            }
        }
    }

    private ActivePointer activePointer(boolean lock) {
        String sql = "SELECT snapshot_id, previous_snapshot_id FROM address_registry_active_snapshot WHERE singleton = TRUE"
                + (lock ? " FOR UPDATE" : "");
        return jdbc.query(sql, result -> result.next()
                ? new ActivePointer(result.getObject(1, UUID.class), result.getObject(2, UUID.class))
                : null);
    }

    private void acquireImportLock() {
        // pg_advisory_xact_lock returns PostgreSQL void, so execute it as a
        // statement rather than asking Spring to coerce the empty value.
        jdbc.execute("SELECT pg_advisory_xact_lock(" + IMPORT_ADVISORY_LOCK + ")");
    }

    private SnapshotSummary snapshot(UUID id) {
        return jdbc.queryForObject("""
                SELECT id, source_date, source_sha256, gpkg_sha256, schema_sha256,
                       source_bytes, gpkg_bytes, source_row_count,
                       imported_row_count, inactive_source_row_count, retired_source_row_count,
                       duplicate_parcel_identities, unnormalized_parcel_rows,
                       ambiguous_parent_identities
                FROM address_registry_snapshots WHERE id = ?
                """, (result, row) -> new SnapshotSummary(
                        result.getObject("id", UUID.class),
                        result.getDate("source_date").toLocalDate(),
                        result.getString("source_sha256").trim(),
                        result.getString("gpkg_sha256").trim(),
                        result.getString("schema_sha256").trim(),
                        result.getLong("source_bytes"),
                        result.getLong("gpkg_bytes"),
                        result.getLong("source_row_count"),
                        result.getLong("imported_row_count"),
                        result.getLong("inactive_source_row_count"),
                        result.getLong("retired_source_row_count"),
                        result.getLong("duplicate_parcel_identities"),
                        result.getLong("unnormalized_parcel_rows"),
                        result.getLong("ambiguous_parent_identities")), id);
    }

    private long centroidCount(UUID snapshotId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM address_registry_centroids WHERE snapshot_id = ?", Long.class, snapshotId);
        return count == null ? 0 : count;
    }

    private int retainedSnapshotCount() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM address_registry_snapshots", Integer.class);
        return count == null ? 0 : count;
    }

    private ImportResult result(
            String outcome,
            UUID runId,
            SnapshotSummary snapshot,
            UUID previous,
            long downloadMillis,
            long validationMillis,
            long loadMillis,
            long centroidMillis,
            long retentionMillis,
            long totalMillis,
            int retainedSnapshots) {
        return new ImportResult(
                outcome, runId, snapshot.id(), previous, snapshot.sourceDate().toString(),
                snapshot.sourceSha256(), snapshot.gpkgSha256(), snapshot.schemaSha256(),
                snapshot.sourceBytes(), snapshot.gpkgBytes(), snapshot.sourceRows(),
                snapshot.importedRows(), snapshot.inactiveRows(), snapshot.retiredRows(),
                snapshot.duplicateParcelIdentities(), snapshot.unnormalizedParcelRows(),
                snapshot.ambiguousParentIdentities(),
                centroidCount(snapshot.id()), retainedSnapshots,
                downloadMillis, validationMillis, loadMillis, centroidMillis, retentionMillis, totalMillis);
    }

    private static ImportResult withRetention(
            ImportResult result,
            int retainedSnapshots,
            long retentionMillis) {
        return new ImportResult(
                result.outcome(), result.runId(), result.snapshotId(), result.previousSnapshotId(),
                result.sourceDate(), result.sourceSha256(), result.gpkgSha256(), result.schemaSha256(),
                result.sourceBytes(), result.gpkgBytes(), result.sourceRows(), result.importedRows(),
                result.inactiveRows(), result.retiredRows(), result.duplicateParcelIdentities(),
                result.unnormalizedParcelRows(), result.ambiguousParentIdentities(), result.centroidRows(),
                retainedSnapshots, result.downloadMillis(), result.validationMillis(), result.loadMillis(),
                result.centroidMillis(), retentionMillis, result.totalMillis() + retentionMillis);
    }

    private void startRun(UUID runId, String action, AddressRegistryImportProperties properties) {
        jdbc.update("""
                INSERT INTO address_registry_import_runs (
                  id, action, outcome, started_at, source_date, canonical_url
                ) VALUES (?, ?, 'RUNNING', CURRENT_TIMESTAMP, ?, ?)
                """,
                runId,
                action,
                properties == null ? null : properties.getSourceDate(),
                properties == null ? null : properties.getCanonicalUrl());
    }

    private void finishSuccessfulRun(ImportResult result) {
        jdbc.update("""
                UPDATE address_registry_import_runs
                SET outcome = ?, finished_at = CURRENT_TIMESTAMP,
                    snapshot_id = ?, previous_snapshot_id = ?, source_date = ?::date,
                    source_sha256 = ?, gpkg_sha256 = ?, source_bytes = ?, gpkg_bytes = ?,
                    source_row_count = ?, imported_row_count = ?,
                    inactive_source_row_count = ?, retired_source_row_count = ?,
                    duplicate_parcel_identities = ?, unnormalized_parcel_rows = ?,
                    ambiguous_parent_identities = ?,
                    download_millis = ?, validation_millis = ?, load_millis = ?,
                    centroid_millis = ?, retention_millis = ?, total_millis = ?
                WHERE id = ?
                """,
                "UNCHANGED".equals(result.outcome()) ? "UNCHANGED" : "SUCCEEDED",
                result.snapshotId(),
                result.previousSnapshotId(),
                result.sourceDate(),
                result.sourceSha256(),
                result.gpkgSha256(),
                result.sourceBytes(),
                result.gpkgBytes(),
                result.sourceRows(),
                result.importedRows(),
                result.inactiveRows(),
                result.retiredRows(),
                result.duplicateParcelIdentities(),
                result.unnormalizedParcelRows(),
                result.ambiguousParentIdentities(),
                result.downloadMillis(),
                result.validationMillis(),
                result.loadMillis(),
                result.centroidMillis(),
                result.retentionMillis(),
                result.totalMillis(),
                result.runId());
    }

    private void finishFailedRun(
            UUID runId,
            Instant started,
            AddressRegistryArtifactStager.Artifact artifact,
            long validationMillis,
            AddressRegistryImportException failure) {
        try {
            jdbc.update("""
                    UPDATE address_registry_import_runs
                    SET outcome = 'FAILED', finished_at = CURRENT_TIMESTAMP,
                        source_sha256 = ?, gpkg_sha256 = ?, source_bytes = ?, gpkg_bytes = ?,
                        download_millis = ?, validation_millis = ?, total_millis = ?,
                        error_code = ?, error_message = ?
                    WHERE id = ?
                    """,
                    artifact == null ? null : artifact.sourceSha256(),
                    artifact == null ? null : artifact.gpkgSha256(),
                    artifact == null ? null : artifact.sourceBytes(),
                    artifact == null ? null : artifact.gpkgBytes(),
                    artifact == null ? null : artifact.downloadMillis(),
                    validationMillis,
                    Duration.between(started, Instant.now()).toMillis(),
                    failure.code(),
                    truncate(failure.getMessage(), 2000),
                    runId);
        } catch (DataAccessException loggingFailure) {
            failure.addSuppressed(loggingFailure);
        }
    }

    private static AddressRegistryImportException classify(RuntimeException failure) {
        Throwable cursor = failure;
        while (cursor != null) {
            if (cursor instanceof AddressRegistryImportException importFailure) {
                return importFailure;
            }
            cursor = cursor.getCause();
        }
        return new AddressRegistryImportException("IMPORT_FAILED", "Address Registry import failed", failure);
    }

    private static String truncate(String value, int limit) {
        if (value == null || value.length() <= limit) {
            return value;
        }
        return value.substring(0, limit);
    }

    private static String text(ResultSet row, String column) throws SQLException {
        String value = row.getString(column);
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String required(ResultSet row, String column) throws SQLException {
        String value = text(row, column);
        if (value == null) {
            throw new AddressRegistryImportException("REQUIRED_VALUE_MISSING", column + " is null or blank");
        }
        return value;
    }

    private static String requiredNormalized(String value, String column) {
        String normalized = AddressRegistryNormalizer.name(value);
        if (normalized == null) {
            throw new AddressRegistryImportException(
                    "REQUIRED_VALUE_MISSING", column + " is present but normalizes to blank");
        }
        return normalized;
    }

    private static long requiredLong(ResultSet row, String column) throws SQLException {
        long value = row.getLong(column);
        if (row.wasNull()) {
            throw new AddressRegistryImportException("REQUIRED_VALUE_MISSING", column + " is null");
        }
        return value;
    }

    private static void nullable(PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    private record SourceCounts(long seen, long active, long inactive, long retired) {
    }

    private record ActivePointer(UUID snapshotId, UUID previousSnapshotId) {
    }

    private record RetentionResult(int retainedSnapshots, long retentionMillis) {
    }

    private record SnapshotSummary(
            UUID id,
            java.time.LocalDate sourceDate,
            String sourceSha256,
            String gpkgSha256,
            String schemaSha256,
            long sourceBytes,
            long gpkgBytes,
            long sourceRows,
            long importedRows,
            long inactiveRows,
            long retiredRows,
            long duplicateParcelIdentities,
            long unnormalizedParcelRows,
            long ambiguousParentIdentities) {
    }

    public record ImportResult(
            String outcome,
            UUID runId,
            UUID snapshotId,
            UUID previousSnapshotId,
            String sourceDate,
            String sourceSha256,
            String gpkgSha256,
            String schemaSha256,
            long sourceBytes,
            long gpkgBytes,
            long sourceRows,
            long importedRows,
            long inactiveRows,
            long retiredRows,
            long duplicateParcelIdentities,
            long unnormalizedParcelRows,
            long ambiguousParentIdentities,
            long centroidRows,
            int retainedSnapshots,
            long downloadMillis,
            long validationMillis,
            long loadMillis,
            long centroidMillis,
            long retentionMillis,
            long totalMillis) {
    }

    public record Status(
            UUID activeSnapshotId,
            String sourceDate,
            String gpkgSha256,
            UUID previousSnapshotId,
            long importedRows,
            long centroidRows,
            int retainedSnapshots) {
    }
}
