package rs.sud.eaukcija.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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

@WebMvcTest({OperatorStatusController.class, OperatorStatusPageController.class})
@ActiveProfiles("test")
class OperatorStatusControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PipelineStatusService service;

    @Test
    void loopbackApiPublishesNoStoreRetainedMetricsAndCorrelationId() throws Exception {
        when(service.status()).thenReturn(statusPayload());

        mvc.perform(get("/api/operator/status")
                        .with(remoteAddress("127.0.0.1"))
                        .header(CorrelationIdFilter.HEADER, "ops-check-30"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(CorrelationIdFilter.HEADER, "ops-check-30"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.state").value("SERVING_LAST_GOOD_DATA"))
                .andExpect(jsonPath("$.ready").value(true))
                .andExpect(jsonPath("$.sync.lastAttempt.status").value("PARTIAL"))
                .andExpect(jsonPath("$.sync.lastSuccessful.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.warnings[0]").value("SYNC_LAST_ATTEMPT_PARTIAL"));
    }

    @Test
    void pageIsLoopbackOnlyAndNoStore() throws Exception {
        mvc.perform(get("/operator/status").with(remoteAddress("::1")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(view().name("operator-status"));

        mvc.perform(get("/operator/status")
                        .with(remoteAddress("203.0.113.20"))
                        .header("X-Forwarded-For", "127.0.0.1"))
                .andExpect(status().isForbidden());
    }

    @Test
    void forwardedLoopbackClaimCannotBypassApiBoundary() throws Exception {
        mvc.perform(get("/api/operator/status")
                        .with(remoteAddress("203.0.113.20"))
                        .header("X-Forwarded-For", "127.0.0.1")
                        .header("Forwarded", "for=127.0.0.1"))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("OPERATOR_STATUS_LOCAL_ONLY"));

        verify(service, never()).status();
    }

    @Test
    void unavailableEvidenceNeverLeaksExceptionPayloadOrInvalidCorrelationInput() throws Exception {
        String secret = "raw-description password=hunter2 thumbnail=https://remote.example/private";
        when(service.status()).thenThrow(new IllegalStateException(secret));
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(OperatorStatusController.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            String body = mvc.perform(get("/api/operator/status")
                            .with(remoteAddress("127.0.0.1"))
                            .header(CorrelationIdFilter.HEADER, secret))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string(CorrelationIdFilter.HEADER,
                            org.hamcrest.Matchers.matchesPattern("[0-9a-f-]{36}")))
                    .andExpect(jsonPath("$.code").value("STATUS_EVIDENCE_UNAVAILABLE"))
                    .andReturn().getResponse().getContentAsString();

            assertThat(body).doesNotContain(secret, "hunter2", "thumbnail");
            assertThat(appender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .allSatisfy(message -> assertThat(message)
                            .doesNotContain(secret, "hunter2", "thumbnail", "password="));
        } finally {
            logger.detachAppender(appender);
        }
    }

    private static PipelineStatus statusPayload() {
        PipelineStatus.RunMetric successful = new PipelineStatus.RunMetric(
                java.util.UUID.fromString("11111111-1111-4111-8111-111111111111"),
                "SCHEDULED", "SUCCEEDED", "COMPLETED",
                Instant.parse("2026-08-25T08:00:00Z"), Instant.parse("2026-08-25T08:05:00Z"),
                300_000L, 589, 2L, 590, 0, 1, 10, 0, 2, 0, 0,
                new PipelineStatus.SnapshotChanges(2, 3, 584), Map.of());
        PipelineStatus.RunMetric partial = new PipelineStatus.RunMetric(
                java.util.UUID.fromString("22222222-2222-4222-8222-222222222222"),
                "SCHEDULED", "PARTIAL", "LISTINGS",
                Instant.parse("2026-08-25T10:00:00Z"), Instant.parse("2026-08-25T10:01:00Z"),
                60_000L, 200, -389L, 200, 0, 0, 0, 0, 3, 1, 1,
                null, Map.of("TIMEOUT", 1L));
        return new PipelineStatus(
                Instant.parse("2026-08-25T12:00:00Z"),
                "SERVING_LAST_GOOD_DATA", true, true, List.of(),
                List.of("SYNC_LAST_ATTEMPT_PARTIAL"),
                List.of(),
                new PipelineStatus.Policy(93_600, 100, 7_200, false),
                new PipelineStatus.Database(true, "15", "15", true),
                new PipelineStatus.Sync(partial, successful, 14_100L, false, "OUTAGE"),
                new PipelineStatus.Enrichment(
                        null, null, 0, null, null, false, Map.of(), null,
                        Map.of(), Map.of(), Map.of(), Map.of()),
                new PipelineStatus.Imports(null, null, null, null),
                new PipelineStatus.Artifacts(null, null,
                        new rs.sud.eaukcija.basemap.BasemapStatus(
                                true, "AVAILABLE", "serbia-v1", "serbia-v1",
                                "a".repeat(64), 42L, Instant.parse("2026-08-25T07:00:00Z"),
                                Instant.parse("2026-08-25T12:00:00Z"), null)));
    }

    private static RequestPostProcessor remoteAddress(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }
}
