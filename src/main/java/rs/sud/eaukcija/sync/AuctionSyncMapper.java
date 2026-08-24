package rs.sud.eaukcija.sync;

import java.time.Instant;

import rs.sud.eaukcija.client.EAukcijaApiTypes.AuctionDetail;
import rs.sud.eaukcija.client.EAukcijaApiTypes.AuctionSummary;
import rs.sud.eaukcija.model.Auction;

/** Builds a detached candidate; the database is not touched until promotion. */
public final class AuctionSyncMapper {

    private AuctionSyncMapper() {
    }

    public static Auction merge(
            Auction existing,
            AuctionSummary summary,
            AuctionDetail refreshedDetail,
            String listingFingerprint,
            Instant detailFetchedAt) {
        Auction auction = existing == null ? new Auction() : existing;
        auction.setId(summary.id());
        auction.setAuctionNumber(summary.auctionNumber());
        auction.setStartDate(instant(summary.startDate()));
        auction.setEndDate(instant(summary.endDate()));
        auction.setStartingPrice(summary.startingPrice());
        auction.setCurrentPrice(summary.currentPrice());
        auction.setMaxOfferedPrice(summary.maxOfferedPrice());
        auction.setShortDescription(summary.shortDescription());
        auction.setStatus(summary.status());
        auction.setFirstSale(summary.firstSale());
        auction.setPropertyType(summary.propertyType());
        auction.setListingFingerprint(listingFingerprint);

        if (refreshedDetail != null) {
            auction.setPublicationDate(instant(refreshedDetail.publicationDate()));
            auction.setEstimatedPrice(refreshedDetail.estimatedPrice());
            auction.setBidStep(refreshedDetail.bidStep());
            auction.setDescription(refreshedDetail.description());
            auction.setExecutorName(refreshedDetail.executorName());
            auction.setCategoryName(refreshedDetail.category() == null ? null : refreshedDetail.category().name());
            auction.setSourceDetailCategoryId(
                    refreshedDetail.category() == null ? null : refreshedDetail.category().id());
            if (refreshedDetail.place() == null) {
                auction.setPlaceName(null);
                auction.setPlaceZipCode(null);
                auction.setMunicipality(null);
                auction.setCadastral(null);
            } else {
                auction.setPlaceName(refreshedDetail.place().name());
                auction.setPlaceZipCode(refreshedDetail.place().zipCode());
                auction.setMunicipality(refreshedDetail.place().municipality());
                auction.setCadastral(refreshedDetail.place().cadastral());
            }
            auction.setDetailsFetched(true);
            auction.setDetailsFetchedAt(detailFetchedAt);
        }
        return auction;
    }

    private static Instant instant(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }
}
