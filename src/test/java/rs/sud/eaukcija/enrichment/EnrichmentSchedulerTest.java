package rs.sud.eaukcija.enrichment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class EnrichmentSchedulerTest {

    private static final String SENTINEL = "password=secret raw-source-payload";
    private static final UUID EXPECTED_KEY =
            UUID.fromString("868a85a4-cbc1-3222-9eb3-62f002f567cd");
    private static final UUID RUN_ID =
            UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID ACTIVE_RUN_ID =
            UUID.fromString("22222222-2222-4222-8222-222222222222");

    private EnrichmentService service;
    private EnrichmentScheduler scheduler;

    @BeforeEach
    void setUp() {
        service = mock(EnrichmentService.class);
        scheduler = new EnrichmentScheduler(
                service,
                Clock.fixed(Instant.parse("2026-08-24T12:34:56.789Z"), ZoneOffset.UTC));
    }

    @Test
    void derivesOneDeterministicIdempotencyKeyPerScheduledOccurrence() {
        scheduler.trigger();
        scheduler.trigger();

        verify(service, times(2)).startScheduled(EXPECTED_KEY);
    }

    @Test
    void overlapAndFailuresLogOnlySafeCodesAndIdentifiers() {
        when(service.startScheduled(any()))
                .thenThrow(new EnrichmentAlreadyRunningException(ACTIVE_RUN_ID));
        assertSafeLog(captureLogs(scheduler::trigger),
                "Scheduled enrichment skipped code=ENRICHMENT_ALREADY_RUNNING activeRunId="
                        + ACTIVE_RUN_ID);

        reset(service);
        when(service.startScheduled(any()))
                .thenThrow(new EnrichmentUnavailableException(SENTINEL));
        assertSafeLog(captureLogs(scheduler::trigger),
                "Scheduled enrichment skipped code=ENRICHMENT_UNAVAILABLE");

        reset(service);
        when(service.startScheduled(any()))
                .thenThrow(new EnrichmentSubmissionException(RUN_ID));
        assertSafeLog(captureLogs(scheduler::trigger),
                "Scheduled enrichment failed code=ENRICHMENT_EXECUTOR_UNAVAILABLE runId=" + RUN_ID);

        reset(service);
        when(service.startScheduled(any())).thenThrow(new IllegalStateException(SENTINEL));
        assertSafeLog(captureLogs(scheduler::trigger),
                "Scheduled enrichment failed code=ENRICHMENT_INTERNAL");
    }

    private static List<ILoggingEvent> captureLogs(Runnable invocation) {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(EnrichmentScheduler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            invocation.run();
            return List.copyOf(appender.list);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private static void assertSafeLog(List<ILoggingEvent> events, String expected) {
        assertThat(events)
                .extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly(expected)
                .allSatisfy(message -> assertThat(message).doesNotContain(SENTINEL));
        assertThat(events).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
    }
}
