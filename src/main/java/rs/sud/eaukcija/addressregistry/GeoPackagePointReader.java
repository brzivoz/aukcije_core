package rs.sud.eaukcija.addressregistry;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Reads the GeoPackage binary header and its embedded two-dimensional WKB point. */
final class GeoPackagePointReader {

    record Point(double easting, double northing) {
    }

    private GeoPackagePointReader() {
    }

    static Point read(ResultSet result, String column) throws SQLException {
        byte[] bytes = result.getBytes(column);
        if (bytes == null) {
            throw invalid("geometry is null");
        }
        return read(bytes);
    }

    static Point read(byte[] bytes) {
        if (bytes.length < 29 || bytes[0] != 'G' || bytes[1] != 'P') {
            throw invalid("geometry has no valid GeoPackage header");
        }
        if (bytes[2] != 0) {
            throw invalid("unsupported GeoPackage geometry version " + bytes[2]);
        }
        int flags = Byte.toUnsignedInt(bytes[3]);
        if ((flags & 0x10) != 0) {
            throw invalid("empty GeoPackage geometry is not allowed");
        }
        ByteOrder headerOrder = (flags & 1) == 1 ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
        int sourceSrid = ByteBuffer.wrap(bytes, 4, 4).order(headerOrder).getInt();
        if (sourceSrid != GeoPackageInspector.EXPECTED_SRID) {
            throw invalid("row geometry has EPSG:" + sourceSrid + " instead of EPSG:"
                    + GeoPackageInspector.EXPECTED_SRID);
        }
        int envelopeIndicator = (flags >> 1) & 0x07;
        int envelopeBytes = switch (envelopeIndicator) {
            case 0 -> 0;
            case 1 -> 32;
            case 2, 3 -> 48;
            case 4 -> 64;
            default -> throw invalid("unsupported GeoPackage envelope indicator " + envelopeIndicator);
        };
        int wkbOffset = 8 + envelopeBytes;
        if (bytes.length < wkbOffset + 21) {
            throw invalid("truncated WKB point");
        }
        int byteOrderMarker = Byte.toUnsignedInt(bytes[wkbOffset]);
        if (byteOrderMarker != 0 && byteOrderMarker != 1) {
            throw invalid("invalid WKB byte order marker");
        }
        ByteOrder wkbOrder = byteOrderMarker == 1 ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
        ByteBuffer wkb = ByteBuffer.wrap(bytes, wkbOffset + 1, bytes.length - wkbOffset - 1).order(wkbOrder);
        long geometryType = Integer.toUnsignedLong(wkb.getInt());
        if (geometryType != 1) {
            throw invalid("expected WKB POINT, got geometry type " + geometryType);
        }
        double easting = wkb.getDouble();
        double northing = wkb.getDouble();
        if (!Double.isFinite(easting) || !Double.isFinite(northing)) {
            throw invalid("point has a non-finite coordinate");
        }
        return new Point(easting, northing);
    }

    private static AddressRegistryImportException invalid(String message) {
        return new AddressRegistryImportException("INVALID_GEOMETRY", message);
    }
}
