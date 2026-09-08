package rs.sud.eaukcija.rgz;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import rs.sud.eaukcija.enrichment.EnrichmentService;
import rs.sud.eaukcija.enrichment.EnrichmentRunStatus;
import rs.sud.eaukcija.map.MapAuctionRequest;
import rs.sud.eaukcija.map.MapAuctionService;
import rs.sud.eaukcija.refresh.RefreshCoordinator;
import rs.sud.eaukcija.spatial.AuctionLocationRepository;
import rs.sud.eaukcija.spatial.BoundingBox;
import rs.sud.eaukcija.spatial.LocationPrecision;
import rs.sud.eaukcija.testsupport.PostgisTestContainer;

/** No seeded extraction, KO matches, geometry, or mocked production stages. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RgzAutomaticEnrichmentIntegrationTest {
    private static final RgzWorkflowFixture FIXTURE = new RgzWorkflowFixture();
    private static final String DATABASE = PostgisTestContainer.createEmptyDatabase();

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        FIXTURE.configure(registry);
        registry.add("spring.datasource.url", () -> DATABASE);
        registry.add("spring.datasource.username", PostgisTestContainer.shared()::getUsername);
        registry.add("spring.datasource.password", PostgisTestContainer.shared()::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired RefreshCoordinator refresh;
    @Autowired EnrichmentService enrichment;
    @Autowired RgzParcelProperties properties;
    @Autowired MapAuctionService map;
    @Autowired AuctionLocationRepository locations;
    @Autowired ObjectMapper mapper;
    @Autowired rs.sud.eaukcija.komatching.ExtractedKoMatchService koMatches;

    @BeforeEach void reset() throws Exception {
        jdbc.execute("""
                TRUNCATE refresh_runs, enrichment_runs, sync_runs, auctions, eaukcija_taxonomies,
                    location_resolution_cache_records, spatial_resolution_geometries, parcel_identities
                RESTART IDENTITY CASCADE
                """);
        jdbc.update("UPDATE enrichment_control SET paused = FALSE WHERE singleton");
        FIXTURE.population = RgzWorkflowFixture.SUCCESSES;
        FIXTURE.overrideScenario = null;
        FIXTURE.parcelRequests.clear();
        Files.deleteIfExists(FIXTURE.killSwitch);
        properties.setDatasetVersion("synthetic-parcels-v1");
        properties.setMaxLogicalLookupsPerRun(100);
    }

    @AfterAll static void close() throws Exception { FIXTURE.close(); }

    @Test void ordinaryRefreshExtractsMatchesFetchesPersistsAndExportsTierOneThenReplaysWithoutRequests() throws Exception {
        var dimitrovgrad = RgzWorkflowFixture.DIMITROVGRAD;
        FIXTURE.population = List.of(dimitrovgrad, RgzWorkflowFixture.CAJETINA, RgzWorkflowFixture.VOZDOVAC,
                new RgzWorkflowFixture.Example(21004, dimitrovgrad.name(), dimitrovgrad.koCode(), "1572", "success"));
        runRefresh();

        assertThat(FIXTURE.parcelRequests).containsExactlyInAnyOrderElementsOf(
                RgzWorkflowFixture.SUCCESSES.stream().map(RgzWorkflowFixture.Example::filter).toList());
        assertThat(count("location_resolution_cache_records WHERE resolver = 'RGZ_WFS_PARCEL'")).isEqualTo(3);
        assertThat(jdbc.queryForList("""
                SELECT GeometryType(geometry.canonical_geometry) FROM rgz_parcel_cache_keys key
                JOIN location_resolution_cache_records cache ON cache.id = key.cache_record_id
                JOIN spatial_resolution_geometries geometry ON geometry.id = cache.geometry_id
                ORDER BY cache.candidate_evidence ->> 'requestedKoCode'
                """, String.class)).containsExactly("POLYGON", "POLYGON", "MULTIPOLYGON");
        assertThat(locations.findBestByAuctionIds(List.of(21001L, 21002L, 21003L, 21004L)).values())
                .hasSize(4).allSatisfy(location -> {
                    assertThat(location.precision()).isEqualTo(LocationPrecision.PARCEL);
                    assertThat(location.coarse()).isFalse();
                });
        var exported = mapper.valueToTree(map.findAuctions(new MapAuctionRequest(
                new BoundingBox(20.48, 44.76, 20.51, 44.79), null, null, null, Instant.EPOCH, null, 100)));
        assertThat(exported.path("features")).hasSize(4);
        exported.path("features").forEach(feature -> {
            assertThat(feature.path("properties").path("precision").asText()).isEqualTo("PARCEL");
            assertThat(feature.path("geometry").path("type").asText()).isIn("Polygon", "MultiPolygon");
        });
        String persisted = jdbc.queryForObject("""
                SELECT jsonb_agg(to_jsonb(cache))::text FROM location_resolution_cache_records cache
                 WHERE resolver = 'RGZ_WFS_PARCEL'
                """, String.class);
        assertThat(persisted).contains("requestedKoCode", "requestedParcelNumber", "returnedKoCode",
                "returnedParcelNumber", "geometryType", "retrievedAt", "wfsVersion", "sourceProjection",
                "scale", "rawResponseSha256", "schemaSha256", RgzParcelResolutionService.DECISION_VERSION);
        String attempts = jdbc.queryForObject("SELECT jsonb_agg(to_jsonb(a))::text FROM location_resolution_attempts a", String.class);
        assertThat(persisted + attempts + exported).doesNotContain(RgzWorkflowFixture.PRIVATE_SENTINEL,
                "owner_name", "cookie", "credential", "session_token", "FutureField", "FutureGeometryProperty");

        int outboundRequests = FIXTURE.server.getRequestCount();
        runEnrichment();
        assertThat(FIXTURE.server.getRequestCount()).isEqualTo(outboundRequests);
        assertThat(FIXTURE.parcelRequests).hasSize(3);
        assertThat(count("rgz_parcel_cache_keys")).isEqualTo(3);
        properties.setDatasetVersion("synthetic-parcels-v2");
        runEnrichment();
        assertThat(FIXTURE.parcelRequests).hasSize(6);
        for (var example : RgzWorkflowFixture.SUCCESSES) {
            assertThat(FIXTURE.parcelRequests.stream().filter(example.filter()::equals).count()).isEqualTo(2);
        }
        assertThat(count("rgz_parcel_cache_keys")).isEqualTo(6);
        runEnrichment();
        assertThat(FIXTURE.parcelRequests).hasSize(6);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-found", "ambiguous", "identity-mismatch", "wrong-crs", "invalid-geometry",
            "schema-error", "response-error", "oversize", "http-error", "invalid-input", "structured-only"})
    void failuresContinueThroughRealFallbackAndNeverMislabelAPoint(String scenario) throws Exception {
        FIXTURE.population = List.of(RgzWorkflowFixture.DIMITROVGRAD.scenario(scenario));
        runRefresh();
        var location = locations.findBestByAuctionIds(List.of(21001L)).get(21001L);
        assertThat(location.precision()).isEqualTo(LocationPrecision.CADASTRAL_MUNICIPALITY);
        assertThat(location.coarse()).isTrue();
        assertThat(count("location_resolution_attempts WHERE location_precision = 'PARCEL'")).isZero();
        if (List.of("invalid-input", "structured-only").contains(scenario)) {
            assertThat(FIXTURE.parcelRequests).isEmpty();
            assertThat(count("property_references WHERE reference_type <> 'STRUCTURED_LOCATION' AND ko_code IS NOT NULL")).isZero();
        } else {
            assertThat(FIXTURE.parcelRequests).hasSize(1);
            String evidence = jdbc.queryForObject("""
                    SELECT candidate_evidence::text FROM location_resolution_attempts
                    WHERE resolver = 'RGZ_WFS_PARCEL' LIMIT 1
                    """, String.class);
            String reason = switch (scenario) {
                case "not-found" -> "AUTHORITATIVE_NOT_FOUND";
                case "ambiguous" -> "MULTIPLE_FEATURES";
                case "identity-mismatch" -> "IDENTITY_MISMATCH";
                case "wrong-crs" -> "INVALID_CRS";
                case "invalid-geometry" -> "INVALID_POLYGON";
                case "schema-error" -> "INVALID_FEATURES";
                case "response-error" -> "INVALID_JSON";
                case "oversize" -> "RESPONSE_TOO_LARGE";
                default -> "HTTP_503";
            };
            assertThat(evidence).contains(reason, "requestedKoCode", "713848", "requestedParcelNumber", "1572")
                    .doesNotContain(RgzWorkflowFixture.PRIVATE_SENTINEL);
            if (List.of("not-found", "ambiguous", "identity-mismatch", "invalid-geometry").contains(scenario)) {
                runEnrichment();
                assertThat(FIXTURE.parcelRequests).hasSize(1);
                assertThat(count("rgz_parcel_cache_keys")).isOne();
            }
        }
    }

    @Test void ceilingAndLiveKillSwitchDeferToFallbackWithoutFailingThePopulation() throws Exception {
        properties.setMaxLogicalLookupsPerRun(1);
        runRefresh();
        assertThat(FIXTURE.parcelRequests).hasSize(1);
        assertThat(locations.findBestByAuctionIds(List.of(21001L, 21002L, 21003L)).values())
                .extracting(location -> location.precision())
                .containsExactlyInAnyOrder(LocationPrecision.PARCEL,
                        LocationPrecision.CADASTRAL_MUNICIPALITY, LocationPrecision.CADASTRAL_MUNICIPALITY);
        Files.createFile(FIXTURE.killSwitch);
        runEnrichment();
        assertThat(FIXTURE.parcelRequests).hasSize(1);
        Files.delete(FIXTURE.killSwitch);
        runEnrichment();
        runEnrichment();
        assertThat(FIXTURE.parcelRequests).hasSize(3);
        assertThat(locations.findBestByAuctionIds(List.of(21001L, 21002L, 21003L)).values())
                .allSatisfy(location -> assertThat(location.precision()).isEqualTo(LocationPrecision.PARCEL));
    }

    @Test void standaloneKoConflictImmediatelyRestoresTheMapFallbackWithoutAnotherEnrichmentRun() throws Exception {
        FIXTURE.population = List.of(RgzWorkflowFixture.DIMITROVGRAD);
        runRefresh();
        long attemptsBefore = count("location_resolution_attempts");
        jdbc.update("""
                UPDATE property_references SET raw_ko = 'Чајетина', normalized_ko = 'CAJETINA'
                 WHERE auction_id = 21001 AND canonical_parcel_number IS NOT NULL
                """);
        koMatches.run();
        assertThat(locations.findBestByAuctionIds(List.of(21001L)).get(21001L).precision())
                .isEqualTo(LocationPrecision.CADASTRAL_MUNICIPALITY);
        var exported = map.findAuctions(new MapAuctionRequest(new BoundingBox(20.48, 44.76, 20.51, 44.79),
                null, null, null, Instant.EPOCH, null, 100));
        assertThat(exported.features()).singleElement().satisfies(feature -> {
            assertThat(feature.properties().precision()).isEqualTo("CADASTRAL_MUNICIPALITY");
            assertThat(feature.geometry().type()).isEqualTo("Point");
        });
        assertThat(count("location_resolution_attempts")).isEqualTo(attemptsBefore);
        assertThat(count("rgz_parcel_cache_keys")).isOne();
        assertThat(FIXTURE.parcelRequests).hasSize(1);
    }

    @Test void failedNewDatasetDoesNotDowngradeLastValidGeometry() throws Exception {
        FIXTURE.population = List.of(RgzWorkflowFixture.DIMITROVGRAD);
        runRefresh();
        UUID selected = locations.findBestByAuctionIds(List.of(21001L)).get(21001L).resolutionAttemptId();
        properties.setDatasetVersion("synthetic-parcels-v2");
        FIXTURE.overrideScenario = "http-error";
        runEnrichment();
        assertThat(locations.findBestByAuctionIds(List.of(21001L)).get(21001L).resolutionAttemptId()).isEqualTo(selected);
        assertThat(count("rgz_parcel_cache_keys")).isOne();
    }

    private void runRefresh() throws Exception {
        UUID id = refresh.startScheduled(UUID.randomUUID()).workflowId();
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (System.nanoTime() < deadline) {
            var state = refresh.findState(id).orElseThrow();
            if (!state.status().equals("RUNNING")) {
                assertThat(state.status()).as("refresh failure: %s", state.failureCode()).isEqualTo("SUCCEEDED");
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("refresh timed out: " + refresh.findState(id));
    }

    private void runEnrichment() throws Exception {
        UUID id = enrichment.startScheduled(UUID.randomUUID()).runId();
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (System.nanoTime() < deadline) {
            var run = enrichment.findRun(id).orElseThrow();
            if (run.status() != EnrichmentRunStatus.RUNNING) {
                assertThat(run.status()).isEqualTo(EnrichmentRunStatus.SUCCEEDED);
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("enrichment timed out");
    }

    private long count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class); }
}
