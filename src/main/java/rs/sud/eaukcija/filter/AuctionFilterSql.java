package rs.sud.eaukcija.filter;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import rs.sud.eaukcija.spatial.LocationPrecision;

/** One parameterized predicate for list, map and counts. Trusted aliases only. */
public final class AuctionFilterSql {
    private AuctionFilterSql() {}
    public record Predicate(String sql, MapSqlParameterSource parameters) {}

    public static Predicate predicate(AuctionFilters f) {
        List<String> clauses = new ArrayList<>();
        MapSqlParameterSource p = new MapSqlParameterSource();
        if (!f.municipalities().isEmpty()) {
            clauses.add("lower(a.municipality) IN (:municipalities)");
            p.addValue("municipalities", f.municipalities().stream().map(value -> value.toLowerCase(java.util.Locale.ROOT)).toList());
        }
        equal(clauses, p, "placeName", "a.place_name", f.placeName());
        equal(clauses, p, "category", "a.category_name", f.category());
        if (f.status() != null) {
            clauses.add("lower(a.status) = lower(:status)");
            p.addValue("status", f.status());
        }
        equal(clauses, p, "firstSale", "a.first_sale", f.firstSale());
        if (f.minPrice() != null) { clauses.add("a.starting_price >= :minPrice"); p.addValue("minPrice", f.minPrice()); }
        if (f.maxPrice() != null) { clauses.add("a.starting_price <= :maxPrice"); p.addValue("maxPrice", f.maxPrice()); }
        if (!f.timeScope().equals("all")) {
            clauses.add("a.end_date " + (f.timeScope().equals("ended") ? "<=" : ">") + " :asOf");
            p.addValue("asOf", Timestamp.from(f.asOf()));
        }
        if (f.from() != null) { clauses.add("a.end_date >= :from"); p.addValue("from", Timestamp.from(f.endsAtOrAfter())); }
        if (f.to() != null) { clauses.add("a.end_date < :to"); p.addValue("to", Timestamp.from(f.endsBefore())); }
        if (f.search() != null) {
            clauses.add("auction_search_text(a.auction_number, a.short_description, a.description) LIKE '%' || auction_search_normalize(:search) || '%' ESCAPE '\\'");
            p.addValue("search", f.search().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_"));
        }
        if (f.precision() == LocationPrecision.NONE) {
            clauses.add("NOT EXISTS (SELECT 1 FROM winners w WHERE w.auction_id = a.id)");
        }
        if (f.parcelSize() != null || (f.precision() != null
                && f.precision() != LocationPrecision.NONE)) {
            var property = propertyPredicate(f);
            // All property-level criteria must hold on ONE already selected canonical winner.
            clauses.add("EXISTS (SELECT 1 FROM winners w WHERE w.auction_id = a.id AND " + property.sql() + ")");
            p.addValues(property.parameters().getValues());
        }
        if (f.changes().active() && f.changes().window() == null) throw new IllegalStateException("Unresolved comparison boundary");
        if (f.changes().usable()) {
            clauses.add(changePredicate(f.changes()));
            p.addValue("changeLower", f.changes().window().lower().sequence());
            p.addValue("changeUpper", f.changes().window().upper().publication().sequence());
        }
        return new Predicate(clauses.isEmpty() ? "TRUE" : String.join(" AND ", clauses), p);
    }

    /** Activity, not latest delta or net hash equality. NEW takes precedence over UPDATED. */
    private static String changePredicate(ChangeCriteria c) {
        String isNew = "EXISTS (SELECT 1 FROM sync_run_auction_observations co WHERE co.auction_id=a.id"
                + " AND co.publication_id > :changeLower AND co.publication_id <= :changeUpper AND co.content_delta='NEW')";
        String updated = "(NOT " + isNew
                + " AND EXISTS (SELECT 1 FROM sync_run_auction_observations co WHERE co.auction_id=a.id AND co.publication_id <= :changeLower)"
                + " AND EXISTS (SELECT 1 FROM sync_run_auction_observations co WHERE co.auction_id=a.id"
                + " AND co.publication_id > :changeLower AND co.publication_id <= :changeUpper"
                + " AND co.comparison_kind IN (" + (c.liveBidding() ? "'SUBSTANTIVE','LIVE_BIDDING_ONLY'" : "'SUBSTANTIVE'") + ")))";
        return c.kind().equals("new") ? isNew : c.kind().equals("updated") ? updated : "(" + isNew + " OR " + updated + ")";
    }

    /** Matching map features, table tiers, auction membership and selection use the same w alias. */
    public static Predicate propertyPredicate(AuctionFilters f) {
        List<String> clauses = new ArrayList<>();
        MapSqlParameterSource p = new MapSqlParameterSource();
        equal(clauses, p, "precision", "w.location_precision", f.precision() == null ? null : f.precision().name());
        if (f.parcelSize() != null) {
            // 1 ar = 100 m². Exact NUMERIC, with both boundaries in the middle band.
            switch (f.parcelSize()) {
                case UNDER_8 -> clauses.add("w.parcel_area_square_metres < :parcelArea8");
                case BETWEEN_8_AND_15 -> clauses.add("w.parcel_area_square_metres BETWEEN :parcelArea8 AND :parcelArea15");
                case OVER_15 -> clauses.add("w.parcel_area_square_metres > :parcelArea15");
            }
            p.addValue("parcelArea8", new BigDecimal("800"));
            p.addValue("parcelArea15", new BigDecimal("1500"));
        }
        return new Predicate(clauses.isEmpty() ? "TRUE" : String.join(" AND ", clauses), p);
    }
    private static void equal(List<String> clauses, MapSqlParameterSource p, String field, String column, Object value) {
        if (value != null) { clauses.add(column + " = :" + field); p.addValue(field, value); }
    }
}
