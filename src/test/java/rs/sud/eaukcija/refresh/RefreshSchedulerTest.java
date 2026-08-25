package rs.sud.eaukcija.refresh;

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

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

class RefreshSchedulerTest {

    private static final String SENTINEL = "password=secret raw-source-payload";

    private RefreshCoordinator coordinator;
    private RefreshScheduler scheduler;

    @BeforeEach
    void setUp() {
        coordinator = mock(RefreshCoordinator.class);
        scheduler = new RefreshScheduler(
                coordinator,
                Clock.fixed(Instant.parse("2026-08-25T01:00:00.789Z"), ZoneOffset.UTC));
    }

    @Test
    void derivesOneDeterministicKeyAndAlwaysEntersTheSharedCoordinator() {
        when(coordinator.startScheduled(any()))
                .thenReturn(new RefreshClaim(
                        java.util.UUID.fromString("40000000-0000-4000-8000-000000000040"),
                        false, false));

        scheduler.trigger();
        scheduler.trigger();

        ArgumentCaptor<java.util.UUID> keys = ArgumentCaptor.forClass(java.util.UUID.class);
        verify(coordinator, times(2)).startScheduled(keys.capture());
        assertThat(keys.getAllValues()).hasSize(2).containsOnly(keys.getValue());
    }

    @Test
    void overlapAndFailureLogsContainOnlySafeCodesAndWorkflowIds() {
        java.util.UUID workflowId =
                java.util.UUID.fromString("40000000-0000-4000-8000-000000000040");
        when(coordinator.startScheduled(any()))
                .thenReturn(new RefreshClaim(workflowId, true, false));
        assertSafeLog(captureLogs(scheduler::trigger),
                "Scheduled refresh attached workflowId=" + workflowId
                        + " code=REFRESH_ALREADY_RUNNING");

        org.mockito.Mockito.reset(coordinator);
        when(coordinator.startScheduled(any()))
                .thenThrow(new IllegalStateException(SENTINEL));
        assertSafeLog(captureLogs(scheduler::trigger),
                "Scheduled refresh failed code=REFRESH_INTERNAL");
    }

    private static List<ILoggingEvent> captureLogs(Runnable invocation) {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(RefreshScheduler.class);
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
