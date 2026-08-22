package rs.sud.eaukcija.spatial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class BoundingBoxTest {

    @Test
    void rejectsNonFiniteOutOfRangeAndWrappingCoordinates() {
        assertThatThrownBy(() -> new BoundingBox(Double.NaN, 40, 20, 45))
                .hasMessage("minLongitude must be finite");
        assertThatThrownBy(() -> new BoundingBox(-181, 40, 20, 45))
                .hasMessage("longitude must be within [-180, 180]");
        assertThatThrownBy(() -> new BoundingBox(10, -91, 20, 45))
                .hasMessage("latitude must be within [-90, 90]");
        assertThatThrownBy(() -> new BoundingBox(20, 40, 10, 45))
                .hasMessage("minLongitude must be less than maxLongitude");
        assertThatThrownBy(() -> new BoundingBox(10, 45, 20, 45))
                .hasMessage("minLatitude must be less than maxLatitude");
    }

    @Test
    void derivedGeometryBoundsAllowAPointButStillRejectInvalidRanges() {
        assertThat(new GeometryBounds(20.5, 44.7, 20.5, 44.7))
                .isEqualTo(new GeometryBounds(20.5, 44.7, 20.5, 44.7));
        assertThatThrownBy(() -> new GeometryBounds(21, 44, 20, 45))
                .hasMessage("invalid longitude bounds");
    }
}
