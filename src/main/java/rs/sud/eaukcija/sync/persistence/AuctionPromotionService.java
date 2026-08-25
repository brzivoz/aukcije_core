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

/** Publishes one complete source snapshot as a single PostgreSQL transaction. */
@Service
public class AuctionPromotionService {

    private final SyncRunRepository runs;

    public AuctionPromotionService(SyncRunRepository runs) {
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
        promote(runId, taxonomySha256, observedAt, candidates, List.of(), List.of());
    }

    @Transactional
    public void promote(
            UUID runId,
            String taxonomySha256,
            Instant observedAt,
            List<AuctionPromotionCandidate> candidates,
            List<AuctionDetailQuarantine> quarantines) {
        promote(runId, taxonomySha256, observedAt, candidates, quarantines, List.of());
    }

    @Transactional
    public void promote(
            UUID runId,
            String taxonomySha256,
            Instant observedAt,
            List<AuctionPromotionCandidate> candidates,
            List<AuctionDetailQuarantine> detailQuarantines,
            List<AuctionListingQuarantine> listingQuarantines) {
        SyncPersistenceValidation.required(runId, "runId");
        SyncPersistenceValidation.sha256(taxonomySha256, "taxonomySha256");
        SyncPersistenceValidation.required(observedAt, "observedAt");
        SyncPersistenceValidation.required(candidates, "candidates");
        SyncPersistenceValidation.required(detailQuarantines, "detailQuarantines");
        SyncPersistenceValidation.required(listingQuarantines, "listingQuarantines");
        candidates = List.copyOf(candidates);
        detailQuarantines = List.copyOf(detailQuarantines);
        listingQuarantines = List.copyOf(listingQuarantines);
        validateUniqueEvidence(candidates, detailQuarantines, listingQuarantines);

        SyncRunView run = runs.lockRunningForPromotion(runId);
        if (!taxonomySha256.equals(run.categoryTreeSha256())) {
            throw new SyncRunStateException("promotion taxonomy does not match the run observation");
        }
        if (run.categoryTreeObservedAt() == null
                || !observedAt.equals(run.categoryTreeObservedAt())) {
            throw new SyncRunStateException("promotion taxonomy observation time does not match the run");
        }
        if (run.uniqueAuctionCount()
                != candidates.size() + detailQuarantines.size() + listingQuarantines.size()) {
            throw new SyncRunStateException(
                    "promotion candidates and quarantines do not match the complete root union");
        }
        if (run.detailsQuarantined() != detailQuarantines.size()) {
            throw new SyncRunStateException(
                    "promotion detail quarantine count does not match the durable run progress");
        }
        if (run.listingRowsQuarantined() != listingQuarantines.size()) {
            throw new SyncRunStateException(
                    "promotion listing quarantine count does not match the durable run progress");
        }
        runs.assertCompleteRoots(runId, run.configuredRoots().size());
        runs.assertCompleteChildren(runId);
        validateMembershipScope(run, runs.childResults(runId), candidates);

        for (AuctionPromotionCandidate candidate : candidates) {
            prepareAuction(runId, taxonomySha256, observedAt, candidate);
        }
        runs.upsertAuctions(candidates);

        runs.replaceMemberships(runId, taxonomySha256, candidates);
        runs.insertSuccessObservations(runId, candidates);
        runs.insertDetailQuarantines(runId, detailQuarantines);
        runs.insertListingQuarantines(runId, listingQuarantines);
        runs.incrementAbsencesForUnobservedInScope(runId);

        // Mark success before queue insertion so the database trigger can prove
        // the success-only gate. Both statements are in this transaction, so a
        // downstream-publication failure rolls the status and every auction
        // mutation back.
        runs.markSucceeded(runId);
        runs.publishEnrichmentInputSnapshots(runId, candidates);
        runs.insertEnrichmentWork(runId, candidates);
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

    private static void validateUniqueEvidence(
            List<AuctionPromotionCandidate> candidates,
            List<AuctionDetailQuarantine> detailQuarantines,
            List<AuctionListingQuarantine> listingQuarantines) {
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
        for (AuctionDetailQuarantine quarantine : detailQuarantines) {
            if (!auctionIds.add(quarantine.auctionId())) {
                throw new IllegalArgumentException(
                        "promotion contains duplicate auction id " + quarantine.auctionId());
            }
        }
        for (AuctionListingQuarantine quarantine : listingQuarantines) {
            if (!auctionIds.add(quarantine.auctionId())) {
                throw new IllegalArgumentException(
                        "promotion contains duplicate auction id " + quarantine.auctionId());
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
