package rs.sud.eaukcija.spatial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ParcelIdentityNormalizerTest {

    @Test
    void canonicalizesSpacingCaseAndSeparatorVariantsWithoutNumericCoercion() {
        assertThat(ParcelIdentityNormalizer.canonicalKoCode("  702 013\u00a0"))
                .isEqualTo("702013");
        assertThat(ParcelIdentityNormalizer.canonicalParcelNumber(" 001572 \u2044 01-a "))
                .isEqualTo("001572/01-A");
        assertThat(ParcelIdentityNormalizer.canonicalParcelNumber("4577\u2011337"))
                .isEqualTo("4577-337");
    }

    @Test
    void missingIdentityPartsAreRejected() {
        assertThatThrownBy(() -> ParcelIdentityNormalizer.canonicalKoCode(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("KO code is required");
        assertThatThrownBy(() -> ParcelIdentityNormalizer.canonicalParcelNumber(" \u00a0 "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("parcel number is required");
    }
}
