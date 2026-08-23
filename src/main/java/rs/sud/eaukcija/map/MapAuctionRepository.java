package rs.sud.eaukcija.map;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKBReader;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import rs.sud.eaukcija.spatial.LocationPrecision;
import rs.sud.eaukcija.spatial.LocationSelectionSql;

/** One indexed, bounded query for every field used by the public GeoJSON response. */
@Repository
@Profile("!local-h2")
public class MapAuctionRepository {

    static final String BEST_SELECTION_ORDER = LocationSelectionSql.bestOrder(
            "location_precision",
            "reference_order",
            "completed_at",
            "resolution_attempt_id");
    static final String PUBLISHABLE_REFERENCE =
            LocationSelectionSql.publishableReferencePredicate("pr.extraction_status");

    static final String VIEWPORT_QUERY = """
            WITH viewport AS (
                SELECT ST_MakeEnvelope(?, ?, ?, ?, 4326) AS bounds
            ), candidates AS (
                SELECT a.id AS auction_id,
                       a.auction_number,
                       a.starting_price AS amount,
                       a.end_date,
                       a.status AS source_status,
                       a.category_name AS property_kind,
                       pr.id AS property_reference_id,
                       pr.reference_order,
                       CASE
                           WHEN pr.parcel_identity_id IS NOT NULL
                               THEN 'parcel:' || pr.parcel_identity_id::text
                           ELSE pr.reference_type || ':' || pr.canonical_key
                       END AS property_key,
                       attempt.id AS resolution_attempt_id,
                       attempt.location_precision,
                       attempt.completed_at,
                       ST_AsBinary(geometry.canonical_geometry) AS geometry_wkb
                  FROM viewport
                  JOIN spatial_resolution_geometries geometry
                    ON geometry.canonical_geometry && viewport.bounds
                   AND ST_Intersects(geometry.canonical_geometry, viewport.bounds)
                  JOIN location_resolution_attempts attempt
                    ON attempt.geometry_id = geometry.id
                   AND attempt.resolution_status = 'RESOLVED'
                  JOIN current_location_resolutions current_resolution
                    ON current_resolution.resolution_attempt_id = attempt.id
                   AND current_resolution.property_reference_id = attempt.property_reference_id
                  JOIN property_references pr
                    ON pr.id = current_resolution.property_reference_id
                  JOIN auctions a
                    ON a.id = pr.auction_id
                 WHERE a.end_date IS NOT NULL
                   AND %s
                   AND a.end_date >= ?
                   AND (?::timestamptz IS NULL OR a.end_date < ?)
                   AND (?::text IS NULL OR a.status = ?)
                   AND (?::text IS NULL OR a.category_name = ?)
            ), ranked AS (
                SELECT candidates.*,
                       row_number() OVER (
                           PARTITION BY auction_id, property_key
                           ORDER BY %s
                       ) AS property_rank
                  FROM candidates
            )
            SELECT auction_id || ':' || md5(property_key) AS feature_id,
                   auction_id,
                   auction_number,
                   amount,
                   end_date,
                   source_status,
                   property_kind,
                   location_precision,
                   geometry_wkb
              FROM ranked
             WHERE property_rank = 1
               AND (?::text IS NULL OR location_precision = ?)
             ORDER BY auction_id, md5(property_key)
             LIMIT ?
            """.formatted(PUBLISHABLE_REFERENCE, BEST_SELECTION_ORDER);

    private final JdbcTemplate jdbc;

    public MapAuctionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<MapAuctionRow> findWithin(MapAuctionRequest request) {
        return jdbc.query(VIEWPORT_QUERY, MapAuctionRepository::mapRow, arguments(request));
    }

    /** Plan for the exact production select, with the same filters and sentinel limit. */
    List<String> explain(MapAuctionRequest request) {
        return jdbc.query(
                "EXPLAIN (COSTS OFF) " + VIEWPORT_QUERY,
                (rs, rowNum) -> rs.getString(1),
                arguments(request));
    }

    private static Object[] arguments(MapAuctionRequest request) {
        String precision = request.precision() == null ? null : request.precision().name();
        Timestamp from = Timestamp.from(request.endsAtOrAfter());
        Timestamp to = request.endsBefore() == null ? null : Timestamp.from(request.endsBefore());
        return new Object[] {
                request.boundingBox().minLongitude(), request.boundingBox().minLatitude(),
                request.boundingBox().maxLongitude(), request.boundingBox().maxLatitude(),
                from,
                to, to,
                request.sourceStatus(), request.sourceStatus(),
                request.propertyKind(), request.propertyKind(),
                precision, precision,
                request.limit() + 1
        };
    }

    private static MapAuctionRow mapRow(ResultSet rs, int rowNumber) throws SQLException {
        OffsetDateTime endTime = rs.getObject("end_date", OffsetDateTime.class);
        try {
            Geometry geometry = new WKBReader().read(rs.getBytes("geometry_wkb"));
            geometry.setSRID(4326);
            return new MapAuctionRow(
                    rs.getString("feature_id"),
                    rs.getLong("auction_id"),
                    rs.getString("auction_number"),
                    rs.getBigDecimal("amount"),
                    endTime.toInstant(),
                    rs.getString("source_status"),
                    rs.getString("property_kind"),
                    LocationPrecision.valueOf(rs.getString("location_precision")),
                    geometry);
        } catch (ParseException e) {
            throw new SQLException("PostGIS returned invalid map geometry WKB", e);
        }
    }
}
