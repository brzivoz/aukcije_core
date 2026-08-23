package rs.sud.eaukcija.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import rs.sud.eaukcija.map.MapDataStatus;
import rs.sud.eaukcija.map.MapDataStatusService;

@WebMvcTest(MapDataStatusController.class)
@ActiveProfiles("test")
class MapDataStatusControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private MapDataStatusService service;

    @Test
    void publishesNoStoreVersionAndFreshnessMetadata() throws Exception {
        when(service.status()).thenReturn(new MapDataStatus(
                true,
                "AVAILABLE",
                "coarse-v1/centroids-v2/aaaaaaaaaaaa",
                Instant.parse("2026-08-23T10:00:00Z"),
                false,
                589,
                587,
                null));

        mvc.perform(get("/api/map/status"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.dataVersion")
                        .value("coarse-v1/centroids-v2/aaaaaaaaaaaa"))
                .andExpect(jsonPath("$.lastSuccessfulSync")
                        .value("2026-08-23T10:00:00Z"))
                .andExpect(jsonPath("$.stale").value(false))
                .andExpect(jsonPath("$.populationCount").value(589))
                .andExpect(jsonPath("$.mappedAuctionCount").value(587));
    }
}
