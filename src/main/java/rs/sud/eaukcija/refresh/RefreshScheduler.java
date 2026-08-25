package rs.sud.eaukcija.refresh;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

/** Daily schedule entering the exact same coordinator as the one-click action. */
@Component
@Profile("!local-h2")
public class RefreshScheduler {

    private static final Logger log = LoggerFactory.getLogger(RefreshScheduler.class);

    private final RefreshCoordinator coordinator;
    private final Clock clock;

    @Autowired
    public RefreshScheduler(RefreshCoordinator coordinator) {
        this(coordinator, Clock.systemUTC());
    }

    RefreshScheduler(RefreshCoordinator coordinator, Clock clock) {
        this.coordinator = coordinator;
        this.clock = clock;
    }

    @Scheduled(
            cron = "${eaukcija.refresh.schedule-cron:0 0 3 * * *}",
            zone = "${eaukcija.refresh.schedule-zone:Europe/Belgrade}")
    public void trigger() {
        UUID key = UUID.nameUUIDFromBytes(("eaukcija-refresh:"
                + clock.instant().truncatedTo(ChronoUnit.SECONDS))
                .getBytes(StandardCharsets.UTF_8));
        try {
            RefreshClaim claim = coordinator.startScheduled(key);
            if (claim.alreadyRunning()) {
                log.info("Scheduled refresh attached workflowId={} code=REFRESH_ALREADY_RUNNING",
                        claim.workflowId());
            }
        } catch (RefreshUnavailableException unavailable) {
            log.info("Scheduled refresh skipped code=REFRESH_UNAVAILABLE");
        } catch (RefreshSubmissionException rejected) {
            log.error("Scheduled refresh failed workflowId={} code=REFRESH_EXECUTOR_UNAVAILABLE",
                    rejected.workflowId());
        } catch (RuntimeException unexpected) {
            log.error("Scheduled refresh failed code=REFRESH_INTERNAL");
        }
    }
}
