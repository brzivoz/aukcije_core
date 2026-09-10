package rs.sud.eaukcija.sync.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import rs.sud.eaukcija.history.SourceHistoryService;
import rs.sud.eaukcija.model.Auction;
import rs.sud.eaukcija.snapshot.AuctionSourceSnapshotFactory;

/** Offline fixture using the real success gates and immutable publication transaction. */
public class SourcePublicationFixture {
    public static final Instant START = Instant.parse("2026-08-20T08:00:00Z");
    public static final Instant END = Instant.parse("2099-08-30T08:00:00Z");
    private final DataSource dataSource;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    public SourcePublicationFixture(DataSource dataSource, ObjectMapper json, PlatformTransactionManager manager) {
        this.dataSource = dataSource; this.json = json; tx = new TransactionTemplate(manager);
    }
    public SourceHistoryService.Frame publish(Instant at, AuctionPromotionCandidate... candidates) throws Exception {
        return publish(at, false, candidates);
    }
    public SourceHistoryService.Frame publish(Instant at, boolean earlierPolicy, AuctionPromotionCandidate... candidates) throws Exception {
        var runs = new SyncRunRepository(dataSource, json, Clock.fixed(at, ZoneOffset.UTC)) {
            @Override public void publishSourceHistory(rs.sud.eaukcija.history.SourceHistoryPublisher.Publication publication, Duration grace) {
                super.publishSourceHistory(publication, grace);
                // Same RUNNING-boundary policy-upgrade fixture as #11; terminal evidence remains immutable.
                if (earlierPolicy) new JdbcTemplate(dataSource).update(
                        "UPDATE sync_run_auction_observations SET comparison_policy='source-review-v0' WHERE run_id=?", publication.runId());
            }
        };
        String hash = "56".repeat(32);
        var run = tx.execute(s -> runs.claim(new SyncRunClaimRequest(UUID.randomUUID().toString(), List.of(7), 3000, SyncTriggerKind.MANUAL)));
        var taxonomy = json.readTree("[{\"value\":7,\"children\":[{\"value\":47,\"children\":[]}]}]");
        int count = candidates.length;
        tx.executeWithoutResult(s -> {
            runs.recordTaxonomy(new TaxonomySnapshot(hash, "fixture-v1", taxonomy, at));
            runs.updateProgress(run.runId(), new SyncRunProgress(SyncRunStage.CATEGORIES, hash, at,
                    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
            runs.recordRootResult(run.runId(), new SyncRunRootResult(7, count, count, count, 0, 1, 1, true, true));
            runs.recordChildResult(run.runId(), new SyncRunChildResult(7, 47, count, count, count, 0, 1, 1, true, true, true));
            runs.updateProgress(run.runId(), new SyncRunProgress(SyncRunStage.PROMOTING, hash, at,
                    2, 2, count, count, 0, 0, count, count, count, 0, 0, 0, 0));
            var promoter = new AuctionPromotionService(runs); promoter.setAbsenceGrace(Duration.ZERO);
            promoter.promote(run.runId(), hash, at, List.of(candidates));
        });
        return new SourceHistoryService(new JdbcTemplate(dataSource)).capture(at);
    }
    public static void point(JdbcTemplate jdbc, long auctionId, String wkt) {
        UUID reference = UUID.randomUUID(), geometry = UUID.randomUUID(), attempt = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO property_references(id, auction_id, reference_order, reference_type, source_field,
                    parser_version, extraction_status, canonical_key)
                VALUES (?, ?, 0, 'STRUCTURED_LOCATION', 'fixture', 'legacy', 'EXTRACTED', ?)
                """, reference, auctionId, reference.toString());
        jdbc.update("""
                INSERT INTO spatial_resolution_geometries(id, source_geometry, source_crs_authority, source_crs_code,
                    original_geometry_valid, make_valid_applied)
                VALUES (?, ST_GeomFromText(?, 4326), 'EPSG', 4326, true, false)
                """, geometry, wkt);
        jdbc.update("""
                INSERT INTO location_resolution_attempts(id, property_reference_id, resolver, resolver_version,
                    input_fingerprint, source_dataset, source_dataset_version, source_dataset_sha256,
                    resolution_status, location_precision, geometry_id, confidence_reason, candidate_evidence,
                    attempted_at, completed_at, resolved_at)
                VALUES (?, ?, 'fixture', 'legacy', repeat('a',64), 'fixture', 'v1', repeat('b',64),
                    'RESOLVED', 'ADDRESS', ?, 'offline fixture', '[]'::jsonb, now(), now(), now())
                """, attempt, reference, geometry);
        jdbc.update("INSERT INTO current_location_resolutions(property_reference_id, resolution_attempt_id, selected_at, selection_reason) VALUES (?, ?, now(), 'offline fixture')", reference, attempt);
    }
    public AuctionPromotionCandidate candidate(long id, String description) { return candidate(id, description, "100", "10", END, "Verified"); }
    public AuctionPromotionCandidate candidate(long id, String description, String price, String current, Instant end, String status) {
        Auction a = new Auction(); a.setId(id); a.setAuctionNumber("H" + id); a.setStartingPrice(new BigDecimal(price));
        a.setStartDate(START); a.setEndDate(end); a.setStatus(status); a.setCategoryName("Непокретности");
        a.setDescription(description); a.setShortDescription("Безбедни кратак опис"); a.setMunicipality("Београд"); a.setPlaceName("Вождовац");
        a.setDetailsFetched(true); a.setFirstSale(true);
        ObjectNode listing = json.createObjectNode(); listing.put("Id", id); listing.put("StartDate", START.toString());
        listing.put("EndDate", end.toString()); listing.put("Status", status); listing.put("StartingPrice", price);
        listing.put("CurrentPrice", current); listing.put("AuctionNumber", "H" + id);
        var detail = listing.deepCopy(); detail.put("Description", description);
        var snapshot = new AuctionSourceSnapshotFactory(json).create(id, listing, detail, SaleScope.IMMOVABLE, START, START);
        return new AuctionPromotionCandidate(a, "b".repeat(64), START, 47, SaleScope.IMMOVABLE, NormalizedPropertyKind.PARCEL,
                List.of(new CategoryMembership(7, CategoryMembershipType.ROOT, "Непокретности"),
                        new CategoryMembership(47, CategoryMembershipType.CHILD, "Парцела")), true, EnrichmentReason.DETAIL_REFRESHED, snapshot);
    }
}
