package rs.sud.eaukcija.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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

import rs.sud.eaukcija.service.SyncService;
import rs.sud.eaukcija.service.SyncSubmissionException;
import rs.sud.eaukcija.service.SyncUnavailableException;
import rs.sud.eaukcija.sync.persistence.SyncAlreadyRunningException;

class SyncSchedulerTest {

    private static final String SENTINEL =
            "password=secret bearer-token personal-name thumbnail-base64";
    private static final UUID EXPECTED_KEY =
            UUID.fromString("013e691b-bd3f-3c94-8c64-f28605033fa0");
    private static final UUID RUN_ID =
            UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID ACTIVE_RUN_ID =
            UUID.fromString("22222222-2222-4222-8222-222222222222");

    private SyncService syncService;
    private SyncScheduler scheduler;

    @BeforeEach
    void setUp() {
        syncService = mock(SyncService.class);
        scheduler = new SyncScheduler(
                syncService,
                Clock.fixed(Instant.parse("2026-08-24T12:34:56.789Z"), ZoneOffset.UTC));
    }

    @Test
    void derivesTheSameDeterministicIdempotencyKeyForTheScheduledOccurrence() {
        scheduler.trigger();
        scheduler.trigger();

        verify(syncService, times(2)).startScheduled(EXPECTED_KEY);
    }

    @Test
    void overlapLogsOnlyTheFixedCodeAndSafeActiveRunId() {
        when(syncService.startScheduled(any()))
                .thenThrow(new SyncAlreadyRunningException(ACTIVE_RUN_ID));

        List<ILoggingEvent> events = captureLogs(scheduler::trigger);

        assertSafeLog(events,
                "Scheduled eAukcija sync skipped code=SYNC_ALREADY_RUNNING activeRunId="
                        + ACTIVE_RUN_ID);
    }

    @Test
    void unavailableFailureDoesNotLogItsSentinelMessageOrThrowable() {
        when(syncService.startScheduled(any()))
                .thenThrow(new SyncUnavailableException(SENTINEL));

        List<ILoggingEvent> events = captureLogs(scheduler::trigger);

        assertSafeLog(events, "Scheduled eAukcija sync skipped code=SYNC_UNAVAILABLE");
    }

    @Test
    void rejectedSubmissionLogsOnlyTheFixedCodeAndSafeRunId() {
        when(syncService.startScheduled(any()))
                .thenThrow(new SyncSubmissionException(RUN_ID));

        List<ILoggingEvent> events = captureLogs(scheduler::trigger);

        assertSafeLog(events,
                "Scheduled eAukcija sync failed code=SYNC_EXECUTOR_UNAVAILABLE runId=" + RUN_ID);
    }

    @Test
    void unexpectedFailureDoesNotLogItsSentinelMessageOrThrowable() {
        when(syncService.startScheduled(any()))
                .thenThrow(new IllegalStateException(SENTINEL));

        List<ILoggingEvent> events = captureLogs(scheduler::trigger);

        assertSafeLog(events, "Scheduled eAukcija sync failed code=SYNC_INTERNAL");
    }

    private static List<ILoggingEvent> captureLogs(Runnable invocation) {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(SyncScheduler.class);
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

    private static void assertSafeLog(List<ILoggingEvent> events, String expectedMessage) {
        assertThat(events)
                .extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly(expectedMessage)
                .allSatisfy(message -> assertThat(message).doesNotContain(SENTINEL));
        assertThat(events).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
    }
}
