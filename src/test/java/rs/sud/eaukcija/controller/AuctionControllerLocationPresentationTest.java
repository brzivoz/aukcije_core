package rs.sud.eaukcija.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import rs.sud.eaukcija.model.Auction;
import rs.sud.eaukcija.repository.AuctionRepository;
import rs.sud.eaukcija.service.SyncService;
import rs.sud.eaukcija.spatial.AuctionLocationRepository;
import rs.sud.eaukcija.spatial.AuctionLocationView;
import rs.sud.eaukcija.spatial.LocationPrecision;

@WebMvcTest(AuctionController.class)
@ActiveProfiles("test")
class AuctionControllerLocationPresentationTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AuctionRepository auctions;

    @MockitoBean
    private SyncService syncService;

    @MockitoBean
    private AuctionLocationRepository locations;

    @Test
    @SuppressWarnings("unchecked")
    void listUiLabelsCoarseCentroidsAndShowsTheHonestyNotice() throws Exception {
        Auction auction = new Auction();
        auction.setId(42L);
        auction.setAuctionNumber("Н42");
        auction.setMunicipality("Општина А");
        auction.setPlaceName("Место А");
        given(auctions.findAll(any(Specification.class), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(auction), PageRequest.of(0, 25), 1));
        given(auctions.findDistinctMunicipalities()).willReturn(List.of("Општина А"));
        given(auctions.findDistinctPlaceNames()).willReturn(List.of("Место А"));
        given(auctions.findDistinctCategories()).willReturn(List.of());
        given(auctions.findDistinctStatuses()).willReturn(List.of());
        given(locations.findBestByAuctionIds(List.of(42L))).willReturn(Map.of(42L, new AuctionLocationView(
                42,
                UUID.randomUUID(),
                UUID.randomUUID(),
                LocationPrecision.SETTLEMENT,
                "Центар насеља",
                true,
                "EXTRACTED",
                true,
                20.5,
                44.5,
                55L,
                "SETTLEMENT_EXACT_NAME: fixture",
                "structured-place-coarse-centroid",
                "coarse-location-v1",
                "fixture-v1",
                Instant.parse("2026-08-23T10:00:00Z"))));

        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Центар насеља")))
                .andExpect(content().string(containsString(
                        "приближна локација области, не адреса, улица или парцела")));
    }
}
