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
    void canonicalizesEquivalentValuesAndExcludesSyncBookkeeping() {
        Auction auction = auction(new BigDecimal("100.00"));
        EnrichmentInputSnapshot first = EnrichmentInputSnapshot.from(auction, objectMapper);

        auction.setStartingPrice(new BigDecimal("100.0"));
        auction.setLastSuccessfulSyncRunId(UUID.randomUUID());
        auction.setAbsenceCount(99);
        auction.setLastSeenAt(Instant.parse("2026-08-24T20:00:00Z"));
        auction.setDetailsFetchedAt(Instant.parse("2026-08-24T20:00:01Z"));
        EnrichmentInputSnapshot sameInput = EnrichmentInputSnapshot.from(auction, objectMapper);

        assertThat(sameInput).isEqualTo(first);
        assertThat(first.canonicalInput().path("startingPrice").asText()).isEqualTo("100");
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
