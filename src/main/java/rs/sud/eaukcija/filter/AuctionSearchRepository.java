package rs.sud.eaukcija.filter;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import rs.sud.eaukcija.model.Auction;
import rs.sud.eaukcija.repository.AuctionRepository;
import rs.sud.eaukcija.spatial.PublishableLocationSql;

/** Bounded list hydration; exactly the same auction-level predicate as GeoJSON/counts. */
@Repository
@Profile("!local-h2")
public class AuctionSearchRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final AuctionRepository auctions;
    public AuctionSearchRepository(JdbcTemplate jdbc, AuctionRepository auctions) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc); this.auctions = auctions;
    }
    public Map<String, Long> catalogueStats() {
        return jdbc.queryForObject("SELECT count(*) AS total, count(*) FILTER (WHERE details_fetched) AS details FROM auctions",
                Map.of(), (rs, n) -> Map.of("total", rs.getLong("total"), "details", rs.getLong("details")));
    }
    public long count(AuctionFilters filters) {
        var p = AuctionFilterSql.predicate(filters);
        return jdbc.queryForObject("WITH " + PublishableLocationSql.CTES + " SELECT count(*) FROM auctions a WHERE " + p.sql(),
                p.parameters(), Long.class);
    }
    public Page<Auction> page(AuctionFilters filters, long total) {
        var p = AuctionFilterSql.predicate(filters);
        String sort = AuctionFilters.SORT_COLUMNS.get(filters.sortBy());
        if (sort == null || !List.of("asc", "desc").contains(filters.sortDir())) throw new IllegalArgumentException("invalid sort");
        var ids = jdbc.queryForList("WITH " + PublishableLocationSql.CTES
                        + " SELECT a.id FROM auctions a WHERE " + p.sql() + " ORDER BY " + sort + " " + filters.sortDir()
                        + " NULLS LAST, a.id ASC LIMIT 25 OFFSET :offset",
                p.parameters().addValue("offset", filters.page() * 25L), Long.class);
        Map<Long, Auction> hydrated = auctions.findAllById(ids).stream().collect(Collectors.toMap(Auction::getId, a -> a));
        return new PageImpl<>(ids.stream().map(hydrated::get).toList(), PageRequest.of(filters.page(), 25), total);
    }
    /** Only honest public winning tiers in the table; evidence remains in /api/locations. */
    public Map<Long, List<String>> precisions(List<Long> ids, AuctionFilters filters) {
        if (ids.isEmpty()) return Map.of();
        var rows = jdbc.query("WITH " + PublishableLocationSql.CTES
                        + " SELECT DISTINCT auction_id, location_precision FROM winners WHERE auction_id IN (:ids)"
                        + (filters.precision() == null ? "" : " AND location_precision = :precision")
                        + " ORDER BY auction_id, location_precision",
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("ids", ids)
                        .addValue("precision", filters.precision() == null ? null : filters.precision().name()),
                (rs, n) -> Map.entry(rs.getLong(1), rs.getString(2)));
        return rows.stream().collect(Collectors.groupingBy(Map.Entry::getKey,
                Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
    }
}
