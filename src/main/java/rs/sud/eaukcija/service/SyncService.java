package rs.sud.eaukcija.service;

import java.time.Clock;
import java.time.Duration;
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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import rs.sud.eaukcija.client.EAukcijaApiTypes.AuctionDetail;
import rs.sud.eaukcija.client.EAukcijaApiTypes.AuctionListData;
import rs.sud.eaukcija.client.EAukcijaApiTypes.AuctionSummary;
import rs.sud.eaukcija.client.EAukcijaApiTypes.CategoryNode;
import rs.sud.eaukcija.client.EAukcijaApiTypes.CategoryTree;
import rs.sud.eaukcija.client.EAukcijaApiTypes.RejectedAuctionSummary;
import rs.sud.eaukcija.client.EAukcijaCallResult;
import rs.sud.eaukcija.client.EAukcijaClient;
import rs.sud.eaukcija.client.EAukcijaClientException;
import rs.sud.eaukcija.client.EAukcijaClientProperties;
import rs.sud.eaukcija.model.Auction;
import rs.sud.eaukcija.repository.AuctionRepository;
import rs.sud.eaukcija.snapshot.AuctionSourceSnapshot;
import rs.sud.eaukcija.snapshot.AuctionSourceSnapshotFactory;
import rs.sud.eaukcija.snapshot.AuctionSourceSnapshotFactory.MinimizedDetail;
import rs.sud.eaukcija.snapshot.AuctionSourceSnapshotFactory.MinimizedListing;
import rs.sud.eaukcija.snapshot.CurrentAuctionSourceSnapshot;
import rs.sud.eaukcija.sync.AuctionSyncMapper;
import rs.sud.eaukcija.sync.ListingFingerprint;
import rs.sud.eaukcija.sync.SyncFailure;
import rs.sud.eaukcija.sync.SyncProgressTracker;
import rs.sud.eaukcija.sync.SyncProperties;
import rs.sud.eaukcija.sync.TaxonomyClassifier;
import rs.sud.eaukcija.sync.persistence.AuctionDetailQuarantine;
import rs.sud.eaukcija.sync.persistence.AuctionListingQuarantine;
import rs.sud.eaukcija.sync.persistence.AuctionPromotionCandidate;
import rs.sud.eaukcija.sync.persistence.AuctionPromotionService;
import rs.sud.eaukcija.sync.persistence.CategoryMembership;
import rs.sud.eaukcija.sync.persistence.CategoryMembershipType;
import rs.sud.eaukcija.sync.persistence.EnrichmentReason;
import rs.sud.eaukcija.sync.persistence.NormalizedPropertyKind;
import rs.sud.eaukcija.sync.persistence.PersistedAuctionDetailQuarantine;
import rs.sud.eaukcija.sync.persistence.PersistedAuctionListingQuarantine;
import rs.sud.eaukcija.sync.persistence.PersistedSyncRunError;
import rs.sud.eaukcija.sync.persistence.SaleScope;
import rs.sud.eaukcija.sync.persistence.SyncRunClaimRequest;
import rs.sud.eaukcija.sync.persistence.SyncRunClaimResult;
import rs.sud.eaukcija.sync.persistence.SyncRunChildResult;
import rs.sud.eaukcija.sync.persistence.SyncRunErrorEvidence;
import rs.sud.eaukcija.sync.persistence.SyncRunRepository;
import rs.sud.eaukcija.sync.persistence.SyncRunRootResult;
import rs.sud.eaukcija.sync.persistence.SyncRunStage;
import rs.sud.eaukcija.sync.persistence.SyncRunStateException;
import rs.sud.eaukcija.sync.persistence.SyncRunStatus;
import rs.sud.eaukcija.sync.persistence.SyncRunView;
import rs.sud.eaukcija.sync.persistence.SyncTriggerKind;
import rs.sud.eaukcija.sync.persistence.TaxonomySnapshot;
import rs.sud.eaukcija.sync.persistence.WorkerLockLease;

/** Complete, durable, source-safe eAukcija synchronization orchestration. */
@Service
public class SyncService {

    static final String TAXONOMY_NORMALIZER_VERSION = "eaukcija-taxonomy-v1";
    private static final int DETAIL_PROGRESS_CHECKPOINT_ITEMS = 25;
    private static final Duration DETAIL_PROGRESS_CHECKPOINT_INTERVAL = Duration.ofSeconds(30);

