package rs.sud.eaukcija.rgz;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import rs.sud.eaukcija.komatching.ExtractedKoMatchService;
import rs.sud.eaukcija.refresh.RefreshCoordinator;
import rs.sud.eaukcija.spatial.AuctionLocationRepository;
import rs.sud.eaukcija.spatial.LocationPrecision;
import rs.sud.eaukcija.spatial.LocationRefinementRepository;
import rs.sud.eaukcija.testsupport.PostgisTestContainer;
import rs.sud.eaukcija.testsupport.RegistryResolutionFixture;

/** Known reported context, actual parser/matcher/stages, synthetic local WFS/registry geometry. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LocationResolutionCoverageIntegrationTest {
    private static final RgzWorkflowFixture.Example HOUSE = new RgzWorkflowFixture.Example(
            181104, "ВЕЛИКА ПЛАНА I", "708585", "4411/2", "success");
    private static final RgzWorkflowFixture.Example LAND = new RgzWorkflowFixture.Example(
            181104, "ВЕЛИКА ПЛАНА I", "708585", "4411/20", "success");
    private static final RgzWorkflowFixture.Example FIELD = new RgzWorkflowFixture.Example(
            181158, "ЉУПТЕН", "701165", "1285", "native-crs-recovery");
    private static final RgzWorkflowFixture FIXTURE = fixture();
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
    @Autowired AuctionLocationRepository locations;
    @Autowired LocationRefinementRepository diagnostics;
    @Autowired ExtractedKoMatchService koMatches;
    @Autowired RgzParcelProperties properties;
    @Autowired rs.sud.eaukcija.map.MapAuctionService map;

    @BeforeEach void reset() throws Exception {
        jdbc.execute("""
                TRUNCATE refresh_runs, enrichment_runs, sync_runs, auctions, eaukcija_taxonomies,
                    location_resolution_cache_records, spatial_resolution_geometries, parcel_identities,
                    address_registry_snapshots RESTART IDENTITY CASCADE
                """);
        jdbc.update("UPDATE enrichment_control SET paused = FALSE WHERE singleton");
        FIXTURE.population = List.of(HOUSE);
        FIXTURE.additionalLookups.clear(); FIXTURE.additionalLookups.add(LAND);
        var source = new ObjectMapper().readTree(rs.sud.eaukcija.testsupport.Fixtures.read(
                "propertyreference/issue55/181104-current.json"));
        FIXTURE.descriptionOverride = source.path("description").asText();
        FIXTURE.shortDescriptionOverride = source.path("shortDescription").asText();
        FIXTURE.placeOverride = "Велика Плана";
        FIXTURE.municipalityOverride = null;
        properties.setMaxAttempts(1);
        properties.setRetryDelays(List.of());
        FIXTURE.overrideScenario = null; FIXTURE.parcelRequests.clear();
        properties.setDatasetVersion("synthetic-parcels-v1");
        properties.setInvalidResultRecheckVersion("");
        Files.deleteIfExists(FIXTURE.killSwitch);
    }
    @AfterAll static void close() throws Exception { FIXTURE.close(); }

    @Test void narrowFieldRecoversThroughNativeCrsWithoutReparsingOrRepairingRejectedGeometry() throws Exception {
        var source = new ObjectMapper().readTree(rs.sud.eaukcija.testsupport.Fixtures.read(
                "propertyreference/issue55/181158-current.json"));
        FIXTURE.population = List.of(FIELD);
        FIXTURE.descriptionOverride = source.path("description").asText();
        FIXTURE.shortDescriptionOverride = source.path("shortDescription").asText();
        FIXTURE.placeOverride = source.path("placeName").asText();
        FIXTURE.municipalityOverride = source.path("municipality").asText();
        runRefresh(); // One-request budget reproduces the retained old rejection.
        assertThat(locations.findBestByAuctionIds(List.of(181158L)).get(181158L).precision())
                .isEqualTo(LocationPrecision.CADASTRAL_MUNICIPALITY);
        assertThat(diagnostics.find(181158).summarySr().split("Адресни регистар није увезен", -1)).hasSize(2);
        var originalCache = jdbc.queryForObject("SELECT id FROM location_resolution_cache_records WHERE resolver='RGZ_WFS_PARCEL'", UUID.class);
        properties.setMaxAttempts(2);
        properties.setRetryDelays(List.of(java.time.Duration.ofMillis(1)));
        properties.setInvalidResultRecheckVersion("native-crs-recovery-v1");
        runEnrichment();
        assertThat(FIXTURE.parcelRequests).containsExactly(FIELD.filter(), FIELD.filter(), FIELD.filter());
        assertThat(locations.findBestByAuctionIds(List.of(181158L)).get(181158L).precision()).isEqualTo(LocationPrecision.PARCEL);
        assertThat(jdbc.queryForObject("SELECT resolution_status FROM location_resolution_cache_records WHERE id=?",
                String.class, originalCache)).isEqualTo("INVALID");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM location_resolution_cache_records WHERE resolver='RGZ_WFS_PARCEL'", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT bool_and(source_crs_code=25834 AND ST_SRID(source_geometry)=25834
                    AND ST_SRID(canonical_geometry)=4326 AND original_geometry_valid AND NOT make_valid_applied
                    AND ST_Equals(canonical_geometry, ST_Transform(source_geometry,4326)))
                FROM spatial_resolution_geometries WHERE source_crs_code=25834
                """, Boolean.class)).isTrue();
        var evidence = new ObjectMapper().readTree(jdbc.queryForObject("""
                SELECT candidate_evidence::text FROM location_resolution_cache_records
                WHERE resolver='RGZ_WFS_PARCEL' AND resolution_status='RESOLVED'
                """, String.class));
        assertThat(evidence.path("geometrySrid").asInt()).isEqualTo(25834);
        assertThat(evidence.path("representationAttempts").size()).isEqualTo(2);
        assertThat(evidence.path("representationAttempts").get(0).path("reason").asText()).isEqualTo("INVALID_GEOMETRY");
        assertThat(evidence.path("propertyWhitelistVersion").asText()).isEqualTo("issue-41-rgz-parcel-v2");
        assertThat(diagnostics.find(181158).references()).anySatisfy(r -> {
            assertThat(r.type()).isEqualTo("PARCEL"); assertThat(r.extractionStatus()).isEqualTo("EXTRACTED");
            assertThat(r.selectedPrecision()).isEqualTo("PARCEL");
        });
        runEnrichment();
        assertThat(FIXTURE.parcelRequests).hasSize(3); // Native success is still fetch-once cache evidence.
    }

    @Test void unlabelledCandidateCannotQueryRgzOrUseRegistryUntilSourceSuppliesParcelContext() throws Exception {
        var example = new RgzWorkflowFixture.Example(181104, HOUSE.name(), HOUSE.koCode(), "81/2", "success");
        FIXTURE.population = List.of(example);
        FIXTURE.additionalLookups.clear();
        FIXTURE.descriptionOverride = "652м2";
        FIXTURE.shortDescriptionOverride = "81/2 Велика Плана I";
        UUID snapshot = RegistryResolutionFixture.snapshot(jdbc);
        RegistryResolutionFixture.point(jdbc, snapshot, 1, HOUSE.koCode(), "Велика Плана", "Тестна", "7", "81/2", "ST1");
        runRefresh();
        assertThat(FIXTURE.parcelRequests).isEmpty();
        assertThat(locations.findBestByAuctionIds(List.of(181104L)).get(181104L).coarse()).isTrue();
        assertThat(diagnostics.find(181104).references()).anySatisfy(r -> {
            assertThat(r.parcelNumber()).isEqualTo("81/2");
            assertThat(r.extractionStatus()).isEqualTo("NEEDS_REVIEW");
            assertThat(r.selectedPrecision()).isNull();
        });
        FIXTURE.descriptionOverride = "81/2 ЊИВА ДРУГЕ КЛАСЕ";
        FIXTURE.shortDescriptionOverride = "ПАРЦЕЛА";
        runRefresh();
        assertThat(FIXTURE.parcelRequests).containsExactly(example.filter());
        assertThat(locations.findBestByAuctionIds(List.of(181104L)).get(181104L).precision()).isEqualTo(LocationPrecision.PARCEL);
        runEnrichment();
        assertThat(FIXTURE.parcelRequests).hasSize(1);
    }

    @Test void contextualParcelExtractionDoesNotOverrideContradictingOfficialKoEvidence() throws Exception {
        FIXTURE.descriptionOverride = "ПОЉОПРИВРЕДНО ЗЕМЉИШТЕ 81/2 КО ЉУПТЕН";
        FIXTURE.shortDescriptionOverride = "ПАРЦЕЛА";
        runRefresh();
        assertThat(FIXTURE.parcelRequests).isEmpty();
        assertThat(locations.findBestByAuctionIds(List.of(181104L)).get(181104L).coarse()).isTrue();
        assertThat(diagnostics.find(181104).references()).anySatisfy(r -> {
            assertThat(r.parcelNumber()).isEqualTo("81/2");
            assertThat(r.extractionStatus()).isEqualTo("EXTRACTED");
            assertThat(r.koStatus()).isNotEqualTo("MATCHED");
            assertThat(r.selectedPrecision()).isNull();
        });
    }

    @Test void reportedCaseReconcilesReviewedAliasesAndLooksUpBothExactParcels() throws Exception {
        runRefresh();
        assertThat(FIXTURE.parcelRequests).containsExactly(HOUSE.filter(), LAND.filter());
        assertThat(diagnostics.find(181104).references().stream().filter(r -> "PARCEL".equals(r.type())))
                .hasSize(2).allSatisfy(r -> {
                    assertThat(r.koStatus()).isEqualTo("MATCHED");
                    assertThat(r.selectedPrecision()).isEqualTo("PARCEL");
                });
        assertThat(locations.findBestByAuctionIds(List.of(181104L)).get(181104L).precision()).isEqualTo(LocationPrecision.PARCEL);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM property_reference_ko_match_results
                 WHERE auction_id = 181104 AND method = 'REVIEWED_ALIAS' AND reconciliation_status = 'AGREES'
                """, Long.class)).isPositive();
        int requests = FIXTURE.server.getRequestCount();
        runEnrichment();
        assertThat(FIXTURE.server.getRequestCount()).isEqualTo(requests);
    }

    @Test void missingRegistryIsActionableAndImportUpgradeFindsAddressWithoutRefetchingCachedParcels() throws Exception {
        FIXTURE.overrideScenario = "not-found";
        runRefresh();
        assertThat(diagnostics.find(181104).registryAvailable()).isFalse();
        assertThat(diagnostics.find(181104).summarySr()).contains("Адресни регистар није увезен");
        assertThat(locations.findBestByAuctionIds(List.of(181104L)).get(181104L).coarse()).isTrue();
        UUID snapshot = RegistryResolutionFixture.snapshot(jdbc);
        RegistryResolutionFixture.point(jdbc, snapshot, 1, "708585", "Велика Плана", "Булевар Ослобођења", "109", "9999", "ST1");
        runEnrichment();
        assertThat(FIXTURE.parcelRequests).hasSize(2);
        assertThat(locations.findBestByAuctionIds(List.of(181104L)).get(181104L).precision()).isEqualTo(LocationPrecision.ADDRESS);
        assertThat(diagnostics.find(181104).references()).anySatisfy(r ->
                assertThat(r.addressReason()).isEqualTo("EXACT_REGISTRY_ADDRESS"));
        assertThat(diagnostics.statistics().get("auctionPrecisionCounts")).isEqualTo(java.util.Map.of("ADDRESS", 1L));
        assertThat(diagnostics.statistics().get("processingStatusCounts")).isEqualTo(java.util.Map.of("SUCCEEDED", 1L));
        var request = new org.springframework.util.LinkedMultiValueMap<String, String>();
        request.add("bbox", "18,41,24,47"); request.add("timeScope", "all");
        var geojson = new ObjectMapper().findAndRegisterModules().valueToTree(map.findAuctions(new rs.sud.eaukcija.map.MapAuctionRequestParser().parse(request)));
        assertThat(geojson.path("features")).hasSize(1);
        assertThat(geojson.path("features").get(0).path("properties").path("precision").asText()).isEqualTo("ADDRESS");
        long attempts = countAttempts(); runEnrichment(); assertThat(countAttempts()).isEqualTo(attempts);
        // Standalone #33 invalidation also revokes registry points, retaining attempt/geometry history.
        jdbc.update("UPDATE property_references SET raw_ko='Не постоји', normalized_ko='NE POSTOJI' WHERE reference_type='ADDRESS'");
        koMatches.run();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM current_location_resolutions c JOIN property_references r ON r.id=c.property_reference_id
                 WHERE r.reference_type='ADDRESS'
                """, Long.class)).isZero();
        assertThat(countAttempts()).isEqualTo(attempts);
    }

    @Test void registryParcelPointsAreAddressesAndAmbiguousHouseNumbersAreNeverGuessed() throws Exception {
        FIXTURE.overrideScenario = "not-found";
        UUID snapshot = RegistryResolutionFixture.snapshot(jdbc);
        RegistryResolutionFixture.point(jdbc, snapshot, 1, "708585", "Велика Плана", "Булевар Ослобођења", "109", "4411/2", "ST1");
        RegistryResolutionFixture.point(jdbc, snapshot, 2, "708585", "Велика Плана", "Булевар Ослобођења", "109", "9999", "ST1");
        runRefresh();
        var report = diagnostics.find(181104);
        assertThat(report.references()).anySatisfy(r -> {
            assertThat(r.parcelNumber()).isEqualTo("4411/2");
            assertThat(r.selectedPrecision()).isEqualTo("ADDRESS");
            assertThat(r.addressReason()).isEqualTo("REGISTRY_PARCEL_ADDRESS");
        });
        assertThat(report.references()).anySatisfy(r -> {
            assertThat(r.type()).isEqualTo("ADDRESS");
            assertThat(r.selectedPrecision()).isNull();
            assertThat(r.addressReason()).isEqualTo("REGISTRY_ADDRESS_AMBIGUOUS");
        });
    }

    @Test void parseFailureExplainsThatRetainedGeometryIsNotNewInputVerification() throws Exception {
        runRefresh();
        jdbc.update("""
                UPDATE enrichment_state SET status='PERMANENT_FAILURE', last_stage='PARSE', output_sha256=NULL,
                    error_class='PARSER_INPUT_INVALID', error_message='PARSER_INPUT_INVALID' WHERE auction_id=181104
                """);
        var report = diagnostics.find(181104);
        assertThat(report.processingStatus()).isEqualTo("PERMANENT_FAILURE");
        assertThat(report.summarySr()).contains("Текст огласа није успешно обрађен", "последња доступна локација");
        assertThat(locations.findBestByAuctionIds(List.of(181104L)).get(181104L).precision()).isEqualTo(LocationPrecision.PARCEL);
    }

    @Test void unmatchedHouseNumberCanOnlyResolveToOneOfficialStreetNotAnArbitraryHouse() throws Exception {
        FIXTURE.overrideScenario = "not-found";
        UUID snapshot = RegistryResolutionFixture.snapshot(jdbc);
        RegistryResolutionFixture.point(jdbc, snapshot, 1, "708585", "Велика Плана", "Булевар Ослобођења", "110", "9999", "ST1");
        runRefresh();
        assertThat(locations.findBestByAuctionIds(List.of(181104L)).get(181104L).precision()).isEqualTo(LocationPrecision.STREET);
        assertThat(diagnostics.find(181104).summarySr()).contains("не и тачан кућни број");
    }

    private long countAttempts() { return jdbc.queryForObject("SELECT count(*) FROM location_resolution_attempts", Long.class); }
    private void runRefresh() throws Exception {
        UUID id = refresh.startScheduled(UUID.randomUUID()).workflowId();
        for (int n=0; n<600; n++) {
            var state = refresh.findState(id).orElseThrow();
            if (!"RUNNING".equals(state.status())) { assertThat(state.status()).as("%s", state.failureCode()).isEqualTo("SUCCEEDED"); return; }
            Thread.sleep(50);
        }
        throw new AssertionError("refresh timeout");
    }
    private void runEnrichment() throws Exception {
        UUID id = enrichment.startScheduled(UUID.randomUUID()).runId();
        for (int n=0; n<600; n++) {
            var state = enrichment.findRun(id).orElseThrow();
            if (state.status() != rs.sud.eaukcija.enrichment.EnrichmentRunStatus.RUNNING) {
                assertThat(state.status()).isEqualTo(rs.sud.eaukcija.enrichment.EnrichmentRunStatus.SUCCEEDED); return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("enrichment timeout");
    }
    private static RgzWorkflowFixture fixture() {
        try {
            var json = new ObjectMapper().readTree(Path.of("config/address-registry/ko-alias-overrides.json").toFile());
            List<Object> aliases = new java.util.ArrayList<>();
            json.path("koAliases").forEach(alias -> { if (HOUSE.koCode().equals(alias.path("koCode").asText())) aliases.add(alias); });
            return new RgzWorkflowFixture(List.of(HOUSE, FIELD), aliases);
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
}
