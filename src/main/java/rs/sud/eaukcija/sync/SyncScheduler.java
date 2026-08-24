package rs.sud.eaukcija.sync;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import rs.sud.eaukcija.service.SyncService;
import rs.sud.eaukcija.service.SyncSubmissionException;
import rs.sud.eaukcija.service.SyncUnavailableException;
import rs.sud.eaukcija.sync.persistence.SyncAlreadyRunningException;

/** Optional schedule; disabled by the default cron marker {@code -}. */
@Component
public class SyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(SyncScheduler.class);

    private final SyncService syncService;
    private final Clock clock;

    @Autowired
    public SyncScheduler(SyncService syncService) {
        this(syncService, Clock.systemUTC());
    }

    SyncScheduler(SyncService syncService, Clock clock) {
        this.syncService = syncService;
        this.clock = clock;
    }

    @Scheduled(
            cron = "${eaukcija.sync.schedule-cron:-}",
            zone = "${eaukcija.sync.schedule-zone:UTC}")
    public void trigger() {
        String occurrence = "eaukcija-scheduled:"
                + clock.instant().truncatedTo(ChronoUnit.SECONDS);
        UUID idempotencyKey = UUID.nameUUIDFromBytes(occurrence.getBytes(StandardCharsets.UTF_8));
        try {
            syncService.startScheduled(idempotencyKey);
        } catch (SyncAlreadyRunningException overlap) {
            log.info("Scheduled eAukcija sync skipped code=SYNC_ALREADY_RUNNING activeRunId={}",
                    overlap.activeRunId());
        } catch (SyncUnavailableException unavailable) {
            log.info("Scheduled eAukcija sync skipped code=SYNC_UNAVAILABLE");
        } catch (SyncSubmissionException rejected) {
            log.error("Scheduled eAukcija sync failed code=SYNC_EXECUTOR_UNAVAILABLE runId={}",
                    rejected.runId());
        } catch (RuntimeException unexpected) {
            // Scheduled-task handlers otherwise log the complete exception and
            // nested database/source messages. Retain only the fixed safe code.
            log.error("Scheduled eAukcija sync failed code=SYNC_INTERNAL");
        }
    }
}
