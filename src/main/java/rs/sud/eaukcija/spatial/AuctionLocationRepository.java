package rs.sud.eaukcija.spatial;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads one best current selection per auction without hiding explicit NONE results. */
@Repository
@Profile("!local-h2")
public class AuctionLocationRepository {

    private final JdbcTemplate jdbc;

    public AuctionLocationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<Long, AuctionLocationView> findBestByAuctionIds(Collection<Long> auctionIds) {
        if (auctionIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(auctionIds.size(), "?"));
        List<Object> arguments = new ArrayList<>(auctionIds);
        List<AuctionLocationView> rows = jdbc.query("""
                WITH ranked AS (
                    SELECT pr.auction_id,
                           pr.id AS property_reference_id,
                           attempt.id AS resolution_attempt_id,
                           attempt.location_precision,
                           CASE WHEN geometry.id IS NULL THEN NULL
                                ELSE ST_X(ST_PointOnSurface(geometry.canonical_geometry)) END AS longitude,
                           CASE WHEN geometry.id IS NULL THEN NULL
                                ELSE ST_Y(ST_PointOnSurface(geometry.canonical_geometry)) END AS latitude,
                           attempt.member_point_count,
                           attempt.confidence_reason,
                           attempt.resolver,
                           attempt.resolver_version,
                           attempt.source_dataset_version,
                           attempt.resolved_at,
                           row_number() OVER (
                               PARTITION BY pr.auction_id
                               ORDER BY CASE attempt.location_precision
                                   WHEN 'PARCEL' THEN 60 WHEN 'ADDRESS' THEN 50 WHEN 'STREET' THEN 40
                                   WHEN 'CADASTRAL_MUNICIPALITY' THEN 30 WHEN 'SETTLEMENT' THEN 20
                                   WHEN 'MUNICIPALITY' THEN 10 ELSE 0 END DESC,
                                   pr.reference_order, attempt.completed_at DESC, attempt.id
                           ) AS selection_rank
                      FROM property_references pr
                      JOIN current_location_resolutions current_resolution
                        ON current_resolution.property_reference_id = pr.id
                      JOIN location_resolution_attempts attempt
                        ON attempt.id = current_resolution.resolution_attempt_id
                       AND attempt.property_reference_id = current_resolution.property_reference_id
                      LEFT JOIN spatial_resolution_geometries geometry ON geometry.id = attempt.geometry_id
                     WHERE pr.auction_id IN (%s)
                )
                SELECT * FROM ranked WHERE selection_rank = 1 ORDER BY auction_id
                """.formatted(placeholders), (resultSet, rowNumber) -> {
            LocationPrecision precision = LocationPrecision.valueOf(resultSet.getString("location_precision"));
            OffsetDateTime resolvedAt = resultSet.getObject("resolved_at", OffsetDateTime.class);
            return new AuctionLocationView(
                    resultSet.getLong("auction_id"),
                    resultSet.getObject("property_reference_id", UUID.class),
                    resultSet.getObject("resolution_attempt_id", UUID.class),
                    precision,
                    LocationPrecisionPresentation.labelSr(precision),
                    LocationPrecisionPresentation.coarse(precision),
                    resultSet.getObject("longitude", Double.class),
                    resultSet.getObject("latitude", Double.class),
                    resultSet.getObject("member_point_count", Long.class),
                    resultSet.getString("confidence_reason"),
                    resultSet.getString("resolver"),
                    resultSet.getString("resolver_version"),
                    resultSet.getString("source_dataset_version"),
                    resolvedAt.toInstant());
        }, arguments.toArray());
        LinkedHashMap<Long, AuctionLocationView> result = new LinkedHashMap<>();
        rows.forEach(row -> result.put(row.auctionId(), row));
        return Map.copyOf(result);
    }
}
