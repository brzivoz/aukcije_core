package rs.sud.eaukcija.rgz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import rs.sud.eaukcija.enrichment.EnrichmentHashing;
import rs.sud.eaukcija.enrichment.EnrichmentItemProcessor;
import rs.sud.eaukcija.enrichment.EnrichmentPipeline;
import rs.sud.eaukcija.enrichment.EnrichmentRunRepository;
import rs.sud.eaukcija.enrichment.EnrichmentRunStatus;
import rs.sud.eaukcija.enrichment.EnrichmentSelector;
import rs.sud.eaukcija.enrichment.EnrichmentStage;
import rs.sud.eaukcija.enrichment.EnrichmentStageName;
import rs.sud.eaukcija.enrichment.EnrichmentTriggerKind;
import rs.sud.eaukcija.enrichment.SelectedResolutionEnrichmentStage;
import rs.sud.eaukcija.enrichment.EnrichmentInputSnapshot;
import rs.sud.eaukcija.enrichment.EnrichmentStageResult;
import rs.sud.eaukcija.enrichment.EnrichmentWorkItem;
import rs.sud.eaukcija.enrichment.ParcelPathEnrichmentStage;
import rs.sud.eaukcija.komatching.ExtractedKoMatchService;
import rs.sud.eaukcija.komatching.KoDictionaryTestArtifact;
import rs.sud.eaukcija.propertyreference.PropertyReferenceExtractionRepository;
import rs.sud.eaukcija.propertyreference.PropertyReferenceParser;
import rs.sud.eaukcija.spatial.BoundingBox;
import rs.sud.eaukcija.spatial.LocationPrecision;
import rs.sud.eaukcija.spatial.SpatialViewportRepository;
import rs.sud.eaukcija.testsupport.PostgisTestContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RgzParcelResolutionIntegrationTest {

    static final PostgreSQLContainer<?> POSTGIS = PostgisTestContainer.shared();
    private static final String JDBC_URL = PostgisTestContainer.createEmptyDatabase();
    private static final Path DICTIONARY = dictionary();
    private static final String ORIGINAL_DATASET = "WFS-2.0.0-updateSequence-6441";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> JDBC_URL);
        registry.add("spring.datasource.username", POSTGIS::getUsername);
        registry.add("spring.datasource.password", POSTGIS::getPassword);
        registry.add("ko.structured-match.dictionary-directory", DICTIONARY::toString);
        registry.add("rgz.enabled", () -> "true");
        registry.add("rgz.dataset-version", () -> ORIGINAL_DATASET);
        registry.add("rgz.capabilities-sha256", () -> "a".repeat(64));
        registry.add("rgz.schema-sha256", () -> "b".repeat(64));
        registry.add("rgz.max-logical-lookups-per-run", () -> "100");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "2");
    }

    @MockitoBean
    private RgzParcelClient client;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PropertyReferenceParser parser;

    @Autowired
    private PropertyReferenceExtractionRepository references;

    @Autowired
    private ExtractedKoMatchService koMatches;

    @Autowired
    private ParcelPathEnrichmentStage stage;

    @Autowired
    private RgzParcelProperties rgzProperties;

    @Autowired
    private SpatialViewportRepository viewport;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private EnrichmentRunRepository runs;

    @TempDir
    Path temporaryDirectory;

    private UUID sourceRunId;

    @BeforeEach
    void setUp() {
        clean();
        reset(client);
        rgzProperties.setInvalidResultRecheckVersion("");
        rgzProperties.setEnabled(true);
        rgzProperties.setCapabilitiesSha256("a".repeat(64));
        rgzProperties.setSchemaSha256("b".repeat(64));
        rgzProperties.setKillSwitchPath(temporaryDirectory.resolve("rgz.disabled"));
        rgzProperties.setDatasetVersion(ORIGINAL_DATASET);
        rgzProperties.setMaxLogicalLookupsPerRun(100);
        sourceRunId = insertSourceRun();
    }

    @AfterEach
    void tearDown() {
        rgzProperties.setInvalidResultRecheckVersion("");
        rgzProperties.setEnabled(true);
        rgzProperties.setCapabilitiesSha256("a".repeat(64));
        rgzProperties.setSchemaSha256("b".repeat(64));
        rgzProperties.setDatasetVersion(ORIGINAL_DATASET);
        rgzProperties.setMaxLogicalLookupsPerRun(100);
        clean();
    }

    @Test
    void explicitRecheckEpochRetriesOnlyInvalidCacheOnceAndKeepsAllOldEvidence() {
        var item = seed(41_090L, "Чајетина", "Насеље А", "Општина А", "КО Чајетина; парцела број 1572");
        koMatches.run();
        when(client.fetch(anyString(), anyString(), any(BooleanSupplier.class))).thenReturn(new RgzParcelResult(
                RgzParcelResult.Status.INVALID, "INVALID_GEOMETRY", "a".repeat(64),
                null, null, null, null, null, null, 1, Map.of("physicalAttempts", 1)));
        stage.process(item.forRun(insertEnrichmentRun()));
        UUID rejected = jdbc.queryForObject("SELECT cache_record_id FROM rgz_parcel_cache_keys", UUID.class);
        stage.process(item.forRun(nextEnrichmentRun()));
        verify(client, times(1)).fetch(anyString(), anyString(), any(BooleanSupplier.class));
        rgzProperties.setInvalidResultRecheckVersion("reviewed-validator-fix-1");
        when(client.fetch(anyString(), anyString(), any(BooleanSupplier.class)))
                .thenReturn(errorResult("HTTP_503"));
        stage.process(item.forRun(nextEnrichmentRun()));
        // Failed recheck leaves the old immutable negative and pointer intact.
        assertThat(jdbc.queryForObject("SELECT cache_record_id FROM rgz_parcel_cache_keys", UUID.class)).isEqualTo(rejected);
        when(client.fetch(anyString(), anyString(), any(BooleanSupplier.class)))
                .thenReturn(success("100001", "1572", "b".repeat(64)));
        stage.process(item.forRun(nextEnrichmentRun()));
        stage.process(item.forRun(nextEnrichmentRun()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM location_resolution_cache_records", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT resolution_status FROM location_resolution_cache_records WHERE id = ?",
                String.class, rejected)).isEqualTo("INVALID");
        assertThat(jdbc.queryForObject("SELECT cache_record_id FROM rgz_parcel_cache_keys", UUID.class)).isNotEqualTo(rejected);
        // Changing recovery epochs never refetches a successful identity.
        rgzProperties.setInvalidResultRecheckVersion("reviewed-validator-fix-2");
        stage.process(item.forRun(nextEnrichmentRun()));
        verify(client, times(3)).fetch(anyString(), anyString(), any(BooleanSupplier.class));
    }

    @Test
    void duplicateFailedLookupIsDeferredAndBothReferencesRecoverFromOneNextRunFetch() {
        var first = seed(41_091L, "Чајетина", "Насеље А", "Општина А", "КО Чајетина; парцела број 1572");
        var second = seed(41_092L, "Чајетина", "Насеље А", "Општина А", "КО Чајетина; парцела број 1572");
        koMatches.run();
        when(client.fetch(anyString(), anyString(), any(BooleanSupplier.class))).thenReturn(errorResult("INVALID_CRS"));
        UUID failedRun = insertEnrichmentRun();
        stage.process(first.forRun(failedRun)); stage.process(second.forRun(failedRun));
        verify(client, times(1)).fetch(anyString(), anyString(), any(BooleanSupplier.class));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM location_resolution_attempts WHERE confidence_reason='LOGICAL_LOOKUP_ALREADY_CLAIMED'",
                Long.class)).isOne();
        when(client.fetch(anyString(), anyString(), any(BooleanSupplier.class))).thenReturn(success("100001", "1572", "c".repeat(64)));
        UUID retryRun = nextEnrichmentRun();
        stage.process(second.forRun(retryRun)); stage.process(first.forRun(retryRun));
        verify(client, times(2)).fetch(anyString(), anyString(), any(BooleanSupplier.class));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM current_location_resolutions", Long.class)).isEqualTo(2);
    }

    @Test
    void automaticStagePersistsSelectsCachesAndRefetchesOnlyForANewDatasetVersion() {
        EnrichmentWorkItem item = seed(
                41_001L, "Чајетина", "Насеље А", "Општина А",
                "КО Чајетина; парцела број 1572");
        koMatches.run();
        when(client.fetch(anyString(), anyString(), any(BooleanSupplier.class)))
                .thenReturn(success("100001", "1572", "a".repeat(64)));
        UUID runId = insertEnrichmentRun();

        EnrichmentStageResult first = stage.process(item.forRun(runId));
        EnrichmentStageResult replay = stage.process(item.forRun(runId));

        assertThat(first.disposition()).isEqualTo(EnrichmentStageResult.Disposition.RESOLVED);
        assertThat(replay).isEqualTo(first);
        verify(client, times(1)).fetch(anyString(), anyString(), any(BooleanSupplier.class));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM location_resolution_cache_records
                 WHERE resolver = 'RGZ_WFS_PARCEL'
                """, Long.class)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM location_resolution_attempts
                 WHERE resolver = 'RGZ_WFS_PARCEL'
                """, Long.class)).isOne();
        assertThat(jdbc.queryForMap("""
                SELECT attempt.location_precision,
                       attempt.upstream_ko_match_input_fingerprint,
                       GeometryType(geometry.canonical_geometry) AS geometry_type,
                       reference.ko_code, reference.parcel_identity_id,
                       cache.candidate_evidence::text AS evidence
                  FROM current_location_resolutions current_resolution
                  JOIN location_resolution_attempts attempt
                    ON attempt.id = current_resolution.resolution_attempt_id
                  JOIN property_references reference
                    ON reference.id = current_resolution.property_reference_id
                  JOIN spatial_resolution_geometries geometry ON geometry.id = attempt.geometry_id
                  JOIN location_resolution_cache_records cache ON cache.id = attempt.used_cache_record_id
                 WHERE reference.auction_id = 41001
                """))
                .containsEntry("location_precision", "PARCEL")
                .containsEntry("geometry_type", "POLYGON")
                .containsEntry("ko_code", "100001")
                .satisfies(row -> {
                    assertThat(row.get("parcel_identity_id")).isNotNull();
                    assertThat(row.get("upstream_ko_match_input_fingerprint").toString()).hasSize(64);
                    assertThat(row.get("evidence").toString())
                            .contains(RgzParcelResolutionService.DECISION_VERSION, "rawResponseSha256")
                            .doesNotContain("owner_name", "cookie", "password", "session");
                });
        assertThat(viewport.findSelectedWithin(new BoundingBox(19, 43, 21, 45), 10))
                .singleElement()
                .satisfies(location -> assertThat(location.precision()).isEqualTo(LocationPrecision.PARCEL));

        rgzProperties.setDatasetVersion("WFS-2.0.0-updateSequence-6442");
        when(client.fetch(anyString(), anyString(), any(BooleanSupplier.class)))
                .thenReturn(success("100001", "1572", "b".repeat(64)));
        stage.process(item.forRun(runId));

        verify(client, times(2)).fetch(anyString(), anyString(), any(BooleanSupplier.class));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM location_resolution_cache_records
                 WHERE resolver = 'RGZ_WFS_PARCEL'
                """, Long.class)).isEqualTo(2);
    }

    @Test
    void standaloneKoConflictInvalidatesOnlyTheCurrentPointerAndRetainsEvidence() {
        EnrichmentWorkItem item = seed(
                41_002L, "Чајетина", "Насеље А", "Општина А",
                "КО Чајетина; парцела број 1572");
        koMatches.run();
        when(client.fetch(anyString(), anyString(), any(BooleanSupplier.class)))
                .thenReturn(success("100001", "1572", "c".repeat(64)));
        stage.process(item.forRun(insertEnrichmentRun()));
        UUID referenceId = parcelReference(41_002L);

        jdbc.update("""
                UPDATE property_references
                   SET raw_ko = 'Урсуле', normalized_ko = 'URSULE'
                 WHERE id = ?
                """, referenceId);
        koMatches.run();

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM current_location_resolutions
                 WHERE property_reference_id = ?
                """, Long.class, referenceId)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM location_resolution_attempts
                 WHERE property_reference_id = ? AND resolver = 'RGZ_WFS_PARCEL'
                """, Long.class, referenceId)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM location_resolution_cache_records
                 WHERE resolver = 'RGZ_WFS_PARCEL'
                """, Long.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM spatial_resolution_geometries", Long.class))
                .isOne();
        assertThat(viewport.findSelectedWithin(new BoundingBox(19, 43, 21, 45), 10)).isEmpty();
        assertThat(jdbc.queryForObject("""
                SELECT result.reconciliation_status FROM current_property_reference_ko_matches current_match
                JOIN property_reference_ko_match_results result
                  ON result.reference_id = current_match.reference_id
                 AND result.input_fingerprint = current_match.input_fingerprint
                WHERE current_match.reference_id = ?
                """, String.class, referenceId)).isEqualTo("CONFLICT");
    }

    @Test
    void perRunCeilingSkipsTheSecondCacheMissAndFallsThroughWithoutFailing() {
        EnrichmentWorkItem first = seed(
                41_003L, "Чајетина", "Насеље А", "Општина А",
                "КО Чајетина; парцела број 1572");
        EnrichmentWorkItem second = seed(
                41_004L, "Чајетина", "Насеље А", "Општина А",
                "КО Чајетина; парцела број 1573");
        koMatches.run();
        rgzProperties.setMaxLogicalLookupsPerRun(1);
        when(client.fetch(anyString(), anyString(), any(BooleanSupplier.class)))
                .thenAnswer(invocation -> success(
                        invocation.getArgument(0), invocation.getArgument(1), "d".repeat(64)));
        UUID runId = insertEnrichmentRun();

        assertThat(stage.process(first.forRun(runId)).disposition())
                .isEqualTo(EnrichmentStageResult.Disposition.RESOLVED);
        assertThat(stage.process(second.forRun(runId)).disposition())
                .isEqualTo(EnrichmentStageResult.Disposition.CONTINUE);

        verify(client, times(1)).fetch(anyString(), anyString(), any(BooleanSupplier.class));
        assertThat(jdbc.queryForObject("""
                SELECT logical_lookup_count FROM rgz_enrichment_run_usage
                 WHERE enrichment_run_id = ?
                """, Integer.class, runId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM location_resolution_attempts
                 WHERE resolver = 'RGZ_WFS_PARCEL'
                   AND confidence_reason = 'RUN_REQUEST_CEILING_REACHED'
                """, Long.class)).isOne();
    }

    @Test
    void authoritativeNotFoundIsCachedAndRepeatedEnrichmentMakesNoRequest() {
        EnrichmentWorkItem item = seed(
                41_005L, "Чајетина", "Насеље А", "Општина А",
                "КО Чајетина; парцела број 9999");
        koMatches.run();
        when(client.fetch(anyString(), anyString(), any(BooleanSupplier.class)))
                .thenReturn(new RgzParcelResult(
                        RgzParcelResult.Status.NOT_FOUND,
                        "AUTHORITATIVE_NOT_FOUND",
                        "e".repeat(64),
                        null, null, null, null, null, null, 1,
                        Map.of(
                                "schemaVersion", "rgz-parcel-evidence-v1",
                                "reason", "AUTHORITATIVE_NOT_FOUND",
                                "rawResponseSha256", "e".repeat(64))));

        UUID runId = insertEnrichmentRun();
        assertThat(stage.process(item.forRun(runId)).disposition())
                .isEqualTo(EnrichmentStageResult.Disposition.CONTINUE);
        assertThat(stage.process(item.forRun(runId)).disposition())
                .isEqualTo(EnrichmentStageResult.Disposition.CONTINUE);

        verify(client, times(1)).fetch(anyString(), anyString(), any(BooleanSupplier.class));
        assertThat(jdbc.queryForMap("""
                SELECT resolution_status, location_precision, geometry_id
                  FROM location_resolution_cache_records
                 WHERE resolver = 'RGZ_WFS_PARCEL'
                """))
                .containsEntry("resolution_status", "NOT_FOUND")
                .containsEntry("location_precision", "NONE")
                .containsEntry("geometry_id", null);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM current_location_resolutions", Long.class)).isZero();
    }

    @Test
    void structuredOnlyKoEvidenceNeverTriggersAParcelRequest() {
        EnrichmentWorkItem item = seed(
                41_006L, "Чајетина", "Насеље А", "Општина А",
                "КО Непостојећа; парцела број 1572");
        koMatches.run();

        assertThat(stage.process(item.forRun(insertEnrichmentRun())).disposition())
                .isEqualTo(EnrichmentStageResult.Disposition.CONTINUE);

        verifyNoInteractions(client);
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                  FROM property_reference_ko_match_results
                 WHERE auction_id = 41006 AND reconciliation_status = 'STRUCTURED_ONLY'
                """, Long.class)).isPositive();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM location_resolution_attempts
                 WHERE resolver = 'RGZ_WFS_PARCEL'
                """, Long.class)).isZero();
    }

    @Test
    void transientRefreshFailurePreservesTheLastValidParcelSelection() {
        EnrichmentWorkItem item = seed(
                41_007L, "Чајетина", "Насеље А", "Општина А",
                "КО Чајетина; парцела број 1572");
        koMatches.run();
        when(client.fetch(anyString(), anyString(), any(BooleanSupplier.class)))
                .thenReturn(success("100001", "1572", "f".repeat(64)));
        UUID runId = insertEnrichmentRun();
        stage.process(item.forRun(runId));
        UUID selectedBefore = jdbc.queryForObject(
                "SELECT resolution_attempt_id FROM current_location_resolutions", UUID.class);

        rgzProperties.setDatasetVersion("WFS-2.0.0-updateSequence-6442");
        when(client.fetch(anyString(), anyString(), any(BooleanSupplier.class)))
                .thenReturn(errorResult("TRANSPORT_TIMEOUT"));
        stage.process(item.forRun(runId));

        assertThat(jdbc.queryForObject(
                "SELECT resolution_attempt_id FROM current_location_resolutions", UUID.class))
                .isEqualTo(selectedBefore);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM location_resolution_attempts
                 WHERE resolver = 'RGZ_WFS_PARCEL'
                   AND resolution_status = 'ERROR'
                   AND candidate_evidence ->> 'decisionVersion' = ?
                """, Long.class, RgzParcelResolutionService.DECISION_VERSION)).isOne();
        verify(client, times(2)).fetch(anyString(), anyString(), any(BooleanSupplier.class));
    }

    @Test
    void rgzServicePerformsNetworkIoWithoutAnOpenDatabaseTransaction() {
        EnrichmentWorkItem item = seed(
                41_008L, "Чајетина", "Насеље А", "Општина А",
                "КО Чајетина; парцела број 1572");
        when(client.fetch(anyString(), anyString(), any(BooleanSupplier.class)))
                .thenAnswer(invocation -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    return success(invocation.getArgument(0), invocation.getArgument(1), "1".repeat(64));
                });

        koMatches.run();
        stage.process(item.forRun(insertEnrichmentRun()));

        verify(client).fetch(anyString(), anyString(), any(BooleanSupplier.class));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM location_resolution_cache_records
                 WHERE resolver = 'RGZ_WFS_PARCEL'
                """, Long.class)).isOne();
    }

    @Test
    void anEnclosingCallerTransactionRefusesToFetchInsteadOfHoldingItOpen() {
        EnrichmentWorkItem item = seed(
                41_012L, "Чајетина", "Насеље А", "Општина А",
                "КО Чајетина; парцела број 1572");
        koMatches.run();
        UUID runId = insertEnrichmentRun();

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager)
                .execute(status -> stage.process(item.forRun(runId))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not run inside a database transaction");

        verifyNoInteractions(client);
    }

    @Test
    void durableReservationAndIdentityClaimSurviveResultPersistenceRollback() {
        EnrichmentWorkItem first = seed(
                41_009L, "Чајетина", "Насеље А", "Општина А",
                "КО Чајетина; парцела број 1572");
        EnrichmentWorkItem second = seed(
                41_010L, "Чајетина", "Насеље А", "Општина А",
                "КО Чајетина; парцела број 1573");
        koMatches.run();
        rgzProperties.setMaxLogicalLookupsPerRun(1);
        UUID firstReference = parcelReference(41_009L);
        when(client.fetch(anyString(), anyString(), any(BooleanSupplier.class)))
                .thenAnswer(invocation -> {
                    assertThat(jdbc.update("""
                            UPDATE property_references
                               SET canonical_parcel_number = '9999'
                             WHERE id = ?
                            """, firstReference)).isOne();
                    return success(invocation.getArgument(0), invocation.getArgument(1), "2".repeat(64));
                });
        UUID runId = insertEnrichmentRun();

        assertThatThrownBy(() -> stage.process(first.forRun(runId)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no longer matches");
        assertThat(jdbc.update("""
                UPDATE property_references
                   SET canonical_parcel_number = '1572'
                 WHERE id = ?
                """, firstReference)).isOne();

        assertThat(stage.process(first.forRun(runId)).disposition())
                .isEqualTo(EnrichmentStageResult.Disposition.CONTINUE);
        assertThat(stage.process(second.forRun(runId)).disposition())
                .isEqualTo(EnrichmentStageResult.Disposition.CONTINUE);

        verify(client, times(1)).fetch(anyString(), anyString(), any(BooleanSupplier.class));
        assertThat(jdbc.queryForObject("""
                SELECT logical_lookup_count FROM rgz_enrichment_run_usage
                 WHERE enrichment_run_id = ?
                """, Integer.class, runId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM rgz_enrichment_run_lookup_claims
                 WHERE enrichment_run_id = ?
                """, Long.class, runId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM location_resolution_cache_records
                 WHERE resolver = 'RGZ_WFS_PARCEL'
                """, Long.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM location_resolution_attempts
                 WHERE resolver = 'RGZ_WFS_PARCEL'
                   AND confidence_reason = 'LOGICAL_LOOKUP_ALREADY_CLAIMED'
                """, Long.class)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM location_resolution_attempts
                 WHERE resolver = 'RGZ_WFS_PARCEL'
                   AND confidence_reason = 'RUN_REQUEST_CEILING_REACHED'
                """, Long.class)).isOne();
    }

    @Test
    void protocolErrorsAreNotCachedAndCanRetryInALaterRun() {
        EnrichmentWorkItem item = seed(
                41_011L, "Чајетина", "Насеље А", "Општина А",
                "КО Чајетина; парцела број 1572");
        koMatches.run();
        when(client.fetch(anyString(), anyString(), any(BooleanSupplier.class)))
                .thenReturn(errorResult("INVALID_CRS"));

        UUID firstRunId = insertEnrichmentRun();
        stage.process(item.forRun(firstRunId));
        jdbc.update("""
                UPDATE enrichment_runs
                   SET status = 'SUCCEEDED', finished_at = CURRENT_TIMESTAMP
                 WHERE id = ?
                """, firstRunId);
        stage.process(item.forRun(insertEnrichmentRun()));

        verify(client, times(2)).fetch(anyString(), anyString(), any(BooleanSupplier.class));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM location_resolution_cache_records
                 WHERE resolver = 'RGZ_WFS_PARCEL'
                """, Long.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM location_resolution_attempts
                 WHERE resolver = 'RGZ_WFS_PARCEL'
                   AND resolution_status = 'ERROR'
                   AND confidence_reason = 'INVALID_CRS'
                """, Long.class)).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TRANSPORT_TIMEOUT", "HTTP_503", "INVALID_CRS"})
    void ordinaryDiscoveryRetriesUncachedErrorsWithoutLosingTheFallback(String reason) {
        EnrichmentWorkItem item = seed(
                41_101L, "Чајетина", "Насеље А", "Општина А",
                "КО Чајетина; парцела број 1572");
        koMatches.run();
        seedCoarseFallback(item.auctionId());
        publishObservations();
        EnrichmentPipeline pipeline = retryPipeline();
        when(client.fetch(anyString(), anyString(), any(BooleanSupplier.class)))
                .thenReturn(errorResult(reason));

        runDiscovered(pipeline);

        assertThat(jdbc.queryForObject("SELECT status FROM enrichment_state", String.class))
                .isEqualTo("SUCCEEDED");
        assertThat(viewport.findSelectedWithin(new BoundingBox(19, 43, 21, 45), 10))
                .singleElement().satisfies(location -> assertThat(location.precision())
                        .isEqualTo(LocationPrecision.CADASTRAL_MUNICIPALITY));
        assertThat(runs.measureBacklog(pipeline.activeVersions(), 3).count()).isOne();
        assertThat(runs.discoverCandidates(pipeline.activeVersions(), EnrichmentSelector.none(), 3, 100))
                .singleElement().satisfies(candidate -> assertThat(candidate.item().auctionId())
                        .isEqualTo(item.auctionId()));

        when(client.fetch(anyString(), anyString(), any(BooleanSupplier.class)))
                .thenReturn(success("100001", "1572", "3".repeat(64)));
        runDiscovered(pipeline);
        assertThat(viewport.findSelectedWithin(new BoundingBox(19, 43, 21, 45), 10))
                .singleElement().satisfies(location -> assertThat(location.precision())
                        .isEqualTo(LocationPrecision.PARCEL));
        // The newly selected parcel changes upstream selection material once;
        // its cache-only stabilization must not keep old ERROR evidence pending.
        runDiscovered(pipeline);
        assertThat(runs.measureBacklog(pipeline.activeVersions(), 3).count()).isZero();
        verify(client, times(2)).fetch(anyString(), anyString(), any(BooleanSupplier.class));
    }

    @Test
    void ordinaryDiscoveryRetriesAfterATerminalFallbackAndCanRetireStaleKoErrors() {
        EnrichmentWorkItem item = seed(
                41_102L, "Чајетина", "Насеље А", "Општина А",
                "КО Чајетина; парцела број 1572");
        koMatches.run();
        publishObservations();
        EnrichmentPipeline pipeline = retryPipeline();
        when(client.fetch(anyString(), anyString(), any(BooleanSupplier.class)))
                .thenReturn(errorResult("TRANSPORT_TIMEOUT"));

        // These are separate ordinary runs, not explicit replay or direct stage calls.
        // A finer-tier outage must not exhaust the generic stage-failure budget.
        for (int run = 0; run < 4; run++) {
            assertThat(runs.discoverCandidates(pipeline.activeVersions(), EnrichmentSelector.none(), 3, 100))
                    .hasSize(1);
            runDiscovered(pipeline);
        }
        assertThat(jdbc.queryForObject("SELECT status FROM enrichment_state", String.class))
                .isEqualTo("TERMINAL_NOT_FOUND");
        verify(client, times(4)).fetch(anyString(), anyString(), any(BooleanSupplier.class));

        jdbc.update("""
                UPDATE property_references SET raw_ko = 'Урсуле', normalized_ko = 'URSULE' WHERE id = ?
                """, parcelReference(item.auctionId()));
        koMatches.run();
        assertThat(runs.discoverCandidates(pipeline.activeVersions(), EnrichmentSelector.none(), 3, 100))
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PERMANENT_FAILURE", "ATTEMPT_LIMIT_REACHED"})
    void oldParcelErrorsDoNotOverrideUnrelatedTerminalStageFailures(String status) {
        seed(41_112L, "Чајетина", "Насеље А", "Општина А", "КО Чајетина; парцела број 1572");
        koMatches.run();
        publishObservations();
        EnrichmentPipeline pipeline = retryPipeline();
        when(client.fetch(anyString(), anyString(), any(BooleanSupplier.class)))
                .thenReturn(errorResult("TRANSPORT_TIMEOUT"));
        runDiscovered(pipeline);
        var versions = pipeline.activeVersions();
        var candidate = runs.discoverCandidates(versions, EnrichmentSelector.none(), 3, 100).get(0);
        UUID run = runs.claim(UUID.randomUUID().toString(), EnrichmentTriggerKind.MANUAL,
                versions, EnrichmentSelector.none(), 100).runId();
        runs.setCandidateCount(run, 1);
        runs.startItem(run, 1, candidate, versions);
        runs.completeItem(run, candidate.item().auctionId(),
                rs.sud.eaukcija.enrichment.EnrichmentStateStatus.valueOf(status), EnrichmentStageName.PARSE,
                null, "STAGE_FAILURE", "TEST_STAGE_FAILURE");
        runs.finish(run, EnrichmentRunStatus.PARTIAL);

        assertThat(runs.discoverCandidates(versions, EnrichmentSelector.none(), 3, 100)).isEmpty();
        assertThat(runs.measureBacklog(versions, 3).count()).isZero();
        verify(client).fetch(anyString(), anyString(), any(BooleanSupplier.class));
    }

    @Test
    void ceilingDeferralsGetTheNextRunSlotAheadOfRepeatedTransportFailures() {
        seed(41_103L, "Чајетина", "Насеље А", "Општина А", "КО Чајетина; парцела број 1572");
        seed(41_104L, "Чајетина", "Насеље А", "Општина А", "КО Чајетина; парцела број 1573");
        koMatches.run();
        publishObservations();
        rgzProperties.setMaxLogicalLookupsPerRun(1);
        when(client.fetch(anyString(), anyString(), any(BooleanSupplier.class)))
                .thenReturn(errorResult("TRANSPORT_TIMEOUT"));
        EnrichmentPipeline pipeline = retryPipeline();

        runDiscovered(pipeline);
        verify(client).fetch(anyString(), org.mockito.ArgumentMatchers.eq("1572"), any(BooleanSupplier.class));
        assertThat(runs.discoverCandidates(pipeline.activeVersions(), EnrichmentSelector.none(), 3, 100))
                .extracting(candidate -> candidate.item().auctionId()).containsExactly(41_104L, 41_103L);

        runDiscovered(pipeline);
        verify(client).fetch(anyString(), org.mockito.ArgumentMatchers.eq("1573"), any(BooleanSupplier.class));
        verify(client, times(2)).fetch(anyString(), anyString(), any(BooleanSupplier.class));
        assertThat(runs.discoverCandidates(pipeline.activeVersions(), EnrichmentSelector.none(), 3, 100))
                .extracting(candidate -> candidate.item().auctionId()).containsExactly(41_103L, 41_104L);
        assertThat(jdbc.queryForList("SELECT logical_lookup_count FROM rgz_enrichment_run_usage", Integer.class))
                .containsExactly(1, 1);
    }

    @Test
    void removingTheKillSwitchMakesDeferredWorkDiscoverableWithoutChangingVersions() throws Exception {
        seed(41_105L, "Чајетина", "Насеље А", "Општина А", "КО Чајетина; парцела број 1572");
        koMatches.run();
        publishObservations();
        EnrichmentPipeline pipeline = retryPipeline();
        var versions = pipeline.activeVersions();
        Files.createFile(rgzProperties.getKillSwitchPath());

        runDiscovered(pipeline);
        verifyNoInteractions(client);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM rgz_enrichment_run_usage", Long.class)).isZero();
        assertThat(runs.discoverCandidates(versions, EnrichmentSelector.none(), 3, 100)).isEmpty();

        Files.delete(rgzProperties.getKillSwitchPath());
        assertThat(pipeline.activeVersions()).isEqualTo(versions);
        assertThat(runs.discoverCandidates(versions, EnrichmentSelector.none(), 3, 100)).hasSize(1);
        when(client.fetch(anyString(), anyString(), any(BooleanSupplier.class)))
                .thenReturn(success("100001", "1572", "4".repeat(64)));
        runDiscovered(pipeline);
        verify(client).fetch(anyString(), anyString(), any(BooleanSupplier.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"NOT_FOUND", "AMBIGUOUS", "INVALID"})
    void terminalCacheOutcomesStopOrdinaryRetryDiscovery(String status) {
        seed(41_106L, "Чајетина", "Насеље А", "Општина А", "КО Чајетина; парцела број 1572");
        koMatches.run();
        publishObservations();
        EnrichmentPipeline pipeline = retryPipeline();
        when(client.fetch(anyString(), anyString(), any(BooleanSupplier.class)))
                .thenReturn(errorResult("INVALID_CRS"));
        runDiscovered(pipeline);
        when(client.fetch(anyString(), anyString(), any(BooleanSupplier.class)))
                .thenReturn(new RgzParcelResult(RgzParcelResult.Status.valueOf(status), status,
                        "5".repeat(64), null, null, null, null, null, null, 1,
                        Map.of("schemaVersion", "rgz-parcel-evidence-v1", "physicalAttempts", 1)));

        runDiscovered(pipeline);
        assertThat(runs.discoverCandidates(pipeline.activeVersions(), EnrichmentSelector.none(), 3, 100))
                .isEmpty();
        verify(client, times(2)).fetch(anyString(), anyString(), any(BooleanSupplier.class));
    }

    @Test
    void disabledFetchingStillAttachesCachedGeometryWithItsOriginalSourcePins() {
        EnrichmentWorkItem first = seed(
                41_107L, "Чајетина", "Насеље А", "Општина А", "КО Чајетина; парцела број 1572");
        EnrichmentWorkItem second = seed(
                41_108L, "Чајетина", "Насеље А", "Општина А", "КО Чајетина; парцела број 1572");
        EnrichmentWorkItem miss = seed(
                41_109L, "Чајетина", "Насеље А", "Општина А", "КО Чајетина; парцела број 1573");
        koMatches.run();
        when(client.fetch(anyString(), anyString(), any(BooleanSupplier.class)))
                .thenReturn(success("100001", "1572", "6".repeat(64)));
        UUID runId = insertEnrichmentRun();
        stage.process(first.forRun(runId));
        rgzProperties.setEnabled(false);
        rgzProperties.setCapabilitiesSha256("");
        rgzProperties.setSchemaSha256("");

        assertThat(stage.process(second.forRun(runId)).disposition())
                .isEqualTo(EnrichmentStageResult.Disposition.RESOLVED);
        assertThat(stage.process(miss.forRun(runId)).disposition())
                .isEqualTo(EnrichmentStageResult.Disposition.CONTINUE);
        verify(client).fetch(anyString(), anyString(), any(BooleanSupplier.class));
        assertThat(jdbc.queryForObject("SELECT logical_lookup_count FROM rgz_enrichment_run_usage", Integer.class))
                .isOne();
        assertThat(jdbc.queryForMap("""
                SELECT source_dataset_sha256, candidate_evidence ->> 'schemaSha256' AS schema_pin
                  FROM location_resolution_attempts WHERE property_reference_id = ?
                """, parcelReference(second.auctionId())))
                .containsEntry("source_dataset_sha256", "a".repeat(64))
                .containsEntry("schema_pin", "b".repeat(64));
    }

    @Test
    void metadataPinChangesReuseTheSameCacheIdentityAndPreserveFetchProvenance() {
        EnrichmentWorkItem first = seed(
                41_110L, "Чајетина", "Насеље А", "Општина А", "КО Чајетина; парцела број 1572");
        EnrichmentWorkItem second = seed(
                41_111L, "Чајетина", "Насеље А", "Општина А", "КО Чајетина; парцела број 1572");
        koMatches.run();
        when(client.fetch(anyString(), anyString(), any(BooleanSupplier.class)))
                .thenReturn(success("100001", "1572", "7".repeat(64)));
        UUID firstRun = insertEnrichmentRun();
        stage.process(first.forRun(firstRun));
        runs.finish(firstRun, EnrichmentRunStatus.SUCCEEDED);
        rgzProperties.setCapabilitiesSha256("c".repeat(64));
        rgzProperties.setSchemaSha256("d".repeat(64));
        UUID secondRun = insertEnrichmentRun();

        assertThat(stage.process(first.forRun(secondRun)).disposition())
                .isEqualTo(EnrichmentStageResult.Disposition.RESOLVED);
        assertThat(stage.process(second.forRun(secondRun)).disposition())
                .isEqualTo(EnrichmentStageResult.Disposition.RESOLVED);
        verify(client).fetch(anyString(), anyString(), any(BooleanSupplier.class));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM rgz_parcel_cache_keys", Long.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM location_resolution_cache_records", Long.class)).isOne();
        assertThat(jdbc.queryForMap("""
                SELECT source_dataset_sha256, candidate_evidence ->> 'schemaSha256' AS schema_pin,
                       candidate_evidence ->> 'rawResponseSha256' AS response_sha
                  FROM location_resolution_attempts WHERE property_reference_id = ?
                """, parcelReference(second.auctionId())))
                .containsEntry("source_dataset_sha256", "a".repeat(64))
                .containsEntry("schema_pin", "b".repeat(64))
                .containsEntry("response_sha", "7".repeat(64));
    }

    @Test
    void aCacheFromAnOlderResolverVersionIsStillAuthoritative() {
        EnrichmentWorkItem item = seed(
                41_113L, "Чајетина", "Насеље А", "Општина А", "КО Чајетина; парцела број 1572");
        koMatches.run();
        UUID cacheId = UUID.randomUUID();
        String fingerprint = EnrichmentHashing.sha256(
                rgzProperties.getFeatureType(), ORIGINAL_DATASET, "100001", "1572");
        jdbc.update("""
                INSERT INTO location_resolution_cache_records (
                    id, resolver, resolver_version, input_fingerprint, source_dataset,
                    source_dataset_version, source_dataset_sha256, resolution_status,
                    location_precision, confidence_reason, resolved_at, candidate_evidence
                ) VALUES (?, 'RGZ_WFS_PARCEL', 'rgz-parcel-v1', ?, 'RGZ_REGDKP_WFS', ?, ?,
                          'NOT_FOUND', 'NONE', 'legacy authoritative not found', CURRENT_TIMESTAMP,
                          jsonb_build_object('capabilitiesSha256', ?::text))
                """, cacheId, fingerprint, ORIGINAL_DATASET, "c".repeat(64), "c".repeat(64));
        jdbc.update("INSERT INTO rgz_parcel_cache_keys VALUES (?, ?)", fingerprint, cacheId);

        assertThat(stage.process(item.forRun(insertEnrichmentRun())).disposition())
                .isEqualTo(EnrichmentStageResult.Disposition.CONTINUE);
        verifyNoInteractions(client);
        assertThat(jdbc.queryForMap("""
                SELECT used_cache_record_id, resolver_version, source_dataset_sha256
                  FROM location_resolution_attempts WHERE property_reference_id = ?
                """, parcelReference(item.auctionId())))
                .containsEntry("used_cache_record_id", cacheId)
                .containsEntry("resolver_version", "rgz-parcel-v1")
                .containsEntry("source_dataset_sha256", "c".repeat(64));
    }

    @Test
    void standaloneKoChangeWhileHttpIsInFlightCannotReinstallAStaleParcelOrKoCode() {
        EnrichmentWorkItem item = seed(
                41_114L, "Чајетина", "Насеље А", "Општина А", "КО Чајетина; парцела број 1572");
        koMatches.run();
        UUID reference = parcelReference(item.auctionId());
        when(client.fetch(anyString(), anyString(), any(BooleanSupplier.class))).thenAnswer(invocation -> {
            jdbc.update("UPDATE property_references SET raw_ko = 'Урсуле', normalized_ko = 'URSULE' WHERE id = ?", reference);
            koMatches.run();
            return success("100001", "1572", "8".repeat(64));
        });
        assertThat(stage.process(item.forRun(insertEnrichmentRun())).disposition())
                .isEqualTo(EnrichmentStageResult.Disposition.CONTINUE);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM current_location_resolutions", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT ko_code FROM property_references WHERE id = ?", String.class, reference)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM rgz_parcel_cache_keys", Long.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM location_resolution_attempts", Long.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM spatial_resolution_geometries", Long.class)).isOne();
        assertThat(viewport.findSelectedWithin(new BoundingBox(19, 43, 21, 45), 10)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"AMBIGUOUS", "NOT_FOUND", "INVALID"})
    void everyNonMatchedCurrentKoStatusRevokesThePointerWithoutDestroyingHistory(String expected) {
        EnrichmentWorkItem item = seed(
                41_115L, "Чајетина", "Насеље А", "Општина А", "КО Чајетина; парцела број 1572");
        koMatches.run();
        when(client.fetch(anyString(), anyString(), any(BooleanSupplier.class)))
                .thenReturn(success("100001", "1572", "9".repeat(64)));
        UUID run = insertEnrichmentRun();
        stage.process(item.forRun(run));
        UUID reference = parcelReference(item.auctionId());
        String raw = switch (expected) {
            case "AMBIGUOUS" -> "URSULE";
            case "NOT_FOUND" -> "XYZQNONEXISTENT";
            default -> "";
        };
        jdbc.update("UPDATE property_references SET raw_ko = ?, normalized_ko = ? WHERE id = ?", raw, raw, reference);
        koMatches.run();
        assertThat(jdbc.queryForObject("""
                SELECT result.status FROM current_property_reference_ko_matches current_match
                JOIN property_reference_ko_match_results result
                  ON result.reference_id = current_match.reference_id
                 AND result.input_fingerprint = current_match.input_fingerprint
                WHERE current_match.reference_id = ?
                """, String.class, reference)).isEqualTo(expected);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM current_location_resolutions", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM rgz_parcel_cache_keys", Long.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM location_resolution_attempts", Long.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM spatial_resolution_geometries", Long.class)).isOne();
        stage.process(item.forRun(run));
        verify(client).fetch(anyString(), anyString(), any(BooleanSupplier.class));
    }

    @Test
    void deletingCurrentKoEvidenceRemovesTheSelectionAndCannotBeBypassedByCacheReplay() {
        EnrichmentWorkItem item = seed(
                41_116L, "Чајетина", "Насеље А", "Општина А", "КО Чајетина; парцела број 1572");
        koMatches.run();
        when(client.fetch(anyString(), anyString(), any(BooleanSupplier.class)))
                .thenReturn(success("100001", "1572", "a".repeat(64)));
        UUID run = insertEnrichmentRun();
        stage.process(item.forRun(run));
        UUID reference = parcelReference(item.auctionId());
        jdbc.update("DELETE FROM current_property_reference_ko_matches WHERE reference_id = ?", reference);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM current_location_resolutions", Long.class)).isZero();
        assertThat(jdbc.update("""
                INSERT INTO current_location_resolutions
                    (property_reference_id, resolution_attempt_id, selected_at, selection_reason)
                SELECT property_reference_id, id, CURRENT_TIMESTAMP, 'stale replay'
                  FROM location_resolution_attempts WHERE property_reference_id = ?
                """, reference)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM rgz_parcel_cache_keys", Long.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM location_resolution_attempts", Long.class)).isOne();
        koMatches.run();
        stage.process(item.forRun(run));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM current_location_resolutions", Long.class)).isOne();
        verify(client).fetch(anyString(), anyString(), any(BooleanSupplier.class));
    }

    /** Extraction/matching are already seeded; exercise the real remaining stages and discovery ledger. */
    private EnrichmentPipeline retryPipeline() {
        return new EnrichmentPipeline(java.util.List.of(
                seededStage(EnrichmentStageName.PARSE), seededStage(EnrichmentStageName.KO_MATCHING),
                stage, seededStage(EnrichmentStageName.ADDRESS_FALLBACK),
                new SelectedResolutionEnrichmentStage(jdbc)));
    }

    private static EnrichmentStage seededStage(EnrichmentStageName name) {
        return new EnrichmentStage() {
            @Override public EnrichmentStageName name() { return name; }
            @Override public String implementationVersion() { return "seeded-v1"; }
            @Override public String activeDatasetVersion() { return "seeded-v1"; }
            @Override public EnrichmentStageResult process(EnrichmentWorkItem item) {
                return EnrichmentStageResult.continuing("a".repeat(64));
            }
        };
    }

    private void runDiscovered(EnrichmentPipeline pipeline) {
        var versions = pipeline.activeVersions();
        var candidates = runs.discoverCandidates(versions, EnrichmentSelector.none(), 3, 100);
        UUID runId = runs.claim(UUID.randomUUID().toString(), EnrichmentTriggerKind.MANUAL,
                versions, EnrichmentSelector.none(), 100).runId();
        runs.setCandidateCount(runId, candidates.size());
        var processor = new EnrichmentItemProcessor(pipeline, transactionManager);
        for (int index = 0; index < candidates.size(); index++) {
            var candidate = candidates.get(index);
            runs.startItem(runId, index + 1, candidate, versions);
            var result = processor.process(candidate.item().forRun(runId));
            runs.completeItem(runId, candidate.item().auctionId(), result.status(), result.lastStage(),
                    result.outputSha256(), null, null);
        }
        runs.finish(runId, EnrichmentRunStatus.SUCCEEDED);
    }

    private void publishObservations() {
        jdbc.update("""
                INSERT INTO sync_run_auction_observations (
                    run_id, auction_id, listing_fingerprint, detail_refreshed,
                    enrichment_eligible, enrichment_reason, source_snapshot_sha256
                ) SELECT ?, id, current_source_snapshot_sha256, TRUE, TRUE, 'NEW', current_source_snapshot_sha256
                    FROM auctions WHERE last_successful_sync_run_id = ?
                """, sourceRunId, sourceRunId);
        String taxonomy = "9".repeat(64);
        jdbc.update("""
                INSERT INTO eaukcija_taxonomies (tree_sha256, normalizer_version, canonical_tree, first_observed_at)
                VALUES (?, 'test-v1', '[]'::jsonb, CURRENT_TIMESTAMP) ON CONFLICT DO NOTHING
                """, taxonomy);
        jdbc.update("""
                UPDATE sync_runs SET status = 'SUCCEEDED', stage = 'COMPLETED', finished_at = CURRENT_TIMESTAMP,
                       category_tree_sha256 = ?, category_tree_observed_at = CURRENT_TIMESTAMP WHERE id = ?
                """, taxonomy, sourceRunId);
        jdbc.update("""
                INSERT INTO auction_enrichment_snapshot_observations (source_sync_run_id, auction_id, snapshot_sha256)
                SELECT ?, id, current_enrichment_snapshot_sha256 FROM auctions WHERE last_successful_sync_run_id = ?
                """, sourceRunId, sourceRunId);
    }

    private void seedCoarseFallback(long auctionId) {
        UUID geometry = UUID.randomUUID();
        UUID attempt = UUID.randomUUID();
        UUID reference = parcelReference(auctionId);
        jdbc.update("""
                INSERT INTO spatial_resolution_geometries (
                    id, source_geometry, source_crs_authority, source_crs_code, canonical_geometry,
                    original_geometry_valid, make_valid_applied
                ) VALUES (?, ST_GeomFromText('POINT(20 44)', 4326), 'EPSG', 4326,
                          ST_GeomFromText('POINT(20 44)', 4326), TRUE, FALSE)
                """, geometry);
        jdbc.update("""
                INSERT INTO location_resolution_attempts (
                    id, property_reference_id, resolver, resolver_version, input_fingerprint,
                    source_dataset, source_dataset_version, source_dataset_sha256,
                    resolution_status, location_precision, geometry_id, confidence_reason,
                    attempted_at, completed_at, resolved_at
                ) VALUES (?, ?, 'COARSE_FIXTURE', 'fixture-v1', ?, 'FIXTURE', 'fixture-v1', ?,
                          'RESOLVED', 'CADASTRAL_MUNICIPALITY', ?, 'coarse fixture',
                          CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, attempt, reference, "a".repeat(64), "b".repeat(64), geometry);
        jdbc.update("""
                INSERT INTO current_location_resolutions (
                    property_reference_id, resolution_attempt_id, selected_at, selection_reason
                ) VALUES (?, ?, CURRENT_TIMESTAMP, 'coarse fixture')
                """, reference, attempt);
    }

    private EnrichmentWorkItem seed(
            long auctionId,
            String cadastral,
            String placeName,
            String municipality,
            String description) {
        String sourceSha = EnrichmentHashing.sha256("source", Long.toString(auctionId));
        String inputSha = EnrichmentHashing.sha256("input", Long.toString(auctionId));
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO auctions (
                    id, auction_number, cadastral, place_name, municipality,
                    end_date, first_sale, details_fetched, last_successful_sync_run_id
                ) VALUES (?, ?, ?, ?, ?, ?, FALSE, TRUE, ?)
                """, auctionId, "ISSUE-41-" + auctionId, cadastral, placeName, municipality,
                now.plusDays(1), sourceRunId);

        ObjectNode source = objectMapper.createObjectNode();
        source.putObject("listing").put("Id", auctionId);
        ObjectNode detail = source.putObject("detail");
        detail.put("Id", auctionId);
        detail.put("Description", description);
        detail.putNull("ShortDescription");
        ObjectNode place = detail.putObject("Place");
        place.put("Cadastral", cadastral);
        place.put("Name", placeName);
        place.put("Municipality", municipality);
        jdbc.update("""
                INSERT INTO auction_source_snapshots (
                    auction_id, content_sha256, schema_version, minimization_policy_version,
                    listing_endpoint, detail_endpoint, canonical_payload,
                    fetched_at, listing_fetched_at, detail_fetched_at,
                    source_start_at, source_end_at, ingest_run_id
                ) VALUES (?, ?, 'test-source-v1', 'test-policy-v1', 'listing', 'detail',
                          CAST(? AS jsonb), ?, ?, ?, ?, ?, ?)
                """, auctionId, sourceSha, source.toString(), now, now, now,
                now.minusDays(1), now.plusDays(1), sourceRunId);
        ObjectNode input = objectMapper.createObjectNode()
                .put("schemaVersion", EnrichmentInputSnapshot.SCHEMA_VERSION)
                .put("sourceSnapshotSha256", sourceSha)
                .put("auctionId", auctionId)
                .put("cadastral", cadastral)
                .put("placeName", placeName)
                .put("municipality", municipality)
                .put("description", description)
                .putNull("shortDescription");
        jdbc.update("""
                INSERT INTO auction_enrichment_input_snapshots (
                    auction_id, snapshot_sha256, canonical_input
                ) VALUES (?, ?, CAST(? AS jsonb))
                """, auctionId, inputSha, input.toString());
        jdbc.update("""
                UPDATE auctions SET current_source_snapshot_sha256 = ?,
                                    current_enrichment_snapshot_sha256 = ?
                 WHERE id = ?
                """, sourceSha, inputSha, auctionId);
        EnrichmentWorkItem item = new EnrichmentWorkItem(
                auctionId,
                sourceRunId,
                inputSha,
                EnrichmentHashing.sha256("dependency", Long.toString(auctionId)),
                EnrichmentHashing.sha256("work", Long.toString(auctionId)),
                input);
        references.replace(item, parser.parse(input));
        return item;
    }

    private UUID parcelReference(long auctionId) {
        return jdbc.queryForObject("""
                SELECT reference.id
                  FROM current_property_reference_extractions current_extraction
                  JOIN property_reference_extraction_memberships membership
                    ON membership.extraction_run_id = current_extraction.extraction_run_id
                  JOIN property_references reference ON reference.id = membership.reference_id
                 WHERE reference.auction_id = ? AND reference.reference_type = 'PARCEL'
                 ORDER BY membership.reference_order LIMIT 1
                """, UUID.class, auctionId);
    }

    private UUID insertSourceRun() {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", OffsetDateTime.class);
        jdbc.update("""
                INSERT INTO sync_runs (
                    id, idempotency_key_sha256, trigger_kind, status, stage,
                    started_at, heartbeat_at, configured_roots, page_size
                ) VALUES (?, ?, 'MANUAL', 'RUNNING', 'PROMOTING', ?, ?, '[7]'::jsonb, 3000)
                """, id, EnrichmentHashing.sha256("issue-41", id.toString()), now, now);
        return id;
    }

    private UUID nextEnrichmentRun() {
        jdbc.update("UPDATE enrichment_runs SET status='SUCCEEDED', finished_at=CURRENT_TIMESTAMP WHERE status='RUNNING'");
        return insertEnrichmentRun();
    }

    private UUID insertEnrichmentRun() {
        UUID id = UUID.randomUUID();
        // Fixture completion uses the database clock as well; host/VM skew must
        // not make an immediate cached run finish before its seeded start.
        OffsetDateTime now = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", OffsetDateTime.class);
        jdbc.update("""
                INSERT INTO enrichment_runs (
                    id, idempotency_key_sha256, trigger_kind, status,
                    started_at, heartbeat_at, parser_version, resolver_version,
                    dataset_version, max_items, candidate_count
                ) VALUES (?, ?, 'MANUAL', 'RUNNING', ?, ?,
                          'issue-41-parser', 'issue-41-resolver', 'issue-41-dataset',
                          100, 100)
                """, id, EnrichmentHashing.sha256("issue-41-enrichment", id.toString()), now, now);
        return id;
    }

    private static RgzParcelResult success(String koCode, String parcel, String responseSha) {
        String geometry = "{\"type\":\"Polygon\",\"coordinates\":[[[20.0,44.0],"
                + "[20.1,44.0],[20.1,44.1],[20.0,44.0]]]}";
        return new RgzParcelResult(
                RgzParcelResult.Status.RESOLVED,
                "EXACT_KO_PARCEL_MATCH",
                responseSha,
                "parcel." + parcel.replace('/', '-'),
                "Polygon",
                geometry,
                new java.math.BigDecimal("406"),
                "EPSG:25834",
                "1000",
                1,
                Map.of(
                        "schemaVersion", "rgz-parcel-evidence-v1",
                        "requestedKoCode", koCode,
                        "requestedParcelNumber", parcel,
                        "rawResponseSha256", responseSha));
    }

    private static RgzParcelResult errorResult(String reason) {
        return new RgzParcelResult(
                RgzParcelResult.Status.ERROR,
                reason,
                null, null, null, null, null, null, null, 1,
                Map.of("schemaVersion", "rgz-parcel-evidence-v1", "reason", reason, "physicalAttempts", 1));
    }

    private void clean() {
        jdbc.execute("""
                TRUNCATE TABLE enrichment_runs, sync_runs, auctions, parcel_identities,
                    location_resolution_cache_records, spatial_resolution_geometries
                RESTART IDENTITY CASCADE
                """);
    }

    private static Path dictionary() {
        try {
            return KoDictionaryTestArtifact.create(
                    Files.createTempDirectory("issue-41-ko-dictionary-"), new ObjectMapper());
        } catch (Exception failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }
}
