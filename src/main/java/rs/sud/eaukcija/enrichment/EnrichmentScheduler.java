package rs.sud.eaukcija.enrichment;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Advanced stage-only schedule; disabled because #40 owns normal scheduling. */
@Component
public class EnrichmentScheduler {

    private static final Logger log = LoggerFactory.getLogger(EnrichmentScheduler.class);

    private final EnrichmentService service;
    private final Clock clock;

    @Autowired
    public EnrichmentScheduler(EnrichmentService service) {
        this(service, Clock.systemUTC());
    }

    EnrichmentScheduler(EnrichmentService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    @Scheduled(
            cron = "${eaukcija.enrichment.schedule-cron:-}",
            zone = "${eaukcija.enrichment.schedule-zone:Europe/Belgrade}")
    public void trigger() {
        String occurrence = "eaukcija-enrichment:"
                + clock.instant().truncatedTo(ChronoUnit.SECONDS);
        UUID key = UUID.nameUUIDFromBytes(occurrence.getBytes(StandardCharsets.UTF_8));
        try {
            service.startScheduled(key);
        } catch (EnrichmentAlreadyRunningException overlap) {
            log.info("Scheduled enrichment skipped code=ENRICHMENT_ALREADY_RUNNING activeRunId={}",
                    overlap.activeRunId());
        } catch (EnrichmentUnavailableException unavailable) {
            log.info("Scheduled enrichment skipped code=ENRICHMENT_UNAVAILABLE");
        } catch (EnrichmentWorkerBusyException busy) {
            log.info("Scheduled enrichment skipped code=ENRICHMENT_WORKER_BUSY runId={} activeSyncRunId={}",
                    busy.runId(), busy.activeSyncRunId());
        } catch (EnrichmentSubmissionException rejected) {
            log.error("Scheduled enrichment failed code=ENRICHMENT_EXECUTOR_UNAVAILABLE runId={}",
                    rejected.runId());
        } catch (RuntimeException unexpected) {
            log.error("Scheduled enrichment failed code=ENRICHMENT_INTERNAL");
        }
    }
}
