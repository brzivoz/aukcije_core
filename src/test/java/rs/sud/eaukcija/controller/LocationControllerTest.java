package rs.sud.eaukcija.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import rs.sud.eaukcija.spatial.AuctionLocationRepository;
import rs.sud.eaukcija.spatial.AuctionLocationView;
import rs.sud.eaukcija.spatial.LocationPrecision;

@WebMvcTest(LocationController.class)
@ActiveProfiles("test")
class LocationControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AuctionLocationRepository locations;

    @Test
    void returnsMachineAndHumanReadablePrecisionWithoutImplyingAnAddress() throws Exception {
        AuctionLocationView view = new AuctionLocationView(
                42,
                UUID.randomUUID(),
                UUID.randomUUID(),
                LocationPrecision.CADASTRAL_MUNICIPALITY,
                "Центар катастарске општине",
                true,
                "EXTRACTED",
                true,
                20.5,
                44.5,
                123L,
                "KO_MATCHED_FROM_STRUCTURED_PLACE: fixture",
                "structured-place-coarse-centroid",
                "coarse-location-v1",
                "2026-08-23-fixture",
                Instant.parse("2026-08-23T10:00:00Z"));
        given(locations.findBestByAuctionIds(List.of(42L))).willReturn(Map.of(42L, view));

        mvc.perform(get("/api/locations/42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.precision").value("CADASTRAL_MUNICIPALITY"))
                .andExpect(jsonPath("$.precisionLabelSr").value("Центар катастарске општине"))
                .andExpect(jsonPath("$.coarse").value(true))
                .andExpect(jsonPath("$.extractionStatus").value("EXTRACTED"))
                .andExpect(jsonPath("$.publishable").value(true))
                .andExpect(jsonPath("$.memberPointCount").value(123))
                .andExpect(jsonPath("$.longitude").value(20.5));
    }

    @Test
    void returnsNeedsReviewEvidenceAsVisibleButUnpublishable() throws Exception {
        AuctionLocationView view = new AuctionLocationView(
                43,
                UUID.randomUUID(),
                UUID.randomUUID(),
                LocationPrecision.PARCEL,
                "Парцела",
                false,
                "NEEDS_REVIEW",
                false,
                20.6,
                44.6,
                null,
                "AMBIGUOUS_PARCEL_CANDIDATE",
                "parcel-resolver",
                "parcel-v1",
                "fixture-v1",
                Instant.parse("2026-08-23T10:00:00Z"));
        given(locations.findBestByAuctionIds(List.of(43L))).willReturn(Map.of(43L, view));

        mvc.perform(get("/api/locations/43"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.precision").value("PARCEL"))
                .andExpect(jsonPath("$.extractionStatus").value("NEEDS_REVIEW"))
                .andExpect(jsonPath("$.publishable").value(false));
    }

    @Test
    void returnsNotFoundWhenNoCurrentResolutionExists() throws Exception {
        given(locations.findBestByAuctionIds(List.of(404L))).willReturn(Map.of());

        mvc.perform(get("/api/locations/404"))
                .andExpect(status().isNotFound());
    }
}
