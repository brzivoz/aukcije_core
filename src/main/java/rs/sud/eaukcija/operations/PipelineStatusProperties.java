package rs.sud.eaukcija.operations;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("operations.status")
public class PipelineStatusProperties {

    private Duration syncStaleAfter = Duration.ofHours(26);
    private long backlogMaxDepth = 100;
    private Duration backlogMaxAge = Duration.ofHours(2);
    private Duration readinessCacheTtl = Duration.ofSeconds(5);

    public Duration getSyncStaleAfter() {
        return syncStaleAfter;
    }

    public void setSyncStaleAfter(Duration syncStaleAfter) {
        this.syncStaleAfter = positive(syncStaleAfter, "sync-stale-after");
    }

    public long getBacklogMaxDepth() {
        return backlogMaxDepth;
    }

    public void setBacklogMaxDepth(long backlogMaxDepth) {
        if (backlogMaxDepth < 1) {
            throw new IllegalArgumentException("backlog-max-depth must be positive");
        }
        this.backlogMaxDepth = backlogMaxDepth;
    }

    public Duration getBacklogMaxAge() {
        return backlogMaxAge;
    }

    public void setBacklogMaxAge(Duration backlogMaxAge) {
        this.backlogMaxAge = positive(backlogMaxAge, "backlog-max-age");
    }

    public Duration getReadinessCacheTtl() {
        return readinessCacheTtl;
    }

    public void setReadinessCacheTtl(Duration readinessCacheTtl) {
        this.readinessCacheTtl = positive(readinessCacheTtl, "readiness-cache-ttl");
    }

    private static Duration positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
