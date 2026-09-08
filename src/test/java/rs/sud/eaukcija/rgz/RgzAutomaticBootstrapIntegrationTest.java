package rs.sud.eaukcija.rgz;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import rs.sud.eaukcija.enrichment.EnrichmentService;
import rs.sud.eaukcija.enrichment.EnrichmentRunRepository;
import rs.sud.eaukcija.refresh.RefreshRepository;
import rs.sud.eaukcija.service.SyncService;
import rs.sud.eaukcija.spatial.AuctionLocationRepository;
import rs.sud.eaukcija.spatial.LocationPrecision;
import rs.sud.eaukcija.sync.persistence.SyncRunRepository;
import rs.sud.eaukcija.sync.persistence.SyncRunStatus;
import rs.sud.eaukcija.testsupport.PostgisTestContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RgzAutomaticBootstrapIntegrationTest {
    static final RgzWorkflowFixture FIXTURE = new RgzWorkflowFixture();
    static final String DATABASE = PostgisTestContainer.createEmptyDatabase();
    @DynamicPropertySource static void configure(DynamicPropertyRegistry registry) {
        FIXTURE.configure(registry);
        registry.add("spring.datasource.url", () -> DATABASE);
        registry.add("spring.datasource.username", PostgisTestContainer.shared()::getUsername);
        registry.add("spring.datasource.password", PostgisTestContainer.shared()::getPassword);
        registry.add("rgz.auto-configure", () -> "true");
        registry.add("rgz.warmup-enabled", () -> "true");
        registry.add("rgz.dataset-version", () -> "");
        registry.add("rgz.capabilities-sha256", () -> "");
        registry.add("rgz.schema-sha256", () -> "");
        registry.add("rgz.auto-start-delay", () -> "PT1H");
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired RgzParcelProperties properties;
    @Autowired RgzParcelClient client;
    @Autowired EnrichmentService enrichment;
    @Autowired EnrichmentRunRepository runs;
    @Autowired SyncRunRepository syncRuns;
    @Autowired RefreshRepository refreshes;
    @Autowired SyncService source;
    @Autowired AuctionLocationRepository locations;
    @Autowired rs.sud.eaukcija.refresh.RefreshCoordinator coordinator;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("syncRunExecutor")
    org.springframework.core.task.TaskExecutor worker;
    RgzAutomaticBootstrap bootstrap;

    @BeforeEach void reset() throws Exception {
        jdbc.execute("""
                TRUNCATE rgz_observed_source_contracts, refresh_runs, enrichment_runs, sync_runs, auctions,
                    eaukcija_taxonomies, location_resolution_cache_records, spatial_resolution_geometries,
                    parcel_identities RESTART IDENTITY CASCADE
                """);
        jdbc.update("UPDATE enrichment_control SET paused = FALSE WHERE singleton");
        Files.deleteIfExists(FIXTURE.killSwitch);
        FIXTURE.metadataRequests.clear();
        FIXTURE.parcelRequests.clear();
        FIXTURE.metadataFailure = null;
        properties.setDatasetVersion("");
        properties.setEnabled(true);
        properties.setWarmupEnabled(true);
        bootstrap = freshBootstrap();
    }
    @AfterAll static void close() throws Exception { FIXTURE.close(); }

    @Test void noPinsOrParcelActionAreNeededAndRestartReusesTheDurableContractAndGeometry() throws Exception {
        ingestSourceOnly();
        assertThat(properties.sourceContractReady()).isFalse();
        bootstrap.tick();
        awaitWarmup();
        assertThat(FIXTURE.metadataRequests).containsExactly("GetCapabilities", "DescribeFeatureType");
        assertThat(FIXTURE.parcelRequests).hasSize(3);
        assertThat(properties.getDatasetVersion()).isEqualTo(RgzParcelProperties.LOCAL_CACHE_EPOCH);
        assertThat(locations.findBestByAuctionIds(List.of(21001L, 21002L, 21003L)).values())
                .hasSize(3).allSatisfy(location -> assertThat(location.precision()).isEqualTo(LocationPrecision.PARCEL));
        String contract = jdbc.queryForObject("SELECT to_jsonb(c)::text FROM rgz_observed_source_contracts c", String.class);
        assertThat(contract).contains("PRIVATE_FIRST_OBSERVATION", "capabilities_sha256", "schema_sha256", "observed_at")
                .doesNotContain("future_field", "WFS_Capabilities", "schemaLocation", "not-a-dataset-edition");
        String originalCapabilities = properties.getCapabilitiesSha256();
        int calls = FIXTURE.server.getRequestCount();
        // New runtime object with no in-memory source pins or run state.
        properties.setDatasetVersion("");
        bootstrap = freshBootstrap();
        bootstrap.tick();
        awaitWarmup();
        assertThat(properties.getCapabilitiesSha256()).isEqualTo(originalCapabilities);
        assertThat(FIXTURE.server.getRequestCount()).isEqualTo(calls);
    }

    @Test void disabledAndKilledInstancesMakeNoMetadataOrParcelRequestAndResumeAutomatically() throws Exception {
        ingestSourceOnly();
        properties.setEnabled(false);
        bootstrap.tick();
        assertThat(FIXTURE.metadataRequests).isEmpty();
        properties.setEnabled(true);
        Files.createFile(FIXTURE.killSwitch);
        bootstrap.tick();
        assertThat(FIXTURE.metadataRequests).isEmpty();
        assertThat(FIXTURE.parcelRequests).isEmpty();
        Files.delete(FIXTURE.killSwitch);
        bootstrap.tick();
        awaitWarmup();
        assertThat(FIXTURE.parcelRequests).hasSize(3);
    }

    @Test void metadataFailureFailsClosedWithBackoffInsteadOfCrashingOrInventingPins() {
        FIXTURE.metadataFailure = "<html>secret-session-sentinel</html>";
        bootstrap.tick();
        assertThat(properties.networkAllowed()).isFalse();
        assertThat(properties.status().state()).isEqualTo("SOURCE_CONTRACT_UNAVAILABLE");
        assertThat(properties.status().toString()).doesNotContain("secret-session-sentinel");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM rgz_observed_source_contracts", Long.class)).isZero();
        bootstrap.tick();
        assertThat(FIXTURE.metadataRequests).hasSize(1);
        assertThat(FIXTURE.parcelRequests).isEmpty();
    }

    @Test void pauseIsRespectedAndCompletedPopulationDoesNotGenerateEmptyEnrichmentRuns() throws Exception {
        ingestSourceOnly();
        jdbc.update("UPDATE enrichment_control SET paused = TRUE WHERE singleton");
        bootstrap.tick();
        assertThat(properties.sourceContractReady()).isTrue();
        assertThat(properties.status().warmupState()).isEqualTo("PAUSED");
        assertThat(FIXTURE.parcelRequests).isEmpty();
        jdbc.update("UPDATE enrichment_control SET paused = FALSE WHERE singleton");
        bootstrap.tick();
        awaitWarmup();
        long completed = jdbc.queryForObject("SELECT count(*) FROM enrichment_runs", Long.class);
        bootstrap.tick();
        bootstrap.tick();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM enrichment_runs", Long.class)).isEqualTo(completed);
    }

    @Test void normalRefreshWaitsForTheBackgroundWorkerInsteadOfFailingSubmission() throws Exception {
        properties.setWarmupEnabled(false);
        bootstrap.tick();
        var occupied = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        worker.execute(() -> {
            occupied.countDown();
            try { release.await(5, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        UUID workflow;
        try {
            assertThat(occupied.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            int calls = FIXTURE.server.getRequestCount();
            workflow = coordinator.startManual(UUID.randomUUID()).workflowId();
            Thread.sleep(200);
            var waiting = refreshes.find(workflow).orElseThrow();
            assertThat(waiting.status()).isEqualTo(rs.sud.eaukcija.refresh.RefreshStatus.RUNNING);
            assertThat(waiting.sourceSyncRunId()).isNull();
            assertThat(FIXTURE.server.getRequestCount()).isEqualTo(calls);
        } finally {
            release.countDown();
        }
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (System.nanoTime() < deadline) {
            var state = coordinator.findState(workflow).orElseThrow();
            if (!state.status().equals("RUNNING")) {
                assertThat(state.status()).as("foreground failure %s", state.failureCode()).isEqualTo("SUCCEEDED");
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("foreground refresh did not resume after background work");
    }

    private RgzAutomaticBootstrap freshBootstrap() {
        return new RgzAutomaticBootstrap(properties, client, jdbc, enrichment, runs, syncRuns, refreshes);
    }
    private void ingestSourceOnly() throws Exception {
        UUID run = source.startManual(UUID.randomUUID()).runId();
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (System.nanoTime() < deadline) {
            var result = source.findRun(run).orElseThrow();
            if (result.status() != SyncRunStatus.RUNNING) {
                assertThat(result.status()).isEqualTo(SyncRunStatus.SUCCEEDED);
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("fixture source timeout");
    }
    private void awaitWarmup() throws Exception {
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (System.nanoTime() < deadline) {
            bootstrap.tick();
            if (runs.activeRunId().isEmpty() && properties.status().warmupState().equals("IDLE")) return;
            Thread.sleep(25);
        }
        throw new AssertionError("fixture warmup timeout: " + properties.status());
    }
}
