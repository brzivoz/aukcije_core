package rs.sud.eaukcija.browser;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;

import rs.sud.eaukcija.model.Auction;
import rs.sud.eaukcija.repository.AuctionRepository;
import rs.sud.eaukcija.testsupport.PostgisTestContainer;

/** Boots the real HTTP application against migrated PostGIS and deterministic data. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class PostgisBrowserFixture {

    protected static final long SEEDED_AUCTION_ID = 34001L;

    @ServiceConnection(name = "postgresql")
    static final PostgreSQLContainer<?> POSTGIS = PostgisTestContainer.shared();

    @LocalServerPort
    private int serverPort;

    @Autowired
    private AuctionRepository auctions;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seedDeterministicAuction() {
        clearBrowserData();

        Auction auction = new Auction();
        auction.setId(SEEDED_AUCTION_ID);
        auction.setAuctionNumber("Н34-001");
        auction.setStartDate(Instant.parse("2026-08-20T08:00:00Z"));
        auction.setEndDate(Instant.parse("2026-08-30T08:00:00Z"));
        auction.setStartingPrice(new BigDecimal("123456.00"));
        auction.setShortDescription("Детерминистичка browser-test аукција");
        auction.setMunicipality("Београд");
        auction.setPlaceName("Вождовац");
        auction.setCategoryName("Непокретности");
        auction.setStatus("Verified");
        auction.setFirstSale(true);
        auction.setDetailsFetched(true);
        auctions.saveAndFlush(auction);
    }

    @AfterEach
    void clearDeterministicAuction() {
        clearBrowserData();
    }

    protected URI applicationUri() {
        return URI.create("http://localhost:" + serverPort + "/");
    }

    /** Clears the complete browser-owned spatial graph, including append-only evidence. */
    protected void clearBrowserData() {
        jdbc.execute("""
                TRUNCATE TABLE
                    coarse_location_resolution_runs,
                    current_location_resolutions,
                    location_resolution_attempts,
                    location_resolution_cache_records,
                    property_references,
                    spatial_resolution_geometries,
                    parcel_identities,
                    auction_structured_ko_matches,
                    auctions
                RESTART IDENTITY CASCADE
                """);
    }
}
