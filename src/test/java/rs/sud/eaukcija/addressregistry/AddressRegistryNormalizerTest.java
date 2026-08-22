package rs.sud.eaukcija.addressregistry;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AddressRegistryNormalizerTest {

    @Test
    void parcelNormalizationPreservesIdentitySeparatorsAndDropsOnlyZeroSubparts() {
        assertThat(AddressRegistryNormalizer.parcel("004577 / 0337")).isEqualTo("4577/337");
        assertThat(AddressRegistryNormalizer.parcel("1572/0")).isEqualTo("1572");
        assertThat(AddressRegistryNormalizer.parcel("1572")).isEqualTo("1572");
        assertThat(AddressRegistryNormalizer.parcel("1572-1")).isNull();
    }

    @Test
    void namesAndHouseNumbersFoldBothSerbianScriptsWithoutDiscardingSeparators() {
        assertThat(AddressRegistryNormalizer.name("Чајетина")).isEqualTo("CAJETINA");
        assertThat(AddressRegistryNormalizer.name("Čajetina")).isEqualTo("CAJETINA");
        assertThat(AddressRegistryNormalizer.houseNumber("10 А/2-1")).isEqualTo("10A/2-1");
    }
}
