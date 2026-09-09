package rs.sud.eaukcija.spatial;

/** Shared publication and canonical-property winner relation, independent of viewport/precision.
 * NOT MATERIALIZED permits the outer spatial read to start at the geometry GiST index.
 * The correlated competitor read is bounded by an auction, and never by the viewport.
 */
public final class PublishableLocationSql {
    private PublishableLocationSql() {}

    public static final String CTES = """
            eligible AS NOT MATERIALIZED (
                SELECT pr.auction_id, pr.id AS property_reference_id, pr.reference_order,
                       CASE WHEN pr.parcel_identity_id IS NOT NULL THEN 'parcel:' || pr.parcel_identity_id::text
                            ELSE pr.reference_type || ':' || pr.canonical_key END AS property_key,
                       attempt.id AS resolution_attempt_id, attempt.location_precision,
                       attempt.completed_at, attempt.geometry_id
                  FROM property_references pr
                  JOIN current_location_resolutions current_resolution ON current_resolution.property_reference_id = pr.id
                  JOIN location_resolution_attempts attempt
                    ON attempt.id = current_resolution.resolution_attempt_id AND attempt.property_reference_id = pr.id
                 WHERE attempt.resolution_status = 'RESOLVED' AND attempt.geometry_id IS NOT NULL
                   AND %s AND %s
                   AND (pr.reference_type <> 'STRUCTURED_LOCATION'
                        OR attempt.location_precision NOT IN ('CADASTRAL_MUNICIPALITY', 'SETTLEMENT', 'MUNICIPALITY')
                        OR NOT EXISTS (
                            SELECT 1 FROM property_references parcel_reference
                            JOIN current_location_resolutions parcel_selection ON parcel_selection.property_reference_id = parcel_reference.id
                            JOIN location_resolution_attempts parcel_attempt
                              ON parcel_attempt.id = parcel_selection.resolution_attempt_id
                             AND parcel_attempt.property_reference_id = parcel_reference.id
                            WHERE parcel_reference.auction_id = pr.auction_id
                              AND parcel_attempt.resolution_status = 'RESOLVED'
                              AND parcel_attempt.geometry_id IS NOT NULL
                              AND parcel_attempt.location_precision IN ('PARCEL', 'ADDRESS', 'STREET')
                              AND %s AND %s
                        ))
            ), winners AS NOT MATERIALIZED (
                SELECT e.* FROM eligible e
                 WHERE e.resolution_attempt_id = (
                    SELECT competitor.resolution_attempt_id FROM eligible competitor
                     WHERE competitor.auction_id = e.auction_id AND competitor.property_key = e.property_key
                     ORDER BY %s LIMIT 1
                 )
            )
            """.formatted(
            LocationSelectionSql.publishableReferencePredicate("pr.extraction_status"),
            LocationSelectionSql.currentParcelEligibilityPredicate("attempt"),
            LocationSelectionSql.publishableReferencePredicate("parcel_reference.extraction_status"),
            LocationSelectionSql.currentParcelEligibilityPredicate("parcel_attempt"),
            LocationSelectionSql.bestOrder("competitor.location_precision", "competitor.reference_order",
                    "competitor.completed_at", "competitor.resolution_attempt_id"));
}
