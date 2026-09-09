package rs.sud.eaukcija.spatial;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Safe current-reference diagnostics; no descriptions, candidate payloads or personal fields. */
@Repository
@Profile("!local-h2")
public class LocationRefinementRepository {
    private final JdbcTemplate jdbc;
    public LocationRefinementRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Report find(long auctionId) {
        if (jdbc.queryForObject("SELECT count(*) FROM auctions WHERE id = ?", Long.class, auctionId) == 0) return null;
        List<Reference> references = jdbc.query("""
                SELECT r.id, r.reference_type, r.canonical_parcel_number, r.extraction_status,
                       k.status AS ko_status, selected.location_precision,
                       parcel.confidence_reason AS parcel_reason, address.confidence_reason AS address_reason,
                       parcel.completed_at AS parcel_at, address.completed_at AS address_at
                  FROM current_property_reference_extractions e
                  JOIN property_reference_extraction_memberships m
                    ON m.extraction_run_id = e.extraction_run_id AND m.auction_id = e.auction_id
                  JOIN property_references r ON r.id = m.reference_id
                  LEFT JOIN current_property_reference_ko_matches c ON c.reference_id = r.id
                  LEFT JOIN property_reference_ko_match_results k
                    ON k.reference_id = c.reference_id AND k.input_fingerprint = c.input_fingerprint
                  LEFT JOIN current_location_resolutions selection ON selection.property_reference_id = r.id
                  LEFT JOIN location_resolution_attempts selected ON selected.id = selection.resolution_attempt_id AND %s
                  LEFT JOIN LATERAL (
                      SELECT confidence_reason, completed_at FROM location_resolution_attempts p
                       WHERE p.property_reference_id = r.id AND p.resolver = 'RGZ_WFS_PARCEL'
                         AND p.upstream_ko_match_input_fingerprint = c.input_fingerprint
                       ORDER BY p.completed_at DESC, p.id DESC LIMIT 1
                  ) parcel ON true
                  LEFT JOIN LATERAL (
                      SELECT confidence_reason, completed_at FROM location_resolution_attempts a
                       WHERE a.property_reference_id = r.id AND a.resolver = 'OFFICIAL_ADDRESS_REGISTRY'
                         AND a.upstream_ko_match_input_fingerprint IS NOT DISTINCT FROM c.input_fingerprint
                       ORDER BY a.completed_at DESC, a.id DESC LIMIT 1
                  ) address ON true
                 WHERE e.auction_id = ? AND r.reference_type IN ('PARCEL', 'ADDRESS')
                 ORDER BY m.reference_order, r.id
                """.formatted(LocationSelectionSql.currentParcelEligibilityPredicate("selected")), (rs, n) -> {
            String extraction = rs.getString("extraction_status");
            String ko = rs.getString("ko_status");
            String parcel = rs.getString("parcel_reason");
            String address = rs.getString("address_reason");
            String precision = rs.getString("location_precision");
            String reason;
            if (precision != null && List.of("PARCEL", "ADDRESS", "STREET").contains(precision)) reason = precision;
            else if (!LocationSelectionSql.publishableExtractionStatus(extraction)) reason = "REFERENCE_NEEDS_REVIEW";
            else if (!"MATCHED".equals(ko)) reason = "KO_UNRESOLVED";
            else if (parcel != null && !"EXACT_KO_PARCEL_MATCH".equals(parcel)) reason = parcel;
            else reason = address == null ? "REFINEMENT_PENDING" : address;
            return new Reference(rs.getObject("id", UUID.class), rs.getString("reference_type"),
                    rs.getString("canonical_parcel_number"), extraction, ko, precision,
                    parcel, address, reason, explanation(reason), instant(rs, "parcel_at"), instant(rs, "address_at"));
        }, auctionId);
        boolean registryAvailable = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM address_registry_active_snapshot WHERE singleton)", Boolean.class));
        var state = jdbc.queryForList("SELECT status, last_stage FROM enrichment_state WHERE auction_id = ?", auctionId);
        String processing = state.isEmpty() ? "PENDING" : state.get(0).get("status").toString();
        boolean parseFailed = !state.isEmpty() && "PARSE".equals(state.get(0).get("last_stage"))
                && List.of("PERMANENT_FAILURE", "RETRYABLE_FAILURE").contains(processing);
        String summary = parseFailed ? explanation("PARSE_FAILED")
                : references.isEmpty() ? explanation("NO_FINER_REFERENCE")
                : String.join(" ", references.stream().map(Reference::explanationSr).distinct().limit(4).toList());
        if (parseFailed && !references.isEmpty()) summary += " Приказана је последња доступна локација.";
        if (!registryAvailable && references.stream().anyMatch(r -> r.selectedPrecision() == null)) {
            summary += " " + explanation("REGISTRY_UNAVAILABLE");
        }
        return new Report(auctionId, registryAvailable, processing, summary, references);
    }

    public static String explanation(String reason) {
        if (reason == null) return "Прецизније одређивање локације још није покушано.";
        return switch (reason) {
            case "PARCEL" -> "Потврђена је геометрија парцеле.";
            case "ADDRESS" -> "Пронађена је званична адресна тачка, не граница парцеле.";
            case "STREET" -> "Потврђена је улица, али не и тачан кућни број.";
            case "PARSE_FAILED" -> "Текст огласа није успешно обрађен; прецизнија локација није проверена.";
            case "REFERENCE_NEEDS_REVIEW" -> "Веза парцеле или адресе са КО захтева проверу текста огласа.";
            case "KO_UNRESOLVED" -> "КО није једнозначно потврђена; противречни подаци нису аутоматски изабрани.";
            case "REGISTRY_UNAVAILABLE" -> "Адресни регистар није увезен; прецизнија адресна локација још није проверена.";
            case "AUTHORITATIVE_NOT_FOUND" -> "РГЗ није вратио парцелу за наведени КО и број.";
            case "REGISTRY_ADDRESS_NOT_FOUND" -> "Адреса није пронађена у активном званичном регистру.";
            case "REGISTRY_ADDRESS_AMBIGUOUS", "REGISTRY_STREET_AMBIGUOUS", "MULTIPLE_FEATURES" ->
                    "Више званичних локација одговара подацима; ниједна није произвољно изабрана.";
            case "INVALID_CRS", "INVALID_GEOMETRY", "INVALID_RING", "INVALID_POLYGON", "INVALID_MULTIPOLYGON",
                 "OPEN_RING", "INVALID_POSITION", "OUTSIDE_SERBIA_BOUNDS", "UNSUPPORTED_GEOMETRY_TYPE" ->
                    "Одговор РГЗ није прошао проверу координата или геометрије; приказана је приближна локација.";
            case "LOGICAL_LOOKUP_ALREADY_CLAIMED", "RUN_REQUEST_CEILING_REACHED", "REFINEMENT_PENDING" ->
                    "Прецизнија провера је одложена за наредни пролаз обраде.";
            case "RGZ_DISABLED", "KILL_SWITCH_ENGAGED" -> "РГЗ провера је искључена; задржана је доступна локација.";
            case "NO_FINER_REFERENCE" -> "Нема издвојене парцеле или адресе за прецизнију проверу.";
            case "INCOMPLETE_ADDRESS" -> "Адреса нема довољно података за једнозначно претраживање регистра.";
            case "IDENTITY_MISMATCH" -> "РГЗ је вратио другу парцелу; резултат није прихваћен.";
            default -> "Прецизнија провера није успела; задржана је последња доступна локација.";
        };
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        var value = rs.getObject(column, java.time.OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public java.util.Map<String, Object> statistics() {
        var precision = counts("WITH " + PublishableLocationSql.CTES + """
                , best AS (SELECT DISTINCT ON (auction_id) auction_id, location_precision FROM winners
                  ORDER BY auction_id, %s DESC)
                SELECT COALESCE(best.location_precision, 'NONE'), count(*) FROM auctions a
                LEFT JOIN best ON best.auction_id = a.id WHERE a.end_date > CURRENT_TIMESTAMP GROUP BY 1
                """.formatted(LocationSelectionSql.precisionRank("location_precision")));
        var processing = counts("""
                SELECT COALESCE(s.status, 'PENDING'), count(*) FROM auctions a
                LEFT JOIN enrichment_state s ON s.auction_id = a.id
                WHERE a.end_date > CURRENT_TIMESTAMP GROUP BY 1
                """);
        var declines = counts("""
                WITH latest AS (
                    SELECT DISTINCT ON (r.id, a.resolver) a.resolver, a.resolution_status, a.confidence_reason
                      FROM current_property_reference_extractions e
                      JOIN auctions auction ON auction.id = e.auction_id
                      JOIN property_reference_extraction_memberships m ON m.extraction_run_id=e.extraction_run_id AND m.auction_id=e.auction_id
                      JOIN property_references r ON r.id=m.reference_id
                      JOIN location_resolution_attempts a ON a.property_reference_id=r.id
                      LEFT JOIN current_property_reference_ko_matches k ON k.reference_id=r.id
                     WHERE auction.end_date > CURRENT_TIMESTAMP
                       AND a.resolver IN ('RGZ_WFS_PARCEL', 'OFFICIAL_ADDRESS_REGISTRY')
                       AND a.upstream_ko_match_input_fingerprint IS NOT DISTINCT FROM k.input_fingerprint
                     ORDER BY r.id, a.resolver, a.completed_at DESC, a.id DESC
                ) SELECT resolver || ':' || confidence_reason, count(*) FROM latest
                  WHERE resolution_status <> 'RESOLVED' GROUP BY 1
                """);
        return java.util.Map.of("timeScope", "NOT_ENDED", "asOf", jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", java.time.OffsetDateTime.class),
                "processingStatusCounts", processing, "auctionPrecisionCounts", precision,
                "latestDeclinedReferenceTierCounts", declines,
                "parserEvaluation", "V2_FULL_DESCRIPTION_HELD_OUT_NOT_YET_EVALUATED");
    }

    private java.util.Map<String, Long> counts(String sql) {
        java.util.Map<String, Long> result = new java.util.TreeMap<>();
        jdbc.query(sql, rs -> { while (rs.next()) result.put(rs.getString(1), rs.getLong(2)); return null; });
        return result;
    }

    public record Report(long auctionId, boolean registryAvailable, String processingStatus, String summarySr, List<Reference> references) { }
    public record Reference(UUID referenceId, String type, String parcelNumber, String extractionStatus,
                            String koStatus, String selectedPrecision, String parcelReason, String addressReason,
                            String reason, String explanationSr, Instant parcelAttemptedAt, Instant addressAttemptedAt) { }
}
