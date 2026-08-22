package rs.sud.eaukcija.spatial;

/** Derived WGS84 bounds; unlike a query viewport, a point may have zero width and height. */
public record GeometryBounds(double minLongitude, double minLatitude,
                             double maxLongitude, double maxLatitude) {

    public GeometryBounds {
        requireFinite("minLongitude", minLongitude);
        requireFinite("minLatitude", minLatitude);
        requireFinite("maxLongitude", maxLongitude);
        requireFinite("maxLatitude", maxLatitude);
        if (minLongitude < -180 || maxLongitude > 180 || minLongitude > maxLongitude) {
            throw new IllegalArgumentException("invalid longitude bounds");
        }
        if (minLatitude < -90 || maxLatitude > 90 || minLatitude > maxLatitude) {
            throw new IllegalArgumentException("invalid latitude bounds");
        }
    }

    private static void requireFinite(String name, double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
