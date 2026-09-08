package rs.sud.eaukcija.filter;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

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
        if (f.precision() != null) {
            if (f.precision().name().equals("NONE")) {
                clauses.add("NOT EXISTS (SELECT 1 FROM winners w WHERE w.auction_id = a.id)");
            } else {
                clauses.add("EXISTS (SELECT 1 FROM winners w WHERE w.auction_id = a.id AND w.location_precision = :precision)");
                p.addValue("precision", f.precision().name());
            }
        }
        return new Predicate(clauses.isEmpty() ? "TRUE" : String.join(" AND ", clauses), p);
    }
    private static void equal(List<String> clauses, MapSqlParameterSource p, String field, String column, Object value) {
        if (value != null) { clauses.add(column + " = :" + field); p.addValue(field, value); }
    }
}
