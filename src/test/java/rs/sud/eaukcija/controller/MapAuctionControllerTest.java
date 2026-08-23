package rs.sud.eaukcija.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.io.WKTReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import rs.sud.eaukcija.map.MapAuctionRepository;
import rs.sud.eaukcija.map.MapAuctionRequestParser;
import rs.sud.eaukcija.map.MapAuctionRow;
import rs.sud.eaukcija.map.MapAuctionService;
import rs.sud.eaukcija.spatial.LocationPrecision;

@WebMvcTest(MapAuctionController.class)
@Import({MapAuctionRequestParser.class, MapAuctionService.class, MapApiExceptionHandler.class})
@ActiveProfiles("test")
class MapAuctionControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private MapAuctionRepository repository;

    @Test
    void exposesTheGeoJsonContractAndLimitMetadata() throws Exception {
        when(repository.findWithin(any())).thenReturn(List.of(row(11), row(12)));

        mvc.perform(get("/api/map/auctions")
                        .queryParam("bbox", "18,41,24,47")
                        .queryParam("from", "2026-08-23")
                        .queryParam("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/geo+json"))
                .andExpect(header().string("X-Map-Feature-Count", "1"))
                .andExpect(header().string("X-Map-Feature-Limit", "1"))
                .andExpect(header().string("X-Map-Truncated", "true"))
                .andExpect(header().string("Cache-Control", containsString("max-age=60")))
                .andExpect(header().string("Cache-Control", containsString("public")))
                .andExpect(header().string("Vary", containsString("Accept")))
                .andExpect(jsonPath("$.type").value("FeatureCollection"))
                .andExpect(jsonPath("$.numberReturned").value(1))
                .andExpect(jsonPath("$.limit").value(1))
                .andExpect(jsonPath("$.truncated").value(true))
                .andExpect(jsonPath("$.features.length()").value(1))
                .andExpect(jsonPath("$.features[0].type").value("Feature"))
                .andExpect(jsonPath("$.features[0].id").value("11:feature"))
                .andExpect(jsonPath("$.features[0].geometry.type").value("Point"))
                .andExpect(jsonPath("$.features[0].geometry.coordinates[0]").value(20.5))
                .andExpect(jsonPath("$.features[0].properties.auctionId").value(11))
                .andExpect(jsonPath("$.features[0].properties.amount").value(125000.50))
                .andExpect(jsonPath("$.features[0].properties.currency").value("RSD"))
                .andExpect(jsonPath("$.features[0].properties.endTime").value("2026-08-24T10:00:00Z"))
                .andExpect(jsonPath("$.features[0].properties.precision").value("ADDRESS"))
                .andExpect(jsonPath("$.features[0].properties.detailUrl")
                        .value("https://eaukcija.sud.rs/#/aukcije/11"))
                .andExpect(jsonPath("$.features[0].properties.description").doesNotExist())
                .andExpect(jsonPath("$.features[0].properties.sourcePayload").doesNotExist());
    }

    @Test
    void strictApplicationJsonAcceptIsSupportedWhileGeoJsonRemainsTheDefault() throws Exception {
        when(repository.findWithin(any())).thenReturn(List.of());

        mvc.perform(get("/api/map/auctions")
                        .accept(MediaType.APPLICATION_JSON)
                        .queryParam("bbox", "18,41,24,47")
                        .queryParam("from", "2026-08-23"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));

        mvc.perform(get("/api/map/auctions")
                        .queryParam("bbox", "18,41,24,47")
                        .queryParam("from", "2026-08-23"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/geo+json"));
    }

    @Test
    void everyInvalidRequestUsesTheStructuredProblemContract() throws Exception {
        mvc.perform(get("/api/map/auctions")
                        .accept("application/geo+json")
                        .queryParam("bbox", "24,41,18,47"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.title").value("Invalid map request"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("INVALID_MAP_REQUEST"))
                .andExpect(jsonPath("$.field").value("bbox"));
    }

    private static MapAuctionRow row(long id) throws Exception {
        return new MapAuctionRow(
                id + ":feature", id, "Н" + id, new BigDecimal("125000.50"),
                Instant.parse("2026-08-24T10:00:00Z"), "Verified", "Парцела",
                LocationPrecision.ADDRESS, new WKTReader().read("POINT(20.5 44.75)"));
    }
}
