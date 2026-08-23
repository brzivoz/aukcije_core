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

    static final String BEST_SELECTION_ORDER = LocationSelectionSql.bestOrder(
            "attempt.location_precision",
            "pr.reference_order",
            "attempt.completed_at",
            "attempt.id");

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
        List<AuctionLocationView> rows = jdbc.query(queryFor(placeholders), (resultSet, rowNumber) -> {
            LocationPrecision precision = LocationPrecision.valueOf(resultSet.getString("location_precision"));
            String extractionStatus = resultSet.getString("extraction_status");
            String resolutionStatus = resultSet.getString("resolution_status");
            OffsetDateTime resolvedAt = resultSet.getObject("resolved_at", OffsetDateTime.class);
            return new AuctionLocationView(
                    resultSet.getLong("auction_id"),
                    resultSet.getObject("property_reference_id", UUID.class),
                    resultSet.getObject("resolution_attempt_id", UUID.class),
                    precision,
                    LocationPrecisionPresentation.labelSr(precision),
                    LocationPrecisionPresentation.coarse(precision),
                    extractionStatus,
                    LocationSelectionSql.publishableSelection(extractionStatus, resolutionStatus),
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

    static String queryFor(String placeholders) {
        return """
                WITH ranked AS (
                    SELECT pr.auction_id,
                           pr.id AS property_reference_id,
                           attempt.id AS resolution_attempt_id,
                           attempt.resolution_status,
                           attempt.location_precision,
                           pr.extraction_status,
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
                               ORDER BY %s
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
                """.formatted(BEST_SELECTION_ORDER, placeholders);
    }
}
