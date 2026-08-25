package rs.sud.eaukcija.map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class MapDataStatusServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-23T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final MapDataStatusRepository repository = mock(MapDataStatusRepository.class);

    @Test
    void reportsExactRetainedVersionCountsAndFreshTimestamp() {
        when(repository.findLatest()).thenReturn(Optional.of(run(
                "coarse-v1", "centroids-v2", NOW.minus(Duration.ofHours(2)), 589, 2)));

        MapDataStatus status = service(Duration.ofHours(24)).status();

        assertThat(status.available()).isTrue();
        assertThat(status.state()).isEqualTo("AVAILABLE");
        assertThat(status.dataVersion()).isEqualTo("coarse-v1/centroids-v2/aaaaaaaaaaaa");
        assertThat(status.lastSuccessfulSync()).isEqualTo(NOW.minus(Duration.ofHours(2)));
        assertThat(status.stale()).isFalse();
        assertThat(status.populationCount()).isEqualTo(589);
        assertThat(status.mappedAuctionCount()).isEqualTo(587);
        assertThat(status.warning()).isNull();
    }

    @Test
    void exposesStaleAndNeverSynchronizedStatesInsteadOfHidingThem() {
        when(repository.findLatest()).thenReturn(Optional.of(run(
                "coarse-v1", "centroids-v2", NOW.minus(Duration.ofHours(25)), 12, 3)));

        MapDataStatus stale = service(Duration.ofHours(24)).status();
        assertThat(stale.available()).isTrue();
        assertThat(stale.stale()).isTrue();
        assertThat(stale.warning()).isEqualTo(MapDataStatusService.STALE_DATA);

        when(repository.findLatest()).thenReturn(Optional.empty());
        MapDataStatus missing = service(Duration.ofHours(24)).status();
        assertThat(missing.available()).isFalse();
        assertThat(missing.state()).isEqualTo("UNAVAILABLE");
        assertThat(missing.lastSuccessfulSync()).isNull();
        assertThat(missing.stale()).isTrue();
        assertThat(missing.warning()).isEqualTo(MapDataStatusService.NO_SUCCESSFUL_SYNC);
    }

    @Test
    void reportsOneBestPublishablePrecisionPerAuctionAndDerivesTheUnmappedRemainder() {
        UUID mapRun = UUID.randomUUID();
        UUID refreshRun = UUID.randomUUID();
        when(repository.findLatest()).thenReturn(Optional.of(
                new MapDataStatusRepository.LatestRun(
                        mapRun, refreshRun, "coarse-v1", "centroids-v2", "a".repeat(64),
                        NOW.minus(Duration.ofHours(2)), 4, 1,
                        Map.of("ADDRESS", 1L, "MUNICIPALITY", 2L))));

        MapDataStatus status = service(Duration.ofHours(24)).status();

        assertThat(status.mappedAuctionCount()).isEqualTo(3);
        assertThat(status.precisionSummary())
                .containsEntry("ADDRESS", 1L)
                .containsEntry("MUNICIPALITY", 2L)
                .containsEntry("NONE", 1L);
        assertThat(status.successfulResolutionRunId()).isEqualTo(mapRun);
        assertThat(status.refreshWorkflowId()).isEqualTo(refreshRun);
    }

    @Test
    void rejectsAZeroOrNegativeFreshnessWindow() {
        assertThatThrownBy(() -> service(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be positive");
    }

    private MapDataStatusService service(Duration staleAfter) {
        return new MapDataStatusService(repository, staleAfter, CLOCK);
    }

    private static MapDataStatusRepository.LatestRun run(
            String resolver,
            String extract,
            Instant finishedAt,
            long population,
            long none) {
        return new MapDataStatusRepository.LatestRun(
                resolver, extract, "a".repeat(64), finishedAt, population, none);
    }
}
