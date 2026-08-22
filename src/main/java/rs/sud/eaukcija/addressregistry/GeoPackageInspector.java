package rs.sud.eaukcija.addressregistry;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

/** Strictly validates the published GeoPackage contract before PostgreSQL is touched. */
@Component
final class GeoPackageInspector {

    static final String EXPECTED_TABLE = "kucni_broj";
    static final int EXPECTED_SRID = 25834;
    static final Set<String> REQUIRED_COLUMNS = Set.of(
            "fid", "geom", "kucni_broj_id", "kucni_broj", "kucni_broj_lat",
            "vrsta_stanja", "vrsta_stanja_lat", "created", "modificationdate", "retired",
            "tip", "tip_lat", "ulica_maticni_broj", "ulica_ime", "ulica_ime_lat",
            "broj_parcele", "broj_dela_parcele", "ko_maticni_broj", "kat_opstina_ime",
            "kat_opstina_ime_lat", "naselje_maticni_broj", "naselje_ime", "naselje_ime_lat",
            "opstina_maticni_broj", "opstina_ime", "opstina_ime_lat", "primary_key");

    record Column(int position, String name, String type, boolean notNull, boolean primaryKey) {
    }

    record Schema(
            String table,
            String geometryColumn,
            String geometryType,
            int sourceSrid,
            int z,
            int m,
            long rowCount,
            String fingerprint,
            List<Column> columns) {
    }

    Schema inspect(Path gpkg, AddressRegistryImportProperties properties) {
        try (Connection connection = openReadOnly(gpkg)) {
            Map<String, Column> columns = readColumns(connection);
            List<String> missing = REQUIRED_COLUMNS.stream()
                    .filter(column -> !columns.containsKey(column))
                    .sorted()
                    .toList();
            if (!missing.isEmpty()) {
                throw new AddressRegistryImportException(
                        "SCHEMA_MISMATCH", "required GPKG columns are missing: " + String.join(", ", missing));
            }

            GeometryMetadata geometry = readGeometryMetadata(connection);
            if (!"geom".equals(geometry.column())
                    || !"POINT".equalsIgnoreCase(geometry.type()) || geometry.z() != 0 || geometry.m() != 0) {
                throw new AddressRegistryImportException(
                        "GEOMETRY_SCHEMA_MISMATCH",
                        "expected geom as a two-dimensional POINT, got " + geometry.column() + " / "
                                + geometry.type() + " with z=" + geometry.z() + " m=" + geometry.m());
            }
            if (geometry.srid() != EXPECTED_SRID) {
                throw new AddressRegistryImportException(
                        "CRS_MISMATCH",
                        "expected EPSG:" + EXPECTED_SRID + ", got EPSG:" + geometry.srid());
            }

            long rowCount = queryCount(connection);
            if (rowCount < properties.getMinimumRows() || rowCount > properties.getMaximumRows()) {
                throw new AddressRegistryImportException(
                        "ROW_COUNT_SANITY",
                        "GPKG row count " + rowCount + " is outside [" + properties.getMinimumRows()
                                + ", " + properties.getMaximumRows() + "]");
            }

            List<Column> ordered = new ArrayList<>(columns.values());
            String fingerprint = fingerprint(ordered, geometry);
            if (properties.getExpectedSchemaSha256() != null
                    && !fingerprint.equals(properties.getExpectedSchemaSha256())) {
                throw new AddressRegistryImportException(
                        "SCHEMA_FINGERPRINT_MISMATCH",
                        "schema SHA-256 " + fingerprint + " does not match expected "
                                + properties.getExpectedSchemaSha256());
            }
            return new Schema(
                    EXPECTED_TABLE,
                    geometry.column(),
                    geometry.type(),
                    geometry.srid(),
                    geometry.z(),
                    geometry.m(),
                    rowCount,
                    fingerprint,
                    List.copyOf(ordered));
        } catch (AddressRegistryImportException e) {
            throw e;
        } catch (SQLException e) {
            throw new AddressRegistryImportException(
                    "GPKG_READ", "could not inspect GeoPackage metadata", e);
        }
    }

    static Connection openReadOnly(Path gpkg) throws SQLException {
        String path = gpkg.toAbsolutePath().normalize().toString();
        // mode=ro is applied before opening the SQLite handle. Xerial rejects
        // changing the flag after connection establishment.
        return DriverManager.getConnection("jdbc:sqlite:file:" + path + "?mode=ro");
    }

    private static Map<String, Column> readColumns(Connection connection) throws SQLException {
        Map<String, Column> columns = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA table_info(\"" + EXPECTED_TABLE + "\")")) {
            while (result.next()) {
                Column column = new Column(
                        result.getInt("cid"),
                        result.getString("name"),
                        result.getString("type"),
                        result.getInt("notnull") != 0,
                        result.getInt("pk") != 0);
                columns.put(column.name(), column);
            }
        }
        if (columns.isEmpty()) {
            throw new AddressRegistryImportException(
                    "SCHEMA_MISMATCH", "required layer table " + EXPECTED_TABLE + " does not exist");
        }
        return columns;
    }

    private static GeometryMetadata readGeometryMetadata(Connection connection) throws SQLException {
        String sql = """
                SELECT column_name, geometry_type_name, srs_id, z, m
                FROM gpkg_geometry_columns
                WHERE table_name = 'kucni_broj'
                """;
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            if (!result.next()) {
                throw new AddressRegistryImportException(
                        "GEOMETRY_SCHEMA_MISMATCH", "kucni_broj has no GeoPackage geometry metadata");
            }
            GeometryMetadata metadata = new GeometryMetadata(
                    result.getString(1), result.getString(2), result.getInt(3), result.getInt(4), result.getInt(5));
            if (result.next()) {
                throw new AddressRegistryImportException(
                        "GEOMETRY_SCHEMA_MISMATCH", "kucni_broj has more than one geometry column");
            }
            return metadata;
        }
    }

    private static long queryCount(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM \"" + EXPECTED_TABLE + "\"")) {
            if (!result.next()) {
                throw new AddressRegistryImportException("ROW_COUNT_SANITY", "could not count GPKG rows");
            }
            return result.getLong(1);
        }
    }

    private static String fingerprint(List<Column> columns, GeometryMetadata geometry) {
        StringBuilder canonical = new StringBuilder("address-registry-schema-v1\n")
                .append("table=").append(EXPECTED_TABLE).append('\n')
                .append("geometry=").append(geometry.column()).append('|')
                .append(geometry.type()).append('|').append(geometry.srid()).append('|')
                .append(geometry.z()).append('|').append(geometry.m()).append('\n');
        for (Column column : columns) {
            canonical.append(column.position()).append('|')
                    .append(column.name()).append('|')
                    .append(column.type() == null ? "" : column.type().toUpperCase()).append('|')
                    .append(column.notNull()).append('|')
                    .append(column.primaryKey()).append('\n');
        }
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private record GeometryMetadata(String column, String type, int srid, int z, int m) {
    }
}
