package rs.sud.eaukcija.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import rs.sud.eaukcija.client.EAukcijaApiTypes.AuctionDetail;
import rs.sud.eaukcija.client.EAukcijaApiTypes.AuctionListData;
import rs.sud.eaukcija.client.EAukcijaApiTypes.AuctionSummary;
import rs.sud.eaukcija.client.EAukcijaApiTypes.CategoryNode;
import rs.sud.eaukcija.client.EAukcijaApiTypes.CategoryTree;
import rs.sud.eaukcija.client.EAukcijaCallResult;
import rs.sud.eaukcija.client.EAukcijaClient;
import rs.sud.eaukcija.client.EAukcijaClientException;
import rs.sud.eaukcija.client.EAukcijaClientProperties;
import rs.sud.eaukcija.model.Auction;
import rs.sud.eaukcija.repository.AuctionRepository;
import rs.sud.eaukcija.sync.AuctionSyncMapper;
import rs.sud.eaukcija.sync.ListingFingerprint;
import rs.sud.eaukcija.sync.SyncFailure;
import rs.sud.eaukcija.sync.SyncProgressTracker;
import rs.sud.eaukcija.sync.SyncProperties;
import rs.sud.eaukcija.sync.TaxonomyClassifier;
import rs.sud.eaukcija.sync.persistence.AuctionPromotionCandidate;
import rs.sud.eaukcija.sync.persistence.AuctionPromotionService;
import rs.sud.eaukcija.sync.persistence.CategoryMembership;
import rs.sud.eaukcija.sync.persistence.CategoryMembershipType;
import rs.sud.eaukcija.sync.persistence.EnrichmentReason;
import rs.sud.eaukcija.sync.persistence.NormalizedPropertyKind;
import rs.sud.eaukcija.sync.persistence.PersistedSyncRunError;
import rs.sud.eaukcija.sync.persistence.SaleScope;
import rs.sud.eaukcija.sync.persistence.SyncRunClaimRequest;
import rs.sud.eaukcija.sync.persistence.SyncRunClaimResult;
import rs.sud.eaukcija.sync.persistence.SyncRunChildResult;
import rs.sud.eaukcija.sync.persistence.SyncRunErrorEvidence;
import rs.sud.eaukcija.sync.persistence.SyncRunRepository;
import rs.sud.eaukcija.sync.persistence.SyncRunRootResult;
import rs.sud.eaukcija.sync.persistence.SyncRunStage;
import rs.sud.eaukcija.sync.persistence.SyncRunStatus;
import rs.sud.eaukcija.sync.persistence.SyncRunView;
import rs.sud.eaukcija.sync.persistence.SyncTriggerKind;
import rs.sud.eaukcija.sync.persistence.TaxonomySnapshot;
import rs.sud.eaukcija.sync.persistence.WorkerLockLease;

/** Complete, durable, source-safe eAukcija synchronization orchestration. */
@Service
public class SyncService {

    static final String TAXONOMY_NORMALIZER_VERSION = "eaukcija-taxonomy-v1";

    private static final Logger log = LoggerFactory.getLogger(SyncService.class);

    private final EAukcijaClient client;
    private final EAukcijaClientProperties clientProperties;
    private final SyncProperties syncProperties;
    private final SyncRunRepository runs;
    private final AuctionRepository auctions;
    private final AuctionPromotionService promotion;
    private final TaskExecutor executor;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public SyncService(
            EAukcijaClient client,
            EAukcijaClientProperties clientProperties,
            SyncProperties syncProperties,
            SyncRunRepository runs,
            AuctionRepository auctions,
            AuctionPromotionService promotion,
            @Qualifier("syncRunExecutor") TaskExecutor executor,
            ObjectMapper objectMapper) {
        this(client, clientProperties, syncProperties, runs, auctions,
                promotion, executor, objectMapper, Clock.systemUTC());
    }

