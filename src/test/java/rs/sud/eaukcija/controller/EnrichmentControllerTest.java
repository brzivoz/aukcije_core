package rs.sud.eaukcija.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import rs.sud.eaukcija.enrichment.EnrichmentAlreadyRunningException;
import rs.sud.eaukcija.enrichment.EnrichmentBacklogStatus;
import rs.sud.eaukcija.enrichment.EnrichmentRunClaim;
import rs.sud.eaukcija.enrichment.EnrichmentRunItemView;
import rs.sud.eaukcija.enrichment.EnrichmentRunStatus;
import rs.sud.eaukcija.enrichment.EnrichmentRunView;
import rs.sud.eaukcija.enrichment.EnrichmentSelector;
import rs.sud.eaukcija.enrichment.EnrichmentSelectorType;
import rs.sud.eaukcija.enrichment.EnrichmentService;
import rs.sud.eaukcija.enrichment.EnrichmentStageName;
import rs.sud.eaukcija.enrichment.EnrichmentStateStatus;
import rs.sud.eaukcija.enrichment.EnrichmentTriggerKind;
import rs.sud.eaukcija.enrichment.EnrichmentVersions;
import rs.sud.eaukcija.enrichment.EnrichmentWorkerBusyException;

@WebMvcTest(EnrichmentController.class)
@ActiveProfiles("test")
class EnrichmentControllerTest {

    private static final UUID KEY = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID OTHER_KEY = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private static final UUID RUN_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID ACTIVE_RUN_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID SOURCE_RUN_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final Instant STARTED_AT = Instant.parse("2026-08-24T12:00:00Z");
    private static final Instant FINISHED_AT = Instant.parse("2026-08-24T12:01:00Z");
    private static final EnrichmentVersions VERSIONS = new EnrichmentVersions(
            "parser-v1", "resolver-v1", "dataset-v1");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private EnrichmentService service;

    @BeforeEach
    void enabledByDefault() {
        when(service.isEnabled()).thenReturn(true);
    }

