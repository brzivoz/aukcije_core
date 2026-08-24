package rs.sud.eaukcija.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskExecutor;

import rs.sud.eaukcija.client.EAukcijaApiTypes.AuctionDetail;
import rs.sud.eaukcija.client.EAukcijaApiTypes.AuctionListData;
import rs.sud.eaukcija.client.EAukcijaApiTypes.AuctionSummary;
import rs.sud.eaukcija.client.EAukcijaApiTypes.Category;
import rs.sud.eaukcija.client.EAukcijaApiTypes.CategoryNode;
import rs.sud.eaukcija.client.EAukcijaApiTypes.CategoryTree;
import rs.sud.eaukcija.client.EAukcijaApiTypes.Place;
import rs.sud.eaukcija.client.EAukcijaApiTypes.RejectedAuctionSummary;
import rs.sud.eaukcija.client.EAukcijaCallResult;
import rs.sud.eaukcija.client.EAukcijaClient;
import rs.sud.eaukcija.client.EAukcijaClientException;
import rs.sud.eaukcija.client.EAukcijaClientProperties;
import rs.sud.eaukcija.client.EAukcijaErrorCode;
import rs.sud.eaukcija.model.Auction;
import rs.sud.eaukcija.repository.AuctionRepository;
import rs.sud.eaukcija.sync.ListingFingerprint;
import rs.sud.eaukcija.sync.SyncProperties;
import rs.sud.eaukcija.sync.persistence.AuctionDetailQuarantine;
import rs.sud.eaukcija.sync.persistence.AuctionListingQuarantine;
import rs.sud.eaukcija.sync.persistence.AuctionPromotionCandidate;
import rs.sud.eaukcija.sync.persistence.AuctionPromotionService;
import rs.sud.eaukcija.sync.persistence.CategoryMembershipType;
import rs.sud.eaukcija.sync.persistence.EnrichmentReason;
import rs.sud.eaukcija.sync.persistence.NormalizedPropertyKind;
import rs.sud.eaukcija.sync.persistence.SaleScope;
import rs.sud.eaukcija.sync.persistence.SyncAlreadyRunningException;
import rs.sud.eaukcija.sync.persistence.SyncRunClaimResult;
import rs.sud.eaukcija.sync.persistence.SyncRunChildResult;
import rs.sud.eaukcija.sync.persistence.SyncRunErrorEvidence;
import rs.sud.eaukcija.sync.persistence.SyncRunProgress;
import rs.sud.eaukcija.sync.persistence.SyncRunRepository;
import rs.sud.eaukcija.sync.persistence.SyncRunRootResult;
import rs.sud.eaukcija.sync.persistence.SyncRunStage;
import rs.sud.eaukcija.sync.persistence.SyncRunStatus;
import rs.sud.eaukcija.sync.persistence.WorkerLockLease;

class SyncServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-24T12:00:00Z");
    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000017");

    private EAukcijaClient client;
    private EAukcijaClientProperties clientProperties;
    private SyncProperties syncProperties;
    private SyncRunRepository runs;
    private AuctionRepository auctions;
    private AuctionPromotionService promotion;
    private WorkerLockLease workerLease;
    private SyncService service;

    @BeforeEach
    void setUp() {
        client = mock(EAukcijaClient.class);
        clientProperties = new EAukcijaClientProperties();
        clientProperties.setRootCategoryIds(List.of(7));
        clientProperties.setPageSize(2);
        syncProperties = new SyncProperties();
        syncProperties.setDetailStaleAfter(Duration.ofDays(1));
        runs = mock(SyncRunRepository.class);
        auctions = mock(AuctionRepository.class);
        promotion = mock(AuctionPromotionService.class);
        workerLease = mock(WorkerLockLease.class);
        when(runs.tryAcquireWorkerLock()).thenReturn(Optional.of(workerLease));
        when(runs.claim(any())).thenReturn(new SyncRunClaimResult(RUN_ID, false));
        when(runs.isRunning(RUN_ID)).thenReturn(true);
        when(auctions.findAllById(any())).thenReturn(List.of());
        for (int childId : List.of(47, 48, 49, 121, 124, 135)) {
            when(client.getAuctionsByCategory(eq(childId), anyInt(), eq(1)))
                    .thenReturn(call(new AuctionListData(List.of(), 0)));
        }
        TaskExecutor direct = Runnable::run;
        service = new SyncService(
                client,
                clientProperties,
                syncProperties,
                runs,
                auctions,
                promotion,
                direct,
                new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void pagesEveryRootDeduplicatesTheUnionAndFetchesEachDetailOnce() {
        when(client.getCategories()).thenReturn(call(taxonomy(7)));
        AuctionSummary one = summary(1, "first");
        AuctionSummary two = summary(2, "second");
        AuctionSummary three = summary(3, "third");
        when(client.getAuctionsByCategory(7, 2, 1))
                .thenReturn(call(new AuctionListData(List.of(one, two), 3)));
        when(client.getAuctionsByCategory(7, 2, 2))
                .thenReturn(call(new AuctionListData(List.of(three), 3)));
        when(client.getAuctionsByCategory(47, 2, 1))
                .thenReturn(call(new AuctionListData(List.of(two), 1)));
        when(client.getImmovablePropertyDetails(1)).thenReturn(call(detail(1)));
        when(client.getImmovablePropertyDetails(2)).thenReturn(call(detail(2)));
        when(client.getImmovablePropertyDetails(3)).thenReturn(call(detail(3)));

        service.startManual(UUID.randomUUID());

        verify(client).getImmovablePropertyDetails(1);
        verify(client).getImmovablePropertyDetails(2);
        verify(client).getImmovablePropertyDetails(3);
        ArgumentCaptor<List<AuctionPromotionCandidate>> candidates = ArgumentCaptor.forClass(List.class);
        verify(promotion).promote(
                eq(RUN_ID), eq("a".repeat(64)), eq(NOW), candidates.capture(), eq(List.of()), eq(List.of()));
        assertThat(candidates.getValue()).hasSize(3);
        assertThat(candidates.getValue())
                .extracting(candidate -> candidate.auction().getId())
                .containsExactly(1L, 2L, 3L);
        assertThat(candidates.getValue().get(1).memberships())
                .extracting(membership -> membership.categoryId())
                .containsExactly(7, 47, 47);
        assertThat(candidates.getValue().get(1).memberships())
                .extracting(membership -> membership.type())
                .containsExactly(
                        CategoryMembershipType.ROOT,
                        CategoryMembershipType.CHILD,
                        CategoryMembershipType.DETAIL);

        ArgumentCaptor<SyncRunRootResult> roots = ArgumentCaptor.forClass(SyncRunRootResult.class);
        verify(runs).recordRootResult(eq(RUN_ID), roots.capture());
        assertThat(roots.getAllValues()).allMatch(SyncRunRootResult::complete);
        ArgumentCaptor<SyncRunChildResult> children = ArgumentCaptor.forClass(SyncRunChildResult.class);
        verify(runs, org.mockito.Mockito.times(6)).recordChildResult(eq(RUN_ID), children.capture());
        assertThat(children.getAllValues()).filteredOn(SyncRunChildResult::complete).hasSize(3);

        ArgumentCaptor<SyncRunProgress> progress = ArgumentCaptor.forClass(SyncRunProgress.class);
        verify(runs, atLeastOnce()).updateProgress(eq(RUN_ID), progress.capture());
        assertThat(progress.getAllValues())
                .filteredOn(snapshot -> snapshot.stage() == SyncRunStage.DETAILS)
                .as("detail outcomes are checkpointed instead of written per item")
                .hasSize(1);
        SyncRunProgress finalProgress = progress.getAllValues().get(progress.getAllValues().size() - 1);
        assertThat(finalProgress.stage()).isEqualTo(SyncRunStage.PROMOTING);
        assertThat(finalProgress.pagesCompleted()).isEqualTo(5);
        assertThat(finalProgress.uniqueAuctionCount()).isEqualTo(3);
        assertThat(finalProgress.duplicateAuctionCount()).isZero();
        assertThat(finalProgress.detailsRequired()).isEqualTo(3);
        assertThat(finalProgress.detailsSucceeded()).isEqualTo(3);
        verify(runs, never()).finishIncomplete(any(), any(), any());
    }

    @Test
    void routesRootEightThroughCommonDetailsAndPromotesBothValidatedScopes() {
        clientProperties.setRootCategoryIds(List.of(7, 8));
        when(client.getCategories()).thenReturn(call(taxonomy(7, 8)));
        when(client.getAuctionsByCategory(7, 2, 1))
                .thenReturn(call(new AuctionListData(List.of(summary(1, "immovable")), 1)));
        when(client.getAuctionsByCategory(8, 2, 1))
                .thenReturn(call(new AuctionListData(List.of(commonSummary(2, "common")), 1)));
        when(client.getAuctionsByCategory(47, 2, 1))
                .thenReturn(call(new AuctionListData(List.of(summary(1, "immovable")), 1)));
        when(client.getAuctionsByCategory(121, 2, 1))
                .thenReturn(call(new AuctionListData(List.of(commonSummary(2, "common")), 1)));
        when(client.getImmovablePropertyDetails(1)).thenReturn(call(detail(1)));
        when(client.getCommonPropertyDetails(2)).thenReturn(call(commonDetail(2)));

        service.startManual(UUID.randomUUID());

        verify(client).getImmovablePropertyDetails(1);
        verify(client).getCommonPropertyDetails(2);
        verify(client, never()).getCommonPropertyDetails(1);
        verify(client, never()).getImmovablePropertyDetails(2);
        ArgumentCaptor<List<AuctionPromotionCandidate>> candidates = ArgumentCaptor.forClass(List.class);
        verify(promotion).promote(
                eq(RUN_ID), eq("a".repeat(64)), eq(NOW), candidates.capture(), eq(List.of()), eq(List.of()));
        assertThat(candidates.getValue())
                .extracting(AuctionPromotionCandidate::saleScope)
                .containsExactly(SaleScope.IMMOVABLE, SaleScope.COMMON);
        assertThat(candidates.getValue())
                .extracting(AuctionPromotionCandidate::propertyKind)
                .containsExactly(NormalizedPropertyKind.PARCEL, NormalizedPropertyKind.PARCEL);
    }

    @Test
    void refreshesFreshDetailsWhenTheObservedSaleScopeChanges() {
        clientProperties.setRootCategoryIds(List.of(8));
        when(client.getCategories()).thenReturn(call(taxonomy(8)));
        AuctionSummary observed = commonSummary(1, "moved-to-common");
        when(client.getAuctionsByCategory(8, 2, 1))
                .thenReturn(call(new AuctionListData(List.of(observed), 1)));
        when(client.getAuctionsByCategory(121, 2, 1))
                .thenReturn(call(new AuctionListData(List.of(observed), 1)));

        Auction prior = existing(
                observed,
                ListingFingerprint.sha256(observed),
                NOW.minusSeconds(60));
        prior.setSaleScope(SaleScope.IMMOVABLE);
        prior.setDescription("stale immovable detail");
        prior.setPlaceName("stale place");
        when(auctions.findAllById(any())).thenReturn(List.of(prior));
        when(client.getCommonPropertyDetails(1)).thenReturn(call(commonDetail(1)));

        service.startManual(UUID.randomUUID());

        verify(client).getCommonPropertyDetails(1);
        verify(client, never()).getImmovablePropertyDetails(1);
        ArgumentCaptor<List<AuctionPromotionCandidate>> candidates = ArgumentCaptor.forClass(List.class);
        verify(promotion).promote(
                eq(RUN_ID), eq("a".repeat(64)), eq(NOW), candidates.capture(), eq(List.of()), eq(List.of()));
        AuctionPromotionCandidate candidate = candidates.getValue().get(0);
        assertThat(candidate.saleScope()).isEqualTo(SaleScope.COMMON);
        assertThat(candidate.propertyKind()).isEqualTo(NormalizedPropertyKind.PARCEL);
        assertThat(candidate.detailRefreshed()).isTrue();
        assertThat(candidate.enrichmentReason()).isEqualTo(EnrichmentReason.DETAIL_REFRESHED);
        assertThat(candidate.sourceDetailCategoryId()).isEqualTo(121);
        assertThat(candidate.auction().getDescription()).isEqualTo("detail");
        assertThat(candidate.auction().getCategoryName()).isEqualTo("Заједничка целина");
        assertThat(candidate.auction().getPlaceName()).isNull();
    }

    @Test
    void incompatibleCrossScopeDuplicateFailsBeforeAnyDetailRequest() {
        clientProperties.setRootCategoryIds(List.of(7, 8));
        when(client.getCategories()).thenReturn(call(taxonomy(7, 8)));
        AuctionSummary duplicate = summary(1, "same-source-record");
        when(client.getAuctionsByCategory(7, 2, 1))
                .thenReturn(call(new AuctionListData(List.of(duplicate), 1)));
        when(client.getAuctionsByCategory(8, 2, 1))
                .thenReturn(call(new AuctionListData(List.of(duplicate), 1)));

        service.startManual(UUID.randomUUID());

        verify(client, never()).getImmovablePropertyDetails(anyLong());
        verify(client, never()).getCommonPropertyDetails(anyLong());
        verify(promotion, never()).promote(any(), any(), any(), any(), any(), any());
        ArgumentCaptor<SyncRunErrorEvidence> error = ArgumentCaptor.forClass(SyncRunErrorEvidence.class);
        verify(runs).appendError(eq(RUN_ID), error.capture());
        assertThat(error.getValue().errorCode()).isEqualTo("CATEGORY_DRIFT");
        assertThat(error.getValue().auctionId()).isEqualTo(1L);
        verify(runs).finishIncomplete(eq(RUN_ID), eq(SyncRunStatus.PARTIAL), any());
    }

    @Test
    void configuredRootTypeDriftFailsBeforeAnyListingRequest() {
        clientProperties.setRootCategoryIds(List.of(7, 8));
        CategoryTree drifted = new CategoryTree(
                List.of(
                        new CategoryNode(
                                "Root 7", 7, "category7", List.of(), "ImmovableProperties"),
                        new CategoryNode(
                                "Root 8", 8, "category8", List.of(), "ImmovableProperties")),
                "[]",
                "a".repeat(64));
        when(client.getCategories()).thenReturn(call(drifted));

        service.startManual(UUID.randomUUID());

        verify(client, never()).getAuctionsByCategory(anyInt(), anyInt(), anyInt());
        verify(promotion, never()).promote(any(), any(), any(), any(), any(), any());
        ArgumentCaptor<SyncRunErrorEvidence> error = ArgumentCaptor.forClass(SyncRunErrorEvidence.class);
        verify(runs).appendError(eq(RUN_ID), error.capture());
        assertThat(error.getValue().stage()).isEqualTo(SyncRunStage.CATEGORIES);
        assertThat(error.getValue().errorCode()).isEqualTo("CATEGORY_DRIFT");
        verify(runs).finishIncomplete(eq(RUN_ID), eq(SyncRunStatus.PARTIAL), any());
    }

    @Test
    void rootOnlyAuctionRemainsUnknownEvenWhenDetailNamesAReviewedCategory() {
        when(client.getCategories()).thenReturn(call(taxonomy(7)));
        when(client.getAuctionsByCategory(7, 2, 1))
                .thenReturn(call(new AuctionListData(List.of(summary(1, "root-only")), 1)));
        when(client.getImmovablePropertyDetails(1)).thenReturn(call(detail(1)));

        service.startManual(UUID.randomUUID());

        ArgumentCaptor<List<AuctionPromotionCandidate>> candidates = ArgumentCaptor.forClass(List.class);
        verify(promotion).promote(eq(RUN_ID), any(), eq(NOW), candidates.capture(), eq(List.of()), eq(List.of()));
        AuctionPromotionCandidate candidate = candidates.getValue().get(0);
        assertThat(candidate.propertyKind()).isEqualTo(NormalizedPropertyKind.UNKNOWN);
        assertThat(candidate.memberships())
                .extracting(membership -> membership.type())
                .containsExactly(CategoryMembershipType.ROOT, CategoryMembershipType.DETAIL);
    }

    @Test
    void reviewedChildMembershipsClassifyParcelBuildingAndUnit() {
        AuctionSummary parcel = summary(1, "parcel");
        AuctionSummary building = summary(2, "building");
        AuctionSummary unit = summary(3, "unit");
        when(client.getCategories()).thenReturn(call(taxonomy(7)));
        when(client.getAuctionsByCategory(7, 2, 1))
                .thenReturn(call(new AuctionListData(List.of(parcel, building), 3)));
        when(client.getAuctionsByCategory(7, 2, 2))
                .thenReturn(call(new AuctionListData(List.of(unit), 3)));
        when(client.getAuctionsByCategory(47, 2, 1))
                .thenReturn(call(new AuctionListData(List.of(parcel), 1)));
        when(client.getAuctionsByCategory(48, 2, 1))
                .thenReturn(call(new AuctionListData(List.of(building), 1)));
        when(client.getAuctionsByCategory(49, 2, 1))
                .thenReturn(call(new AuctionListData(List.of(unit), 1)));
        when(client.getImmovablePropertyDetails(1)).thenReturn(call(detail(1)));
        when(client.getImmovablePropertyDetails(2)).thenReturn(call(detail(2)));
        when(client.getImmovablePropertyDetails(3)).thenReturn(call(detail(3)));

        service.startManual(UUID.randomUUID());

        ArgumentCaptor<List<AuctionPromotionCandidate>> candidates = ArgumentCaptor.forClass(List.class);
        verify(promotion).promote(eq(RUN_ID), any(), eq(NOW), candidates.capture(), eq(List.of()), eq(List.of()));
        assertThat(candidates.getValue())
                .extracting(AuctionPromotionCandidate::propertyKind)
                .containsExactly(
                        NormalizedPropertyKind.PARCEL,
                        NormalizedPropertyKind.BUILDING,
                        NormalizedPropertyKind.UNIT);
    }

    @Test
    void newDirectChildIsFetchedAndRetainedButClassifiesUnknown() {
        when(client.getCategories()).thenReturn(call(taxonomyWithNewChild(7, 999)));
        AuctionSummary auction = summary(1, "new-child");
        when(client.getAuctionsByCategory(7, 2, 1))
                .thenReturn(call(new AuctionListData(List.of(auction), 1)));
        when(client.getAuctionsByCategory(999, 2, 1))
                .thenReturn(call(new AuctionListData(List.of(auction), 1)));
        when(client.getImmovablePropertyDetails(1)).thenReturn(call(detail(1)));

        service.startManual(UUID.randomUUID());

        verify(client).getAuctionsByCategory(999, 2, 1);
        ArgumentCaptor<List<AuctionPromotionCandidate>> candidates = ArgumentCaptor.forClass(List.class);
        verify(promotion).promote(eq(RUN_ID), any(), eq(NOW), candidates.capture(), eq(List.of()), eq(List.of()));
        AuctionPromotionCandidate candidate = candidates.getValue().get(0);
        assertThat(candidate.propertyKind()).isEqualTo(NormalizedPropertyKind.UNKNOWN);
        assertThat(candidate.memberships())
                .filteredOn(membership -> membership.type() == CategoryMembershipType.CHILD)
                .extracting(membership -> membership.categoryId())
                .containsExactly(999);
    }

    @Test
    void missingReviewedChildFailsBeforeAnyListingRequest() {
        when(client.getCategories()).thenReturn(call(taxonomyWithoutChild(7, 49)));

        service.startManual(UUID.randomUUID());

        verify(client, never()).getAuctionsByCategory(anyInt(), anyInt(), anyInt());
        verify(promotion, never()).promote(any(), any(), any(), any(), any(), any());
        ArgumentCaptor<SyncRunErrorEvidence> error = ArgumentCaptor.forClass(SyncRunErrorEvidence.class);
        verify(runs).appendError(eq(RUN_ID), error.capture());
        assertThat(error.getValue().stage()).isEqualTo(SyncRunStage.CATEGORIES);
        assertThat(error.getValue().errorCode()).isEqualTo("CATEGORY_DRIFT");
    }

    @Test
    void newDirectChildWithCrossScopeTypeFailsBeforeAnyListingRequest() {
        CategoryTree baseline = taxonomyWithNewChild(7, 999);
        CategoryNode root = baseline.roots().get(0);
        List<CategoryNode> children = root.children().stream()
                .map(child -> child.value() == 999
                        ? new CategoryNode(
                                child.title(), child.value(), child.key(), child.children(),
                                "CommonProperties")
                        : child)
                .toList();
        when(client.getCategories()).thenReturn(call(new CategoryTree(
                List.of(new CategoryNode(
                        root.title(), root.value(), root.key(), children, root.categoryType())),
                "[]",
                "a".repeat(64))));

        service.startManual(UUID.randomUUID());

        verify(client, never()).getAuctionsByCategory(anyInt(), anyInt(), anyInt());
        verify(promotion, never()).promote(any(), any(), any(), any(), any(), any());
        ArgumentCaptor<SyncRunErrorEvidence> error = ArgumentCaptor.forClass(SyncRunErrorEvidence.class);
        verify(runs).appendError(eq(RUN_ID), error.capture());
        assertThat(error.getValue().errorCode()).isEqualTo("CATEGORY_DRIFT");
    }

    @Test
    void childIdsOutsideTheParentRootFailSubsetEvidenceAndNeverAddDiscovery() {
        when(client.getCategories()).thenReturn(call(taxonomy(7)));
        when(client.getAuctionsByCategory(7, 2, 1))
                .thenReturn(call(new AuctionListData(List.of(summary(1, "root")), 1)));
        when(client.getAuctionsByCategory(47, 2, 1))
                .thenReturn(call(new AuctionListData(List.of(summary(2, "child-only")), 1)));

        service.startManual(UUID.randomUUID());

        verify(client, never()).getImmovablePropertyDetails(anyLong());
        verify(promotion, never()).promote(any(), any(), any(), any(), any(), any());
        ArgumentCaptor<SyncRunChildResult> child = ArgumentCaptor.forClass(SyncRunChildResult.class);
        verify(runs, org.mockito.Mockito.times(4)).recordChildResult(eq(RUN_ID), child.capture());
        SyncRunChildResult failedChild = child.getAllValues().get(child.getAllValues().size() - 1);
        assertThat(failedChild.childCategoryId()).isEqualTo(47);
        assertThat(failedChild.subsetOfParentRoot()).isFalse();
        assertThat(failedChild.complete()).isFalse();
        ArgumentCaptor<SyncRunErrorEvidence> error = ArgumentCaptor.forClass(SyncRunErrorEvidence.class);
        verify(runs).appendError(eq(RUN_ID), error.capture());
        assertThat(error.getValue().errorCode()).isEqualTo("CHILD_ID_OUTSIDE_ROOT");
        assertThat(error.getValue().childCategoryId()).isEqualTo(47);
        assertThat(error.getValue().auctionId()).isEqualTo(2L);
    }

    @Test
    void firstPageChildTimeoutRetainsAttributableIncompleteEvidence() {
        AuctionSummary one = summary(1, "one");
        when(client.getCategories()).thenReturn(call(taxonomy(7)));
        when(client.getAuctionsByCategory(7, 2, 1))
                .thenReturn(call(new AuctionListData(List.of(one), 1)));
        EAukcijaClientException timeout = clientFailure(EAukcijaErrorCode.TIMEOUT, null, 3);
        when(client.getAuctionsByCategory(47, 2, 1))
                .thenThrow(timeout);

        service.startManual(UUID.randomUUID());

        verify(client, never()).getImmovablePropertyDetails(anyLong());
        verify(promotion, never()).promote(any(), any(), any(), any(), any(), any());
        ArgumentCaptor<SyncRunChildResult> children = ArgumentCaptor.forClass(SyncRunChildResult.class);
        verify(runs, org.mockito.Mockito.times(4)).recordChildResult(eq(RUN_ID), children.capture());
        SyncRunChildResult failedChild = children.getAllValues()
                .get(children.getAllValues().size() - 1);
        assertThat(failedChild.parentRootCategoryId()).isEqualTo(7);
        assertThat(failedChild.childCategoryId()).isEqualTo(47);
        assertThat(failedChild.pagesCompleted()).isZero();
        assertThat(failedChild.complete()).isFalse();
        ArgumentCaptor<SyncRunErrorEvidence> error = ArgumentCaptor.forClass(SyncRunErrorEvidence.class);
        verify(runs).appendError(eq(RUN_ID), error.capture());
        assertThat(error.getValue().errorCode()).isEqualTo("TIMEOUT");
        assertThat(error.getValue().rootCategoryId()).isEqualTo(7);
        assertThat(error.getValue().childCategoryId()).isEqualTo(47);
        assertThat(error.getValue().pageNumber()).isEqualTo(1);
        assertThat(error.getValue().attemptNumber()).isEqualTo(3);
        verify(runs).finishIncomplete(eq(RUN_ID), eq(SyncRunStatus.PARTIAL), any());
    }

    @Test
    void successfullyPagesAChildAndRetainsCompleteSubsetEvidence() {
        AuctionSummary one = summary(1, "one");
        AuctionSummary two = summary(2, "two");
        AuctionSummary three = summary(3, "three");
        when(client.getCategories()).thenReturn(call(taxonomy(7)));
        when(client.getAuctionsByCategory(7, 2, 1))
                .thenReturn(call(new AuctionListData(List.of(one, two), 3)));
        when(client.getAuctionsByCategory(7, 2, 2))
                .thenReturn(call(new AuctionListData(List.of(three), 3)));
        when(client.getAuctionsByCategory(47, 2, 1))
                .thenReturn(call(new AuctionListData(List.of(one, two), 3)));
        when(client.getAuctionsByCategory(47, 2, 2))
                .thenReturn(call(new AuctionListData(List.of(three), 3)));
        when(client.getImmovablePropertyDetails(1)).thenReturn(call(detail(1)));
        when(client.getImmovablePropertyDetails(2)).thenReturn(call(detail(2)));
        when(client.getImmovablePropertyDetails(3)).thenReturn(call(detail(3)));

        service.startManual(UUID.randomUUID());

        verify(client).getAuctionsByCategory(47, 2, 1);
        verify(client).getAuctionsByCategory(47, 2, 2);
        verify(client).getImmovablePropertyDetails(1);
        verify(client).getImmovablePropertyDetails(2);
        verify(client).getImmovablePropertyDetails(3);
        verify(promotion).promote(eq(RUN_ID), any(), eq(NOW), any(), eq(List.of()), eq(List.of()));
        ArgumentCaptor<SyncRunChildResult> children = ArgumentCaptor.forClass(SyncRunChildResult.class);
        verify(runs, org.mockito.Mockito.times(6)).recordChildResult(eq(RUN_ID), children.capture());
        SyncRunChildResult child47 = children.getAllValues().stream()
                .filter(result -> result.childCategoryId() == 47 && result.complete())
                .findFirst()
                .orElseThrow();
        assertThat(child47.sourceTotalCount()).isEqualTo(3);
        assertThat(child47.rowsObserved()).isEqualTo(3);
        assertThat(child47.uniqueIds()).isEqualTo(3);
        assertThat(child47.pagesExpected()).isEqualTo(2);
        assertThat(child47.pagesCompleted()).isEqualTo(2);
        assertThat(child47.subsetOfParentRoot()).isTrue();
    }

    @Test
    void childPaginationRequiresAStableTotal() {
        AuctionSummary one = summary(1, "one");
        AuctionSummary two = summary(2, "two");
        AuctionSummary three = summary(3, "three");
        when(client.getCategories()).thenReturn(call(taxonomy(7)));
        when(client.getAuctionsByCategory(7, 2, 1))
                .thenReturn(call(new AuctionListData(List.of(one, two), 3)));
        when(client.getAuctionsByCategory(7, 2, 2))
                .thenReturn(call(new AuctionListData(List.of(three), 3)));
        when(client.getAuctionsByCategory(47, 2, 1))
                .thenReturn(call(new AuctionListData(List.of(one, two), 3)));
        when(client.getAuctionsByCategory(47, 2, 2))
                .thenReturn(call(new AuctionListData(List.of(three), 4)));

        service.startManual(UUID.randomUUID());

        verify(client, never()).getImmovablePropertyDetails(anyLong());
        verify(promotion, never()).promote(any(), any(), any(), any(), any(), any());
        ArgumentCaptor<SyncRunErrorEvidence> error = ArgumentCaptor.forClass(SyncRunErrorEvidence.class);
        verify(runs).appendError(eq(RUN_ID), error.capture());
        assertThat(error.getValue().errorCode()).isEqualTo("CHILD_TOTAL_CHANGED");
        assertThat(error.getValue().rootCategoryId()).isEqualTo(7);
        assertThat(error.getValue().pageNumber()).isEqualTo(2);
    }

    @Test
    void emptyCommonRootAndItsChildrenStillFormACompleteScope() {
        clientProperties.setRootCategoryIds(List.of(7, 8));
        when(client.getCategories()).thenReturn(call(taxonomy(7, 8)));
        when(client.getAuctionsByCategory(7, 2, 1))
                .thenReturn(call(new AuctionListData(List.of(), 0)));
        when(client.getAuctionsByCategory(8, 2, 1))
                .thenReturn(call(new AuctionListData(List.of(), 0)));

        service.startManual(UUID.randomUUID());

        verify(client, never()).getCommonPropertyDetails(anyLong());
        verify(client, never()).getImmovablePropertyDetails(anyLong());
        verify(promotion).promote(eq(RUN_ID), any(), eq(NOW), eq(List.of()), eq(List.of()), eq(List.of()));
        ArgumentCaptor<SyncRunChildResult> children = ArgumentCaptor.forClass(SyncRunChildResult.class);
        verify(runs, org.mockito.Mockito.times(12)).recordChildResult(eq(RUN_ID), children.capture());
        assertThat(children.getAllValues()).filteredOn(SyncRunChildResult::complete).hasSize(6);
    }

    @Test
    void aLaterPageWithAChangedTotalEndsPartialWithoutPromotion() {
        when(client.getCategories()).thenReturn(call(taxonomy(7)));
        clientProperties.setRootCategoryIds(List.of(7));
        when(client.getAuctionsByCategory(7, 2, 1))
                .thenReturn(call(new AuctionListData(List.of(summary(1, "one"), summary(2, "two")), 3)));
        when(client.getAuctionsByCategory(7, 2, 2))
                .thenReturn(call(new AuctionListData(List.of(summary(3, "three")), 4)));

        service.startManual(UUID.randomUUID());

        verify(promotion, never()).promote(any(), any(), any(), any(), any(), any());
        ArgumentCaptor<SyncRunErrorEvidence> error = ArgumentCaptor.forClass(SyncRunErrorEvidence.class);
        verify(runs).appendError(eq(RUN_ID), error.capture());
        assertThat(error.getValue().errorCode()).isEqualTo("TOTAL_CHANGED");
        assertThat(error.getValue().rootCategoryId()).isEqualTo(7);
        assertThat(error.getValue().pageNumber()).isEqualTo(2);
        verify(runs).finishIncomplete(
                eq(RUN_ID), eq(SyncRunStatus.PARTIAL), any(SyncRunProgress.class));
    }

    @Test
    void refreshesNewChangedAndStaleDetailsButReusesFreshUnchangedDetails() {
        clientProperties.setRootCategoryIds(List.of(7));
        clientProperties.setPageSize(10);
        when(client.getCategories()).thenReturn(call(taxonomy(7)));
        List<AuctionSummary> summaries = List.of(
                summary(1, "new"),
                summary(2, "changed"),
                summary(3, "stale"),
                summary(4, "fresh"));
        when(client.getAuctionsByCategory(7, 10, 1))
                .thenReturn(call(new AuctionListData(summaries, 4)));

        Auction changed = existing(summaries.get(1), "b".repeat(64), NOW.minusSeconds(60));
        Auction stale = existing(
                summaries.get(2), ListingFingerprint.sha256(summaries.get(2)), NOW.minus(Duration.ofDays(2)));
        Auction fresh = existing(
                summaries.get(3), ListingFingerprint.sha256(summaries.get(3)), NOW.minusSeconds(60));
        when(auctions.findAllById(any())).thenReturn(List.of(changed, stale, fresh));
        when(client.getImmovablePropertyDetails(1)).thenReturn(call(detail(1)));
        when(client.getImmovablePropertyDetails(2)).thenReturn(call(detail(2)));
        when(client.getImmovablePropertyDetails(3)).thenReturn(call(detail(3)));

        service.startManual(UUID.randomUUID());

        verify(client).getImmovablePropertyDetails(1);
        verify(client).getImmovablePropertyDetails(2);
        verify(client).getImmovablePropertyDetails(3);
        verify(client, never()).getImmovablePropertyDetails(4);
        ArgumentCaptor<List<AuctionPromotionCandidate>> candidates = ArgumentCaptor.forClass(List.class);
        verify(promotion).promote(eq(RUN_ID), any(), eq(NOW), candidates.capture(), eq(List.of()), eq(List.of()));
        assertThat(candidates.getValue())
                .extracting(AuctionPromotionCandidate::enrichmentReason)
                .containsExactly(
                        EnrichmentReason.NEW,
                        EnrichmentReason.LISTING_CHANGED,
                        EnrichmentReason.DETAIL_REFRESHED,
                        EnrichmentReason.NONE);
        assertThat(candidates.getValue().get(3).detailRefreshed()).isFalse();
    }

    @Test
    void crossPageDuplicateIdsAreDeduplicatedWhileUniqueCoverageStillProgresses() {
        clientProperties.setRootCategoryIds(List.of(7));
        when(client.getCategories()).thenReturn(call(taxonomy(7)));
        AuctionSummary one = summary(1, "one");
        AuctionSummary two = summary(2, "two");
        AuctionSummary three = summary(3, "three");
        when(client.getAuctionsByCategory(7, 2, 1))
                .thenReturn(call(new AuctionListData(List.of(one, two), 3)));
        when(client.getAuctionsByCategory(7, 2, 2))
                .thenReturn(call(new AuctionListData(List.of(two, three), 3)));
        when(client.getImmovablePropertyDetails(1)).thenReturn(call(detail(1)));
        when(client.getImmovablePropertyDetails(2)).thenReturn(call(detail(2)));
        when(client.getImmovablePropertyDetails(3)).thenReturn(call(detail(3)));

        service.startManual(UUID.randomUUID());

        verify(client).getImmovablePropertyDetails(1);
        verify(client).getImmovablePropertyDetails(2);
        verify(client).getImmovablePropertyDetails(3);
        verify(promotion).promote(eq(RUN_ID), any(), eq(NOW), any(), eq(List.of()), eq(List.of()));
        ArgumentCaptor<SyncRunRootResult> root = ArgumentCaptor.forClass(SyncRunRootResult.class);
        verify(runs).recordRootResult(eq(RUN_ID), root.capture());
        assertThat(root.getValue().complete()).isTrue();
        assertThat(root.getValue().rowsObserved()).isEqualTo(4);
        assertThat(root.getValue().uniqueIds()).isEqualTo(3);
        assertThat(root.getValue().duplicateIds()).isEqualTo(1);
        verify(runs, never()).finishIncomplete(any(), any(), any());
    }

    @Test
    void duplicateOnlyPageFailsWhenUniqueCoverageStopsProgressing() {
        clientProperties.setRootCategoryIds(List.of(7));
        when(client.getCategories()).thenReturn(call(taxonomy(7)));
        AuctionSummary one = summary(1, "one");
        when(client.getAuctionsByCategory(7, 2, 1))
                .thenReturn(call(new AuctionListData(List.of(one, one), 2)));
        when(client.getAuctionsByCategory(7, 2, 2))
                .thenReturn(call(new AuctionListData(List.of(one), 2)));

        service.startManual(UUID.randomUUID());

        verify(client, never()).getImmovablePropertyDetails(anyLong());
        verify(promotion, never()).promote(any(), any(), any(), any(), any(), any());
        ArgumentCaptor<SyncRunErrorEvidence> error = ArgumentCaptor.forClass(SyncRunErrorEvidence.class);
        verify(runs).appendError(eq(RUN_ID), error.capture());
        assertThat(error.getValue().errorCode()).isEqualTo("NO_UNIQUE_PROGRESS");
    }

    @Test
    void shortIntermediatePageEndsPartialWithoutPromotion() {
        clientProperties.setRootCategoryIds(List.of(7));
        when(client.getCategories()).thenReturn(call(taxonomy(7)));
        when(client.getAuctionsByCategory(7, 2, 1))
                .thenReturn(call(new AuctionListData(List.of(summary(1, "only-one")), 3)));

        service.startManual(UUID.randomUUID());

        verify(promotion, never()).promote(any(), any(), any(), any(), any(), any());
        ArgumentCaptor<SyncRunErrorEvidence> error = ArgumentCaptor.forClass(SyncRunErrorEvidence.class);
        verify(runs).appendError(eq(RUN_ID), error.capture());
        assertThat(error.getValue().errorCode()).isEqualTo("SHORT_INTERMEDIATE_PAGE");
        assertThat(error.getValue().rootCategoryId()).isEqualTo(7);
        assertThat(error.getValue().pageNumber()).isEqualTo(1);
        verify(runs).finishIncomplete(eq(RUN_ID), eq(SyncRunStatus.PARTIAL), any());
    }

    @Test
    void laterListingTimeoutRetainsCoordinatesRetriesAndNeverPromotes() {
        clientProperties.setRootCategoryIds(List.of(7));
        when(client.getCategories()).thenReturn(call(taxonomy(7)));
        when(client.getAuctionsByCategory(7, 2, 1)).thenReturn(call(new AuctionListData(
                List.of(summary(1, "one"), summary(2, "two")), 3)));
        EAukcijaClientException timeout = clientFailure(EAukcijaErrorCode.TIMEOUT, null, 3);
        when(client.getAuctionsByCategory(7, 2, 2))
                .thenThrow(timeout);

        service.startManual(UUID.randomUUID());

        verify(promotion, never()).promote(any(), any(), any(), any(), any(), any());
        ArgumentCaptor<SyncRunErrorEvidence> error = ArgumentCaptor.forClass(SyncRunErrorEvidence.class);
        verify(runs).appendError(eq(RUN_ID), error.capture());
        assertThat(error.getValue().errorCode()).isEqualTo("TIMEOUT");
        assertThat(error.getValue().rootCategoryId()).isEqualTo(7);
        assertThat(error.getValue().pageNumber()).isEqualTo(2);
        assertThat(error.getValue().attemptNumber()).isEqualTo(3);
        ArgumentCaptor<SyncRunProgress> terminal = ArgumentCaptor.forClass(SyncRunProgress.class);
        verify(runs).finishIncomplete(eq(RUN_ID), eq(SyncRunStatus.PARTIAL), terminal.capture());
        assertThat(terminal.getValue().retryCount()).isEqualTo(2);
    }

    @Test
    void persistenceDomainInvalidDetailIsQuarantinedWhileGoodRecordsStillPromote() {
        clientProperties.setRootCategoryIds(List.of(7));
        when(client.getCategories()).thenReturn(call(taxonomy(7)));
        when(client.getAuctionsByCategory(7, 2, 1))
                .thenReturn(call(new AuctionListData(
                        List.of(summary(1, "one"), summary(2, "invalid")), 3)));
        when(client.getAuctionsByCategory(7, 2, 2))
                .thenReturn(call(new AuctionListData(List.of(summary(3, "three")), 3)));
        when(client.getImmovablePropertyDetails(1)).thenReturn(call(detail(1)));
        // EAukcijaClient maps a detail that cannot fit the auction persistence
        // domain (bounded text, U+0000, or NUMERIC(38,2)) to this redacted code.
        EAukcijaClientException invalidDetail =
                clientFailure(EAukcijaErrorCode.INVALID_DATA, 200, 1);
        when(client.getImmovablePropertyDetails(2))
                .thenThrow(invalidDetail);
        when(client.getImmovablePropertyDetails(3)).thenReturn(call(detail(3)));

        service.startManual(UUID.randomUUID());

        ArgumentCaptor<List<AuctionPromotionCandidate>> candidates = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<AuctionDetailQuarantine>> quarantines = ArgumentCaptor.forClass(List.class);
        verify(promotion).promote(
                eq(RUN_ID), any(), eq(NOW), candidates.capture(), quarantines.capture(), eq(List.of()));
        assertThat(candidates.getValue())
                .extracting(candidate -> candidate.auction().getId())
                .containsExactly(1L, 3L);
        assertThat(quarantines.getValue()).containsExactly(new AuctionDetailQuarantine(
                2L, ListingFingerprint.sha256(summary(2, "invalid")), "INVALID_DATA"));
        ArgumentCaptor<SyncRunErrorEvidence> error = ArgumentCaptor.forClass(SyncRunErrorEvidence.class);
        verify(runs).appendError(eq(RUN_ID), error.capture(), eq(true), eq(true));
        assertThat(error.getValue().stage()).isEqualTo(SyncRunStage.DETAILS);
        assertThat(error.getValue().auctionId()).isEqualTo(2L);
        assertThat(error.getValue().errorCode()).isEqualTo("INVALID_DATA");
        verify(runs, never()).finishIncomplete(any(), any(), any());
    }

    @Test
    void persistenceDomainInvalidListingIsHeldBackWhileGoodRecordsStillPromote() {
        when(client.getCategories()).thenReturn(call(taxonomy(7)));
        AuctionSummary good = summary(1, "good");
        RejectedAuctionSummary rejected = rejectedSummary(2, "b");
        when(client.getAuctionsByCategory(7, 2, 1))
                .thenReturn(call(new AuctionListData(List.of(good), 2, List.of(rejected))));
        when(client.getImmovablePropertyDetails(1)).thenReturn(call(detail(1)));

        service.startManual(UUID.randomUUID());

        verify(client).getImmovablePropertyDetails(1);
        verify(client, never()).getImmovablePropertyDetails(2);
        ArgumentCaptor<List<AuctionPromotionCandidate>> candidates = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<AuctionListingQuarantine>> listingQuarantines =
                ArgumentCaptor.forClass(List.class);
        verify(promotion).promote(
                eq(RUN_ID),
                any(),
                eq(NOW),
                candidates.capture(),
                eq(List.of()),
                listingQuarantines.capture());
        assertThat(candidates.getValue())
                .extracting(candidate -> candidate.auction().getId())
                .containsExactly(1L);
        assertThat(listingQuarantines.getValue()).containsExactly(
                new AuctionListingQuarantine(
                        2L, "b".repeat(64), "INVALID_DATA", 7, null, 1));

        ArgumentCaptor<SyncRunErrorEvidence> error = ArgumentCaptor.forClass(SyncRunErrorEvidence.class);
        verify(runs).appendError(eq(RUN_ID), error.capture(), eq(true), eq(true));
        assertThat(error.getValue().stage()).isEqualTo(SyncRunStage.LISTINGS);
        assertThat(error.getValue().rootCategoryId()).isEqualTo(7);
        assertThat(error.getValue().childCategoryId()).isNull();
        assertThat(error.getValue().pageNumber()).isEqualTo(1);
        assertThat(error.getValue().auctionId()).isEqualTo(2L);
        assertThat(error.getValue().errorCode()).isEqualTo("INVALID_DATA");

        ArgumentCaptor<SyncRunProgress> progress = ArgumentCaptor.forClass(SyncRunProgress.class);
        verify(runs, atLeastOnce()).updateProgress(eq(RUN_ID), progress.capture());
        SyncRunProgress terminal = progress.getAllValues().get(progress.getAllValues().size() - 1);
        assertThat(terminal.listingRowsObserved()).isEqualTo(2);
        assertThat(terminal.listingRowsQuarantined()).isEqualTo(1);
        assertThat(terminal.uniqueAuctionCount()).isEqualTo(2);
        assertThat(terminal.duplicateAuctionCount()).isZero();
        assertThat(terminal.detailsRequired()).isEqualTo(1);
        assertThat(terminal.detailsSucceeded()).isEqualTo(1);
        assertThat(terminal.errorCount()).isEqualTo(1);
        assertThat(terminal.unresolvedErrorCount()).isZero();
        verify(runs, never()).finishIncomplete(any(), any(), any());
    }

    @Test
    void listingRejectionRepeatedInAChildIsQuarantinedOnceAndNeverDetailed() {
        when(client.getCategories()).thenReturn(call(taxonomy(7)));
        AuctionSummary good = summary(1, "good");
        AuctionSummary laterRejected = summary(2, "bad-in-child");
        RejectedAuctionSummary rejected = rejectedSummary(2, "c");
        when(client.getAuctionsByCategory(7, 2, 1))
                .thenReturn(call(new AuctionListData(List.of(good, laterRejected), 2)));
        when(client.getAuctionsByCategory(47, 2, 1))
                .thenReturn(call(new AuctionListData(List.of(good), 2, List.of(rejected))));
        when(client.getAuctionsByCategory(48, 2, 1))
                .thenReturn(call(new AuctionListData(List.of(), 1, List.of(rejected))));
        when(client.getImmovablePropertyDetails(1)).thenReturn(call(detail(1)));

        service.startManual(UUID.randomUUID());

        verify(client).getImmovablePropertyDetails(1);
        verify(client, never()).getImmovablePropertyDetails(2);
        verify(runs, org.mockito.Mockito.times(1))
                .appendError(eq(RUN_ID), any(), eq(true), anyBoolean());
        ArgumentCaptor<List<AuctionListingQuarantine>> listingQuarantines =
                ArgumentCaptor.forClass(List.class);
        verify(promotion).promote(
                eq(RUN_ID),
                any(),
                eq(NOW),
                any(),
                eq(List.of()),
                listingQuarantines.capture());
        assertThat(listingQuarantines.getValue()).containsExactly(
                new AuctionListingQuarantine(
                        2L, "c".repeat(64), "INVALID_DATA", 7, 47, 1));

        ArgumentCaptor<SyncRunChildResult> children = ArgumentCaptor.forClass(SyncRunChildResult.class);
        verify(runs, org.mockito.Mockito.times(6)).recordChildResult(eq(RUN_ID), children.capture());
        assertThat(children.getAllValues())
                .filteredOn(result -> result.childCategoryId() == 47 && result.complete())
                .singleElement()
                .satisfies(result -> {
                    assertThat(result.rowsObserved()).isEqualTo(2);
                    assertThat(result.uniqueIds()).isEqualTo(2);
                    assertThat(result.subsetOfParentRoot()).isTrue();
                });
    }

    @Test
    void listingQuarantineLimitLeavesTheOverflowFailureUnresolvedAndBlocksPromotion() {
        syncProperties.setMaxQuarantinedListings(1);
        when(client.getCategories()).thenReturn(call(taxonomy(7)));
        when(client.getAuctionsByCategory(7, 2, 1)).thenReturn(call(new AuctionListData(
                List.of(),
                2,
                List.of(rejectedSummary(1, "d"), rejectedSummary(2, "e")))));

        service.startManual(UUID.randomUUID());

        verify(client, never()).getImmovablePropertyDetails(anyLong());
        verify(promotion, never()).promote(any(), any(), any(), any(), any(), any());
        verify(runs).appendError(eq(RUN_ID), any(), eq(true), eq(true));
        ArgumentCaptor<SyncRunErrorEvidence> terminalError =
                ArgumentCaptor.forClass(SyncRunErrorEvidence.class);
        verify(runs).appendError(eq(RUN_ID), terminalError.capture());
        assertThat(terminalError.getValue().stage()).isEqualTo(SyncRunStage.LISTINGS);
        assertThat(terminalError.getValue().rootCategoryId()).isEqualTo(7);
        assertThat(terminalError.getValue().pageNumber()).isEqualTo(1);
        assertThat(terminalError.getValue().auctionId()).isEqualTo(2L);
        assertThat(terminalError.getValue().errorCode()).isEqualTo("INVALID_DATA");
        ArgumentCaptor<SyncRunProgress> terminal = ArgumentCaptor.forClass(SyncRunProgress.class);
        verify(runs).finishIncomplete(eq(RUN_ID), eq(SyncRunStatus.PARTIAL), terminal.capture());
        assertThat(terminal.getValue().listingRowsObserved()).isEqualTo(2);
        assertThat(terminal.getValue().listingRowsQuarantined()).isEqualTo(1);
        assertThat(terminal.getValue().uniqueAuctionCount()).isEqualTo(2);
        assertThat(terminal.getValue().duplicateAuctionCount()).isZero();
        assertThat(terminal.getValue().errorCount()).isEqualTo(2);
        assertThat(terminal.getValue().unresolvedErrorCount()).isEqualTo(1);
    }

    @Test
    void maxErrorsCapsRetainedListingEvidenceButAllHoldbacksRemainCounted() {
        syncProperties.setMaxErrors(1);
        syncProperties.setMaxQuarantinedListings(2);
        when(client.getCategories()).thenReturn(call(taxonomy(7)));
        when(client.getAuctionsByCategory(7, 2, 1)).thenReturn(call(new AuctionListData(
                List.of(),
                2,
                List.of(rejectedSummary(1, "f"), rejectedSummary(2, "a")))));

        service.startManual(UUID.randomUUID());

        verify(runs).appendError(eq(RUN_ID), any(), eq(true), eq(true));
        verify(runs).appendError(eq(RUN_ID), any(), eq(true), eq(false));
        ArgumentCaptor<List<AuctionListingQuarantine>> listingQuarantines =
                ArgumentCaptor.forClass(List.class);
        verify(promotion).promote(
                eq(RUN_ID),
                any(),
                eq(NOW),
                eq(List.of()),
                eq(List.of()),
                listingQuarantines.capture());
        assertThat(listingQuarantines.getValue()).hasSize(2);
        ArgumentCaptor<SyncRunProgress> progress = ArgumentCaptor.forClass(SyncRunProgress.class);
        verify(runs, atLeastOnce()).updateProgress(eq(RUN_ID), progress.capture());
        SyncRunProgress terminal = progress.getAllValues().get(progress.getAllValues().size() - 1);
        assertThat(terminal.listingRowsQuarantined()).isEqualTo(2);
        assertThat(terminal.errorCount()).isEqualTo(2);
        assertThat(terminal.unresolvedErrorCount()).isZero();
        assertThat(terminal.detailsRequired()).isZero();
    }

    @Test
    void maxErrorsCapsRetainedDetailEvidenceButAllQuarantinesRemainCounted() {
        syncProperties.setMaxErrors(2);
        syncProperties.setMaxQuarantinedDetails(3);
        when(client.getCategories()).thenReturn(call(taxonomy(7)));
        when(client.getAuctionsByCategory(7, 2, 1))
                .thenReturn(call(new AuctionListData(
                        List.of(summary(1, "one"), summary(2, "two")), 3)));
        when(client.getAuctionsByCategory(7, 2, 2))
                .thenReturn(call(new AuctionListData(List.of(summary(3, "three")), 3)));
        EAukcijaClientException invalidJson =
                clientFailure(EAukcijaErrorCode.INVALID_JSON, 200, 1);
        when(client.getImmovablePropertyDetails(anyLong())).thenThrow(invalidJson);

        service.startManual(UUID.randomUUID());

        verify(runs, org.mockito.Mockito.times(2))
                .appendError(eq(RUN_ID), any(), eq(true), eq(true));
        verify(runs).appendError(eq(RUN_ID), any(), eq(true), eq(false));
        ArgumentCaptor<List<AuctionDetailQuarantine>> quarantines = ArgumentCaptor.forClass(List.class);
        verify(promotion).promote(
                eq(RUN_ID), any(), eq(NOW), eq(List.of()), quarantines.capture(), eq(List.of()));
        assertThat(quarantines.getValue()).hasSize(3);
        ArgumentCaptor<SyncRunProgress> progress = ArgumentCaptor.forClass(SyncRunProgress.class);
        verify(runs, atLeastOnce()).updateProgress(eq(RUN_ID), progress.capture());
        SyncRunProgress finalProgress = progress.getAllValues().get(progress.getAllValues().size() - 1);
        assertThat(finalProgress.errorCount()).isEqualTo(3);
        assertThat(finalProgress.unresolvedErrorCount()).isZero();
        assertThat(finalProgress.detailsQuarantined()).isEqualTo(3);
        assertThat(finalProgress.unknownPropertyKindCount()).isEqualTo(3);
    }

    @Test
    void quarantineLimitLeavesTheOverflowFailureUnresolvedAndBlocksPromotion() {
        syncProperties.setMaxQuarantinedDetails(1);
        when(client.getCategories()).thenReturn(call(taxonomy(7)));
        when(client.getAuctionsByCategory(7, 2, 1))
                .thenReturn(call(new AuctionListData(
                        List.of(summary(1, "one"), summary(2, "two")), 2)));
        EAukcijaClientException invalidData =
                clientFailure(EAukcijaErrorCode.INVALID_DATA, 200, 1);
        when(client.getImmovablePropertyDetails(anyLong())).thenThrow(invalidData);

        service.startManual(UUID.randomUUID());

        verify(promotion, never()).promote(any(), any(), any(), any(), any(), any());
        ArgumentCaptor<SyncRunProgress> terminal = ArgumentCaptor.forClass(SyncRunProgress.class);
        verify(runs).finishIncomplete(eq(RUN_ID), eq(SyncRunStatus.PARTIAL), terminal.capture());
        assertThat(terminal.getValue().detailsQuarantined()).isEqualTo(1);
        assertThat(terminal.getValue().detailsFailed()).isEqualTo(1);
        assertThat(terminal.getValue().errorCount()).isEqualTo(2);
        assertThat(terminal.getValue().unresolvedErrorCount()).isEqualTo(1);
    }

    @Test
    void authenticationDetailFailureRemainsFatalInsteadOfBeingQuarantined() {
        when(client.getCategories()).thenReturn(call(taxonomy(7)));
        when(client.getAuctionsByCategory(7, 2, 1))
                .thenReturn(call(new AuctionListData(List.of(summary(1, "one")), 1)));
        EAukcijaClientException unauthorized =
                clientFailure(EAukcijaErrorCode.HTTP_STATUS, 401, 1);
        when(client.getImmovablePropertyDetails(1)).thenThrow(unauthorized);

        service.startManual(UUID.randomUUID());

        verify(promotion, never()).promote(any(), any(), any(), any(), any(), any());
        verify(runs, never()).appendError(eq(RUN_ID), any(), eq(true), anyBoolean());
        ArgumentCaptor<SyncRunErrorEvidence> error = ArgumentCaptor.forClass(SyncRunErrorEvidence.class);
        verify(runs).appendError(eq(RUN_ID), error.capture());
        assertThat(error.getValue().httpStatus()).isEqualTo(401);
        verify(runs).finishIncomplete(eq(RUN_ID), eq(SyncRunStatus.PARTIAL), any());
    }

    @Test
    void lateTaskWhoseRunWasRecoveredDoesNotCallTheSourceOrFailTheTerminalRunAgain() {
        when(runs.isRunning(RUN_ID)).thenReturn(false);

        service.startManual(UUID.randomUUID());

        verify(client, never()).getCategories();
        verify(promotion, never()).promote(any(), any(), any(), any(), any(), any());
        verify(runs, never()).finishIncomplete(any(), any(), any());
        verify(workerLease).close();
    }

    @Test
    void youngActiveRunSkipsRecoveryAndCannotStealTheWorkerHandoffLock() {
        UUID activeRunId = UUID.fromString("00000000-0000-0000-0000-000000000099");
        SyncAlreadyRunningException active = new SyncAlreadyRunningException(activeRunId);
        when(runs.activeRunId()).thenReturn(Optional.of(activeRunId));
        when(runs.claim(any())).thenThrow(active);
        when(runs.isStale(activeRunId, syncProperties.getRunningStaleAfter())).thenReturn(false);

        assertThatThrownBy(() -> service.startManual(UUID.randomUUID())).isSameAs(active);

        verify(runs, never()).tryAcquireWorkerLock();
        verify(runs, never()).recoverOrphanedRunningRuns(any(), any(), anyInt());
    }

    @Test
    void youngSameKeyRunReplaysWithoutRecoveryOrWorkerLockCompetition() {
        when(runs.activeRunId()).thenReturn(Optional.of(RUN_ID));
        when(runs.isStale(RUN_ID, syncProperties.getRunningStaleAfter())).thenReturn(false);
        when(runs.claim(any())).thenReturn(new SyncRunClaimResult(RUN_ID, true));

        SyncRunClaimResult replay = service.startManual(UUID.randomUUID());

        assertThat(replay).isEqualTo(new SyncRunClaimResult(RUN_ID, true));
        verify(runs, never()).tryAcquireWorkerLock();
        verify(runs, never()).recoverOrphanedRunningRuns(any(), any(), anyInt());
        verify(client, never()).getCategories();
    }

    @Test
    void staleSameKeyRunIsRecoveredBeforeClaimCanReplayItAsRunning() {
        WorkerLockLease recoveryLease = mock(WorkerLockLease.class);
        when(runs.activeRunId()).thenReturn(Optional.of(RUN_ID));
        when(runs.isStale(RUN_ID, syncProperties.getRunningStaleAfter())).thenReturn(true);
        when(runs.tryAcquireWorkerLock()).thenReturn(Optional.of(recoveryLease));
        when(runs.recoverOrphanedRunningRuns(
                recoveryLease,
                syncProperties.getRunningStaleAfter(),
                syncProperties.getMaxErrors()))
                .thenReturn(List.of(RUN_ID));
        when(runs.claim(any())).thenReturn(new SyncRunClaimResult(RUN_ID, true));

        SyncRunClaimResult replay = service.startManual(UUID.randomUUID());

        assertThat(replay).isEqualTo(new SyncRunClaimResult(RUN_ID, true));
        verify(runs).recoverOrphanedRunningRuns(
                recoveryLease,
                syncProperties.getRunningStaleAfter(),
                syncProperties.getMaxErrors());
        verify(recoveryLease).close();
        verify(client, never()).getCategories();
    }

    @Test
    void startupRecoveryFailureKeepsOnlyAFixedRedactedDiagnostic() {
        String sentinel = "password=startup-recovery-secret";
        when(runs.activeRunId()).thenReturn(Optional.of(RUN_ID));
        when(runs.isStale(RUN_ID, syncProperties.getRunningStaleAfter())).thenReturn(true);
        when(runs.tryAcquireWorkerLock()).thenThrow(new IllegalStateException(sentinel));
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(SyncService.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.addAppender(events);

        try {
            assertThatCode(service::recoverStaleRunsAfterStartup).doesNotThrowAnyException();

            assertThat(events.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .containsExactly("Could not recover stale eAukcija sync runs "
                            + "code=STALE_RECOVERY_FAILED")
                    .allMatch(message -> !message.contains(sentinel));
            assertThat(events.list)
                    .allMatch(event -> event.getThrowableProxy() == null);
        } finally {
            logger.detachAppender(events);
            events.stop();
        }
    }

    @Test
    void startupRecoveryDoesNotCompeteWithAYoungClaimWorkerHandoff() {
        when(runs.activeRunId()).thenReturn(Optional.of(RUN_ID));
        when(runs.isStale(RUN_ID, syncProperties.getRunningStaleAfter())).thenReturn(false);

        service.recoverStaleRunsAfterStartup();

        verify(runs, never()).tryAcquireWorkerLock();
        verify(runs, never()).recoverOrphanedRunningRuns(any(), any(), anyInt());
    }

    private static <T> EAukcijaCallResult<T> call(T value) {
        return new EAukcijaCallResult<>(value, 0, 1);
    }

    private static EAukcijaClientException clientFailure(
            EAukcijaErrorCode code, Integer status, int attempts) {
        EAukcijaClientException failure = mock(EAukcijaClientException.class);
        when(failure.code()).thenReturn(code);
        when(failure.httpStatus()).thenReturn(status);
        when(failure.attempts()).thenReturn(attempts);
        when(failure.retries()).thenReturn(attempts - 1);
        return failure;
    }

    private static RejectedAuctionSummary rejectedSummary(long auctionId, String hashDigit) {
        return new RejectedAuctionSummary(
                auctionId,
                hashDigit.repeat(64),
                EAukcijaErrorCode.INVALID_DATA);
    }

    private static CategoryTree taxonomy(int... rootIds) {
        List<CategoryNode> roots = new ArrayList<>();
        for (int rootId : rootIds) {
            String categoryType = rootId == 8
                    ? "CommonProperties"
                    : "ImmovableProperties";
            List<CategoryNode> children = switch (rootId) {
                case 7 -> List.of(
                        new CategoryNode(
                                "Парцела", 47, "category47", List.of(), categoryType),
                        new CategoryNode(
                                "Објекат", 48, "category48", List.of(), categoryType),
                        new CategoryNode(
                                "Посебан део", 49, "category49", List.of(), categoryType));
                case 8 -> List.of(
                        new CategoryNode(
                                "Заједничка целина", 121, "category121", List.of(), categoryType),
                        new CategoryNode(
                                "Заједнички објекат", 124, "category124", List.of(), categoryType),
                        new CategoryNode(
                                "Заједничка непокретност", 135, "category135", List.of(), categoryType));
                default -> List.of();
            };
            roots.add(new CategoryNode(
                    "Root " + rootId,
                    rootId,
                    "category" + rootId,
                    children,
                    categoryType));
        }
        return new CategoryTree(List.copyOf(roots), "[]", "a".repeat(64));
    }

    private static CategoryTree taxonomyWithNewChild(int rootId, int childId) {
        CategoryTree baseline = taxonomy(rootId);
        CategoryNode root = baseline.roots().get(0);
        List<CategoryNode> children = new ArrayList<>(root.children());
        children.add(new CategoryNode(
                "Нова категорија",
                childId,
                "category" + childId,
                List.of(),
                root.categoryType()));
        return new CategoryTree(
                List.of(new CategoryNode(
                        root.title(), root.value(), root.key(), List.copyOf(children), root.categoryType())),
                "[]",
                "a".repeat(64));
    }

    private static CategoryTree taxonomyWithoutChild(int rootId, int removedChildId) {
        CategoryTree baseline = taxonomy(rootId);
        CategoryNode root = baseline.roots().get(0);
        List<CategoryNode> children = root.children().stream()
                .filter(child -> child.value() != removedChildId)
                .toList();
        return new CategoryTree(
                List.of(new CategoryNode(
                        root.title(), root.value(), root.key(), children, root.categoryType())),
                "[]",
                "a".repeat(64));
    }

    private static AuctionSummary summary(long id, String description) {
        return new AuctionSummary(
                id,
                "N" + id,
                "2026-08-24T10:00:00Z",
                "2026-08-24T11:00:00Z",
                new BigDecimal("100.00"),
                null,
                null,
                description,
                "Verified",
                true,
                "ImmovableProperties");
    }

    private static AuctionSummary commonSummary(long id, String description) {
        return new AuctionSummary(
                id,
                "N" + id,
                "2026-08-24T10:00:00Z",
                "2026-08-24T11:00:00Z",
                new BigDecimal("100.00"),
                null,
                null,
                description,
                "Verified",
                true,
                "CommonProperties");
    }

    private static AuctionDetail detail(long id) {
        return new AuctionDetail(
                id,
                "N" + id,
                "2026-08-24T10:00:00Z",
                "2026-08-24T11:00:00Z",
                "2026-08-24T09:00:00Z",
                new BigDecimal("100.00"),
                new BigDecimal("120.00"),
                null,
                null,
                new BigDecimal("5.00"),
                "short",
                "detail",
                "Verified",
                true,
                "ImmovableProperties",
                "executor",
                new Category(47, "Парцела"),
                new Place(1, "Place", "11000", "Municipality", "KO"));
    }

    private static AuctionDetail commonDetail(long id) {
        return new AuctionDetail(
                id,
                "N" + id,
                "2026-08-24T10:00:00Z",
                "2026-08-24T11:00:00Z",
                "2026-08-24T09:00:00Z",
                new BigDecimal("100.00"),
                new BigDecimal("120.00"),
                null,
                null,
                new BigDecimal("5.00"),
                "short",
                "detail",
                "Verified",
                true,
                "CommonProperties",
                "executor",
                new Category(121, "Заједничка целина"),
                null);
    }

    private static Auction existing(
            AuctionSummary summary, String fingerprint, Instant detailsFetchedAt) {
        Auction auction = new Auction();
        auction.setId(summary.id());
        auction.setAuctionNumber(summary.auctionNumber());
        auction.setStartDate(Instant.parse(summary.startDate()));
        auction.setEndDate(Instant.parse(summary.endDate()));
        auction.setDetailsFetched(true);
        auction.setDetailsFetchedAt(detailsFetchedAt);
        auction.setListingFingerprint(fingerprint);
        auction.setSourceDetailCategoryId(47);
        auction.setCategoryName("Парцела");
        auction.setSaleScope("CommonProperties".equals(summary.propertyType())
                ? SaleScope.COMMON
                : SaleScope.IMMOVABLE);
        return auction;
    }
}
