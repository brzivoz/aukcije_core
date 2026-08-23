package rs.sud.eaukcija.map;

import java.util.ArrayList;
import java.util.List;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;

/** Minimal GeoJSON geometry serializer for the schema-allowed WGS84 types. */
public record GeoJsonGeometry(String type, Object coordinates) {

    public static GeoJsonGeometry from(Geometry geometry) {
        if (geometry instanceof Point point) {
            return new GeoJsonGeometry("Point", coordinate(point.getCoordinate()));
        }
        if (geometry instanceof Polygon polygon) {
            return new GeoJsonGeometry("Polygon", polygon(polygon));
        }
        if (geometry instanceof MultiPolygon multiPolygon) {
            List<Object> polygons = new ArrayList<>();
            for (int index = 0; index < multiPolygon.getNumGeometries(); index++) {
                polygons.add(polygon((Polygon) multiPolygon.getGeometryN(index)));
            }
            return new GeoJsonGeometry("MultiPolygon", polygons);
        }
        throw new IllegalArgumentException("unsupported map geometry type: " + geometry.getGeometryType());
    }

    private static List<Object> polygon(Polygon polygon) {
        List<Object> rings = new ArrayList<>();
        rings.add(line(polygon.getExteriorRing()));
        for (int index = 0; index < polygon.getNumInteriorRing(); index++) {
            rings.add(line(polygon.getInteriorRingN(index)));
        }
        return rings;
    }

    private static List<Object> line(LineString line) {
        List<Object> coordinates = new ArrayList<>();
        for (Coordinate coordinate : line.getCoordinates()) {
            coordinates.add(coordinate(coordinate));
        }
        return coordinates;
    }

    private static List<Double> coordinate(Coordinate coordinate) {
        return List.of(coordinate.getX(), coordinate.getY());
    }
}
