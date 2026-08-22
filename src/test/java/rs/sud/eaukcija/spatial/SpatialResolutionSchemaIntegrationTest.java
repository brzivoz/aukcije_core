package rs.sud.eaukcija.spatial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import rs.sud.eaukcija.testsupport.PostgisTestContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class SpatialResolutionSchemaIntegrationTest {

    private static final String DATASET_HASH = "d".repeat(64);

    static final PostgreSQLContainer<?> POSTGIS = PostgisTestContainer.shared();
    private static final String JDBC_URL = PostgisTestContainer.createEmptyDatabase();

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> JDBC_URL);
        registry.add("spring.datasource.username", POSTGIS::getUsername);
        registry.add("spring.datasource.password", POSTGIS::getPassword);
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ParcelIdentityRepository parcelIdentities;

    @Autowired
    private SpatialViewportRepository viewportRepository;

    @BeforeEach
    @AfterEach
    void clearSpatialPopulation() {
        // This class owns a dedicated database inside the shared container, so
        // a future parallel test configuration cannot truncate another suite's fixtures.
        jdbc.execute("TRUNCATE TABLE auctions CASCADE");
        jdbc.execute("TRUNCATE TABLE parcel_identities RESTART IDENTITY CASCADE");
    }

    @Test
    void oneAuctionRetainsManyRawReferencesAgainstOneCanonicalParcelIdentity() {
        insertAuction(2001);

        ParcelIdentityRepository.ParcelIdentity first =
                parcelIdentities.getOrCreate(" 702 013 ", " 001572 \u2044 01-a ");
        String insertedVersion = jdbc.queryForObject("""
                SELECT xmin::text FROM parcel_identities WHERE id = ?
                """, String.class, first.id());
        ParcelIdentityRepository.ParcelIdentity replay =
                parcelIdentities.getOrCreate("702013", "001572/01-A");

        assertThat(replay).isEqualTo(first);
        assertThat(jdbc.queryForObject("""
                SELECT xmin::text FROM parcel_identities WHERE id = ?
                """, String.class, first.id())).isEqualTo(insertedVersion);
        UUID firstReference = insertReference(
                2001, 0, "парц. бр. 001572 ⁄ 01-a", "001572/01-A", first, "parcel:first");
        UUID secondReference = insertReference(
                2001, 1, "001572/01-A", "001572/01-A", first, "parcel:second");

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM parcel_identities", Integer.class)).isOne();
        assertThat(jdbc.queryForList("""
                SELECT id, raw_parcel_number, canonical_parcel_number, parcel_identity_id
                  FROM property_references
                 ORDER BY reference_order
                """))
                .satisfiesExactly(
                        row -> assertThat(row)
                                .containsEntry("id", firstReference)
                                .containsEntry("raw_parcel_number", "парц. бр. 001572 ⁄ 01-a")
                                .containsEntry("canonical_parcel_number", "001572/01-A")
                                .containsEntry("parcel_identity_id", first.id()),
                        row -> assertThat(row)
                                .containsEntry("id", secondReference)
                                .containsEntry("raw_parcel_number", "001572/01-A")
                                .containsEntry("parcel_identity_id", first.id()));
    }

    @Test
    void storesProjectedSourceAndCanonicalWgs84WithoutLosingGeometryType() {
        UUID projectedPoint = UUID.randomUUID();
        String projected = jdbc.queryForObject("""
                SELECT ST_AsEWKT(ST_Transform(ST_SetSRID(ST_MakePoint(22.780484, 43.013322), 4326), 25834))
                """, String.class);
        insertGeometry(projectedPoint, projected, 25834, false, null);

        UUID polygon = UUID.randomUUID();
        insertGeometry(polygon,
                "SRID=4326;POLYGON((20.40 44.70,20.60 44.70,20.60 44.85,20.40 44.85,20.40 44.70))",
                4326, false, null);
        UUID multiPolygon = UUID.randomUUID();
        insertGeometry(multiPolygon, """
                SRID=4326;MULTIPOLYGON(
                  ((19.60 43.65,19.75 43.65,19.75 43.80,19.60 43.80,19.60 43.65)),
                  ((22.70 42.95,22.85 42.95,22.85 43.10,22.70 43.10,22.70 42.95))
                )
                """, 4326, false, null);

        Map<String, Object> transformed = jdbc.queryForMap("""
                SELECT ST_SRID(source_geometry) AS source_srid,
                       ST_SRID(canonical_geometry) AS canonical_srid,
                       GeometryType(source_geometry) AS source_type,
                       GeometryType(canonical_geometry) AS canonical_type,
                       ST_X(canonical_geometry) AS lon,
                       ST_Y(canonical_geometry) AS lat
                  FROM spatial_resolution_geometries WHERE id = ?
                """, projectedPoint);
        assertThat(transformed)
                .containsEntry("source_srid", 25834)
                .containsEntry("canonical_srid", 4326)
                .containsEntry("source_type", "POINT")
                .containsEntry("canonical_type", "POINT");
        assertThat((Double) transformed.get("lon")).isCloseTo(22.780484, offset(0.0000001));
        assertThat((Double) transformed.get("lat")).isCloseTo(43.013322, offset(0.0000001));
        assertThat(jdbc.queryForList("""
                SELECT GeometryType(canonical_geometry)
                  FROM spatial_resolution_geometries
                 WHERE id IN (?, ?)
                 ORDER BY GeometryType(canonical_geometry)
                """, String.class, polygon, multiPolygon))
                .containsExactly("MULTIPOLYGON", "POLYGON");
    }

    @Test
    void rejectsBadSridBoundsEmptyAndUnrecordedInvalidGeometryButAllowsAuditedRepair() {
        assertConstraintViolation(
                "SRID=4326;POINT(20 44)", 25834, false, null,
                "ck_spatial_source_srid");
        assertConstraintViolation(
                "SRID=4326;POINT(181 44)", 4326, false, null,
                "ck_spatial_canonical_bounds");
        assertConstraintViolation(
                "SRID=4326;POINT EMPTY", 4326, false, null,
                "ck_spatial_non_empty");
        String bowTie = "SRID=4326;POLYGON((20 44,21 45,21 44,20 45,20 44))";
        assertConstraintViolation(
                bowTie, 4326, false, null,
                "ck_spatial_canonical_valid");

        UUID repaired = UUID.randomUUID();
        insertGeometry(repaired, bowTie, 4326, true, "Self-intersection repaired after source validation");
        assertThat(jdbc.queryForMap("""
                SELECT original_geometry_valid, make_valid_applied, make_valid_reason,
                       ST_IsValid(canonical_geometry) AS canonical_valid,
                       GeometryType(canonical_geometry) AS canonical_type
                  FROM spatial_resolution_geometries WHERE id = ?
                """, repaired))
                .containsEntry("original_geometry_valid", false)
                .containsEntry("make_valid_applied", true)
                .containsEntry("make_valid_reason", "Self-intersection repaired after source validation")
                .containsEntry("canonical_valid", true)
                .containsEntry("canonical_type", "MULTIPOLYGON");
    }

    @Test
    void attemptsAreAppendOnlyAndCurrentSelectionSupersedesWithoutErasingHistoryOrCache() {
        insertAuction(2002);
        UUID reference = insertStructuredReference(2002, 0, "structured:place");
        UUID coarseGeometry = UUID.randomUUID();
        insertGeometry(coarseGeometry, "SRID=4326;POINT(20.495631 44.770078)", 4326, false, null);
        UUID cache = insertCache(coarseGeometry, "CADASTRAL_MUNICIPALITY", "coarse-v1");
        UUID coarseAttempt = insertResolvedAttempt(
                reference, coarseGeometry, cache, "CADASTRAL_MUNICIPALITY", "coarse-v1", 17L);
        select(reference, coarseAttempt, "best available coarse resolution");

        assertThatThrownBy(() -> jdbc.update("""
                UPDATE location_resolution_attempts SET confidence_reason = 'mutated' WHERE id = ?
                """, coarseAttempt))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("location_resolution_attempts is append-only");
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM location_resolution_attempts WHERE id = ?", coarseAttempt))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("location_resolution_attempts is append-only");

        UUID parcelGeometry = UUID.randomUUID();
        insertGeometry(parcelGeometry, """
                SRID=4326;POLYGON((20.48 44.76,20.50 44.76,20.50 44.78,20.48 44.78,20.48 44.76))
                """, 4326, false, null);
        UUID parcelAttempt = insertResolvedAttempt(
                reference, parcelGeometry, null, "PARCEL", "private-wfs-v1", null);
        select(reference, parcelAttempt, "verified parcel supersedes coarse centroid");

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM location_resolution_attempts WHERE property_reference_id = ?",
                Integer.class, reference)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT resolution_attempt_id FROM current_location_resolutions WHERE property_reference_id = ?",
                UUID.class, reference)).isEqualTo(parcelAttempt);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM location_resolution_cache_records WHERE id = ?",
                Integer.class, cache)).isOne();
        assertThat(jdbc.queryForMap("""
                SELECT resolver, resolver_version, source_dataset, source_dataset_version,
                       source_dataset_sha256, confidence_reason, candidate_evidence::text AS evidence,
                       member_point_count
                  FROM location_resolution_attempts WHERE id = ?
                """, coarseAttempt))
                .containsEntry("resolver", "test-resolver")
                .containsEntry("resolver_version", "coarse-v1")
                .containsEntry("source_dataset", "fixture-centroids")
                .containsEntry("source_dataset_version", "2026-08-23")
                .containsEntry("source_dataset_sha256", DATASET_HASH)
                .containsEntry("confidence_reason", "deterministic fixture match")
                .containsEntry("evidence", "[{\"candidate\": \"702013\"}]")
                .containsEntry("member_point_count", 17L);
    }

    @Test
    void explicitNoneCanBeSelectedWhileALaterFailureCannotReplaceItOrEnterAViewport() {
        insertAuction(2003);
        UUID reference = insertStructuredReference(2003, 0, "structured:none");
        UUID none = insertNonGeometryAttempt(reference, "NONE", true);
        select(reference, none, "explicit no-location result");

        assertThat(jdbc.queryForObject("""
                SELECT resolution_attempt_id
                  FROM current_location_resolutions
                 WHERE property_reference_id = ?
                """, UUID.class, reference)).isEqualTo(none);
        assertThat(viewportRepository.findSelectedWithin(
                new BoundingBox(18, 41, 24, 47), 100)).isEmpty();

        UUID failure = insertNonGeometryAttempt(reference, "ERROR", false);
        assertThatThrownBy(() -> select(reference, failure, "must not replace last valid result"))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("only RESOLVED or NONE attempts may be selected");
        assertThat(jdbc.queryForObject("""
                SELECT resolution_attempt_id
                  FROM current_location_resolutions
                 WHERE property_reference_id = ?
                """, UUID.class, reference)).isEqualTo(none);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM location_resolution_attempts WHERE property_reference_id = ?
                """, Integer.class, reference)).isEqualTo(2);
    }

    @Test
    void boundedRepositoryReturnsStableSelectedPolygonMultiPolygonAndPointResultsUsingGist() {
        UUID polygon = selectedLocation(
                10, 0, "polygon",
                "SRID=4326;POLYGON((20.40 44.70,20.60 44.70,20.60 44.85,20.40 44.85,20.40 44.70))",
                "PARCEL");
        selectedLocation(
                20, 0, "point", "SRID=4326;POINT(20.495631 44.770078)",
                "CADASTRAL_MUNICIPALITY");
        selectedLocation(
                25, 0, "street-point", "SRID=4326;POINT(20.52 44.78)",
                "STREET");
        selectedLocation(
                30, 0, "multipolygon", """
                        SRID=4326;MULTIPOLYGON(
                          ((19.60 43.65,19.75 43.65,19.75 43.80,19.60 43.80,19.60 43.65)),
                          ((22.70 42.95,22.85 42.95,22.85 43.10,22.70 43.10,22.70 42.95))
                        )
                        """, "PARCEL");

        BoundingBox belgrade = new BoundingBox(20.2, 44.6, 20.8, 44.9);
        List<ViewportLocation> first = viewportRepository.findSelectedWithin(belgrade, 100);
        List<ViewportLocation> replay = viewportRepository.findSelectedWithin(belgrade, 100);

        assertThat(first).extracting(ViewportLocation::auctionId).containsExactly(10L, 20L, 25L);
        assertThat(replay).extracting(ViewportLocation::propertyReferenceId)
                .containsExactlyElementsOf(first.stream().map(ViewportLocation::propertyReferenceId).toList());
        assertThat(first.get(0).geometry().getGeometryType()).isEqualTo("Polygon");
        assertThat(first.get(0).centroid().getSRID()).isEqualTo(4326);
        assertThat(first.get(0).geometry().covers(first.get(0).representativePoint())).isTrue();
        assertThat(first.get(0).bounds()).isEqualTo(
                new GeometryBounds(20.4, 44.7, 20.6, 44.85));
        assertThat(first.get(0).resolutionAttemptId()).isEqualTo(polygon);
        assertThat(first.get(2).precision()).isEqualTo(LocationPrecision.STREET);
        assertThat(first.get(2).geometry().getGeometryType()).isEqualTo("Point");
        assertThat(viewportRepository.findSelectedWithin(
                new BoundingBox(18, 41, 24, 47), 2))
                .extracting(ViewportLocation::auctionId)
                .containsExactly(10L, 20L);
        assertThat(viewportRepository.findSelectedWithin(
                new BoundingBox(2, 48, 3, 49), 100)).isEmpty();
        assertThatThrownBy(() -> viewportRepository.findSelectedWithin(belgrade, 0))
                .hasMessage("limit must be between 1 and 5000");
        assertThatThrownBy(() -> viewportRepository.findSelectedWithin(belgrade, 5001))
                .hasMessage("limit must be between 1 and 5000");

        seedRealisticPlanPopulation(20_000, 5);
        List<String> plan = viewportRepository.explainSelectedWithin(belgrade, 100);
        assertThat(String.join("\n", plan))
                .contains("idx_spatial_resolution_geometries_canonical")
                .contains("idx_location_resolution_attempts_geometry")
                .contains("st_intersects")
                .doesNotContain("Seq Scan on location_resolution_attempts");
    }

    @Test
    void schemaContainsNoTenantOrStoredCentroidAndBoundsDuplicates() {
        assertThat(jdbc.queryForList("""
                SELECT table_name
                  FROM information_schema.tables
                 WHERE table_schema = 'public'
                   AND table_name IN (
                       'parcel_identities', 'property_references', 'spatial_resolution_geometries',
                       'location_resolution_cache_records', 'location_resolution_attempts',
                       'current_location_resolutions'
                   )
                 ORDER BY table_name
                """, String.class)).containsExactly(
                        "current_location_resolutions",
                        "location_resolution_attempts",
                        "location_resolution_cache_records",
                        "parcel_identities",
                        "property_references",
                        "spatial_resolution_geometries");
        assertThat(jdbc.queryForList("""
                SELECT column_name
                  FROM information_schema.columns
                 WHERE table_schema = 'public'
                   AND table_name IN (
                       'parcel_identities', 'property_references', 'spatial_resolution_geometries',
                       'location_resolution_cache_records', 'location_resolution_attempts',
                       'current_location_resolutions'
                   )
                   AND (column_name LIKE '%tenant%' OR column_name IN ('centroid', 'bounds'))
                """, String.class)).isEmpty();
    }

    private void assertConstraintViolation(
            String ewkt, int sourceSrid, boolean makeValid, String reason, String constraint) {
        try {
            insertGeometry(UUID.randomUUID(), ewkt, sourceSrid, makeValid, reason);
            throw new AssertionError("expected constraint violation: " + constraint);
        } catch (DataIntegrityViolationException failure) {
            assertThat(failure.getMostSpecificCause().getMessage()).contains(constraint);
        }
    }

    private void insertAuction(long id) {
        jdbc.update("""
                INSERT INTO auctions (id, auction_number, first_sale, details_fetched)
                VALUES (?, ?, false, true)
                """, id, "N" + id);
    }

    private UUID insertReference(
            long auctionId, int order, String rawParcel, String canonicalParcel,
            ParcelIdentityRepository.ParcelIdentity identity, String canonicalKey) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO property_references (
                    id, auction_id, reference_order, reference_type,
                    raw_ko, normalized_ko, ko_code,
                    raw_parcel_number, canonical_parcel_number, parcel_identity_id,
                    source_field, source_offset_start, source_offset_end, raw_evidence,
                    parser_version, extraction_status, canonical_key
                ) VALUES (?, ?, ?, 'PARCEL', ?, ?, ?, ?, ?, ?,
                          'description', 0, ?, ?, 'fixture-parser-v1', 'EXTRACTED', ?)
                """,
                id, auctionId, order,
                "К.О. Димитровград", "DIMITROVGRAD", identity.koCode(),
                rawParcel, canonicalParcel, identity.id(),
                rawParcel.length(), rawParcel, canonicalKey);
        return id;
    }

    private UUID insertStructuredReference(long auctionId, int order, String canonicalKey) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO property_references (
                    id, auction_id, reference_order, reference_type,
                    raw_ko, normalized_ko, source_field, raw_evidence,
                    parser_version, extraction_status, canonical_key
                ) VALUES (?, ?, ?, 'STRUCTURED_LOCATION', 'Вождовац', 'VOZDOVAC',
                          'Place', 'fixture structured place', 'structured-place-v1', 'EXTRACTED', ?)
                """, id, auctionId, order, canonicalKey);
        return id;
    }

    private void insertGeometry(UUID id, String ewkt, int sourceSrid, boolean makeValid, String reason) {
        jdbc.update("""
                WITH source AS (SELECT ST_GeomFromEWKT(?) AS geometry)
                INSERT INTO spatial_resolution_geometries (
                    id, source_geometry, source_crs_authority, source_crs_code,
                    original_geometry_valid, make_valid_applied, make_valid_reason
                )
                SELECT ?, geometry, 'EPSG', ?, ST_IsValid(geometry), ?, ?
                  FROM source
                """, ewkt, id, sourceSrid, makeValid, reason);
    }

    private void seedRealisticPlanPopulation(int geometries, int attemptsPerGeometry) {
        jdbc.update("""
                INSERT INTO auctions (id, auction_number, first_sale, details_fetched)
                SELECT 1000000 + value, 'PLAN-' || value, false, true
                  FROM generate_series(1, ?) AS value
                """, geometries);
        jdbc.update("""
                INSERT INTO property_references (
                    id, auction_id, reference_order, reference_type,
                    source_field, parser_version, extraction_status, canonical_key
                )
                SELECT md5('plan-reference-' || value)::uuid,
                       1000000 + value, 0, 'STRUCTURED_LOCATION',
                       'plan-fixture', 'plan-v1', 'EXTRACTED', 'plan:' || value
                  FROM generate_series(1, ?) AS value
                """, geometries);
        jdbc.update("""
                INSERT INTO spatial_resolution_geometries (
                    id, source_geometry, source_crs_authority, source_crs_code,
                    original_geometry_valid, make_valid_applied
                )
                SELECT md5('plan-geometry-' || value)::uuid,
                       ST_SetSRID(ST_MakePoint(
                           CASE WHEN value <= 10 THEN 20.4 + value * 0.001
                                ELSE 22.0 + (value % 100) * 0.0001 END,
                           CASE WHEN value <= 10 THEN 44.7 + value * 0.001
                                ELSE 43.0 + (value % 100) * 0.0001 END
                       ), 4326),
                       'EPSG', 4326, true, false
                  FROM generate_series(1, ?) AS value
                """, geometries);
        jdbc.update("""
                INSERT INTO location_resolution_attempts (
                    id, property_reference_id,
                    resolver, resolver_version, input_fingerprint,
                    source_dataset, source_dataset_version, source_dataset_sha256,
                    resolution_status, location_precision, geometry_id,
                    confidence_reason, candidate_evidence,
                    attempted_at, completed_at, resolved_at
                )
                SELECT md5('plan-attempt-' || value || '-' || attempt_number)::uuid,
                       md5('plan-reference-' || value)::uuid,
                       'plan-resolver', 'plan-v1',
                       repeat(substr(md5(value || ':' || attempt_number), 1, 1), 64),
                       'plan-dataset', 'plan-v1', repeat('e', 64),
                       'RESOLVED', 'MUNICIPALITY', md5('plan-geometry-' || value)::uuid,
                       'plan fixture', '[]'::jsonb,
                       CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                  FROM generate_series(1, ?) AS value
                 CROSS JOIN generate_series(1, ?) AS attempt_number
                """, geometries, attemptsPerGeometry);
        jdbc.update("""
                INSERT INTO current_location_resolutions (
                    property_reference_id, resolution_attempt_id, selected_at, selection_reason
                )
                SELECT md5('plan-reference-' || value)::uuid,
                       md5('plan-attempt-' || value || '-' || ?)::uuid,
                       CURRENT_TIMESTAMP, 'plan fixture selection'
                  FROM generate_series(1, ?) AS value
                """, attemptsPerGeometry, geometries);
        jdbc.execute("ANALYZE spatial_resolution_geometries");
        jdbc.execute("ANALYZE location_resolution_attempts");
        jdbc.execute("ANALYZE current_location_resolutions");
        jdbc.execute("ANALYZE property_references");
    }

    private UUID insertCache(UUID geometryId, String precision, String resolverVersion) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO location_resolution_cache_records (
                    id, resolver, resolver_version, input_fingerprint,
                    source_dataset, source_dataset_version, source_dataset_sha256, source_feature_id,
                    resolution_status, location_precision, geometry_id,
                    confidence_reason, candidate_evidence, member_point_count, resolved_at
                ) VALUES (?, 'test-resolver', ?, ?, 'fixture-centroids', '2026-08-23', ?, '702013',
                          'RESOLVED', ?, ?, 'deterministic fixture match',
                          '[{\"candidate\":\"702013\"}]'::jsonb, 17, ?)
                """, id, resolverVersion, fingerprint(resolverVersion), DATASET_HASH,
                precision, geometryId, timestamp("2026-08-23T09:00:00Z"));
        return id;
    }

    private UUID insertResolvedAttempt(
            UUID referenceId, UUID geometryId, UUID cacheId,
            String precision, String resolverVersion, Long memberPointCount) {
        UUID id = UUID.randomUUID();
        Timestamp attempted = timestamp("2026-08-23T08:59:00Z");
        Timestamp resolved = timestamp("2026-08-23T09:00:00Z");
        jdbc.update("""
                INSERT INTO location_resolution_attempts (
                    id, property_reference_id, used_cache_record_id,
                    resolver, resolver_version, input_fingerprint,
                    source_dataset, source_dataset_version, source_dataset_sha256, source_feature_id,
                    resolution_status, location_precision, geometry_id,
                    confidence_reason, candidate_evidence, member_point_count,
                    attempted_at, completed_at, resolved_at
                ) VALUES (?, ?, ?, 'test-resolver', ?, ?,
                          'fixture-centroids', '2026-08-23', ?, '702013',
                          'RESOLVED', ?, ?, 'deterministic fixture match',
                          '[{\"candidate\":\"702013\"}]'::jsonb, ?, ?, ?, ?)
                """, id, referenceId, cacheId, resolverVersion, fingerprint(resolverVersion), DATASET_HASH,
                precision, geometryId, memberPointCount, attempted, resolved, resolved);
        return id;
    }

    private void select(UUID referenceId, UUID attemptId, String reason) {
        jdbc.update("""
                INSERT INTO current_location_resolutions (
                    property_reference_id, resolution_attempt_id, selected_at, selection_reason
                ) VALUES (?, ?, ?, ?)
                ON CONFLICT (property_reference_id) DO UPDATE
                SET resolution_attempt_id = EXCLUDED.resolution_attempt_id,
                    selected_at = EXCLUDED.selected_at,
                    selection_reason = EXCLUDED.selection_reason
                """, referenceId, attemptId, timestamp("2026-08-23T09:01:00Z"), reason);
    }

    private UUID insertNonGeometryAttempt(UUID referenceId, String status, boolean resolved) {
        UUID id = UUID.randomUUID();
        Timestamp attempted = timestamp("2026-08-23T08:59:00Z");
        Timestamp completed = timestamp("2026-08-23T09:00:00Z");
        jdbc.update("""
                INSERT INTO location_resolution_attempts (
                    id, property_reference_id,
                    resolver, resolver_version, input_fingerprint,
                    source_dataset, source_dataset_version, source_dataset_sha256,
                    resolution_status, location_precision,
                    confidence_reason, candidate_evidence,
                    attempted_at, completed_at, resolved_at
                ) VALUES (?, ?, 'test-resolver', 'none-v1', ?,
                          'fixture-centroids', '2026-08-23', ?,
                          ?, 'NONE', 'no deterministic location', '[]'::jsonb,
                          ?, ?, ?)
                """, id, referenceId, fingerprint(id.toString()), DATASET_HASH,
                status, attempted, completed, resolved ? completed : null);
        return id;
    }

    private UUID selectedLocation(long auctionId, int order, String key, String ewkt, String precision) {
        insertAuction(auctionId);
        UUID reference = insertStructuredReference(auctionId, order, key);
        UUID geometry = UUID.randomUUID();
        insertGeometry(geometry, ewkt, 4326, false, null);
        UUID attempt = insertResolvedAttempt(reference, geometry, null, precision, key + "-resolver-v1", null);
        select(reference, attempt, "fixture selection");
        return attempt;
    }

    private static String fingerprint(String value) {
        char character = (char) ('a' + Math.floorMod(value.hashCode(), 6));
        return String.valueOf(character).repeat(64);
    }

    private static Timestamp timestamp(String value) {
        return Timestamp.from(Instant.parse(value));
    }
}
