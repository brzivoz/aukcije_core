package rs.sud.eaukcija.spatial;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import rs.sud.eaukcija.controller.LocationRefinementController;
import rs.sud.eaukcija.operations.LocationRefinementStatusController;

class LocationRefinementControllerTest {
    @Test void locationDiagnosticIsNoStoreAndDistinguishesProcessingFromPrecision() throws Exception {
        var repository = mock(LocationRefinementRepository.class);
        when(repository.find(55)).thenReturn(new LocationRefinementRepository.Report(55, false,
                "SUCCEEDED", "Адресни регистар није увезен.", List.of()));
        var mvc = MockMvcBuilders.standaloneSetup(new LocationRefinementController(repository)).build();
        mvc.perform(get("/api/locations/55/refinement")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.processingStatus").value("SUCCEEDED"))
                .andExpect(jsonPath("$.registryAvailable").value(false));
        mvc.perform(get("/api/locations/999/refinement")).andExpect(status().isNotFound());
    }

    @Test void operatorMetricsAreLoopbackOnlyAndRedactUnavailableEvidence() throws Exception {
        var repository = mock(LocationRefinementRepository.class);
        var mvc = MockMvcBuilders.standaloneSetup(new LocationRefinementStatusController(repository)).build();
        mvc.perform(get("/api/operator/location-refinement").with(request -> {
            request.setRemoteAddr("198.51.100.5"); return request;
        }).header("X-Forwarded-For", "127.0.0.1")).andExpect(status().isForbidden());
        verifyNoInteractions(repository);
        when(repository.statistics()).thenReturn(Map.of("auctionPrecisionCounts", Map.of("ADDRESS", 1)));
        mvc.perform(get("/api/operator/location-refinement")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.auctionPrecisionCounts.ADDRESS").value(1));
        when(repository.statistics()).thenThrow(new IllegalStateException("private secret database payload"));
        mvc.perform(get("/api/operator/location-refinement")).andExpect(status().isServiceUnavailable())
                .andExpect(content().json("{\"code\":\"REFINEMENT_EVIDENCE_UNAVAILABLE\"}"));
    }
}
