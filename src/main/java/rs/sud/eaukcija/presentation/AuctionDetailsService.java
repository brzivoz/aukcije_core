package rs.sud.eaukcija.presentation;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** One deliberate local read, by primary key. No snapshots, executor names, evidence or geometry. */
@Service
@Profile("!local-h2")
public class AuctionDetailsService {
    private final JdbcTemplate jdbc;
    public AuctionDetailsService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<Details> find(long id) {
        return jdbc.query("""
                SELECT id, auction_number, category_name, municipality, place_name,
                       starting_price, estimated_price, start_date, end_date, publication_date,
                       status, first_sale, short_description, description
                FROM auctions WHERE id = ?
                """, (rs, n) -> new Details(rs.getLong("id"),
                AuctionPresentation.text(rs.getString("auction_number"), 256),
                AuctionPresentation.category(rs.getString("category_name")),
                AuctionPresentation.text(rs.getString("municipality"), 256),
                AuctionPresentation.text(rs.getString("place_name"), 256),
                rs.getBigDecimal("starting_price"), rs.getBigDecimal("estimated_price"),
                instant(rs.getObject("start_date", OffsetDateTime.class)),
                instant(rs.getObject("end_date", OffsetDateTime.class)),
                instant(rs.getObject("publication_date", OffsetDateTime.class)),
                AuctionPresentation.statusLabel(rs.getString("status")), rs.getBoolean("first_sale"),
                AuctionPresentation.description(rs.getString("short_description"), 2000),
                AuctionPresentation.description(rs.getString("description"), 4000),
                "https://eaukcija.sud.rs/#/aukcije/" + id), id).stream().findFirst();
    }
    private static Instant instant(OffsetDateTime value) { return value == null ? null : value.toInstant(); }

    // Limits mirror the promoted VARCHAR columns. The complete retained user-facing text is returned,
    // not raw source JSON or parser snippets. Markup is plain text, escaped by Thymeleaf / textContent.
    public record Details(long auctionId, String auctionNumber, String category, String municipality, String placeName,
                          BigDecimal startingPrice, BigDecimal estimatedPrice, Instant startTime, Instant endTime,
                          Instant publicationTime, String statusLabel, boolean firstSale,
                          String shortDescription, String description, String detailUrl) {}
}
