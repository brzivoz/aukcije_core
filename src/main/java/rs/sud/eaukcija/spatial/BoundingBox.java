package rs.sud.eaukcija.spatial;

/** A non-wrapping WGS84 viewport. Longitude is always the first coordinate. */
public record BoundingBox(double minLongitude, double minLatitude,
                          double maxLongitude, double maxLatitude) {

    public BoundingBox {
        requireFinite("minLongitude", minLongitude);
        requireFinite("minLatitude", minLatitude);
        requireFinite("maxLongitude", maxLongitude);
        requireFinite("maxLatitude", maxLatitude);
        if (minLongitude < -180 || maxLongitude > 180) {
            throw new IllegalArgumentException("longitude must be within [-180, 180]");
        }
        if (minLatitude < -90 || maxLatitude > 90) {
            throw new IllegalArgumentException("latitude must be within [-90, 90]");
        }
        if (minLongitude >= maxLongitude) {
            throw new IllegalArgumentException("minLongitude must be less than maxLongitude");
        }
        if (minLatitude >= maxLatitude) {
            throw new IllegalArgumentException("minLatitude must be less than maxLatitude");
        }
    }

    private static void requireFinite(String name, double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
