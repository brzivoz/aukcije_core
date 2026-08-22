package rs.sud.eaukcija.addressregistry;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import rs.sud.eaukcija.testsupport.Fixtures;

/** Builds a tiny deterministic GeoPackage from the committed JSON fixture. */
final class AddressRegistryGpkgFixture {

    enum Fault {
        NONE,
        MISSING_REQUIRED_COLUMN,
        WRONG_CRS,
        DUPLICATE_PRIMARY_KEY,
        INVALID_GEOMETRY,
        PARENT_CONFLICT,
        OUTSIDE_SERBIA
    }

    private AddressRegistryGpkgFixture() {
    }

    static Path create(Path directory, int variant, Fault fault) throws Exception {
        Files.createDirectories(directory);
        Path gpkg = directory.resolve("address-registry-" + variant + "-" + fault + ".gpkg");
        Files.deleteIfExists(gpkg);
        int srid = fault == Fault.WRONG_CRS ? 32634 : 25834;
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + gpkg)) {
            createMetadata(connection, srid);
            createSourceTable(connection, fault == Fault.MISSING_REQUIRED_COLUMN);
            insertRows(connection, variant, fault, srid);
        }
        return gpkg;
    }

    private static void createMetadata(Connection connection, int srid) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE gpkg_contents (
                      table_name TEXT NOT NULL PRIMARY KEY, data_type TEXT NOT NULL,
                      identifier TEXT UNIQUE, description TEXT DEFAULT '',
                      last_change DATETIME NOT NULL, min_x DOUBLE, min_y DOUBLE,
                      max_x DOUBLE, max_y DOUBLE, srs_id INTEGER
                    )
                    """);
            statement.execute("""
                    CREATE TABLE gpkg_geometry_columns (
                      table_name TEXT NOT NULL, column_name TEXT NOT NULL,
                      geometry_type_name TEXT NOT NULL, srs_id INTEGER NOT NULL,
                      z TINYINT NOT NULL, m TINYINT NOT NULL
                    )
                    """);
            statement.execute("INSERT INTO gpkg_contents VALUES "
                    + "('kucni_broj','features','kucni_broj','fixture','2026-08-22T00:00:00Z',NULL,NULL,NULL,NULL,"
                    + srid + ")");
            statement.execute("INSERT INTO gpkg_geometry_columns VALUES "
                    + "('kucni_broj','geom','POINT'," + srid + ",0,0)");
        }
    }

    private static void createSourceTable(Connection connection, boolean omitParcel) throws Exception {
        String parcelColumn = omitParcel ? "" : "broj_parcele TEXT,";
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE kucni_broj (
                      fid INTEGER PRIMARY KEY NOT NULL,
                      geom POINT,
                      kucni_broj_id MEDIUMINT,
                      kucni_broj TEXT,
                      kucni_broj_lat TEXT,
                      vrsta_stanja TEXT,
                      vrsta_stanja_lat TEXT,
                      created DATE,
                      modificationdate DATETIME,
                      retired DATE,
                      tip TEXT,
                      tip_lat TEXT,
                      ulica_maticni_broj TEXT,
                      ulica_ime TEXT,
                      ulica_ime_lat TEXT,
                      %s
                      broj_dela_parcele TEXT,
                      ko_maticni_broj INTEGER,
                      kat_opstina_ime TEXT,
                      kat_opstina_ime_lat TEXT,
                      naselje_maticni_broj INTEGER,
                      naselje_ime TEXT,
                      naselje_ime_lat TEXT,
                      opstina_maticni_broj INTEGER,
                      opstina_ime TEXT,
                      opstina_ime_lat TEXT,
                      primary_key MEDIUMINT NOT NULL,
                      wkt TEXT
                    )
                    """.formatted(parcelColumn));
        }
    }

    private static void insertRows(Connection connection, int variant, Fault fault, int srid) throws Exception {
        JsonNode rows = new ObjectMapper().readTree(Fixtures.read("address-registry/points.json")).get("rows");
        boolean omitParcel = fault == Fault.MISSING_REQUIRED_COLUMN;
        String columns = """
                fid, geom, kucni_broj_id, kucni_broj, kucni_broj_lat,
                vrsta_stanja, vrsta_stanja_lat, created, modificationdate, retired,
                tip, tip_lat, ulica_maticni_broj, ulica_ime, ulica_ime_lat,
                %s broj_dela_parcele, ko_maticni_broj, kat_opstina_ime, kat_opstina_ime_lat,
                naselje_maticni_broj, naselje_ime, naselje_ime_lat,
                opstina_maticni_broj, opstina_ime, opstina_ime_lat, primary_key, wkt
                """.formatted(omitParcel ? "" : "broj_parcele,");
        int parameterCount = omitParcel ? 27 : 28;
        String placeholders = String.join(",", java.util.Collections.nCopies(parameterCount, "?"));
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO kucni_broj (" + columns + ") VALUES (" + placeholders + ")")) {
            int rowNumber = 0;
            for (JsonNode row : rows) {
                rowNumber++;
                int index = 1;
                long fid = row.get("fid").asLong();
                long primaryKey = row.get("primaryKey").asLong();
                if (fault == Fault.DUPLICATE_PRIMARY_KEY && rowNumber == 2) {
                    primaryKey = rows.get(0).get("primaryKey").asLong();
                }
                double easting = row.get("easting").asDouble();
                double northing = row.get("northing").asDouble();
                if (fault == Fault.OUTSIDE_SERBIA && rowNumber == 2) {
                    easting = 100_000;
                    northing = 1_000_000;
                }
                insert.setLong(index++, fid);
                insert.setBytes(index++, fault == Fault.INVALID_GEOMETRY && rowNumber == 2
                        ? new byte[] {0, 1, 2, 3}
                        : point(srid, easting, northing));
                insert.setString(index++, row.get("houseNumberId").asText());
                String variantSuffix = rowNumber == 1 && variant > 0 ? "-" + variant : "";
                insert.setString(index++, row.get("houseNumber").asText() + variantSuffix);
                insert.setString(index++, row.get("houseNumberLatin").asText() + variantSuffix);
                insert.setString(index++, row.get("status").asText());
                insert.setString(index++, row.get("statusLatin").asText());
                insert.setString(index++, "2026-08-01");
                insert.setString(index++, "2026-08-21T10:15:30Z");
                if (row.get("retired").isNull()) {
                    insert.setNull(index++, java.sql.Types.VARCHAR);
                } else {
                    insert.setString(index++, row.get("retired").asText());
                }
                insert.setString(index++, "КУЋНИ БРОЈ");
                insert.setString(index++, "KUĆNI BROJ");
                insert.setString(index++, row.get("streetId").asText());
                insert.setString(index++, row.get("street").asText());
                insert.setString(index++, row.get("streetLatin").asText());
                if (!omitParcel) {
                    insert.setString(index++, row.get("parcel").asText());
                }
                insert.setString(index++, row.get("parcelPart").asText());
                JsonNode koIdentity = fault == Fault.PARENT_CONFLICT && rowNumber == 2 ? rows.get(0) : row;
                insert.setString(index++, koIdentity.get("koId").asText());
                insert.setString(index++, koIdentity.get("ko").asText());
                insert.setString(index++, koIdentity.get("koLatin").asText());
                insert.setString(index++, row.get("settlementId").asText());
                insert.setString(index++, row.get("settlement").asText());
                insert.setString(index++, row.get("settlementLatin").asText());
                insert.setString(index++, row.get("municipalityId").asText());
                insert.setString(index++, row.get("municipality").asText());
                insert.setString(index++, row.get("municipalityLatin").asText());
                insert.setLong(index++, primaryKey);
                insert.setString(index, "POINT (" + easting + " " + northing + ")");
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private static byte[] point(int srid, double easting, double northing) {
        ByteBuffer buffer = ByteBuffer.allocate(29).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put((byte) 'G').put((byte) 'P').put((byte) 0).put((byte) 1);
        buffer.putInt(srid);
        buffer.put((byte) 1).putInt(1).putDouble(easting).putDouble(northing);
        return buffer.array();
    }
}
