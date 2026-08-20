package rs.sud.eaukcija.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import rs.sud.eaukcija.client.EAukcijaClient;
import rs.sud.eaukcija.client.EAukcijaApiTypes.*;
import rs.sud.eaukcija.model.Auction;
import rs.sud.eaukcija.repository.AuctionRepository;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class SyncService {

    private static final Logger log = LoggerFactory.getLogger(SyncService.class);

    private final EAukcijaClient client;
    private final AuctionRepository repo;
    private final int categoryId;
    private final int pageSize;

    private final AtomicBoolean syncing = new AtomicBoolean(false);
    private final AtomicInteger progress = new AtomicInteger(0);
    private volatile int totalPages = 0;
    private volatile String syncStatus = "idle";

    public SyncService(
            EAukcijaClient client,
            AuctionRepository repo,
            @Value("${eaukcija.api.category-id}") int categoryId,
            @Value("${eaukcija.api.page-size}") int pageSize
    ) {
        this.client = client;
        this.repo = repo;
        this.categoryId = categoryId;
        this.pageSize = pageSize;
    }

    public String getSyncStatus() { return syncStatus; }
    public int getProgress() { return progress.get(); }
    public int getTotalPages() { return totalPages; }
    public boolean isSyncing() { return syncing.get(); }

    /**
     * Sync all auction listings (basic info) from all pages.
     */
    public void syncListings() {
        if (!syncing.compareAndSet(false, true)) {
            log.info("Sync already in progress");
            return;
        }

        try {
            syncStatus = "Fetching listings...";
            progress.set(0);

            // First call to get total count
            var firstPage = client.getAuctionsByCategory(categoryId, pageSize, 1);
            int totalCount = firstPage.data().totalCount();
            totalPages = (int) Math.ceil((double) totalCount / pageSize);
            log.info("Total auctions: {}, pages: {}", totalCount, totalPages);

            saveAuctionSummaries(firstPage.data().auctions());
            progress.set(1);

            for (int page = 2; page <= totalPages; page++) {
                try {
                    syncStatus = "Fetching page " + page + "/" + totalPages;
                    var pageData = client.getAuctionsByCategory(categoryId, pageSize, page);
                    saveAuctionSummaries(pageData.data().auctions());
                    progress.set(page);

                    // Be polite with rate limiting
                    Thread.sleep(200);
                } catch (Exception e) {
                    log.error("Error fetching page {}: {}", page, e.getMessage());
                }
            }

            syncStatus = "Listings sync complete. " + repo.count() + " auctions loaded.";
            log.info(syncStatus);
        } finally {
            syncing.set(false);
        }
    }

    /**
     * Fetch details for auctions that don't have them yet (gets location info).
     */
    public void syncDetails() {
        if (!syncing.compareAndSet(false, true)) {
            log.info("Sync already in progress");
            return;
        }

        try {
            List<Auction> unfetched = repo.findAll().stream()
                    .filter(a -> !a.isDetailsFetched())
                    .toList();

            int total = unfetched.size();
            log.info("Fetching details for {} auctions", total);
            progress.set(0);
            totalPages = total;

            for (int i = 0; i < unfetched.size(); i++) {
                Auction a = unfetched.get(i);
                syncStatus = "Fetching details " + (i + 1) + "/" + total + " (ID: " + a.getId() + ")";

                try {
                    var detail = client.getImmovablePropertyDetails(a.getId());
                    if (detail.data() != null) {
                        updateAuctionFromDetail(a, detail.data());
                    }
                    a.setDetailsFetched(true);
                    repo.save(a);

                    // Rate limit
                    Thread.sleep(150);
                } catch (Exception e) {
                    log.error("Error fetching detail for auction {}: {}", a.getId(), e.getMessage());
                }

                progress.set(i + 1);
            }

            syncStatus = "Details sync complete. " + repo.countByDetailsFetched(true) + " auctions with details.";
            log.info(syncStatus);
        } finally {
            syncing.set(false);
        }
    }

    private void saveAuctionSummaries(List<AuctionSummary> summaries) {
        for (var s : summaries) {
            if (repo.existsById(s.id())) continue;

            Auction a = new Auction();
            a.setId(s.id());
            a.setAuctionNumber(s.auctionNumber());
            a.setStartDate(parseInstant(s.startDate()));
            a.setEndDate(parseInstant(s.endDate()));
            a.setStartingPrice(s.startingPrice());
            a.setCurrentPrice(s.currentPrice());
            a.setMaxOfferedPrice(s.maxOfferedPrice());
            a.setShortDescription(s.shortDescription());
            a.setStatus(s.status());
            a.setFirstSale(s.firstSale());
            a.setPropertyType(s.propertyType());
            a.setDetailsFetched(false);
            repo.save(a);
        }
    }

    private void updateAuctionFromDetail(Auction a, AuctionDetail d) {
        a.setPublicationDate(parseInstant(d.publicationDate()));
        a.setEstimatedPrice(d.estimatedPrice());
        a.setBidStep(d.bidStep());
        a.setDescription(d.description());
        a.setExecutorName(d.executorName());

        if (d.category() != null) {
            a.setCategoryName(d.category().name());
        }
        if (d.place() != null) {
            a.setPlaceName(d.place().name());
            a.setPlaceZipCode(d.place().zipCode());
            a.setMunicipality(d.place().municipality());
            a.setCadastral(d.place().cadastral());
        }
    }

    private Instant parseInstant(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) return null;
        try {
            return Instant.parse(dateStr);
        } catch (Exception e) {
            return null;
        }
    }
}
