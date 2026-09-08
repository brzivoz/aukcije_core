package rs.sud.eaukcija.rgz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class RgzSourceContractVerifierTest {
    @Test void autoModeUsesAHonestStableEpochAndNeedsAVerifiedContractBeforeParcelNetworking() throws Exception {
        RgzParcelProperties properties = automatic();
        properties.validate();
        assertThat(properties.getDatasetVersion()).isEqualTo(RgzParcelProperties.LOCAL_CACHE_EPOCH);
        assertThat(properties.datasetVersionPolicy()).isEqualTo("PRIVATE_FIRST_OBSERVATION");
        assertThat(properties.networkAllowed()).isFalse();
        assertThat(properties.metadataNetworkAllowed()).isTrue();
        RgzSourceContractVerifier.verifyCapabilities(bytes(RgzMetadataFixture.CAPABILITIES), properties.getFeatureType());
        var contract = RgzSourceContractVerifier.verifySchema(properties, "a".repeat(64),
                bytes(RgzMetadataFixture.SCHEMA), "b".repeat(64));
        properties.installContract(contract);
        assertThat(properties.networkAllowed()).isTrue();
        assertThat(properties.status().sourceContractObservedAt()).isNotNull();
        properties.installContract(new RgzSourceContract(properties.sourceContractKey(), "c".repeat(64), "d".repeat(64), Instant.now()));
        assertThat(properties.getDatasetVersion()).isEqualTo(RgzParcelProperties.LOCAL_CACHE_EPOCH);
        assertThat(properties.getDatasetVersion()).doesNotContain("updateSequence", "not-a-dataset-edition");
    }

    @Test void rejectsUnavailableLayersWrongCrsAndSchemaTypesWithoutPromotingTheContract() {
        var properties = automatic();
        for (String invalid : List.of("<html/>",
                RgzMetadataFixture.CAPABILITIES.replace("dkp:dkp_parcels_weekly_only_utm", "dkp:objekat"),
                RgzMetadataFixture.CAPABILITIES.replace("25834", "3857"),
                RgzMetadataFixture.CAPABILITIES.replace("application/json", "text/html"))) {
            assertThatThrownBy(() -> RgzSourceContractVerifier.verifyCapabilities(bytes(invalid), properties.getFeatureType()))
                    .isInstanceOf(Exception.class);
        }
        for (String invalid : List.of("<html/>", RgzMetadataFixture.SCHEMA.replace("xsd:int", "xsd:string"),
                RgzMetadataFixture.SCHEMA.replace("GeometryPropertyType", "PointPropertyType"),
                RgzMetadataFixture.SCHEMA.replace("parcel_num", "owner_name"))) {
            assertThatThrownBy(() -> RgzSourceContractVerifier.verifySchema(properties, "a".repeat(64), bytes(invalid), "b".repeat(64)))
                    .isInstanceOf(Exception.class);
        }
        assertThat(properties.sourceContractReady()).isFalse();
    }

    @Test void xmlEntitiesAndExternalResourcesAreProhibited() {
        var properties = automatic();
        String attack = "<!DOCTYPE x [<!ENTITY secret SYSTEM 'file:///etc/passwd'>]>"
                + RgzMetadataFixture.CAPABILITIES.replace("application/json", "&secret;");
        assertThatThrownBy(() -> RgzSourceContractVerifier.verifyCapabilities(bytes(attack), properties.getFeatureType()))
                .isInstanceOf(org.xml.sax.SAXParseException.class);
        assertThat(properties.sourceContractReady()).isFalse();
    }

    @Test void changingDatasetOrEndpointCannotReuseAnotherSourcesDiscoveredPins() {
        var properties = automatic();
        properties.installContract(new RgzSourceContract(properties.sourceContractKey(), "a".repeat(64), "b".repeat(64), Instant.now()));
        properties.setDatasetVersion("operator-edition-2");
        assertThat(properties.sourceContractReady()).isFalse();
        assertThat(properties.datasetVersionPolicy()).isEqualTo("OPERATOR_PINNED");
    }

    private static RgzParcelProperties automatic() {
        var properties = new RgzParcelProperties();
        properties.setEnabled(true);
        properties.setAutoConfigure(true);
        return properties;
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
