package rs.sud.eaukcija.operations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Standard readiness/health bridge; details contain fixed codes only. */
@Component("pipelineReadiness")
@Profile("!local-h2")
public class PipelineReadinessHealthIndicator implements HealthIndicator {

    private final PipelineStatusService service;
    private final Duration cacheTtl;
    private final Clock clock;
    private volatile CachedHealth cached;

    @Autowired
    public PipelineReadinessHealthIndicator(
            PipelineStatusService service,
            PipelineStatusProperties properties) {
        this(service, properties.getReadinessCacheTtl(), Clock.systemUTC());
    }

    PipelineReadinessHealthIndicator(
            PipelineStatusService service,
            Duration cacheTtl,
            Clock clock) {
        this.service = service;
        this.cacheTtl = cacheTtl;
        this.clock = clock;
    }

    @Override
    public Health health() {
        Instant now = clock.instant();
        CachedHealth current = cached;
        if (current != null && now.isBefore(current.expiresAt())) {
            return current.health();
        }
        synchronized (this) {
            current = cached;
            if (current != null && now.isBefore(current.expiresAt())) {
                return current.health();
            }
            Health evaluated = evaluate();
            cached = new CachedHealth(now.plus(cacheTtl), evaluated);
            return evaluated;
        }
    }

    private Health evaluate() {
        try {
            PipelineStatus status = service.status();
            Health.Builder builder = status.ready() ? Health.up() : Health.down();
            return builder
                    .withDetail("state", status.state())
                    .withDetail("failures", status.readinessFailures())
                    .build();
        } catch (RuntimeException unavailable) {
            return Health.down()
                    .withDetail("state", "UNAVAILABLE")
                    .withDetail("failures", java.util.List.of("STATUS_EVIDENCE_UNAVAILABLE"))
                    .build();
        }
    }

    private record CachedHealth(Instant expiresAt, Health health) {
    }
}