    SyncService(
            EAukcijaClient client,
            EAukcijaClientProperties clientProperties,
            SyncProperties syncProperties,
            SyncRunRepository runs,
            AuctionRepository auctions,
            AuctionPromotionService promotion,
            TaskExecutor executor,
            ObjectMapper objectMapper,
            Clock clock) {
        this.client = client;
        this.clientProperties = clientProperties;
        this.syncProperties = syncProperties;
        this.runs = runs;
        this.auctions = auctions;
        this.promotion = promotion;
        this.executor = executor;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public SyncRunClaimResult startManual(UUID idempotencyKey) {
        return start(idempotencyKey, SyncTriggerKind.MANUAL);
    }

    public SyncRunClaimResult startScheduled(UUID idempotencyKey) {
        return start(idempotencyKey, SyncTriggerKind.SCHEDULED);
    }

    public Optional<SyncRunView> findRun(UUID runId) {
        return syncProperties.isEnabled() ? runs.find(runId) : Optional.empty();
    }

    public Optional<SyncRunView> findLatestRun() {
        return syncProperties.isEnabled() ? runs.findLatest() : Optional.empty();
    }

    public List<PersistedSyncRunError> errors(UUID runId) {
        return syncProperties.isEnabled() ? runs.errors(runId) : List.of();
    }

    public List<SyncRunRootResult> rootResults(UUID runId) {
        return syncProperties.isEnabled() ? runs.rootResults(runId) : List.of();
    }

    public List<SyncRunChildResult> childResults(UUID runId) {
        return syncProperties.isEnabled() ? runs.childResults(runId) : List.of();
    }

    public Optional<UUID> activeRunId() {
        return syncProperties.isEnabled() ? runs.activeRunId() : Optional.empty();
    }

    public boolean isEnabled() {
        return syncProperties.isEnabled();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverStaleRunsAfterStartup() {
        try {
            recoverStaleRuns();
        } catch (RuntimeException recoveryFailure) {
            // Application-event infrastructure logs uncaught listener failures with
            // their complete cause chain. Source and database messages are not safe
            // operator evidence, so retain only a stable diagnostic code here. A
            // later trigger repeats recovery before it can claim a new run.
            log.error("Could not recover stale eAukcija sync runs code=STALE_RECOVERY_FAILED");
        }
    }

    public List<UUID> recoverStaleRuns() {
        if (!syncProperties.isEnabled()) {
            return List.of();
        }
        Optional<WorkerLockLease> acquired = runs.tryAcquireWorkerLock();
        if (acquired.isEmpty()) {
            return List.of();
        }
        try (WorkerLockLease lease = acquired.orElseThrow()) {
            List<UUID> recovered = runs.recoverOrphanedRunningRuns(
                    lease, syncProperties.getRunningStaleAfter());
            recovered.forEach(runId -> log.warn(
                    "Recovered stale eAukcija sync run runId={} code=STALE_RUN_RECOVERED", runId));
            return recovered;
        }
    }

    private SyncRunClaimResult start(UUID idempotencyKey, SyncTriggerKind triggerKind) {
        if (!syncProperties.isEnabled()) {
            throw new SyncUnavailableException("durable sync is unavailable for the active database profile");
        }
        recoverStaleRuns();
        SyncRunClaimResult claim = runs.claim(new SyncRunClaimRequest(
                idempotencyKey.toString(),
                clientProperties.getRootCategoryIds(),
                clientProperties.getPageSize(),
                triggerKind));
        if (claim.replayed()) {
            return claim;
        }
        try {
            executor.execute(() -> executeClaimedRun(claim.runId()));
        } catch (RejectedExecutionException failure) {
            failSubmission(claim.runId());
            throw new SyncSubmissionException(claim.runId());
        } catch (RuntimeException failure) {
            // Any synchronous handoff failure leaves the already-claimed run
            // recoverable and gives the caller its durable coordinates.
            failSubmission(claim.runId());
            throw new SyncSubmissionException(claim.runId());
        }
        return claim;
    }

    private void failSubmission(UUID runId) {
        SyncProgressTracker tracker = new SyncProgressTracker();
        tracker.error();
        try {
            runs.appendError(runId, SyncFailure.contract(
                    SyncRunStage.CATEGORIES, "EXECUTOR_REJECTED", null, null, null).evidence());
            runs.finishIncomplete(runId, SyncRunStatus.FAILED,
                    tracker.snapshot(SyncRunStage.CATEGORIES));
        } catch (RuntimeException persistenceFailure) {
            log.error("Could not terminalize rejected eAukcija sync run runId={} code=RUN_LEDGER_FAILURE", runId);
        }
    }

    private void executeClaimedRun(UUID runId) {
        SyncProgressTracker tracker = new SyncProgressTracker();
        Optional<WorkerLockLease> acquired;
        try {
            acquired = acquireWorkerLock();
        } catch (SyncFailure failure) {
            terminalize(runId, tracker, failure);
            return;
        }
        if (acquired.isEmpty()) {
            terminalize(runId, tracker, SyncFailure.contract(
                    SyncRunStage.CATEGORIES, "WORKER_LOCK_UNAVAILABLE", null, null, null));
            return;
        }

        WorkerLockLease lease = acquired.orElseThrow();
        try {
            // Recovery may have terminalized a task that sat unscheduled past
            // the stale threshold. Never let that late task call the source or
            // block a replacement run after it finally acquires the lock.
            if (!runs.isRunning(runId)) {
                log.info("Skipped terminal eAukcija sync task runId={} code=RUN_NO_LONGER_ACTIVE", runId);
                return;
            }
            runSourceAndPromote(runId, tracker);
        } catch (SyncFailure failure) {
            terminalize(runId, tracker, failure);
        } catch (RuntimeException unexpected) {
            terminalize(runId, tracker, SyncFailure.contract(
                    SyncRunStage.PROMOTING, "INTERNAL", null, null, null));
        } finally {
            try {
                lease.close();
            } catch (RuntimeException releaseFailure) {
                // Closing the dedicated JDBC connection releases the session lock
                // even when the explicit unlock acknowledgement failed. A release
                // failure after promotion must not rewrite a SUCCEEDED run.
                log.error("Could not release eAukcija worker lock runId={} code=WORKER_LOCK_RELEASE_FAILURE",
                        runId);
            }
        }
    }

    private Optional<WorkerLockLease> acquireWorkerLock() {
        for (int attempt = 1; attempt <= 5; attempt++) {
            try {
                Optional<WorkerLockLease> acquired = runs.tryAcquireWorkerLock();
                if (acquired.isPresent() || attempt == 5) {
                    return acquired;
                }
                Thread.sleep(100);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            } catch (RuntimeException lockFailure) {
                throw SyncFailure.contract(
                        SyncRunStage.CATEGORIES, "WORKER_LOCK_FAILURE", null, null, null);
            }
        }
        return Optional.empty();
    }

    private void runSourceAndPromote(UUID runId, SyncProgressTracker tracker) {
        EAukcijaCallResult<CategoryTree> taxonomyCall = fetchTaxonomy();
        tracker.retries(taxonomyCall.retries());
        CategoryTree taxonomy = taxonomyCall.data();
        Instant observedAt = Instant.now(clock);
        persistTaxonomy(runId, taxonomy, observedAt, tracker);

        TaxonomyIndex taxonomyIndex = new TaxonomyIndex(taxonomy);
        TaxonomyClassifier classifier = new TaxonomyClassifier(taxonomy);
        try {
            classifier.validateConfiguredRoots(clientProperties.getRootCategoryIds());
        } catch (IllegalArgumentException drift) {
            throw SyncFailure.contract(
                    SyncRunStage.CATEGORIES, "CATEGORY_DRIFT", null, null, null);
        }
        Map<Long, StagedAuction> union = new LinkedHashMap<>();
        Map<Integer, Set<Long>> rootAuctionIds = new LinkedHashMap<>();
        long[] totalRows = {0};
        for (int rootId : clientProperties.getRootCategoryIds()) {
            rootAuctionIds.put(rootId,
                    fetchCompleteRoot(runId, rootId, taxonomyIndex, union, totalRows, tracker));
        }
        // Retain the exact expected child set before any child request. A crash
        // or first-page timeout therefore still leaves attributable, immutable
        // incomplete evidence for every child captured by this taxonomy.
        for (int rootId : clientProperties.getRootCategoryIds()) {
            for (CategoryNode child : taxonomyIndex.directChildren(rootId)) {
                runs.recordChildResult(
                        runId,
                        new ChildAccumulator(rootId, child.value()).result(false));
            }
        }
        for (int rootId : clientProperties.getRootCategoryIds()) {
            for (CategoryNode child : taxonomyIndex.directChildren(rootId)) {
                fetchCompleteChild(
                        runId,
                        rootId,
                        child,
                        rootAuctionIds.get(rootId),
                        union,
                        totalRows,
                        tracker);
            }
        }

        tracker.listingCounts(totalRows[0], union.size(), totalRows[0] - union.size());
        runs.updateProgress(runId, tracker.snapshot(SyncRunStage.LISTINGS));

        Map<Long, Auction> existing = new HashMap<>();
        auctions.findAllById(union.keySet()).forEach(auction -> existing.put(auction.getId(), auction));
        Instant staleBefore = observedAt.minus(syncProperties.getDetailStaleAfter());
        List<StagedAuction> ordered = union.values().stream()
                .sorted(Comparator.comparingLong(staged -> staged.summary.id()))
                .toList();
        for (StagedAuction staged : ordered) {
            try {
                staged.classification = classifier.classify(
                        staged.contributingRoots,
                        staged.contributingChildren);
            } catch (IllegalArgumentException drift) {
                throw SyncFailure.contract(
                        SyncRunStage.DETAILS, "CATEGORY_DRIFT", null, null, staged.summary.id());
            }
        }
        long required = ordered.stream()
                .filter(staged -> detailRequired(
                        existing.get(staged.summary.id()),
                        staged.fingerprint,
                        staleBefore,
                        staged.classification.saleScope()))
                .count();
        tracker.detailsRequired(required);
        runs.updateProgress(runId, tracker.snapshot(SyncRunStage.DETAILS));

        for (StagedAuction staged : ordered) {
            Auction prior = existing.get(staged.summary.id());
            staged.enrichmentReason = prior == null
                    ? EnrichmentReason.NEW
                    : !staged.fingerprint.equals(prior.getListingFingerprint())
                    ? EnrichmentReason.LISTING_CHANGED
                    : detailRequired(
                            prior,
                            staged.fingerprint,
                            staleBefore,
                            staged.classification.saleScope())
                    ? EnrichmentReason.DETAIL_REFRESHED
                    : EnrichmentReason.NONE;
            if (!detailRequired(
                    prior,
                    staged.fingerprint,
                    staleBefore,
                    staged.classification.saleScope())) {
                staged.existing = prior;
                continue;
            }
            staged.existing = prior;
            tracker.detailAttempted();
            runs.updateProgress(runId, tracker.snapshot(SyncRunStage.DETAILS));
            try {
                EAukcijaCallResult<AuctionDetail> detail = switch (staged.classification.saleScope()) {
                    case IMMOVABLE -> client.getImmovablePropertyDetails(staged.summary.id());
                    case COMMON -> client.getCommonPropertyDetails(staged.summary.id());
                };
                tracker.retries(detail.retries());
                staged.detail = detail.data();
                staged.detailRefreshed = true;
                staged.detailFetchedAt = Instant.now(clock);
                tracker.detailSucceeded();
                runs.updateProgress(runId, tracker.snapshot(SyncRunStage.DETAILS));
            } catch (EAukcijaClientException failure) {
                tracker.detailFailed();
                throw SyncFailure.client(
                        SyncRunStage.DETAILS, null, null, staged.summary.id(), failure);
            }
        }

        List<AuctionPromotionCandidate> candidates = new ArrayList<>(ordered.size());
        long unknownKinds = 0;
        for (StagedAuction staged : ordered) {
            Auction current = AuctionSyncMapper.merge(
                    staged.existing,
                    staged.summary,
                    staged.detail,
                    staged.fingerprint,
                    staged.detailFetchedAt == null ? observedAt : staged.detailFetchedAt);
            if (staged.classification.propertyKind() == NormalizedPropertyKind.UNKNOWN) {
                unknownKinds++;
            }
            candidates.add(new AuctionPromotionCandidate(
                    current,
                    staged.fingerprint,
                    current.getDetailsFetchedAt(),
                    current.getSourceDetailCategoryId(),
                    staged.classification.saleScope(),
                    staged.classification.propertyKind(),
                    memberships(staged, taxonomyIndex),
                    staged.detailRefreshed,
                    staged.enrichmentReason));
        }

        tracker.unknownKinds(unknownKinds);
        runs.updateProgress(runId, tracker.snapshot(SyncRunStage.PROMOTING));
        try {
            promotion.promote(runId, taxonomy.canonicalSha256(), observedAt, candidates);
        } catch (RuntimeException persistenceFailure) {
            throw SyncFailure.contract(
                    SyncRunStage.PROMOTING, "PROMOTION_FAILED", null, null, null);
        }
        log.info("eAukcija sync succeeded runId={} uniqueAuctions={} retries={}",
                runId, union.size(), tracker.snapshot(SyncRunStage.COMPLETED).retryCount());
    }

    private EAukcijaCallResult<CategoryTree> fetchTaxonomy() {
        try {
            return client.getCategories();
        } catch (EAukcijaClientException failure) {
            throw SyncFailure.client(SyncRunStage.CATEGORIES, null, null, null, failure);
        }
    }

    private void persistTaxonomy(
            UUID runId,
            CategoryTree taxonomy,
            Instant observedAt,
            SyncProgressTracker tracker) {
        try {
            runs.recordTaxonomy(new TaxonomySnapshot(
                    taxonomy.canonicalSha256(),
                    TAXONOMY_NORMALIZER_VERSION,
                    objectMapper.readTree(taxonomy.canonicalJson()),
                    observedAt));
        } catch (JsonProcessingException invalidCanonicalTree) {
            throw SyncFailure.contract(
                    SyncRunStage.CATEGORIES, "INVALID_CANONICAL_TAXONOMY", null, null, null);
        }
        tracker.taxonomy(taxonomy.canonicalSha256(), observedAt);
        runs.updateProgress(runId, tracker.snapshot(SyncRunStage.CATEGORIES));
    }

    private Set<Long> fetchCompleteRoot(
            UUID runId,
            int rootId,
            TaxonomyIndex taxonomy,
            Map<Long, StagedAuction> union,
            long[] totalRows,
            SyncProgressTracker tracker) {
        RootAccumulator root = new RootAccumulator(rootId);
        try {
            for (int page = 1; ; page++) {
                if (page > syncProperties.getMaxPagesPerRoot()) {
                    throw SyncFailure.contract(
                            SyncRunStage.LISTINGS, "PAGE_LIMIT_EXCEEDED", rootId, page, null);
                }
                EAukcijaCallResult<AuctionListData> response;
                try {
                    response = client.getAuctionsByCategory(
                            rootId, clientProperties.getPageSize(), page);
                } catch (EAukcijaClientException failure) {
                    throw SyncFailure.client(SyncRunStage.LISTINGS, rootId, page, null, failure);
                }
                tracker.retries(response.retries());
                AuctionListData data = response.data();
                if (!root.initialized()) {
                    root.initialize(data.totalCount(), clientProperties.getPageSize());
                    if (root.pagesExpected > syncProperties.getMaxPagesPerRoot()) {
                        throw SyncFailure.contract(
                                SyncRunStage.LISTINGS, "PAGE_LIMIT_EXCEEDED", rootId, page, null);
                    }
                    tracker.expectPages(root.pagesExpected);
                } else if (data.totalCount() != root.sourceTotal) {
                    root.totalConsistent = false;
                    throw SyncFailure.contract(
                            SyncRunStage.LISTINGS, "TOTAL_CHANGED", rootId, page, null);
                }

                int rowCount = data.auctions().size();
                if (root.sourceTotal == 0 && rowCount != 0) {
                    throw SyncFailure.contract(
                            SyncRunStage.LISTINGS, "ZERO_TOTAL_WITH_ROWS", rootId, page, null);
                }
                if (root.uniqueIds.size() < root.sourceTotal && rowCount == 0) {
                    throw SyncFailure.contract(
                            SyncRunStage.LISTINGS, "EMPTY_PAGE_BEFORE_TOTAL", rootId, page, null);
                }
                if (page < root.pagesExpected && rowCount != clientProperties.getPageSize()) {
                    throw SyncFailure.contract(
                            SyncRunStage.LISTINGS, "SHORT_INTERMEDIATE_PAGE", rootId, page, null);
                }
                int uniqueBefore = root.uniqueIds.size();
                for (AuctionSummary summary : data.auctions()) {
                    root.rowsObserved++;
                    totalRows[0]++;
                    root.uniqueIds.add(summary.id());
                    String fingerprint = ListingFingerprint.sha256(summary);
                    StagedAuction present = union.get(summary.id());
                    if (present == null) {
                        present = new StagedAuction(summary, fingerprint);
                        union.put(summary.id(), present);
                    } else if (!present.fingerprint.equals(fingerprint)) {
                        throw SyncFailure.contract(
                                SyncRunStage.LISTINGS, "CONFLICTING_DUPLICATE", rootId, page, summary.id());
                    }
                    present.contributingRoots.add(rootId);
                }
                if (root.uniqueIds.size() > root.sourceTotal) {
                    throw SyncFailure.contract(
                            SyncRunStage.LISTINGS, "UNIQUE_IDS_EXCEED_TOTAL", rootId, page, null);
                }
                root.pagesCompleted++;
                tracker.pageCompleted(rowCount, union.size(), totalRows[0] - union.size());
                runs.updateProgress(runId, tracker.snapshot(SyncRunStage.LISTINGS));

                if (root.uniqueIds.size() == root.sourceTotal) {
                    break;
                }
                if (root.uniqueIds.size() == uniqueBefore) {
                    throw SyncFailure.contract(
                            SyncRunStage.LISTINGS, "NO_UNIQUE_PROGRESS", rootId, page, null);
                }
                if (page >= root.pagesExpected) {
                    root.pagesExpected++;
                    tracker.expectPages(1);
                }
            }

            boolean complete = root.totalConsistent
                    && root.uniqueIds.size() == root.sourceTotal
                    && root.pagesCompleted == root.pagesExpected;
            runs.recordRootResult(runId, root.result(complete));
            if (!complete) {
                throw SyncFailure.contract(
                        SyncRunStage.LISTINGS, "ROOT_UNIQUE_TOTAL_MISMATCH", rootId, null, null);
            }
            if (!taxonomy.hasRoot(rootId)) {
                throw SyncFailure.contract(
                        SyncRunStage.CATEGORIES, "CONFIGURED_ROOT_MISSING", rootId, null, null);
            }
            return Set.copyOf(root.uniqueIds);
        } catch (SyncFailure failure) {
            if (root.initialized()) {
                runs.recordRootResult(runId, root.result(false));
            }
            throw failure;
        }
    }

    private void fetchCompleteChild(
            UUID runId,
            int rootId,
            CategoryNode child,
            Set<Long> rootAuctionIds,
            Map<Long, StagedAuction> union,
            long[] totalRows,
            SyncProgressTracker tracker) {
        ChildAccumulator observed = new ChildAccumulator(rootId, child.value());
        try {
            for (int page = 1; ; page++) {
                if (page > syncProperties.getMaxPagesPerRoot()) {
                    throw SyncFailure.childContract(
                            SyncRunStage.LISTINGS, "PAGE_LIMIT_EXCEEDED",
                            rootId, child.value(), page, null);
                }
                EAukcijaCallResult<AuctionListData> response;
                try {
                    response = client.getAuctionsByCategory(
                            child.value(), clientProperties.getPageSize(), page);
                } catch (EAukcijaClientException failure) {
                    throw SyncFailure.childClient(
                            SyncRunStage.LISTINGS, rootId, child.value(), page, null, failure);
                }
                tracker.retries(response.retries());
                AuctionListData data = response.data();
                if (!observed.initialized()) {
                    observed.initialize(data.totalCount(), clientProperties.getPageSize());
                    if (observed.pagesExpected > syncProperties.getMaxPagesPerRoot()) {
                        throw SyncFailure.childContract(
                                SyncRunStage.LISTINGS, "PAGE_LIMIT_EXCEEDED",
                                rootId, child.value(), page, null);
                    }
                    tracker.expectPages(observed.pagesExpected);
                } else if (data.totalCount() != observed.sourceTotal) {
                    observed.totalConsistent = false;
                    throw SyncFailure.childContract(
                            SyncRunStage.LISTINGS, "CHILD_TOTAL_CHANGED",
                            rootId, child.value(), page, null);
                }

                int rowCount = data.auctions().size();
                if (observed.sourceTotal == 0 && rowCount != 0) {
                    throw SyncFailure.childContract(
                            SyncRunStage.LISTINGS, "CHILD_ZERO_TOTAL_WITH_ROWS",
                            rootId, child.value(), page, null);
                }
                if (observed.uniqueIds.size() < observed.sourceTotal && rowCount == 0) {
                    throw SyncFailure.childContract(
                            SyncRunStage.LISTINGS, "CHILD_EMPTY_PAGE_BEFORE_TOTAL",
                            rootId, child.value(), page, null);
                }
                if (page < observed.pagesExpected && rowCount != clientProperties.getPageSize()) {
                    throw SyncFailure.childContract(
                            SyncRunStage.LISTINGS, "CHILD_SHORT_INTERMEDIATE_PAGE",
                            rootId, child.value(), page, null);
                }
                int uniqueBefore = observed.uniqueIds.size();
                for (AuctionSummary summary : data.auctions()) {
                    observed.rowsObserved++;
                    if (!rootAuctionIds.contains(summary.id())) {
                        observed.subsetOfParentRoot = false;
                        throw SyncFailure.childContract(
                                SyncRunStage.LISTINGS,
                                "CHILD_ID_OUTSIDE_ROOT",
                                rootId,
                                child.value(),
                                page,
                                summary.id());
                    }
                    observed.uniqueIds.add(summary.id());
                    StagedAuction staged = union.get(summary.id());
                    if (staged == null) {
                        throw SyncFailure.childContract(
                                SyncRunStage.LISTINGS,
                                "CHILD_ID_OUTSIDE_DISCOVERY",
                                rootId,
                                child.value(),
                                page,
                                summary.id());
                    }
                    staged.contributingChildren.add(child.value());
                }
                if (observed.uniqueIds.size() > observed.sourceTotal) {
                    throw SyncFailure.childContract(
                            SyncRunStage.LISTINGS, "CHILD_UNIQUE_IDS_EXCEED_TOTAL",
                            rootId, child.value(), page, null);
                }
                observed.pagesCompleted++;
                tracker.pageCompleted(0, union.size(), totalRows[0] - union.size());
                runs.updateProgress(runId, tracker.snapshot(SyncRunStage.LISTINGS));

                if (observed.uniqueIds.size() == observed.sourceTotal) {
                    break;
                }
                if (observed.uniqueIds.size() == uniqueBefore) {
                    throw SyncFailure.childContract(
                            SyncRunStage.LISTINGS, "CHILD_NO_UNIQUE_PROGRESS",
                            rootId, child.value(), page, null);
                }
                if (page >= observed.pagesExpected) {
                    observed.pagesExpected++;
                    tracker.expectPages(1);
                }
            }

            boolean complete = observed.totalConsistent
                    && observed.uniqueIds.size() == observed.sourceTotal
                    && observed.pagesCompleted == observed.pagesExpected;
            runs.recordChildResult(runId, observed.result(complete));
            if (!complete) {
                throw SyncFailure.childContract(
                        SyncRunStage.LISTINGS, "CHILD_UNIQUE_TOTAL_MISMATCH",
                        rootId, child.value(), null, null);
            }
        } catch (SyncFailure failure) {
            runs.recordChildResult(runId, observed.result(false));
            throw failure;
        }
    }

    private static boolean detailRequired(
            Auction existing,
            String fingerprint,
            Instant staleBefore,
            SaleScope targetScope) {
        return existing == null
                || !existing.isDetailsFetched()
                || existing.getDetailsFetchedAt() == null
                || !fingerprint.equals(existing.getListingFingerprint())
                || existing.getDetailsFetchedAt().isBefore(staleBefore)
                || existing.getSaleScope() != targetScope;
    }

    private static List<CategoryMembership> memberships(StagedAuction staged, TaxonomyIndex taxonomy) {
        List<CategoryMembership> memberships = new ArrayList<>();
        staged.contributingRoots.stream().sorted().forEach(root -> memberships.add(
                new CategoryMembership(root, CategoryMembershipType.ROOT, taxonomy.rootTitle(root))));
        staged.contributingChildren.stream().sorted().forEach(child -> memberships.add(
                new CategoryMembership(child, CategoryMembershipType.CHILD, taxonomy.childTitle(child))));
        if (staged.detail != null && staged.detail.category() != null) {
            memberships.add(new CategoryMembership(
                    staged.detail.category().id(),
                    CategoryMembershipType.DETAIL,
                    staged.detail.category().name()));
        } else if (staged.existing != null && staged.existing.getSourceDetailCategoryId() != null) {
            memberships.add(new CategoryMembership(
                    staged.existing.getSourceDetailCategoryId(),
                    CategoryMembershipType.DETAIL,
                    staged.existing.getCategoryName()));
        }
        return List.copyOf(memberships);
    }

    private void terminalize(UUID runId, SyncProgressTracker tracker, SyncFailure failure) {
        tracker.retries(failure.retries());
        tracker.error();
        try {
            if (tracker.errorCount() <= syncProperties.getMaxErrors()) {
                runs.appendError(runId, failure.evidence());
            }
            SyncRunStatus status = tracker.hasSourceProgress()
                    ? SyncRunStatus.PARTIAL
                    : SyncRunStatus.FAILED;
            runs.finishIncomplete(runId, status, tracker.snapshot(failure.stage()));
            log.warn("eAukcija sync ended runId={} status={} stage={} code={}",
                    runId, status, failure.stage(), failure.evidence().errorCode());
        } catch (RuntimeException persistenceFailure) {
            log.error("Could not terminalize eAukcija sync run runId={} code=RUN_LEDGER_FAILURE", runId);
        }
    }

    private static final class StagedAuction {
        private final AuctionSummary summary;
        private final String fingerprint;
        private final Set<Integer> contributingRoots = new LinkedHashSet<>();
        private final Set<Integer> contributingChildren = new LinkedHashSet<>();
        private TaxonomyClassifier.Classification classification;
        private Auction existing;
        private AuctionDetail detail;
        private Instant detailFetchedAt;
        private boolean detailRefreshed;
        private EnrichmentReason enrichmentReason;

        private StagedAuction(AuctionSummary summary, String fingerprint) {
            this.summary = summary;
            this.fingerprint = fingerprint;
        }
    }

    private static final class ChildAccumulator {
        private final int parentRootId;
        private final int childId;
        private final Set<Long> uniqueIds = new LinkedHashSet<>();
        private long sourceTotal = -1;
        private long rowsObserved;
        private int pagesExpected;
        private int pagesCompleted;
        private boolean totalConsistent = true;
        private boolean subsetOfParentRoot = true;

        private ChildAccumulator(int parentRootId, int childId) {
            this.parentRootId = parentRootId;
            this.childId = childId;
        }

        private boolean initialized() {
            return sourceTotal >= 0;
        }

        private void initialize(long total, int pageSize) {
            sourceTotal = total;
            pagesExpected = Math.max(1, (int) Math.ceil((double) total / pageSize));
        }

        private SyncRunChildResult result(boolean complete) {
            return new SyncRunChildResult(
                    parentRootId,
                    childId,
                    Math.max(0, sourceTotal),
                    rowsObserved,
                    uniqueIds.size(),
                    rowsObserved - uniqueIds.size(),
                    pagesExpected,
                    pagesCompleted,
                    totalConsistent,
                    subsetOfParentRoot,
                    complete);
        }
    }

    private static final class RootAccumulator {
        private final int rootId;
        private final Set<Long> uniqueIds = new LinkedHashSet<>();
        private long sourceTotal = -1;
        private long rowsObserved;
        private int pagesExpected;
        private int pagesCompleted;
        private boolean totalConsistent = true;

        private RootAccumulator(int rootId) {
            this.rootId = rootId;
        }

        private boolean initialized() {
            return sourceTotal >= 0;
        }

        private void initialize(long total, int pageSize) {
            sourceTotal = total;
            pagesExpected = Math.max(1, (int) Math.ceil((double) total / pageSize));
        }

        private SyncRunRootResult result(boolean complete) {
            return new SyncRunRootResult(
                    rootId,
                    Math.max(0, sourceTotal),
                    rowsObserved,
                    uniqueIds.size(),
                    rowsObserved - uniqueIds.size(),
                    pagesExpected,
                    pagesCompleted,
                    totalConsistent,
                    complete);
        }
    }

    private static final class TaxonomyIndex {
        private final Map<Integer, CategoryNode> roots;
        private final Map<Integer, CategoryNode> children;

        private TaxonomyIndex(CategoryTree tree) {
            Map<Integer, CategoryNode> indexed = new HashMap<>();
            Map<Integer, CategoryNode> indexedChildren = new HashMap<>();
            tree.roots().forEach(root -> {
                indexed.put(root.value(), root);
                if (root.children() != null) {
                    root.children().forEach(child -> indexedChildren.put(child.value(), child));
                }
            });
            this.roots = Map.copyOf(indexed);
            this.children = Map.copyOf(indexedChildren);
        }

        private boolean hasRoot(int rootId) {
            return roots.containsKey(rootId);
        }

        private String rootTitle(int rootId) {
            CategoryNode root = roots.get(rootId);
            return root == null ? null : root.title();
        }

        private List<CategoryNode> directChildren(int rootId) {
            CategoryNode root = roots.get(rootId);
            if (root == null || root.children() == null) {
                return List.of();
            }
            return root.children().stream()
                    .sorted(Comparator.comparingInt(CategoryNode::value))
                    .toList();
        }

        private String childTitle(int childId) {
            CategoryNode child = children.get(childId);
            return child == null ? null : child.title();
        }
    }
}
