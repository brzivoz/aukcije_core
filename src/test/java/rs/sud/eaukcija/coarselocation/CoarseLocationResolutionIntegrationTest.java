package rs.sud.eaukcija.coarselocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import rs.sud.eaukcija.spatial.AuctionLocationRepository;
import rs.sud.eaukcija.spatial.AuctionLocationView;
import rs.sud.eaukcija.spatial.LocationPrecision;
import rs.sud.eaukcija.testsupport.PostgisTestContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class CoarseLocationResolutionIntegrationTest {

    static final PostgreSQLContainer<?> POSTGIS = PostgisTestContainer.shared();
    private static final String JDBC_URL = PostgisTestContainer.createEmptyDatabase();
    private static final Path CENTROIDS = createArtifact();

    @DynamicPropertySource
    static void isolatedRuntime(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> JDBC_URL);
        registry.add("spring.datasource.username", POSTGIS::getUsername);
        registry.add("spring.datasource.password", POSTGIS::getPassword);
        registry.add("coarse.location.centroid-directory", CENTROIDS::toString);
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CoarseLocationResolutionService service;

    @Autowired
    private AuctionLocationRepository locations;

    @BeforeEach
    @AfterEach
    void resetPopulation() {
        jdbc.execute("""
                TRUNCATE TABLE auctions, parcel_identities, spatial_resolution_geometries,
                    location_resolution_cache_records, structured_ko_match_runs,
                    coarse_location_resolution_runs RESTART IDENTITY CASCADE
                """);
    }

    @Test
    void persistsEveryTierEvidenceHistoryIdempotencyAndNonDowngradingSelection() {
        insertPopulation();

        CoarseLocationResolutionService.RunResult first = service.run();

        assertThat(first.populationCount()).isEqualTo(6);
        assertThat(first.processedCount()).isEqualTo(6);
        assertThat(first.unchangedCount()).isZero();
        assertThat(first.tierCounts())
                .containsEntry("CADASTRAL_MUNICIPALITY", 1L)
                .containsEntry("SETTLEMENT", 3L)
                .containsEntry("MUNICIPALITY", 1L)
                .containsEntry("NONE", 1L);
        assertThat(first.municipalityAliasKoCount()).isOne();
        assertThat(first.structuredKoStatusCounts())
                .containsEntry("MATCHED", 1L)
                .containsEntry("AMBIGUOUS", 1L)
                .containsEntry("NOT_FOUND", 3L)
                .containsEntry("INVALID", 1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM current_location_resolutions", Integer.class)).isEqualTo(6);
        assertThat(jdbc.queryForList("""
                SELECT location_precision FROM location_resolution_attempts
                 WHERE resolver = ? ORDER BY property_reference_id
                """, String.class, CoarseLocationResolver.RESOLVER))
                .containsOnly(
                        "CADASTRAL_MUNICIPALITY", "SETTLEMENT", "MUNICIPALITY", "NONE")
                .doesNotContain("PARCEL", "ADDRESS", "STREET");
        assertThat(jdbc.queryForObject("""
                SELECT member_point_count FROM location_resolution_attempts attempt
                JOIN property_references reference ON reference.id = attempt.property_reference_id
                WHERE reference.auction_id = 38001 AND attempt.resolver = ?
                """, Long.class, CoarseLocationResolver.RESOLVER)).isEqualTo(101L);
        assertThat(jdbc.queryForObject("""
                SELECT candidate_evidence::text FROM location_resolution_attempts attempt
                JOIN property_references reference ON reference.id = attempt.property_reference_id
                WHERE reference.auction_id = 38006 AND attempt.resolver = ?
                """, String.class, CoarseLocationResolver.RESOLVER))
                .contains("structuredKoMatch", "reviewed-city-alias", "S300", "SETTLEMENT");

        Map<Long, AuctionLocationView> views = locations.findBestByAuctionIds(
                List.of(38001L, 38002L, 38003L, 38004L, 38005L, 38006L));
        assertThat(views.get(38001L).precision()).isEqualTo(LocationPrecision.CADASTRAL_MUNICIPALITY);
        assertThat(views.get(38001L).precisionLabelSr()).isEqualTo("Центар катастарске општине");
        assertThat(views.get(38001L).coarse()).isTrue();
        assertThat(views.get(38001L).longitude()).isCloseTo(20.1, offset(0.0000001));
        assertThat(views.get(38005L).precision()).isEqualTo(LocationPrecision.NONE);
        assertThat(views.get(38005L).longitude()).isNull();
        assertThat(views.get(38005L).precisionLabelSr()).isEqualTo("Није лоцирано");

        int attemptCount = count("location_resolution_attempts");
        int geometryCount = count("spatial_resolution_geometries");
        int cacheCount = count("location_resolution_cache_records");
        CoarseLocationResolutionService.RunResult replay = service.run();
        assertThat(replay.processedCount()).isZero();
        assertThat(replay.unchangedCount()).isEqualTo(6);
        assertThat(count("location_resolution_attempts")).isEqualTo(attemptCount);
        assertThat(count("spatial_resolution_geometries")).isEqualTo(geometryCount);
        assertThat(count("location_resolution_cache_records")).isEqualTo(cacheCount);

        jdbc.update("UPDATE auctions SET place_name = 'Нема' WHERE id = 38002");
        CoarseLocationResolutionService.RunResult changed = service.run();
        assertThat(changed.processedCount()).isOne();
        assertThat(changed.unchangedCount()).isEqualTo(5);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM location_resolution_attempts attempt
                JOIN property_references reference ON reference.id = attempt.property_reference_id
                WHERE reference.auction_id = 38002 AND attempt.resolver = ?
                """, Integer.class, CoarseLocationResolver.RESOLVER)).isEqualTo(2);
        assertThat(locations.findBestByAuctionIds(List.of(38002L)).get(38002L).precision())
                .isEqualTo(LocationPrecision.MUNICIPALITY);

        UUID reference = jdbc.queryForObject("""
                SELECT id FROM property_references WHERE auction_id = 38001 AND parser_version = ?
                """, UUID.class, CoarseLocationResolver.REFERENCE_PARSER_VERSION);
        UUID addressAttempt = selectExternalAddress(reference);
        jdbc.update("UPDATE auctions SET place_name = 'Промењено место' WHERE id = 38001");
        CoarseLocationResolutionService.RunResult afterHigherTier = service.run();
        assertThat(afterHigherTier.processedCount()).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT resolution_attempt_id FROM current_location_resolutions WHERE property_reference_id = ?
                """, UUID.class, reference)).isEqualTo(addressAttempt);
        assertThat(locations.findBestByAuctionIds(List.of(38001L)).get(38001L).precision())
                .isEqualTo(LocationPrecision.ADDRESS);

        int retainedRuns = count("coarse_location_resolution_runs");
        int retainedAttempts = count("location_resolution_attempts");
        jdbc.update("""
                UPDATE auction_structured_ko_matches
                   SET dictionary_source_sha256 = ? WHERE auction_id = 38001
                """, "b".repeat(64));
        assertThatThrownBy(service::run)
                .isInstanceOf(CoarseLocationResolutionException.class)
                .extracting(failure -> ((CoarseLocationResolutionException) failure).getCode())
                .isEqualTo("STRUCTURED_KO_SNAPSHOT_MISMATCH");
        assertThat(count("coarse_location_resolution_runs")).isEqualTo(retainedRuns);
        assertThat(count("location_resolution_attempts")).isEqualTo(retainedAttempts);
        assertThat(jdbc.queryForObject("""
                SELECT resolution_attempt_id FROM current_location_resolutions WHERE property_reference_id = ?
                """, UUID.class, reference)).isEqualTo(addressAttempt);
    }

    private void insertPopulation() {
        insertAuction(38001, "КО ТЕСТ", null, "Општина А");
        insertMatch(38001, "MATCHED", "MUNICIPALITY_CONTEXT", "K100",
                "MUNICIPALITY_CONTEXT_REVIEWED_ALIAS: reviewed fixture", """
                [{"koCode":"K100","municipalityContextMatch":true,
                  "municipalities":[{"code":"M100"}],
                  "municipalityAliasReviews":[{"id":"reviewed-city-alias"}]}]
                """);

        insertAuction(38002, "НЕПОЗНАТО", "Čajetina", "Opština A");
        insertMatch(38002, "NOT_FOUND", "NONE", null, "NOT_FOUND: fixture", "[]");

        insertAuction(38003, null, null, "Општина Б");
        insertMatch(38003, "INVALID", "NONE", null, "INVALID: fixture", "[]");

        insertAuction(38004, "ГРАД", "ЧАЈЕТИНА", null);
        insertMatch(38004, "AMBIGUOUS", "EXACT_NORMALIZED_NAME", null, "AMBIGUOUS_NAME: fixture", """
                [{"koCode":"K200","municipalityContextMatch":false,"municipalities":[{"code":"M100"}]},
                 {"koCode":"K300","municipalityContextMatch":false,"municipalities":[{"code":"M200"}]}]
                """);

        insertAuction(38005, "НЕМА", "НЕМА", "НЕМА");
        insertMatch(38005, "NOT_FOUND", "NONE", null, "NOT_FOUND: fixture", "[]");

        insertAuction(38006, "ГРАД", "Grad", "Opština B-grad");
        insertMatch(38006, "NOT_FOUND", "FUZZY_REVIEW", null, "FUZZY_REVIEW_ONLY: fixture", """
                [{"koCode":"K300","municipalityContextMatch":true,
                  "municipalities":[{"code":"M200"}],
                  "municipalityAliasReviews":[{"id":"reviewed-city-alias"}]}]
                """);
    }

    private void insertAuction(long id, String cadastral, String place, String municipality) {
        jdbc.update("""
                INSERT INTO auctions (id, auction_number, cadastral, place_name, municipality,
                                      first_sale, details_fetched)
                VALUES (?, ?, ?, ?, ?, false, true)
                """, id, "N" + id, cadastral, place, municipality);
    }

    private void insertMatch(
            long auctionId,
            String status,
            String method,
            String matchedKoCode,
            String rationale,
            String candidates) {
        Map<String, Object> auction = jdbc.queryForMap(
                "SELECT cadastral, place_name, municipality FROM auctions WHERE id = ?", auctionId);
        jdbc.update("""
                INSERT INTO auction_structured_ko_matches (
                    auction_id, source_cadastral, source_place_name, source_municipality,
                    input_fingerprint, status, method, rationale, matched_ko_code,
                    dictionary_version, dictionary_source_sha256, normalizer_version,
                    alias_dataset_version, alias_sha256,
                    municipality_alias_dataset_version, municipality_alias_sha256,
                    candidates, resolved_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'fixture-dictionary', ?, 'serbian-name-v1',
                          'fixture-review-v1', ?, 'fixture-review-v1', ?, CAST(? AS jsonb), CURRENT_TIMESTAMP)
                """,
                auctionId,
                auction.get("cadastral"),
                auction.get("place_name"),
                auction.get("municipality"),
                fingerprint(auctionId),
                status,
                method,
                rationale,
                matchedKoCode,
                CentroidTestArtifact.SOURCE_HASH,
                "c".repeat(64),
                "d".repeat(64),
                candidates);
    }

    private UUID selectExternalAddress(UUID reference) {
        UUID geometry = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO spatial_resolution_geometries (
                    id, source_geometry, source_crs_authority, source_crs_code,
                    original_geometry_valid, make_valid_applied
                ) VALUES (?, ST_SetSRID(ST_MakePoint(20.11, 44.11), 4326), 'EPSG', 4326, true, false)
                """, geometry);
        UUID attempt = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("""
                INSERT INTO location_resolution_attempts (
                    id, property_reference_id, resolver, resolver_version, input_fingerprint,
                    source_dataset, source_dataset_version, source_dataset_sha256, source_feature_id,
                    resolution_status, location_precision, geometry_id,
                    confidence_reason, candidate_evidence,
                    attempted_at, completed_at, resolved_at
                ) VALUES (?, ?, 'fixture-address-resolver', 'address-v1', ?,
                          'fixture-addresses', 'fixture-v1', ?, 'address-1',
                          'RESOLVED', 'ADDRESS', ?, 'verified fixture address', '[]'::jsonb, ?, ?, ?)
                """, attempt, reference, "e".repeat(64), "f".repeat(64), geometry, now, now, now);
        jdbc.update("""
                UPDATE current_location_resolutions
                   SET resolution_attempt_id = ?, selected_at = ?, selection_reason = 'verified address fixture'
                 WHERE property_reference_id = ?
                """, attempt, now, reference);
        return attempt;
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private static String fingerprint(long id) {
        return Integer.toHexString(Math.floorMod(Long.hashCode(id), 16)).repeat(64);
    }

    private static Path createArtifact() {
        try {
            return CentroidTestArtifact.create(
                    Files.createTempDirectory("coarse-location-it-"),
                    new ObjectMapper().findAndRegisterModules());
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
