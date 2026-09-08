package rs.sud.eaukcija.map;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKBReader;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import rs.sud.eaukcija.filter.AuctionFilterSql;
import rs.sud.eaukcija.spatial.LocationPrecision;
import rs.sud.eaukcija.spatial.PublishableLocationSql;

/** Indexed spatial subset of the shared filtered population. Never hydrates descriptions. */
@Repository
@Profile("!local-h2")
public class MapAuctionRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public MapAuctionRepository(JdbcTemplate jdbc) { this.jdbc = new NamedParameterJdbcTemplate(jdbc); }

    private static final String VIEWPORT = """
            SELECT ST_MakeEnvelope(:west, :south, :east, :north, 4326) AS bounds
            """;
    private static String within(MapAuctionRequest request) {
        return """
                FROM viewport
                JOIN spatial_resolution_geometries geometry
                  ON geometry.canonical_geometry && viewport.bounds
                 AND ST_Intersects(geometry.canonical_geometry, viewport.bounds)
                JOIN winners w ON w.geometry_id = geometry.id
                JOIN auctions a ON a.id = w.auction_id
                WHERE %s
                """.formatted(AuctionFilterSql.predicate(request.filters()).sql())
                + (request.precision() == null ? "" : " AND w.location_precision = :mapPrecision");
    }
    static String query(MapAuctionRequest request) {
        return "WITH " + PublishableLocationSql.CTES + ", viewport AS (" + VIEWPORT + ") " + """
                SELECT a.id || ':' || md5(w.property_key) AS feature_id,
                       a.id AS auction_id, a.auction_number, a.starting_price AS amount,
                       a.end_date, a.status AS source_status, a.category_name AS property_kind,
                       w.location_precision, ST_AsBinary(geometry.canonical_geometry) AS geometry_wkb
                """ + within(request) + " ORDER BY a.id, md5(w.property_key) LIMIT :featureLimit";
    }
    private static org.springframework.jdbc.core.namedparam.MapSqlParameterSource arguments(MapAuctionRequest request) {
        return AuctionFilterSql.predicate(request.filters()).parameters()
                .addValue("west", request.boundingBox().minLongitude()).addValue("south", request.boundingBox().minLatitude())
                .addValue("east", request.boundingBox().maxLongitude()).addValue("north", request.boundingBox().maxLatitude())
                .addValue("mapPrecision", request.precision() == null ? null : request.precision().name())
                .addValue("featureLimit", request.limit() + 1);
    }
    public List<MapAuctionRow> findWithin(MapAuctionRequest request) {
        return jdbc.query(query(request), arguments(request), MapAuctionRepository::mapRow);
    }
    List<String> explain(MapAuctionRequest request) {
        return jdbc.query("EXPLAIN (COSTS OFF) " + query(request), arguments(request), (rs, n) -> rs.getString(1));
    }
    public Counts counts(MapAuctionRequest request) {
        String sql = "WITH " + PublishableLocationSql.CTES + ", viewport AS (" + VIEWPORT + "), population AS ("
                + "SELECT a.id, NOT EXISTS (SELECT 1 FROM winners w WHERE w.auction_id = a.id) AS unmapped "
                + "FROM auctions a WHERE " + AuctionFilterSql.predicate(request.filters()).sql() + "), visible AS ("
                + "SELECT a.id " + within(request) + ") " + """
                SELECT (SELECT count(*) FROM population) AS filtered,
                       (SELECT count(*) FROM population WHERE unmapped) AS unmapped,
                       (SELECT count(DISTINCT id) FROM visible) AS mapped,
                       (SELECT count(*) FROM visible) AS features
                """;
        return jdbc.queryForObject(sql, arguments(request), (rs, n) -> new Counts(
                rs.getLong("filtered"), rs.getLong("unmapped"), rs.getLong("mapped"), rs.getLong("features")));
    }
    public String selectionState(MapAuctionRequest request) {
        String sql = "WITH " + PublishableLocationSql.CTES + ", viewport AS (" + VIEWPORT + ") "
                + "SELECT CASE WHEN (" + AuctionFilterSql.predicate(request.filters()).sql() + ") IS NOT TRUE THEN 'OUTSIDE_FILTERS' "
                + "WHEN NOT EXISTS (SELECT 1 FROM winners w WHERE w.auction_id = a.id) THEN 'UNMAPPED' "
                + "WHEN EXISTS (SELECT 1 " + within(request) + " AND a.id = :selected) THEN 'VISIBLE' "
                + "ELSE 'OUTSIDE_VIEWPORT' END FROM auctions a WHERE a.id = :selected";
        var result = jdbc.queryForList(sql, arguments(request).addValue("selected", request.filters().auction()), String.class);
        return result.isEmpty() ? "NOT_FOUND" : result.get(0);
    }
    public record Counts(long filteredAuctionCount, long unmappedAuctionCount,
                         long mappedAuctionCountInViewport, long featureCountInViewport) {
        public long outsideViewportAuctionCount() {
            return filteredAuctionCount - unmappedAuctionCount - mappedAuctionCountInViewport;
        }
    }
    private static MapAuctionRow mapRow(ResultSet rs, int rowNumber) throws SQLException {
        OffsetDateTime endTime = rs.getObject("end_date", OffsetDateTime.class);
        try {
            Geometry geometry = new WKBReader().read(rs.getBytes("geometry_wkb"));
            geometry.setSRID(4326);
            return new MapAuctionRow(rs.getString("feature_id"), rs.getLong("auction_id"),
                    rs.getString("auction_number"), rs.getBigDecimal("amount"),
                    endTime == null ? null : endTime.toInstant(), rs.getString("source_status"),
                    rs.getString("property_kind"), LocationPrecision.valueOf(rs.getString("location_precision")), geometry);
        } catch (ParseException e) { throw new SQLException("PostGIS returned invalid map geometry WKB", e); }
    }
}