    @Test
    void loopbackTriggerReturnsItsOwnReplayAndRejectsADifferentOverlap() throws Exception {
        when(service.startManual(KEY)).thenReturn(new EnrichmentRunClaim(RUN_ID, false));
        when(service.findRun(RUN_ID)).thenReturn(Optional.of(runningView()));

        mvc.perform(post("/api/enrichment/runs")
                        .with(remoteAddress("127.0.0.1"))
                        .header("Idempotency-Key", KEY))
                .andExpect(status().isAccepted())
                .andExpect(header().string(HttpHeaders.LOCATION, runUrl(RUN_ID)))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.runId").value(RUN_ID.toString()))
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.statusUrl").value(runUrl(RUN_ID)))
                .andExpect(jsonPath("$.replayed").value(false));

        when(service.startManual(KEY)).thenReturn(new EnrichmentRunClaim(RUN_ID, true));
        mvc.perform(post("/api/enrichment/runs")
                        .with(remoteAddress("127.0.0.1"))
                        .header("Idempotency-Key", KEY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.runId").value(RUN_ID.toString()))
                .andExpect(jsonPath("$.replayed").value(true));

        when(service.startManual(OTHER_KEY))
                .thenThrow(new EnrichmentAlreadyRunningException(ACTIVE_RUN_ID));
        mvc.perform(post("/api/enrichment/runs")
                        .with(remoteAddress("::1"))
                        .header("Idempotency-Key", OTHER_KEY))
                .andExpect(status().isConflict())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("ENRICHMENT_ALREADY_RUNNING"))
                .andExpect(jsonPath("$.activeRunId").value(ACTIVE_RUN_ID.toString()))
                .andExpect(jsonPath("$.statusUrl").value(runUrl(ACTIVE_RUN_ID)));

        reset(service);
        when(service.isEnabled()).thenReturn(true);
        when(service.startManual(KEY))
                .thenThrow(new EnrichmentWorkerBusyException(RUN_ID, SOURCE_RUN_ID));
        mvc.perform(post("/api/enrichment/runs")
                        .with(remoteAddress("127.0.0.1"))
                        .header("Idempotency-Key", KEY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ENRICHMENT_WORKER_BUSY"))
                .andExpect(jsonPath("$.runId").value(RUN_ID.toString()))
                .andExpect(jsonPath("$.activeSyncRunId").value(SOURCE_RUN_ID.toString()))
                .andExpect(jsonPath("$.statusUrl").value(runUrl(RUN_ID)));
    }

    @Test
    void triggerAndControlIgnoreForwardedLoopbackClaims() throws Exception {
        mvc.perform(post("/api/enrichment/runs")
                        .with(remoteAddress("203.0.113.29"))
                        .header("Idempotency-Key", KEY)
                        .header("X-Forwarded-For", "127.0.0.1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ENRICHMENT_LOCAL_ONLY"));
        mvc.perform(post("/api/enrichment/runs")
                        .with(remoteAddress("127.0.0.1"))
                        .header("Idempotency-Key", KEY)
                        .header("Sec-Fetch-Site", "cross-site")
                        .header("Origin", "https://hostile.example"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ENRICHMENT_LOCAL_ONLY"));
        mvc.perform(post("/api/enrichment/pause")
                        .with(remoteAddress("203.0.113.29"))
                        .header("Forwarded", "for=127.0.0.1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ENRICHMENT_LOCAL_ONLY"));

        verify(service, never()).startManual(any());
        verify(service, never()).pause();
    }

    @Test
    void replayRequiresExactlyOneBoundedTypedSelector() throws Exception {
        EnrichmentSelector selector = new EnrichmentSelector(
                EnrichmentSelectorType.SOURCE_SYNC_RUN, SOURCE_RUN_ID.toString());
        when(service.startReplay(KEY, selector, 25))
                .thenReturn(new EnrichmentRunClaim(RUN_ID, false));
        when(service.findRun(RUN_ID)).thenReturn(Optional.of(runningView()));

        mvc.perform(post("/api/enrichment/replays")
                        .with(remoteAddress("127.0.0.1"))
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceSyncRunId":"%s","maxItems":25}
                                """.formatted(SOURCE_RUN_ID)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.runId").value(RUN_ID.toString()));
        verify(service).startReplay(KEY, selector, 25);

        mvc.perform(post("/api/enrichment/replays")
                        .with(remoteAddress("127.0.0.1"))
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"auctionId":29,"version":"parser-v1","maxItems":25}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REPLAY_REQUEST"));

        mvc.perform(post("/api/enrichment/replays")
                        .with(remoteAddress("127.0.0.1"))
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REPLAY_REQUEST"));

        mvc.perform(post("/api/enrichment/replays")
                        .with(remoteAddress("127.0.0.1"))
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"auctionId\":29,\"maxItems\":1001}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REPLAY_REQUEST"));
    }

    @Test
    void pauseResumeAndStatusExposeOnlyBoundedOperationalState() throws Exception {
        when(service.pause()).thenReturn(true);
        when(service.resume()).thenReturn(false);
        when(service.status()).thenReturn(new EnrichmentBacklogStatus(
                true,
                false,
                VERSIONS,
                12,
                Instant.parse("2026-08-24T11:00:00Z"),
                3,
                Map.of(
                        EnrichmentStateStatus.PENDING, 7L,
                        EnrichmentStateStatus.SUCCEEDED, 600L,
                        EnrichmentStateStatus.RETRYABLE_FAILURE, 5L),
                ACTIVE_RUN_ID));

        mvc.perform(post("/api/enrichment/pause").with(remoteAddress("127.0.0.1")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.paused").value(true));
        mvc.perform(post("/api/enrichment/resume").with(remoteAddress("::1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paused").value(false));
        mvc.perform(get("/api/enrichment/status"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.paused").value(false))
                .andExpect(jsonPath("$.backlogSize").value(12))
                .andExpect(jsonPath("$.oldestPendingSince").value("2026-08-24T11:00:00Z"))
                .andExpect(jsonPath("$.populationGapCount").value(3))
                .andExpect(jsonPath("$.statusDistribution.PENDING").value(7))
                .andExpect(jsonPath("$.statusDistribution.SUCCEEDED").value(600))
                .andExpect(jsonPath("$.activeVersions.parserVersion").value("parser-v1"))
                .andExpect(jsonPath("$.activeRunId").value(ACTIVE_RUN_ID.toString()))
                .andExpect(jsonPath("$.canonicalInput").doesNotExist())
                .andExpect(jsonPath("$.rawPayload").doesNotExist());
    }

    @Test
    void retainedRunContainsOnlyHashesCountersAndRedactedFailureCodes() throws Exception {
        EnrichmentRunView run = new EnrichmentRunView(
                RUN_ID,
                EnrichmentTriggerKind.MANUAL,
                EnrichmentRunStatus.PARTIAL,
                STARTED_AT,
                FINISHED_AT,
                FINISHED_AT,
                VERSIONS,
                EnrichmentSelector.none(),
                1_000,
                2,
                2,
                1,
                0,
                0,
                0,
                1,
                0);
        EnrichmentRunItemView item = new EnrichmentRunItemView(
                2,
                29L,
                "a".repeat(64),
                1,
                EnrichmentStateStatus.PERMANENT_FAILURE,
                EnrichmentStageName.KO_MATCHING,
                STARTED_AT,
                FINISHED_AT,
                null,
                "PERMANENT_STAGE_FAILURE",
                "KO_MATCH_ARTIFACT_INVALID");
        when(service.findRun(RUN_ID)).thenReturn(Optional.of(run));
        when(service.items(RUN_ID)).thenReturn(List.of(item));

        mvc.perform(get(runUrl(RUN_ID)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.run.runId").value(RUN_ID.toString()))
                .andExpect(jsonPath("$.run.candidateCount").value(2))
                .andExpect(jsonPath("$.run.succeededCount").value(1))
                .andExpect(jsonPath("$.items[0].auctionId").value(29))
                .andExpect(jsonPath("$.items[0].attemptNumber").value(1))
                .andExpect(jsonPath("$.items[0].lastStage").value("KO_MATCHING"))
                .andExpect(jsonPath("$.items[0].errorClass").value("PERMANENT_STAGE_FAILURE"))
                .andExpect(jsonPath("$.items[0].errorMessage").value("KO_MATCH_ARTIFACT_INVALID"))
                .andExpect(jsonPath("$.items[0].canonicalInput").doesNotExist());
    }

    @Test
    void malformedIdentifiersReturnNoStoreProblemsWithoutTouchingTheLedger() throws Exception {
        mvc.perform(post("/api/enrichment/runs")
                        .with(remoteAddress("127.0.0.1"))
                        .header("Idempotency-Key", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));
        mvc.perform(get("/api/enrichment/runs/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ENRICHMENT_RUN_ID"));

        verify(service, never()).startManual(any());
        verify(service, never()).findRun(any());
    }

    private static EnrichmentRunView runningView() {
        return new EnrichmentRunView(
                RUN_ID,
                EnrichmentTriggerKind.MANUAL,
                EnrichmentRunStatus.RUNNING,
                STARTED_AT,
                STARTED_AT,
                null,
                VERSIONS,
                EnrichmentSelector.none(),
                1_000,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0);
    }

    private static String runUrl(UUID runId) {
        return "/api/enrichment/runs/" + runId;
    }

    private static RequestPostProcessor remoteAddress(String value) {
        return request -> {
            request.setRemoteAddr(value);
            return request;
        };
    }
}
