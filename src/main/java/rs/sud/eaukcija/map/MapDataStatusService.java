package rs.sud.eaukcija.map;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Converts retained successful population evidence into explicit UI freshness state. */
@Service
@Profile("!local-h2")
public class MapDataStatusService {

    static final String NO_SUCCESSFUL_SYNC = "NO_SUCCESSFUL_MAP_SYNC";
    static final String STALE_DATA = "MAP_DATA_STALE";

    private final MapDataStatusRepository repository;
    private final Duration staleAfter;
    private final Clock clock;

    @Autowired
    public MapDataStatusService(
            MapDataStatusRepository repository,
            @Value("${map.data.stale-after:PT24H}") Duration staleAfter) {
        this(repository, staleAfter, Clock.systemUTC());
    }

    MapDataStatusService(
            MapDataStatusRepository repository,
            Duration staleAfter,
            Clock clock) {
        if (staleAfter.isNegative() || staleAfter.isZero()) {
            throw new IllegalArgumentException("map.data.stale-after must be positive");
        }
        this.repository = repository;
        this.staleAfter = staleAfter;
        this.clock = clock;
    }

    public MapDataStatus status() {
        return repository.findLatest()
                .map(this::available)
                .orElseGet(() -> new MapDataStatus(
                        false,
                        "UNAVAILABLE",
                        null,
                        null,
                        true,
                        0,
                        0,
                        NO_SUCCESSFUL_SYNC));
    }

    private MapDataStatus available(MapDataStatusRepository.LatestRun run) {
        Instant staleBoundary = clock.instant().minus(staleAfter);
        boolean stale = run.finishedAt().isBefore(staleBoundary);
        long mapped = Math.max(0, run.populationCount() - run.noneCount());
        return new MapDataStatus(
                true,
                "AVAILABLE",
                version(run),
                run.finishedAt(),
                stale,
                run.populationCount(),
                mapped,
                stale ? STALE_DATA : null);
    }

    private static String version(MapDataStatusRepository.LatestRun run) {
        return safeVersion(run.resolverVersion())
                + "/" + safeVersion(run.extractVersion())
                + "/" + run.extractSourceSha256().substring(0, 12);
    }

    private static String safeVersion(String value) {
        StringBuilder safe = new StringBuilder(Math.min(value.length(), 64));
        for (int offset = 0; offset < value.length() && safe.length() < 64;) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (!Character.isISOControl(codePoint)
                    && Character.getType(codePoint) != Character.FORMAT) {
                safe.appendCodePoint(codePoint);
            }
        }
        return safe.toString().trim();
    }
}
