package rs.sud.eaukcija.addressregistry;

/** Deterministic inverse projection for EPSG:25834 (ETRS89 / UTM zone 34N). */
final class Etrs89Utm34ToWgs84 {

    private static final double SEMI_MAJOR_AXIS = 6_378_137.0;
    private static final double INVERSE_FLATTENING = 298.257_222_101;
    private static final double SCALE_FACTOR = 0.9996;
    private static final double FALSE_EASTING = 500_000.0;
    private static final double CENTRAL_MERIDIAN_RADIANS = Math.toRadians(21.0);

    record Point(double longitude, double latitude) {
    }

    private Etrs89Utm34ToWgs84() {
    }

    static Point transform(double easting, double northing) {
        if (!Double.isFinite(easting) || !Double.isFinite(northing)) {
            throw invalid("point has a non-finite coordinate");
        }

        double flattening = 1.0 / INVERSE_FLATTENING;
        double eccentricitySquared = flattening * (2.0 - flattening);
        double secondEccentricitySquared = eccentricitySquared / (1.0 - eccentricitySquared);
        double e1 = (1.0 - Math.sqrt(1.0 - eccentricitySquared))
                / (1.0 + Math.sqrt(1.0 - eccentricitySquared));

        double x = easting - FALSE_EASTING;
        double meridionalArc = northing / SCALE_FACTOR;
        double mu = meridionalArc / (SEMI_MAJOR_AXIS * (1.0
                - eccentricitySquared / 4.0
                - 3.0 * Math.pow(eccentricitySquared, 2) / 64.0
                - 5.0 * Math.pow(eccentricitySquared, 3) / 256.0));

        double footprintLatitude = mu
                + (3.0 * e1 / 2.0 - 27.0 * Math.pow(e1, 3) / 32.0) * Math.sin(2.0 * mu)
                + (21.0 * Math.pow(e1, 2) / 16.0 - 55.0 * Math.pow(e1, 4) / 32.0) * Math.sin(4.0 * mu)
                + 151.0 * Math.pow(e1, 3) / 96.0 * Math.sin(6.0 * mu)
                + 1097.0 * Math.pow(e1, 4) / 512.0 * Math.sin(8.0 * mu);

        double sin = Math.sin(footprintLatitude);
        double cos = Math.cos(footprintLatitude);
        double tan = Math.tan(footprintLatitude);
        double n1 = SEMI_MAJOR_AXIS / Math.sqrt(1.0 - eccentricitySquared * sin * sin);
        double r1 = SEMI_MAJOR_AXIS * (1.0 - eccentricitySquared)
                / Math.pow(1.0 - eccentricitySquared * sin * sin, 1.5);
        double t1 = tan * tan;
        double c1 = secondEccentricitySquared * cos * cos;
        double d = x / (n1 * SCALE_FACTOR);

        double latitude = footprintLatitude - (n1 * tan / r1) * (
                d * d / 2.0
                        - (5.0 + 3.0 * t1 + 10.0 * c1 - 4.0 * c1 * c1
                        - 9.0 * secondEccentricitySquared) * Math.pow(d, 4) / 24.0
                        + (61.0 + 90.0 * t1 + 298.0 * c1 + 45.0 * t1 * t1
                        - 252.0 * secondEccentricitySquared - 3.0 * c1 * c1)
                        * Math.pow(d, 6) / 720.0);
        double longitude = CENTRAL_MERIDIAN_RADIANS + (
                d - (1.0 + 2.0 * t1 + c1) * Math.pow(d, 3) / 6.0
                        + (5.0 - 2.0 * c1 + 28.0 * t1 - 3.0 * c1 * c1
                        + 8.0 * secondEccentricitySquared + 24.0 * t1 * t1)
                        * Math.pow(d, 5) / 120.0) / cos;

        return new Point(Math.toDegrees(longitude), Math.toDegrees(latitude));
    }

    private static AddressRegistryImportException invalid(String message) {
        return new AddressRegistryImportException("INVALID_GEOMETRY", message);
    }
}