    private static final Logger log = LoggerFactory.getLogger(SyncService.class);

    private final EAukcijaClient client;
    private final EAukcijaClientProperties clientProperties;
    private final SyncProperties syncProperties;
    private final SyncRunRepository runs;
    private final AuctionRepository auctions;
    private final AuctionPromotionService promotion;
    private final TaskExecutor executor;
    private final ObjectMapper objectMapper;
    private final AuctionSourceSnapshotFactory sourceSnapshotFactory;
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
            ObjectMapper objectMapper,
            AuctionSourceSnapshotFactory sourceSnapshotFactory) {
        this(client, clientProperties, syncProperties, runs, auctions,
                promotion, executor, objectMapper, sourceSnapshotFactory, Clock.systemUTC());
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
            AuctionSourceSnapshotFactory sourceSnapshotFactory,
            Clock clock) {
        this.client = client;
        this.clientProperties = clientProperties;
        this.syncProperties = syncProperties;
        this.runs = runs;
        this.auctions = auctions;
        this.promotion = promotion;
        this.executor = executor;
        this.objectMapper = objectMapper;
        this.sourceSnapshotFactory = sourceSnapshotFactory;
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

    public List<PersistedAuctionDetailQuarantine> detailQuarantines(UUID runId) {
        return syncProperties.isEnabled() ? runs.detailQuarantines(runId) : List.of();
    }

    public List<PersistedAuctionListingQuarantine> listingQuarantines(UUID runId) {
        return syncProperties.isEnabled() ? runs.listingQuarantines(runId) : List.of();
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
            if (!syncProperties.isEnabled()) {
                return;
            }
            Optional<UUID> activeRunId = runs.activeRunId();
            if (activeRunId.isEmpty()
                    || !runs.isStale(
                            activeRunId.orElseThrow(),
                            syncProperties.getRunningStaleAfter())) {
                return;
            }
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
                    lease,
                    syncProperties.getRunningStaleAfter(),
                    syncProperties.getMaxErrors());
            recovered.forEach(runId -> log.warn(
                    "Recovered stale eAukcija sync run runId={} code=STALE_RUN_RECOVERED", runId));
            return recovered;
        }
    }

    private SyncRunClaimResult start(UUID idempotencyKey, SyncTriggerKind triggerKind) {
        if (!syncProperties.isEnabled()) {
            throw new SyncUnavailableException("durable sync is unavailable for the active database profile");
        }
        SyncRunClaimRequest request = new SyncRunClaimRequest(
                idempotencyKey.toString(),
                clientProperties.getRootCategoryIds(),
                clientProperties.getPageSize(),
                triggerKind);
        Optional<UUID> activeRunId = runs.activeRunId();
        if (activeRunId.isPresent()
                && runs.isStale(activeRunId.orElseThrow(), syncProperties.getRunningStaleAfter())) {
            recoverStaleRuns();
        }
        SyncRunClaimResult claim = runs.claim(request);
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
            // Deliberately omit the throwable: source and driver exception text
            // can contain response data. Persist only the fixed diagnostic code.
            log.error("Unexpected eAukcija sync failure runId={} code=INTERNAL", runId);
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
        Map<Long, AuctionListingQuarantine> listingQuarantines = new LinkedHashMap<>();
        Set<Long> observedRootAuctionIds = new LinkedHashSet<>();
        Map<Integer, Set<Long>> rootAuctionIds = new LinkedHashMap<>();
        long[] totalRows = {0};
        for (int rootId : clientProperties.getRootCategoryIds()) {
            rootAuctionIds.put(rootId,
                    fetchCompleteRoot(
                            runId, rootId, taxonomyIndex, union, listingQuarantines,
                            observedRootAuctionIds,
                            totalRows, tracker));
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
                        listingQuarantines,
                        observedRootAuctionIds,
                        totalRows,
                        tracker);
            }
        }

        long listingUniqueCount = listingUniqueCount(union, listingQuarantines);
        tracker.listingCounts(
                totalRows[0], listingUniqueCount, totalRows[0] - listingUniqueCount);
        runs.updateProgress(runId, tracker.snapshot(SyncRunStage.LISTINGS));

        Map<Long, Auction> existing = new HashMap<>();
        auctions.findAllById(union.keySet()).forEach(auction -> existing.put(auction.getId(), auction));
        Map<Long, CurrentAuctionSourceSnapshot> currentSourceSnapshots;
        try {
            currentSourceSnapshots = runs.currentSourceSnapshots(union.keySet());
        } catch (SyncRunStateException invalidLineage) {
            log.warn("Rejected stored source-snapshot lineage runId={} "
                    + "code=SOURCE_SNAPSHOT_LINEAGE_INVALID", runId);
            throw SyncFailure.contract(
                    SyncRunStage.DETAILS, "SOURCE_SNAPSHOT_LINEAGE_INVALID", null, null, null);
        } catch (DataAccessException readFailure) {
            log.warn("Could not read source snapshots runId={} "
                    + "code=SOURCE_SNAPSHOT_READ_FAILED", runId);
            throw SyncFailure.contract(
                    SyncRunStage.DETAILS, "SOURCE_SNAPSHOT_READ_FAILED", null, null, null);
        }
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
        long required = 0;
        for (StagedAuction staged : ordered) {
            staged.existing = existing.get(staged.summary.id());
            staged.currentSourceSnapshot = currentSourceSnapshots.get(staged.summary.id());
            staged.detailRequired = detailRequired(
                    staged.existing,
                    staged.currentSourceSnapshot,
                    staged.fingerprint,
                    staleBefore,
                    staged.classification.saleScope());
            if (staged.detailRequired) {
                required++;
            }
            staged.enrichmentReason = staged.existing == null
                    ? EnrichmentReason.NEW
                    : !staged.fingerprint.equals(staged.existing.getListingFingerprint())
                    ? EnrichmentReason.LISTING_CHANGED
                    : staged.detailRequired
                    ? EnrichmentReason.DETAIL_REFRESHED
                    : EnrichmentReason.NONE;
        }
        tracker.detailsRequired(required);
        runs.updateProgress(runId, tracker.snapshot(SyncRunStage.DETAILS));

        List<AuctionDetailQuarantine> quarantines = new ArrayList<>();
        int detailOutcomesSinceCheckpoint = 0;
        Instant detailCheckpointAt = Instant.now(clock);
        for (StagedAuction staged : ordered) {
            if (!staged.detailRequired) {
                continue;
            }
            tracker.detailAttempted();
            try {
                EAukcijaCallResult<AuctionDetail> detail = switch (staged.classification.saleScope()) {
                    case IMMOVABLE -> client.getImmovablePropertyDetails(staged.summary.id());
                    case COMMON -> client.getCommonPropertyDetails(staged.summary.id());
                };
                tracker.retries(detail.retries());
                staged.detail = detail.data();
                try {
                    staged.minimizedDetail = sourceSnapshotFactory.minimizeDetail(
                            staged.summary.id(), detail.sourceData());
                } catch (IllegalArgumentException invalidSourceDetail) {
                    log.warn("Rejected eAukcija source detail runId={} auctionId={} "
                            + "code=SOURCE_SNAPSHOT_INVALID", runId, staged.summary.id());
                    throw SyncFailure.contract(
                            SyncRunStage.DETAILS,
                            "SOURCE_SNAPSHOT_INVALID",
                            null,
                            null,
                            staged.summary.id());
                }
                staged.detailRefreshed = true;
                staged.detailFetchedAt = Instant.now(clock);
                tracker.detailSucceeded();
            } catch (EAukcijaClientException failure) {
                SyncFailure detailFailure = SyncFailure.client(
                        SyncRunStage.DETAILS, null, null, staged.summary.id(), failure);
                if (!quarantinableDetailFailure(failure)
                        || quarantines.size() >= syncProperties.getMaxQuarantinedDetails()) {
                    tracker.detailFailed();
                    throw detailFailure;
                }
                tracker.retries(detailFailure.retries());
                tracker.detailQuarantined();
                tracker.resolvedError();
                runs.appendError(
                        runId,
                        detailFailure.evidence(),
                        true,
                        tracker.errorCount() <= syncProperties.getMaxErrors());
                staged.quarantined = true;
                quarantines.add(new AuctionDetailQuarantine(
                        staged.summary.id(), staged.fingerprint, failure.code().name()));
                log.warn("Quarantined invalid eAukcija detail runId={} auctionId={} code={}",
                        runId, staged.summary.id(), failure.code());
            }
            detailOutcomesSinceCheckpoint++;
            Instant checkpointNow = Instant.now(clock);
            if (detailOutcomesSinceCheckpoint == DETAIL_PROGRESS_CHECKPOINT_ITEMS
                    || !checkpointNow.isBefore(detailCheckpointAt.plus(
                            DETAIL_PROGRESS_CHECKPOINT_INTERVAL))) {
                runs.updateProgress(runId, tracker.snapshot(SyncRunStage.DETAILS));
                detailOutcomesSinceCheckpoint = 0;
                detailCheckpointAt = checkpointNow;
            }
        }

        List<AuctionPromotionCandidate> candidates = new ArrayList<>(ordered.size() - quarantines.size());
        long unknownKinds = 0;
        for (StagedAuction staged : ordered) {
            if (staged.classification.propertyKind() == NormalizedPropertyKind.UNKNOWN) {
                unknownKinds++;
            }
            if (staged.quarantined) {
                continue;
            }
            Auction current = AuctionSyncMapper.merge(
                    staged.existing,
                    staged.summary,
                    staged.detail,
                    staged.fingerprint,
                    staged.detailFetchedAt == null ? observedAt : staged.detailFetchedAt);
            AuctionSourceSnapshot sourceSnapshot;
            try {
                sourceSnapshot = staged.detailRefreshed
                        ? sourceSnapshotFactory.create(
                                staged.summary.id(),
                                staged.minimizedListing,
                                staged.minimizedDetail,
                                staged.classification.saleScope(),
                                staged.listingFetchedAt,
                                staged.detailFetchedAt)
                        : sourceSnapshotFactory.combineWithCurrentDetail(
                                staged.summary.id(),
                                staged.minimizedListing,
                                staged.currentSourceSnapshot,
                                staged.listingFetchedAt);
            } catch (IllegalArgumentException invalidSnapshot) {
                log.warn("Rejected eAukcija source snapshot runId={} auctionId={} "
                        + "code=SOURCE_SNAPSHOT_INVALID", runId, staged.summary.id());
                throw SyncFailure.contract(
                        SyncRunStage.DETAILS,
                        "SOURCE_SNAPSHOT_INVALID",
                        null,
                        null,
                        staged.summary.id());
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
                    staged.enrichmentReason,
                    sourceSnapshot));
        }

        tracker.unknownKinds(unknownKinds);
        runs.updateProgress(runId, tracker.snapshot(SyncRunStage.PROMOTING));
        try {
            promotion.promote(
                    runId,
                    taxonomy.canonicalSha256(),
                    observedAt,
                    candidates,
                    quarantines,
                    List.copyOf(listingQuarantines.values()));
        } catch (RuntimeException persistenceFailure) {
            throw SyncFailure.contract(
                    SyncRunStage.PROMOTING, "PROMOTION_FAILED", null, null, null);
        }
        log.info("eAukcija sync succeeded runId={} uniqueAuctions={} listingQuarantined={} detailQuarantined={} retries={}",
                runId, listingUniqueCount, listingQuarantines.size(), quarantines.size(),
                tracker.snapshot(SyncRunStage.COMPLETED).retryCount());
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
            Map<Long, AuctionListingQuarantine> listingQuarantines,
            Set<Long> observedRootAuctionIds,
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
                Instant listingFetchedAt = Instant.now(clock);
                AuctionListData data = response.data();
                Map<Long, MinimizedListing> sourceListings;
                try {
                    sourceListings = sourceListings(response.sourceData());
                } catch (IllegalArgumentException invalidSourcePage) {
                    log.warn("Rejected eAukcija source listing page runId={} rootId={} page={} "
                            + "code=SOURCE_LISTING_INVALID", runId, rootId, page);
                    throw SyncFailure.contract(
                            SyncRunStage.LISTINGS,
                            "SOURCE_LISTING_INVALID",
                            rootId,
                            page,
                            null);
                }
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

                int rowCount = data.auctions().size() + data.rejectedAuctions().size();
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
                for (RejectedAuctionSummary rejected : data.rejectedAuctions()) {
                    root.rowsObserved++;
                    totalRows[0]++;
                    root.uniqueIds.add(rejected.auctionId());
                    observedRootAuctionIds.add(rejected.auctionId());
                    quarantineListing(
                            runId,
                            rootId,
                            null,
                            page,
                            rejected,
                            union,
                            listingQuarantines,
                            tracker);
                }
                for (AuctionSummary summary : data.auctions()) {
                    root.rowsObserved++;
                    totalRows[0]++;
                    root.uniqueIds.add(summary.id());
                    observedRootAuctionIds.add(summary.id());
                    if (listingQuarantines.containsKey(summary.id())) {
                        continue;
                    }
                    String fingerprint = ListingFingerprint.sha256(summary);
                    MinimizedListing minimizedListing = sourceListings.get(summary.id());
                    if (minimizedListing == null) {
                        log.warn("Missing eAukcija source listing runId={} rootId={} page={} "
                                + "auctionId={} code=SOURCE_LISTING_INVALID",
                                runId, rootId, page, summary.id());
                        throw SyncFailure.contract(
                                SyncRunStage.LISTINGS,
                                "SOURCE_LISTING_INVALID",
                                rootId,
                                page,
                                summary.id());
                    }
                    StagedAuction present = union.get(summary.id());
                    if (present == null) {
                        present = new StagedAuction(
                                summary,
                                fingerprint,
                                minimizedListing,
                                listingFetchedAt);
                        union.put(summary.id(), present);
                    } else if (!present.fingerprint.equals(fingerprint)
                            || !present.minimizedListing.equals(minimizedListing)) {
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
                long uniqueCount = listingUniqueCount(union, listingQuarantines);
                tracker.pageCompleted(rowCount, uniqueCount, totalRows[0] - uniqueCount);
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
            long uniqueCount = observedRootAuctionIds.size();
            tracker.listingCounts(totalRows[0], uniqueCount, totalRows[0] - uniqueCount);
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
            Map<Long, AuctionListingQuarantine> listingQuarantines,
            Set<Long> observedRootAuctionIds,
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

                int rowCount = data.auctions().size() + data.rejectedAuctions().size();
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
                for (RejectedAuctionSummary rejected : data.rejectedAuctions()) {
                    observed.rowsObserved++;
                    if (!rootAuctionIds.contains(rejected.auctionId())) {
                        observed.subsetOfParentRoot = false;
                        throw SyncFailure.childContract(
                                SyncRunStage.LISTINGS,
                                "CHILD_ID_OUTSIDE_ROOT",
                                rootId,
                                child.value(),
                                page,
                                rejected.auctionId());
                    }
                    observed.uniqueIds.add(rejected.auctionId());
                    quarantineListing(
                            runId,
                            rootId,
                            child.value(),
                            page,
                            rejected,
                            union,
                            listingQuarantines,
                            tracker);
                }
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
                    if (listingQuarantines.containsKey(summary.id())) {
                        continue;
                    }
                    // The root-subset check above proves the union entry exists.
                    union.get(summary.id()).contributingChildren.add(child.value());
                }
                if (observed.uniqueIds.size() > observed.sourceTotal) {
                    throw SyncFailure.childContract(
                            SyncRunStage.LISTINGS, "CHILD_UNIQUE_IDS_EXCEED_TOTAL",
                            rootId, child.value(), page, null);
                }
                observed.pagesCompleted++;
                long uniqueCount = listingUniqueCount(union, listingQuarantines);
                tracker.pageCompleted(0, uniqueCount, totalRows[0] - uniqueCount);
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
            long uniqueCount = observedRootAuctionIds.size();
            tracker.listingCounts(totalRows[0], uniqueCount, totalRows[0] - uniqueCount);
            runs.recordChildResult(runId, observed.result(false));
            throw failure;
        }
    }

    private void quarantineListing(
            UUID runId,
            int rootId,
            Integer childId,
            int page,
            RejectedAuctionSummary rejected,
            Map<Long, StagedAuction> union,
            Map<Long, AuctionListingQuarantine> quarantines,
            SyncProgressTracker tracker) {
        if (quarantines.containsKey(rejected.auctionId())) {
            return;
        }
        SyncFailure listingFailure = childId == null
                ? SyncFailure.contract(
                        SyncRunStage.LISTINGS,
                        rejected.errorCode().name(),
                        rootId,
                        page,
                        rejected.auctionId())
                : SyncFailure.childContract(
                        SyncRunStage.LISTINGS,
                        rejected.errorCode().name(),
                        rootId,
                        childId,
                        page,
                        rejected.auctionId());
        if (quarantines.size() >= syncProperties.getMaxQuarantinedListings()) {
            throw listingFailure;
        }

        tracker.listingRowQuarantined();
        tracker.resolvedError();
        runs.appendError(
                runId,
                listingFailure.evidence(),
                true,
                tracker.errorCount() <= syncProperties.getMaxErrors());
        union.remove(rejected.auctionId());
        quarantines.put(rejected.auctionId(), new AuctionListingQuarantine(
                rejected.auctionId(),
                rejected.sourceRowSha256(),
                rejected.errorCode().name(),
                rootId,
                childId,
                page));
        log.warn(
                "Quarantined invalid eAukcija listing runId={} rootId={} childId={} page={} auctionId={} code={}",
                runId,
                rootId,
                childId,
                page,
                rejected.auctionId(),
                rejected.errorCode());
    }

    private static long listingUniqueCount(
            Map<Long, StagedAuction> union,
            Map<Long, AuctionListingQuarantine> listingQuarantines) {
        return (long) union.size() + listingQuarantines.size();
    }

    private static boolean detailRequired(
            Auction existing,
            CurrentAuctionSourceSnapshot currentSourceSnapshot,
            String fingerprint,
            Instant staleBefore,
            SaleScope targetScope) {
        return existing == null
                || currentSourceSnapshot == null
                || !existing.isDetailsFetched()
                || existing.getDetailsFetchedAt() == null
                || !fingerprint.equals(existing.getListingFingerprint())
                || existing.getDetailsFetchedAt().isBefore(staleBefore)
                || existing.getSaleScope() != targetScope;
    }

    private Map<Long, MinimizedListing> sourceListings(JsonNode sourceData) {
        if (sourceData == null || !sourceData.isObject()
                || !sourceData.path("Auctions").isArray()) {
            throw new IllegalArgumentException("listing source Data is incomplete");
        }
        Map<Long, MinimizedListing> indexed = new LinkedHashMap<>();
        for (JsonNode candidate : sourceData.path("Auctions")) {
            if (candidate == null || !candidate.isObject()) {
                continue;
            }
            JsonNode id = candidate.get("Id");
            if (id == null || !id.isIntegralNumber() || !id.canConvertToLong()
                    || id.longValue() < 1) {
                continue;
            }
            long auctionId = id.longValue();
            MinimizedListing minimized;
            try {
                minimized = sourceSnapshotFactory.minimizeListing(auctionId, candidate);
            } catch (IllegalArgumentException rejectedSourceRow) {
                // Invalid rows already represented by rejectedAuctions are
                // quarantined below. A valid summary with no retained source
                // row still fails closed through the missing-row check.
                continue;
            }
            MinimizedListing previous = indexed.putIfAbsent(auctionId, minimized);
            if (previous != null && !previous.equals(minimized)) {
                throw new IllegalArgumentException("conflicting source listing rows");
            }
        }
        return Map.copyOf(indexed);
    }

    private static boolean quarantinableDetailFailure(EAukcijaClientException failure) {
        return switch (failure.code()) {
            case BODY_TOO_LARGE,
                    INVALID_CONTENT_TYPE,
                    INVALID_JSON,
                    INVALID_ENVELOPE,
                    APPLICATION_ERROR,
                    INVALID_DATA -> true;
            case HTTP_STATUS -> failure.httpStatus() != null
                    && failure.httpStatus() != 401
                    && failure.httpStatus() != 403
                    && failure.httpStatus() != 408
                    && failure.httpStatus() != 429
                    && failure.httpStatus() != 500
                    && failure.httpStatus() != 502
                    && failure.httpStatus() != 503
                    && failure.httpStatus() != 504;
            default -> false;
        };
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
            } else {
                runs.appendError(runId, failure.evidence(), false, false);
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
        private final MinimizedListing minimizedListing;
        private final Instant listingFetchedAt;
        private final Set<Integer> contributingRoots = new LinkedHashSet<>();
        private final Set<Integer> contributingChildren = new LinkedHashSet<>();
        private TaxonomyClassifier.Classification classification;
        private Auction existing;
        private CurrentAuctionSourceSnapshot currentSourceSnapshot;
        private AuctionDetail detail;
        private MinimizedDetail minimizedDetail;
        private Instant detailFetchedAt;
        private boolean detailRefreshed;
        private boolean detailRequired;
        private boolean quarantined;
        private EnrichmentReason enrichmentReason;

        private StagedAuction(
                AuctionSummary summary,
                String fingerprint,
                MinimizedListing minimizedListing,
                Instant listingFetchedAt) {
            this.summary = summary;
            this.fingerprint = fingerprint;
            this.minimizedListing = minimizedListing;
            this.listingFetchedAt = listingFetchedAt;
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
