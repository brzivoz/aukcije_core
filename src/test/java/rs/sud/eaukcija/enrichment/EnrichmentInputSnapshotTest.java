package rs.sud.eaukcija.enrichment;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import rs.sud.eaukcija.model.Auction;

class EnrichmentInputSnapshotTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void hashesOnlyTheLocationFieldsConsumedByThePipeline() {
        Auction auction = auction(new BigDecimal("100.00"));
        EnrichmentInputSnapshot first = EnrichmentInputSnapshot.from(auction, objectMapper);

        auction.setStartingPrice(new BigDecimal("999999.99"));
        auction.setCurrentPrice(new BigDecimal("123456.78"));
        auction.setMaxOfferedPrice(new BigDecimal("120000.00"));
        auction.setBidStep(new BigDecimal("1000"));
        auction.setStatus("SOLD");
        auction.setDescription("completely different description");
        auction.setListingFingerprint("f".repeat(64));
        auction.setLastSuccessfulSyncRunId(UUID.randomUUID());
        auction.setAbsenceCount(99);
        auction.setLastSeenAt(Instant.parse("2026-08-24T20:00:00Z"));
        auction.setDetailsFetchedAt(Instant.parse("2026-08-24T20:00:01Z"));
        EnrichmentInputSnapshot sameInput = EnrichmentInputSnapshot.from(auction, objectMapper);

        assertThat(sameInput).isEqualTo(first);
        assertThat(first.canonicalInput().fieldNames())
                .toIterable()
                .containsExactly("schemaVersion", "auctionId", "placeName", "municipality", "cadastral");
        assertThat(first.canonicalInput().has("startingPrice")).isFalse();
        assertThat(first.canonicalInput().has("currentPrice")).isFalse();
        assertThat(first.canonicalInput().has("status")).isFalse();
        assertThat(first.canonicalInput().has("description")).isFalse();
        assertThat(first.canonicalInput().has("listingFingerprint")).isFalse();
        assertThat(first.canonicalInput().has("lastSuccessfulSyncRunId")).isFalse();
        assertThat(first.canonicalInput().has("absenceCount")).isFalse();
        assertThat(first.canonicalInput().has("lastSeenAt")).isFalse();
        assertThat(first.canonicalInput().has("detailsFetchedAt")).isFalse();

        ((ObjectNode) first.canonicalInput()).put("cadastral", "mutated by caller");
        assertThat(first.canonicalInput().path("cadastral").asText()).isEqualTo("ГРАД");
    }

    @Test
    void relevantInputChangeProducesANewHashWithoutMutatingThePriorSnapshot() {
        Auction auction = auction(new BigDecimal("100"));
        EnrichmentInputSnapshot original = EnrichmentInputSnapshot.from(auction, objectMapper);

        auction.setCadastral("Нови КО");
        EnrichmentInputSnapshot changed = EnrichmentInputSnapshot.from(auction, objectMapper);

        assertThat(changed.sha256()).isNotEqualTo(original.sha256());
        assertThat(original.canonicalInput().path("cadastral").asText()).isEqualTo("ГРАД");
        assertThat(changed.canonicalInput().path("cadastral").asText()).isEqualTo("Нови КО");

        auction.setCadastral("ГРАД");
        auction.setPlaceName("Ново насеље");
        assertThat(EnrichmentInputSnapshot.from(auction, objectMapper).sha256())
                .isNotEqualTo(original.sha256());

        auction.setPlaceName("Насеље Б");
        auction.setMunicipality("Нова општина");
        assertThat(EnrichmentInputSnapshot.from(auction, objectMapper).sha256())
                .isNotEqualTo(original.sha256());
    }

    private static Auction auction(BigDecimal price) {
        Auction auction = new Auction();
        auction.setId(29L);
        auction.setAuctionNumber("N29");
        auction.setStartingPrice(price);
        auction.setCadastral("ГРАД");
        auction.setPlaceName("Насеље Б");
        auction.setMunicipality("Општина Б-град");
        auction.setDescription("парцела 1572");
        auction.setFirstSale(false);
        auction.setDetailsFetched(true);
        return auction;
    }
}
