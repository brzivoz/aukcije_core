package rs.sud.eaukcija.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import rs.sud.eaukcija.service.SyncService;
import rs.sud.eaukcija.service.SyncSubmissionException;
import rs.sud.eaukcija.service.SyncUnavailableException;
import rs.sud.eaukcija.sync.persistence.PersistedAuctionDetailQuarantine;
import rs.sud.eaukcija.sync.persistence.PersistedAuctionListingQuarantine;
import rs.sud.eaukcija.sync.persistence.PersistedSyncRunError;
import rs.sud.eaukcija.sync.persistence.SyncAlreadyRunningException;
import rs.sud.eaukcija.sync.persistence.SyncRunChildResult;
import rs.sud.eaukcija.sync.persistence.SyncRunClaimResult;
import rs.sud.eaukcija.sync.persistence.SyncRunRootResult;
import rs.sud.eaukcija.sync.persistence.SyncRunStage;
import rs.sud.eaukcija.sync.persistence.SyncRunStatus;
import rs.sud.eaukcija.sync.persistence.SyncRunView;
import rs.sud.eaukcija.sync.persistence.SyncTriggerKind;

@WebMvcTest(SyncController.class)
@ActiveProfiles("test")
class SyncControllerTest {

    private static final UUID KEY = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID SECOND_KEY = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private static final UUID RUN_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID ACTIVE_RUN_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final Instant STARTED_AT = Instant.parse("2026-08-24T08:00:00Z");
    private static final Instant HEARTBEAT_AT = Instant.parse("2026-08-24T08:03:00Z");
    private static final Instant FINISHED_AT = Instant.parse("2026-08-24T08:05:00Z");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private SyncService syncService;

    @BeforeEach
    void durableSyncIsEnabledByDefault() {
        when(syncService.isEnabled()).thenReturn(true);
    }

    @Test
    void loopbackTriggerReturnsAcceptedLocationNoStoreAndRunBody() throws Exception {
        when(syncService.startManual(KEY)).thenReturn(new SyncRunClaimResult(RUN_ID, false));
        when(syncService.findRun(RUN_ID)).thenReturn(Optional.of(runningView(RUN_ID)));

        mvc.perform(post("/api/sync/runs")
                        .with(remoteAddress("127.0.0.1"))
                        .header("Idempotency-Key", KEY))
                .andExpect(status().isAccepted())
                .andExpect(header().string(HttpHeaders.LOCATION, statusUrl(RUN_ID)))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.runId").value(RUN_ID.toString()))
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.statusUrl").value(statusUrl(RUN_ID)))
                .andExpect(jsonPath("$.replayed").value(false));

        verify(syncService).startManual(KEY);
        verify(syncService).findRun(RUN_ID);
    }

    @Test
    void replayOfActiveRunRemainsAcceptedAndReturnsTheSameRun() throws Exception {
        when(syncService.startManual(KEY)).thenReturn(new SyncRunClaimResult(RUN_ID, true));
        when(syncService.findRun(RUN_ID)).thenReturn(Optional.of(runningView(RUN_ID)));

        mvc.perform(post("/api/sync/runs")
                        .with(remoteAddress("::1"))
                        .header("Idempotency-Key", KEY))
                .andExpect(status().isAccepted())
                .andExpect(header().string(HttpHeaders.LOCATION, statusUrl(RUN_ID)))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.runId").value(RUN_ID.toString()))
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.replayed").value(true));
    }

