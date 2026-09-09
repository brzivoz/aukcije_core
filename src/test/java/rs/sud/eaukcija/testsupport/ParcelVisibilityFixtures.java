package rs.sud.eaukcija.testsupport;

import java.util.List;

/** Deliberately difficult valid cadastral shapes, shared by real PostGIS API/browser regressions. */
public final class ParcelVisibilityFixtures {
    private ParcelVisibilityFixtures() {}

    public record Shape(String name, String wkt) {}

    public static final List<Shape> SHAPES = List.of(
            new Shape("tiny", "POLYGON((20.456 44.789,20.4560001 44.789,20.4560001 44.7890001,20.456 44.7890001,20.456 44.789))"),
            new Shape("narrow", "POLYGON((20.458 44.789,20.4580001 44.789,20.4580001 44.790,20.458 44.790,20.458 44.789))"),
            new Shape("concave", "POLYGON((20.460 44.789,20.462 44.789,20.462 44.791,20.4615 44.791,20.4615 44.7895,20.4605 44.7895,20.4605 44.791,20.460 44.791,20.460 44.789))"),
            new Shape("disjoint", "MULTIPOLYGON(((20.450 44.786,20.451 44.786,20.451 44.787,20.450 44.787,20.450 44.786)),((20.464 44.786,20.4645 44.786,20.4645 44.7865,20.464 44.7865,20.464 44.786)))"),
            new Shape("hole", "POLYGON((20.463 44.790,20.465 44.790,20.465 44.792,20.463 44.792,20.463 44.790),(20.4635 44.7905,20.4635 44.7915,20.4645 44.7915,20.4645 44.7905,20.4635 44.7905))")
    );
}
