package rs.sud.eaukcija.sync.persistence;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rs.sud.eaukcija.model.Auction;
import rs.sud.eaukcija.repository.AuctionRepository;

/** Publishes one complete source snapshot as a single PostgreSQL transaction. */
@Service
public class AuctionPromotionService {

    private final AuctionRepository auctions;
    private final SyncRunRepository runs;

    public AuctionPromotionService(AuctionRepository auctions, SyncRunRepository runs) {
        this.auctions = auctions;
        this.runs = runs;
    }

    /**
     * Atomically promotes current auction state, exact successful-run
     * observations, absence counters, and enrichment work. Any exception rolls
     * all of them back and leaves the run RUNNING for the caller to terminalize.
     */
    @Transactional
    public void promote(
            UUID runId,
            String taxonomySha256,
            Instant observedAt,
            List<AuctionPromotionCandidate> candidates) {
        SyncPersistenceValidation.required(runId, "runId");
        SyncPersistenceValidation.sha256(taxonomySha256, "taxonomySha256");
        SyncPersistenceValidation.required(observedAt, "observedAt");
        SyncPersistenceValidation.required(candidates, "candidates");
        candidates = List.copyOf(candidates);
        validateUniqueCandidates(candidates);

        SyncRunView run = runs.lockRunningForPromotion(runId);
        if (!taxonomySha256.equals(run.categoryTreeSha256())) {
            throw new SyncRunStateException("promotion taxonomy does not match the run observation");
        }
        if (run.categoryTreeObservedAt() == null
                || !observedAt.equals(run.categoryTreeObservedAt())) {
            throw new SyncRunStateException("promotion taxonomy observation time does not match the run");
        }
        if (run.uniqueAuctionCount() != candidates.size()) {
            throw new SyncRunStateException(
                    "promotion candidate count does not match the complete root union");
        }
        runs.assertCompleteRoots(runId, run.configuredRoots().size());
        runs.assertCompleteChildren(runId);
        validateMembershipScope(run, runs.childResults(runId), candidates);

        for (AuctionPromotionCandidate candidate : candidates) {
            prepareAuction(runId, taxonomySha256, observedAt, candidate);
        }
        auctions.saveAllAndFlush(candidates.stream().map(AuctionPromotionCandidate::auction).toList());

        for (AuctionPromotionCandidate candidate : candidates) {
            runs.replaceMemberships(runId, taxonomySha256, candidate);
            runs.insertSuccessObservation(runId, candidate);
        }
        runs.incrementAbsencesForUnobservedInScope(runId);

        // Mark success before queue insertion so the database trigger can prove
        // the success-only gate. Both statements are in this transaction, so a
        // queue failure rolls the status and every auction mutation back.
        runs.markSucceeded(runId);
        for (AuctionPromotionCandidate candidate : candidates) {
            runs.insertEnrichmentWork(runId, candidate);
        }
    }

    private static void prepareAuction(
            UUID runId,
            String taxonomySha256,
            Instant observedAt,
            AuctionPromotionCandidate candidate) {
        Auction auction = candidate.auction();
        auction.setListingFingerprint(candidate.listingFingerprint());
        auction.setDetailsFetched(true);
        auction.setDetailsFetchedAt(candidate.detailsFetchedAt());
        auction.setSourceDetailCategoryId(candidate.sourceDetailCategoryId());
        auction.setSaleScope(candidate.saleScope());
        auction.setNormalizedPropertyKind(candidate.propertyKind());
        auction.setTaxonomySha256(taxonomySha256);
        auction.setLastSuccessfulSyncRunId(runId);
        auction.setAbsenceCount(0);
        auction.setLastSeenAt(observedAt);
    }

    private static void validateUniqueCandidates(List<AuctionPromotionCandidate> candidates) {
        Set<Long> auctionIds = new HashSet<>();
        for (AuctionPromotionCandidate candidate : candidates) {
            if (!auctionIds.add(candidate.auction().getId())) {
                throw new IllegalArgumentException(
                        "promotion contains duplicate auction id " + candidate.auction().getId());
            }
            Set<String> memberships = new HashSet<>();
            for (CategoryMembership membership : candidate.memberships()) {
                String key = membership.categoryId() + ":" + membership.type();
                if (!memberships.add(key)) {
                    throw new IllegalArgumentException(
                            "promotion contains duplicate category membership for auction "
                                    + candidate.auction().getId());
                }
            }
        }
    }

    private static void validateMembershipScope(
            SyncRunView run,
            List<SyncRunChildResult> childResults,
            List<AuctionPromotionCandidate> candidates) {
        Set<Integer> configuredRoots = Set.copyOf(run.configuredRoots());
        Map<Integer, Integer> childParents = new HashMap<>();
        for (SyncRunChildResult child : childResults) {
            Integer previous = childParents.putIfAbsent(
                    child.childCategoryId(), child.parentRootCategoryId());
            if (previous != null && previous != child.parentRootCategoryId()) {
                throw new SyncRunStateException(
                        "captured child category belongs to more than one configured root");
            }
        }
        for (AuctionPromotionCandidate candidate : candidates) {
            Set<Integer> candidateRoots = new HashSet<>();
            candidate.memberships().stream()
                    .filter(membership -> membership.type() == CategoryMembershipType.ROOT)
                    .forEach(membership -> candidateRoots.add(membership.categoryId()));
            for (CategoryMembership membership : candidate.memberships()) {
                if (membership.type() == CategoryMembershipType.ROOT
                        && !configuredRoots.contains(membership.categoryId())) {
                    throw new SyncRunStateException(
                            "promotion root membership is outside the run configured roots");
                }
                if (membership.type() == CategoryMembershipType.CHILD) {
                    Integer parentRoot = childParents.get(membership.categoryId());
                    if (parentRoot == null || !candidateRoots.contains(parentRoot)) {
                        throw new SyncRunStateException(
                                "promotion child membership is outside its captured parent root");
                    }
                }
            }
        }
    }
}
