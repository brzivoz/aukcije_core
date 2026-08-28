package rs.sud.eaukcija.sync.persistence;

import java.time.Instant;
import java.util.List;

import rs.sud.eaukcija.model.Auction;
import rs.sud.eaukcija.snapshot.AuctionSourceSnapshot;

public record AuctionPromotionCandidate(
        Auction auction,
        String listingFingerprint,
        Instant detailsFetchedAt,
        Integer sourceDetailCategoryId,
        SaleScope saleScope,
        NormalizedPropertyKind propertyKind,
        List<CategoryMembership> memberships,
        boolean detailRefreshed,
        EnrichmentReason enrichmentReason,
        AuctionSourceSnapshot sourceSnapshot) {

    public AuctionPromotionCandidate {
        SyncPersistenceValidation.required(auction, "auction");
        SyncPersistenceValidation.required(auction.getId(), "auction.id");
        if (auction.getId() <= 0) {
            throw new IllegalArgumentException("auction.id must be positive");
        }
        SyncPersistenceValidation.sha256(listingFingerprint, "listingFingerprint");
        SyncPersistenceValidation.required(detailsFetchedAt, "detailsFetchedAt");
        if (!auction.isDetailsFetched()) {
            throw new IllegalArgumentException("a promoted auction must have valid details");
        }
        if (sourceDetailCategoryId != null && sourceDetailCategoryId <= 0) {
            throw new IllegalArgumentException("sourceDetailCategoryId must be positive");
        }
        SyncPersistenceValidation.required(saleScope, "saleScope");
        SyncPersistenceValidation.required(propertyKind, "propertyKind");
        SyncPersistenceValidation.required(memberships, "memberships");
        memberships = List.copyOf(memberships);
        if (memberships.isEmpty()
                || memberships.stream().noneMatch(membership -> membership.type() == CategoryMembershipType.ROOT)) {
            throw new IllegalArgumentException("a promoted auction must retain a contributing root category");
        }
        SyncPersistenceValidation.required(enrichmentReason, "enrichmentReason");
        SyncPersistenceValidation.required(sourceSnapshot, "sourceSnapshot");
        if (sourceSnapshot.auctionId() != auction.getId()) {
            throw new IllegalArgumentException("sourceSnapshot belongs to another auction");
        }
        if (enrichmentReason == EnrichmentReason.DETAIL_REFRESHED && !detailRefreshed) {
            throw new IllegalArgumentException("DETAIL_REFRESHED requires a refreshed detail response");
        }
    }

    public boolean enrichmentEligible() {
        return enrichmentReason != EnrichmentReason.NONE;
    }
}
