package rs.sud.eaukcija.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

class PipelineReadinessHealthIndicatorTest {

    private final PipelineStatusService service = mock(PipelineStatusService.class);
    private final PipelineReadinessHealthIndicator indicator =
            new PipelineReadinessHealthIndicator(
                    service, Duration.ofSeconds(5),
                    Clock.fixed(Instant.parse("2026-08-25T12:00:00Z"), ZoneOffset.UTC));

    @Test
    void mapsFailClosedStatusToDownUsingFixedEvidenceCodes() {
        when(service.status()).thenReturn(minimal(false, List.of("SUCCESSFUL_SYNC_STALE")));

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(indicator.health().getDetails().get("failures"))
                .isEqualTo(List.of("SUCCESSFUL_SYNC_STALE"));
    }

    @Test
    void evidenceFailureIsDownWithoutExceptionText() {
        when(service.status()).thenThrow(new IllegalStateException("password=hunter2 raw payload"));

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(indicator.health().getDetails().toString())
                .contains("STATUS_EVIDENCE_UNAVAILABLE")
                .doesNotContain("hunter2", "password", "payload");
    }

    @Test
    void repeatedHealthReadsUseTheShortLivedCachedEvaluation() {
        when(service.status()).thenReturn(minimal(true, List.of()));

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);

        verify(service, times(1)).status();
    }

    private static PipelineStatus minimal(boolean ready, List<String> failures) {
        return new PipelineStatus(
                Instant.parse("2026-08-25T12:00:00Z"),
                ready ? "FRESH_AND_HEALTHY" : "SERVING_LAST_GOOD_DATA",
                ready, !ready, failures, List.of(),
                List.of(),
                new PipelineStatus.Policy(93_600, 100, 7_200, false),
                new PipelineStatus.Database(true, "15", "15", true),
                new PipelineStatus.Sync(null, null, null, !ready, "UNKNOWN"),
                new PipelineStatus.Enrichment(
                        null, null, 0, null, null, false,
                        java.util.Map.of(), null, java.util.Map.of(),
                        java.util.Map.of(), java.util.Map.of(), java.util.Map.of()),
                new PipelineStatus.Imports(null, null, null, null),
                new PipelineStatus.Artifacts(null, null, null));
    }
}