    @Test
    void replayOfTerminalRunReturnsOk() throws Exception {
        when(syncService.startManual(KEY)).thenReturn(new SyncRunClaimResult(RUN_ID, true));
        when(syncService.findRun(RUN_ID)).thenReturn(Optional.of(terminalView(RUN_ID, SyncRunStatus.SUCCEEDED)));

        mvc.perform(post("/api/sync/runs")
                        .with(remoteAddress("127.0.0.1"))
                        .header("Idempotency-Key", KEY))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.LOCATION, statusUrl(RUN_ID)))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.runId").value(RUN_ID.toString()))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.replayed").value(true));
    }

    @Test
    void missingAndInvalidIdempotencyKeysUseTheBadRequestProblemContract() throws Exception {
        mvc.perform(post("/api/sync/runs").with(remoteAddress("127.0.0.1")))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));

        mvc.perform(post("/api/sync/runs")
                        .with(remoteAddress("127.0.0.1"))
                        .header("Idempotency-Key", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));

        verify(syncService, never()).startManual(any());
        verify(syncService, never()).findRun(any());
        verify(syncService, never()).rootResults(any());
        verify(syncService, never()).childResults(any());
        verify(syncService, never()).listingQuarantines(any());
        verify(syncService, never()).detailQuarantines(any());
        verify(syncService, never()).errors(any());
    }

    @Test
    void nonLoopbackTriggerIsForbiddenEvenWhenForwardedHeadersClaimLoopback() throws Exception {
        mvc.perform(post("/api/sync/runs")
                        .with(remoteAddress("203.0.113.9"))
                        .header("Idempotency-Key", KEY)
                        .header("X-Forwarded-For", "127.0.0.1")
                        .header("Forwarded", "for=127.0.0.1;proto=http;host=localhost"))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.code").value("SYNC_LOCAL_ONLY"));

        verify(syncService, never()).startManual(any());
    }

    @Test
    void distinctKeyOverlapReturnsConflictWithActiveRunCoordinates() throws Exception {
        when(syncService.startManual(KEY)).thenThrow(new SyncAlreadyRunningException(ACTIVE_RUN_ID));

        mvc.perform(post("/api/sync/runs")
                        .with(remoteAddress("127.0.0.1"))
                        .header("Idempotency-Key", KEY))
                .andExpect(status().isConflict())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("SYNC_ALREADY_RUNNING"))
                .andExpect(jsonPath("$.activeRunId").value(ACTIVE_RUN_ID.toString()))
                .andExpect(jsonPath("$.statusUrl").value(statusUrl(ACTIVE_RUN_ID)));
    }

    @Test
    void disabledAndRejectedSubmissionUseDistinctServiceUnavailableProblems() throws Exception {
        when(syncService.startManual(KEY))
                .thenThrow(new SyncUnavailableException("disabled for this profile"));
        when(syncService.startManual(SECOND_KEY)).thenThrow(new SyncSubmissionException(RUN_ID));

        mvc.perform(post("/api/sync/runs")
                        .with(remoteAddress("127.0.0.1"))
                        .header("Idempotency-Key", KEY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.code").value("SYNC_UNAVAILABLE"))
                .andExpect(jsonPath("$.runId").doesNotExist());

        mvc.perform(post("/api/sync/runs")
                        .with(remoteAddress("127.0.0.1"))
                        .header("Idempotency-Key", SECOND_KEY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.code").value("SYNC_EXECUTOR_UNAVAILABLE"))
                .andExpect(jsonPath("$.runId").value(RUN_ID.toString()))
                .andExpect(jsonPath("$.statusUrl").value(statusUrl(RUN_ID)));
    }

    @Test
    void statusFlattensDurableRunRootAndSafeErrorEvidenceWithNoStore() throws Exception {
        SyncRunView view = new SyncRunView(
                RUN_ID,
                SyncTriggerKind.MANUAL,
                SyncRunStatus.PARTIAL,
                SyncRunStage.DETAILS,
                STARTED_AT,
                HEARTBEAT_AT,
                FINISHED_AT,
                List.of(7, 8),
                3_000,
                "a".repeat(64),
                Instant.parse("2026-08-24T08:00:30Z"),
                2,
                2,
                7,
                1,
                7,
                0,
                1,
                5,
                5,
                3,
                1,
                1,
                2,
                2,
                1);
        SyncRunRootResult root = new SyncRunRootResult(7, 7, 7, 7, 0, 1, 1, true, true);
        SyncRunChildResult child = new SyncRunChildResult(
                7, 47, 2, 3, 2, 1, 1, 1, true, true, true);
        PersistedSyncRunError error = new PersistedSyncRunError(
                1,
                Instant.parse("2026-08-24T08:04:00Z"),
                SyncRunStage.DETAILS,
                7,
                null,
                1,
                180466L,
                429,
                "RATE_LIMITED",
                true,
                3);
        when(syncService.findRun(RUN_ID)).thenReturn(Optional.of(view));
        when(syncService.rootResults(RUN_ID)).thenReturn(List.of(root));
        when(syncService.childResults(RUN_ID)).thenReturn(List.of(child));
        when(syncService.listingQuarantines(RUN_ID)).thenReturn(List.of(
                new PersistedAuctionListingQuarantine(
                        180465L,
                        "c".repeat(64),
                        "INVALID_DATA",
                        7,
                        47,
                        1,
                        Instant.parse("2026-08-24T08:03:00Z"))));
        when(syncService.detailQuarantines(RUN_ID)).thenReturn(List.of(
                new PersistedAuctionDetailQuarantine(
                        180467L,
                        "b".repeat(64),
                        "INVALID_DATA",
                        Instant.parse("2026-08-24T08:03:30Z"))));
        when(syncService.errors(RUN_ID)).thenReturn(List.of(error));

        mvc.perform(get(statusUrl(RUN_ID)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.runId").value(RUN_ID.toString()))
                .andExpect(jsonPath("$.triggerKind").value("MANUAL"))
                .andExpect(jsonPath("$.status").value("PARTIAL"))
                .andExpect(jsonPath("$.stage").value("DETAILS"))
                .andExpect(jsonPath("$.startedAt").value(STARTED_AT.toString()))
                .andExpect(jsonPath("$.heartbeatAt").value(HEARTBEAT_AT.toString()))
                .andExpect(jsonPath("$.finishedAt").value(FINISHED_AT.toString()))
                .andExpect(jsonPath("$.configuredRoots[0]").value(7))
                .andExpect(jsonPath("$.configuredRoots[1]").value(8))
                .andExpect(jsonPath("$.pageSize").value(3000))
                .andExpect(jsonPath("$.categoryTreeSha256").value("a".repeat(64)))
                .andExpect(jsonPath("$.pagesExpected").value(2))
                .andExpect(jsonPath("$.pagesCompleted").value(2))
                .andExpect(jsonPath("$.listingRowsObserved").value(7))
                .andExpect(jsonPath("$.listingRowsQuarantined").value(1))
                .andExpect(jsonPath("$.uniqueAuctionCount").value(7))
                .andExpect(jsonPath("$.unknownPropertyKindCount").value(1))
                .andExpect(jsonPath("$.detailsRequired").value(5))
                .andExpect(jsonPath("$.detailsAttempted").value(5))
                .andExpect(jsonPath("$.detailsSucceeded").value(3))
                .andExpect(jsonPath("$.detailsQuarantined").value(1))
                .andExpect(jsonPath("$.detailsFailed").value(1))
                .andExpect(jsonPath("$.retryCount").value(2))
                .andExpect(jsonPath("$.errorCount").value(2))
                .andExpect(jsonPath("$.unresolvedErrorCount").value(1))
                .andExpect(jsonPath("$.rootResults[0].rootCategoryId").value(7))
                .andExpect(jsonPath("$.rootResults[0].sourceTotalCount").value(7))
                .andExpect(jsonPath("$.rootResults[0].complete").value(true))
                .andExpect(jsonPath("$.childResults[0].parentRootCategoryId").value(7))
                .andExpect(jsonPath("$.childResults[0].childCategoryId").value(47))
                .andExpect(jsonPath("$.childResults[0].sourceTotalCount").value(2))
                .andExpect(jsonPath("$.childResults[0].duplicateIds").value(1))
                .andExpect(jsonPath("$.childResults[0].subsetOfParentRoot").value(true))
                .andExpect(jsonPath("$.childResults[0].complete").value(true))
                .andExpect(jsonPath("$.listingQuarantines[0].auctionId").value(180465))
                .andExpect(jsonPath("$.listingQuarantines[0].sourceRowSha256")
                        .value("c".repeat(64)))
                .andExpect(jsonPath("$.listingQuarantines[0].errorCode").value("INVALID_DATA"))
                .andExpect(jsonPath("$.listingQuarantines[0].rootCategoryId").value(7))
                .andExpect(jsonPath("$.listingQuarantines[0].childCategoryId").value(47))
                .andExpect(jsonPath("$.listingQuarantines[0].pageNumber").value(1))
                .andExpect(jsonPath("$.listingQuarantines[0].occurredAt")
                        .value("2026-08-24T08:03:00Z"))
                .andExpect(jsonPath("$.detailQuarantines[0].auctionId").value(180467))
                .andExpect(jsonPath("$.detailQuarantines[0].listingFingerprint")
                        .value("b".repeat(64)))
                .andExpect(jsonPath("$.detailQuarantines[0].errorCode").value("INVALID_DATA"))
                .andExpect(jsonPath("$.detailQuarantines[0].occurredAt")
                        .value("2026-08-24T08:03:30Z"))
                .andExpect(jsonPath("$.errors[0].ordinal").value(1))
                .andExpect(jsonPath("$.errors[0].stage").value("DETAILS"))
                .andExpect(jsonPath("$.errors[0].rootCategoryId").value(7))
                .andExpect(jsonPath("$.errors[0].childCategoryId").doesNotExist())
                .andExpect(jsonPath("$.errors[0].pageNumber").value(1))
                .andExpect(jsonPath("$.errors[0].auctionId").value(180466))
                .andExpect(jsonPath("$.errors[0].httpStatus").value(429))
                .andExpect(jsonPath("$.errors[0].errorCode").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.errors[0].retryable").value(true))
                .andExpect(jsonPath("$.errors[0].attemptNumber").value(3))
                .andExpect(jsonPath("$.errors[0].resolved").value(false))
                .andExpect(jsonPath("$.errors[0].safeMessage").doesNotExist())
                .andExpect(jsonPath("$.errors[0].errorClass").doesNotExist())
                .andExpect(jsonPath("$.errors[0].sourcePayload").doesNotExist());

        verify(syncService).rootResults(RUN_ID);
        verify(syncService).childResults(RUN_ID);
        verify(syncService).listingQuarantines(RUN_ID);
        verify(syncService).detailQuarantines(RUN_ID);
        verify(syncService).errors(RUN_ID);
    }

    @Test
    void unknownRunUsesTheNotFoundProblemContractWithoutReadingChildEvidence() throws Exception {
        when(syncService.findRun(RUN_ID)).thenReturn(Optional.empty());

        mvc.perform(get(statusUrl(RUN_ID)))
                .andExpect(status().isNotFound())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("SYNC_RUN_NOT_FOUND"));

        verify(syncService, never()).rootResults(RUN_ID);
        verify(syncService, never()).childResults(RUN_ID);
        verify(syncService, never()).listingQuarantines(RUN_ID);
        verify(syncService, never()).detailQuarantines(RUN_ID);
        verify(syncService, never()).errors(RUN_ID);
    }

    @Test
    void disabledProfileStatusUsesTheUnavailableContractRatherThanNotFound() throws Exception {
        when(syncService.isEnabled()).thenReturn(false);

        mvc.perform(get(statusUrl(RUN_ID)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.code").value("SYNC_UNAVAILABLE"));

        verify(syncService, never()).findRun(any());
        verify(syncService, never()).rootResults(any());
        verify(syncService, never()).childResults(any());
        verify(syncService, never()).listingQuarantines(any());
        verify(syncService, never()).detailQuarantines(any());
        verify(syncService, never()).errors(any());
    }

    @Test
    void malformedRunIdUsesFixedRedactedProblemWithoutCallingTheLedger() throws Exception {
        mvc.perform(get("/api/sync/runs/password=secret"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("INVALID_SYNC_RUN_ID"))
                .andExpect(jsonPath("$.detail").value("The synchronization run ID must be a UUID."));

        verify(syncService, never()).startManual(any());
        verify(syncService, never()).findRun(any());
        verify(syncService, never()).rootResults(any());
        verify(syncService, never()).childResults(any());
        verify(syncService, never()).listingQuarantines(any());
        verify(syncService, never()).errors(any());
    }

    @Test
    void retainedLedgerFailureAfterClaimReturnsSafeRetryCoordinates() throws Exception {
        when(syncService.startManual(KEY)).thenReturn(new SyncRunClaimResult(RUN_ID, false));
        when(syncService.findRun(RUN_ID)).thenReturn(Optional.empty());

        mvc.perform(post("/api/sync/runs")
                        .with(remoteAddress("127.0.0.1"))
                        .header("Idempotency-Key", KEY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("SYNC_LEDGER_UNAVAILABLE"))
                .andExpect(jsonPath("$.runId").value(RUN_ID.toString()))
                .andExpect(jsonPath("$.statusUrl").value(statusUrl(RUN_ID)));
    }

    @Test
    void postClaimLedgerFailureLogsOnlySafeRetryCoordinates() throws Exception {
        String sentinel = "password=secret bearer-token personal-name thumbnail-base64";
        when(syncService.startManual(KEY)).thenReturn(new SyncRunClaimResult(RUN_ID, false));
        when(syncService.findRun(RUN_ID)).thenThrow(new IllegalStateException(sentinel));
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(SyncController.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.addAppender(events);
        try {
            mvc.perform(post("/api/sync/runs")
                            .with(remoteAddress("127.0.0.1"))
                            .header("Idempotency-Key", KEY))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                    .andExpect(jsonPath("$.code").value("SYNC_LEDGER_UNAVAILABLE"))
                    .andExpect(jsonPath("$.runId").value(RUN_ID.toString()))
                    .andExpect(jsonPath("$.statusUrl").value(statusUrl(RUN_ID)))
                    .andExpect(content().string(org.hamcrest.Matchers.not(
                            org.hamcrest.Matchers.containsString(sentinel))));

            assertThat(events.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .containsExactly("eAukcija sync ledger unavailable runId=" + RUN_ID
                            + " code=SYNC_LEDGER_UNAVAILABLE")
                    .allSatisfy(message -> assertThat(message).doesNotContain(sentinel));
            assertThat(events.list).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
        } finally {
            logger.detachAppender(events);
            events.stop();
        }
    }

    @Test
    void statusRootEvidenceFailureLogsOnlyAFixedCodeAndReturnsARedactedProblem() throws Exception {
        String sentinel = "password=secret bearer-token personal-name thumbnail-base64";
        when(syncService.findRun(RUN_ID)).thenReturn(Optional.of(runningView(RUN_ID)));
        when(syncService.rootResults(RUN_ID)).thenThrow(new IllegalStateException(sentinel));
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(SyncController.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.addAppender(events);
        try {
            mvc.perform(get(statusUrl(RUN_ID)))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                    .andExpect(jsonPath("$.code").value("SYNC_LEDGER_UNAVAILABLE"))
                    .andExpect(content().string(org.hamcrest.Matchers.not(
                            org.hamcrest.Matchers.containsString(sentinel))));

            assertThat(events.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .containsExactly("eAukcija sync status failed runId=" + RUN_ID
                            + " code=SYNC_LEDGER_UNAVAILABLE")
                    .allSatisfy(message -> assertThat(message).doesNotContain(sentinel));
            assertThat(events.list).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
            verify(syncService, never()).errors(RUN_ID);
        } finally {
            logger.detachAppender(events);
            events.stop();
        }
    }

    @Test
    void statusChildEvidenceFailureLogsOnlyAFixedCodeAndReturnsARedactedProblem() throws Exception {
        String sentinel = "password=secret bearer-token personal-name thumbnail-base64";
        when(syncService.findRun(RUN_ID)).thenReturn(Optional.of(runningView(RUN_ID)));
        when(syncService.rootResults(RUN_ID)).thenReturn(List.of());
        when(syncService.childResults(RUN_ID)).thenThrow(new IllegalStateException(sentinel));
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(SyncController.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.addAppender(events);
        try {
            mvc.perform(get(statusUrl(RUN_ID)))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                    .andExpect(jsonPath("$.code").value("SYNC_LEDGER_UNAVAILABLE"))
                    .andExpect(content().string(org.hamcrest.Matchers.not(
                            org.hamcrest.Matchers.containsString(sentinel))));

            assertThat(events.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .containsExactly("eAukcija sync status failed runId=" + RUN_ID
                            + " code=SYNC_LEDGER_UNAVAILABLE")
                    .allSatisfy(message -> assertThat(message).doesNotContain(sentinel));
            assertThat(events.list).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
            verify(syncService, never()).errors(RUN_ID);
        } finally {
            logger.detachAppender(events);
            events.stop();
        }
    }

    @Test
    void statusErrorEvidenceFailureLogsOnlyAFixedCodeAndReturnsARedactedProblem() throws Exception {
        String sentinel = "password=secret bearer-token personal-name thumbnail-base64";
        when(syncService.findRun(RUN_ID)).thenReturn(Optional.of(runningView(RUN_ID)));
        when(syncService.rootResults(RUN_ID)).thenReturn(List.of());
        when(syncService.childResults(RUN_ID)).thenReturn(List.of());
        when(syncService.listingQuarantines(RUN_ID)).thenReturn(List.of());
        when(syncService.detailQuarantines(RUN_ID)).thenReturn(List.of());
        when(syncService.errors(RUN_ID)).thenThrow(new IllegalStateException(sentinel));
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(SyncController.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.addAppender(events);
        try {
            mvc.perform(get(statusUrl(RUN_ID)))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                    .andExpect(jsonPath("$.code").value("SYNC_LEDGER_UNAVAILABLE"))
                    .andExpect(content().string(org.hamcrest.Matchers.not(
                            org.hamcrest.Matchers.containsString(sentinel))));

            assertThat(events.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .containsExactly("eAukcija sync status failed runId=" + RUN_ID
                            + " code=SYNC_LEDGER_UNAVAILABLE")
                    .allSatisfy(message -> assertThat(message).doesNotContain(sentinel));
            assertThat(events.list).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
        } finally {
            logger.detachAppender(events);
            events.stop();
        }
    }

    @Test
    void unexpectedLedgerFailureLogsOnlyAFixedCodeAndReturnsARedactedProblem() throws Exception {
        String sentinel = "password=secret bearer-token personal-name thumbnail-base64";
        when(syncService.startManual(KEY)).thenThrow(new IllegalStateException(sentinel));
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(SyncController.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.addAppender(events);
        try {
            mvc.perform(post("/api/sync/runs")
                            .with(remoteAddress("127.0.0.1"))
                            .header("Idempotency-Key", KEY))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                    .andExpect(jsonPath("$.code").value("SYNC_LEDGER_UNAVAILABLE"))
                    .andExpect(content().string(org.hamcrest.Matchers.not(
                            org.hamcrest.Matchers.containsString(sentinel))));

            assertThat(events.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .containsExactly("eAukcija sync trigger failed code=SYNC_LEDGER_UNAVAILABLE")
                    .allSatisfy(message -> assertThat(message).doesNotContain(sentinel));
            assertThat(events.list).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
        } finally {
            logger.detachAppender(events);
            events.stop();
        }
    }

    private static SyncRunView runningView(UUID runId) {
        return new SyncRunView(
                runId,
                SyncTriggerKind.MANUAL,
                SyncRunStatus.RUNNING,
                SyncRunStage.CLAIMED,
                STARTED_AT,
                STARTED_AT,
                null,
                List.of(7, 8),
                3_000,
                null,
                null,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0);
    }

    private static SyncRunView terminalView(UUID runId, SyncRunStatus status) {
        return new SyncRunView(
                runId,
                SyncTriggerKind.MANUAL,
                status,
                SyncRunStage.COMPLETED,
                STARTED_AT,
                FINISHED_AT,
                FINISHED_AT,
                List.of(7, 8),
                3_000,
                "a".repeat(64),
                STARTED_AT,
                2,
                2,
                7,
                7,
                0,
                1,
                4,
                4,
                4,
                0,
                0,
                0,
                0);
    }

    private static RequestPostProcessor remoteAddress(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    private static String statusUrl(UUID runId) {
        return "/api/sync/runs/" + runId;
    }
}
