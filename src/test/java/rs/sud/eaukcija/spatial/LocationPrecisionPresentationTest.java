package rs.sud.eaukcija.spatial;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

class LocationPrecisionPresentationTest {

    @Test
    void everyPrecisionHasADistinctConsumerLabelAndOnlyCoarseValuesAreMarkedCoarse() {
        assertThat(Arrays.stream(LocationPrecision.values())
                .map(LocationPrecisionPresentation::labelSr))
                .doesNotContainNull()
                .doesNotHaveDuplicates();
        assertThat(Arrays.stream(LocationPrecision.values())
                .filter(LocationPrecisionPresentation::coarse))
                .containsExactly(
                        LocationPrecision.CADASTRAL_MUNICIPALITY,
                        LocationPrecision.SETTLEMENT,
                        LocationPrecision.MUNICIPALITY);
    }
}
