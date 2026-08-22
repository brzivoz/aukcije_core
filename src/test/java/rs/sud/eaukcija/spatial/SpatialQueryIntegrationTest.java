package rs.sud.eaukcija.spatial;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import rs.sud.eaukcija.testsupport.SpatialFixture;

/**
 * Query-behaviour coverage for the spatial work in #20 and #26.
 *
 * <p>Scope note: this asserts what PostGIS <em>returns</em> for a given query
 * over known points, never what the schema looks like. The fixture builds its
 * own scratch table, so any assertion about a geometry column's SRID, its type,
 * or its index would be asserting this test's own DDL back to itself and could
 * not detect missing application or Flyway schema wiring. Those assertions
 * live in {@link SpatialResolutionSchemaIntegrationTest}, against V7's real
 * application schema and production repository query.
 *
 * <p>What is worth proving here, and does not depend on our schema, is that this
 * specific image resolves bounding boxes and spheroidal distances correctly —
 * a wrong answer would silently misplace every auction on the map.
 */
class SpatialQueryIntegrationTest {

    /** Belgrade city centre, the reference point for distance ordering. */
    private static final double BELGRADE_LON = 20.457273;
    private static final double BELGRADE_LAT = 44.787197;

    private static SpatialFixture fixture;

    @BeforeAll
    static void loadFixture() throws SQLException {
        fixture = SpatialFixture.loadPoints("spatial_fixture");
    }

    @AfterAll
    static void dropFixture() throws SQLException {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void aBoundingBoxSelectsOnlyThePlacesInsideIt() throws SQLException {
        // A box around Belgrade only.
        assertThat(fixture.namesInBox(20.2, 44.6, 20.8, 44.9)).containsExactly("VOŽDOVAC");

        // A box around western Serbia only.
        assertThat(fixture.namesInBox(19.4, 43.5, 20.0, 43.9)).containsExactly("ČAJETINA");

        // A box spanning the whole country returns everything.
        assertThat(fixture.namesInBox(18.0, 41.0, 24.0, 47.0))
                .containsExactlyInAnyOrder("DIMITROVGRAD", "ČAJETINA", "VOŽDOVAC");
    }

    @Test
    void aBoundingBoxOutsideSerbiaReturnsNothingRatherThanFailing() throws SQLException {
        assertThat(fixture.namesInBox(2.0, 48.0, 3.0, 49.0)).isEmpty();
    }

    @Test
    void aBoundingBoxEdgeIncludesAPointExactlyOnIt() throws SQLException {
        // ST_Intersects is inclusive of the boundary. #26 pages a viewport by
        // splitting it into boxes, so an exclusive edge would drop auctions
        // that land exactly on a seam.
        var vozdovac = SpatialFixture.PLACES.stream()
                .filter(place -> place.name().equals("VOŽDOVAC")).findFirst().orElseThrow();
        assertThat(fixture.namesInBox(vozdovac.longitude(), vozdovac.latitude(), 20.8, 44.9))
                .contains("VOŽDOVAC");
    }

    @Test
    void distanceOrderingIsNearestFirst() throws SQLException {
        assertThat(fixture.namesByDistanceFrom(BELGRADE_LON, BELGRADE_LAT))
                .containsExactly("VOŽDOVAC", "ČAJETINA", "DIMITROVGRAD");
    }

    @Test
    void distanceIsMeasuredInMetresOnTheSpheroid() throws SQLException {
        // Belgrade centre to Voždovac is a few kilometres. A geometry-typed
        // distance would return degrees here and read as roughly 0.02.
        assertThat(fixture.distanceMetres("VOŽDOVAC", BELGRADE_LON, BELGRADE_LAT))
                .isBetween(1_000.0, 10_000.0);

        // Belgrade to Dimitrovgrad, on the Bulgarian border, is about 271 km by
        // air: 1.774 degrees of latitude and 2.323 of longitude at that
        // latitude work out to hypot(197, 186) km.
        assertThat(fixture.distanceMetres("DIMITROVGRAD", BELGRADE_LON, BELGRADE_LAT))
                .isBetween(265_000.0, 277_000.0);
    }
}
