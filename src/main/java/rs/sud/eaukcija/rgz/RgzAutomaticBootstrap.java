package rs.sud.eaukcija.rgz;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import rs.sud.eaukcija.enrichment.EnrichmentRunRepository;
import rs.sud.eaukcija.enrichment.EnrichmentRunStatus;
import rs.sud.eaukcija.enrichment.EnrichmentService;
import rs.sud.eaukcija.refresh.RefreshRepository;
import rs.sud.eaukcija.sync.persistence.SyncRunRepository;

/** Local POC auto-activation and bounded background refinement of already-retained auctions. */
@Component
@Profile("!local-h2")
public class RgzAutomaticBootstrap {
    private static final Logger log = LoggerFactory.getLogger(RgzAutomaticBootstrap.class);
    private final RgzParcelProperties properties;
    private final RgzParcelClient client;
    private final JdbcTemplate jdbc;
    private final EnrichmentService enrichment;
    private final EnrichmentRunRepository runs;
    private final SyncRunRepository syncRuns;
    private final RefreshRepository refreshes;
    private Instant nextAttempt = Instant.EPOCH;
    private UUID warmupRun;

    public RgzAutomaticBootstrap(RgzParcelProperties properties, RgzParcelClient client, JdbcTemplate jdbc,
            EnrichmentService enrichment, EnrichmentRunRepository runs,
            SyncRunRepository syncRuns, RefreshRepository refreshes) {
        this.properties = properties;
        this.client = client;
        this.jdbc = jdbc;
        this.enrichment = enrichment;
        this.runs = runs;
        this.syncRuns = syncRuns;
        this.refreshes = refreshes;
    }

    @Scheduled(initialDelayString = "${rgz.auto-start-delay:PT2S}",
            fixedDelayString = "${rgz.auto-poll-interval:PT30S}")
    public synchronized void tick() {
        if (!properties.isEnabled() || properties.killSwitchEngaged()
                || Instant.now().isBefore(nextAttempt)) return;
        try {
            if (!properties.sourceContractReady() && properties.isAutoConfigure()) {
                if (runs.activeRunId().isPresent() || syncRuns.activeRunId().isPresent()
                        || refreshes.findActive().isPresent()) return;
                // The shared session lease serializes metadata bootstrap across
                // instances, but there is no transaction across network I/O.
                var lease = syncRuns.tryAcquireWorkerLock();
                if (lease.isEmpty()) return;
                try (var ignored = lease.orElseThrow()) {
                    configure();
                }
            }
            if (properties.networkAllowed() && properties.isWarmupEnabled()) warmup();
        } catch (Exception failure) {
            // Never log endpoint response XML, exceptions, paths or credentials.
            properties.activationFailed("SOURCE_CONTRACT_UNAVAILABLE");
            properties.warmupState("RETRY_DELAY");
            nextAttempt = Instant.now().plus(properties.getAutoRetryDelay());
            log.warn("RGZ automatic bootstrap deferred code=RGZ_AUTO_RETRY_DELAY");
        }
    }

    /** Foreground refresh waits for the current batch, then owns the shared worker.
     * The monitor also serializes it with a metadata bootstrap that is already in flight. */
    public synchronized boolean backgroundWorkInProgress() {
        return properties.isEnabled() && (properties.isAutoConfigure() || properties.isWarmupEnabled())
                && (runs.activeRunId().isPresent() || enrichment.workerOccupied());
    }

    private void configure() throws Exception {
        RgzSourceContract cached = retainedContract();
        if (cached != null) {
            properties.installContract(cached);
            return;
        }
        var capabilities = client.fetchMetadata(true);
        if (capabilities.failureCode() != null) throw new IllegalStateException("metadata unavailable");
        RgzSourceContractVerifier.verifyCapabilities(capabilities.body(), properties.getFeatureType());
        var schema = client.fetchMetadata(false);
        if (schema.failureCode() != null) throw new IllegalStateException("metadata unavailable");
        RgzSourceContract contract = RgzSourceContractVerifier.verifySchema(properties,
                capabilities.sha256(), schema.body(), schema.sha256());
        jdbc.update("""
                INSERT INTO rgz_observed_source_contracts (
                    source_key, feature_type, dataset_version, dataset_version_policy,
                    capabilities_sha256, schema_sha256, wfs_version, source_crs, decision_version, observed_at
                ) VALUES (?, ?, ?, ?, ?, ?, '2.0.0', 'EPSG:25834', ?, ?)
                ON CONFLICT (source_key) DO NOTHING
                """, contract.sourceKey(), properties.getFeatureType(), properties.getDatasetVersion(),
                properties.datasetVersionPolicy(), contract.capabilitiesSha256(), contract.schemaSha256(),
                RgzParcelResolutionService.DECISION_VERSION, OffsetDateTime.ofInstant(contract.observedAt(), ZoneOffset.UTC));
        properties.installContract(retainedContract());
        log.info("RGZ source contract ready policy={} code=RGZ_AUTO_CONFIGURED", properties.datasetVersionPolicy());
    }

    private RgzSourceContract retainedContract() {
        var rows = jdbc.query("""
                SELECT source_key, capabilities_sha256, schema_sha256, observed_at
                  FROM rgz_observed_source_contracts WHERE source_key = ?
                """, (row, index) -> new RgzSourceContract(row.getString("source_key").trim(),
                row.getString("capabilities_sha256").trim(), row.getString("schema_sha256").trim(),
                row.getObject("observed_at", OffsetDateTime.class).toInstant()), properties.sourceContractKey());
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void warmup() {
        if (!enrichment.isEnabled() || runs.isPaused()) {
            properties.warmupState("PAUSED");
            return;
        }
        if (warmupRun != null) {
            var run = enrichment.findRun(warmupRun).orElseThrow();
            if (run.status() == EnrichmentRunStatus.RUNNING) return;
            boolean noProgressDuringOutage = Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM location_resolution_attempts
                        WHERE enrichment_run_id = ? AND resolver = 'RGZ_WFS_PARCEL'
                          AND resolution_status = 'ERROR'
                          AND confidence_reason NOT IN ('RUN_REQUEST_CEILING_REACHED', 'LOGICAL_LOOKUP_ALREADY_CLAIMED'))
                       AND NOT EXISTS (SELECT 1 FROM location_resolution_attempts
                        WHERE enrichment_run_id = ? AND resolver = 'RGZ_WFS_PARCEL' AND resolution_status = 'RESOLVED')
                    """, Boolean.class, warmupRun, warmupRun));
            warmupRun = null;
            if (noProgressDuringOutage || run.status() == EnrichmentRunStatus.FAILED) {
                nextAttempt = Instant.now().plus(properties.getAutoRetryDelay());
                properties.warmupState("RETRY_DELAY");
                return;
            }
        }
        if (runs.activeRunId().isPresent() || syncRuns.activeRunId().isPresent() || refreshes.findActive().isPresent()) {
            properties.warmupState("WAITING_FOR_WORKER");
            return;
        }
        var backlog = enrichment.status();
        if (backlog.backlogSize() == 0 && backlog.populationGapCount() == 0) {
            properties.warmupState("IDLE");
            return;
        }
        warmupRun = enrichment.startScheduledBatch(UUID.randomUUID(), properties.getWarmupBatchSize()).runId();
        properties.warmupState("RUNNING");
        log.info("RGZ background enrichment started runId={} code=RGZ_AUTO_WARMUP", warmupRun);
    }
}
