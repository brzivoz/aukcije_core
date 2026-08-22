package rs.sud.eaukcija.spatial;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKBReader;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Single-query, bounded access to currently selected WGS84 resolutions. */
@Repository
@Profile("!local-h2")
public class SpatialViewportRepository {

    public static final int MAX_VIEWPORT_RESULTS = 5_000;

    private static final String SELECTED_WITHIN = """
            WITH viewport AS (
                SELECT ST_MakeEnvelope(?, ?, ?, ?, 4326) AS bounds
            )
            SELECT pr.auction_id,
                   pr.id AS property_reference_id,
                   attempt.id AS resolution_attempt_id,
                   attempt.location_precision,
                   ST_AsBinary(geometry.canonical_geometry) AS geometry_wkb,
                   ST_AsBinary(ST_Centroid(geometry.canonical_geometry)) AS centroid_wkb,
                   ST_AsBinary(ST_PointOnSurface(geometry.canonical_geometry)) AS representative_point_wkb,
                   ST_XMin(Box3D(geometry.canonical_geometry)) AS min_longitude,
                   ST_YMin(Box3D(geometry.canonical_geometry)) AS min_latitude,
                   ST_XMax(Box3D(geometry.canonical_geometry)) AS max_longitude,
                   ST_YMax(Box3D(geometry.canonical_geometry)) AS max_latitude,
                   attempt.resolver,
                   attempt.resolver_version,
                   attempt.source_dataset,
                   attempt.source_dataset_version,
                   attempt.source_dataset_sha256,
                   attempt.resolved_at,
                   attempt.confidence_reason,
                   attempt.member_point_count
              FROM viewport
              JOIN spatial_resolution_geometries geometry
                ON geometry.canonical_geometry && viewport.bounds
               AND ST_Intersects(geometry.canonical_geometry, viewport.bounds)
              JOIN location_resolution_attempts attempt
                ON attempt.geometry_id = geometry.id
              JOIN current_location_resolutions current_resolution
                ON current_resolution.resolution_attempt_id = attempt.id
               AND current_resolution.property_reference_id = attempt.property_reference_id
              JOIN property_references pr
                ON pr.id = current_resolution.property_reference_id
             ORDER BY pr.auction_id, pr.reference_order, pr.canonical_key, pr.id
             LIMIT ?
            """;

    private final JdbcTemplate jdbc;

    public SpatialViewportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ViewportLocation> findSelectedWithin(BoundingBox box, int limit) {
        validateLimit(limit);
        return jdbc.query(SELECTED_WITHIN, SpatialViewportRepository::mapLocation, arguments(box, limit));
    }

    /** Returns PostgreSQL's plan for the exact production query without executing it. */
    public List<String> explainSelectedWithin(BoundingBox box, int limit) {
        validateLimit(limit);
        return jdbc.query(
                "EXPLAIN (COSTS OFF) " + SELECTED_WITHIN,
                (rs, rowNum) -> rs.getString(1),
                arguments(box, limit));
    }

    private static Object[] arguments(BoundingBox box, int limit) {
        return new Object[] {
                box.minLongitude(), box.minLatitude(), box.maxLongitude(), box.maxLatitude(), limit
        };
    }

    private static void validateLimit(int limit) {
        if (limit < 1 || limit > MAX_VIEWPORT_RESULTS) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_VIEWPORT_RESULTS);
        }
    }

    private static ViewportLocation mapLocation(ResultSet rs, int rowNum) throws SQLException {
        Geometry geometry = readGeometry(rs, "geometry_wkb");
        Point centroid = readPoint(rs, "centroid_wkb");
        Point representativePoint = readPoint(rs, "representative_point_wkb");
        OffsetDateTime resolvedAt = rs.getObject("resolved_at", OffsetDateTime.class);
        return new ViewportLocation(
                rs.getLong("auction_id"),
                rs.getObject("property_reference_id", UUID.class),
                rs.getObject("resolution_attempt_id", UUID.class),
                LocationPrecision.valueOf(rs.getString("location_precision")),
                geometry,
                centroid,
                representativePoint,
                new GeometryBounds(
                        rs.getDouble("min_longitude"),
                        rs.getDouble("min_latitude"),
                        rs.getDouble("max_longitude"),
                        rs.getDouble("max_latitude")),
                rs.getString("resolver"),
                rs.getString("resolver_version"),
                rs.getString("source_dataset"),
                rs.getString("source_dataset_version"),
                rs.getString("source_dataset_sha256"),
                resolvedAt.toInstant(),
                rs.getString("confidence_reason"),
                rs.getObject("member_point_count", Long.class));
    }

    private static Point readPoint(ResultSet rs, String column) throws SQLException {
        Geometry geometry = readGeometry(rs, column);
        if (geometry instanceof Point point) {
            return point;
        }
        throw new SQLException("PostGIS returned a non-point value in " + column);
    }

    private static Geometry readGeometry(ResultSet rs, String column) throws SQLException {
        try {
            Geometry geometry = new WKBReader().read(rs.getBytes(column));
            geometry.setSRID(4326);
            return geometry;
        } catch (ParseException e) {
            throw new SQLException("PostGIS returned invalid WKB in " + column, e);
        }
    }
}
