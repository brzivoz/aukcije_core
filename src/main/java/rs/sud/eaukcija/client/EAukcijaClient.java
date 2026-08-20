package rs.sud.eaukcija.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import rs.sud.eaukcija.client.EAukcijaApiTypes.*;

@Component
public class EAukcijaClient {

    private static final Logger log = LoggerFactory.getLogger(EAukcijaClient.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    public EAukcijaClient(
            @Value("${eaukcija.api.base-url}") String baseUrl,
            ObjectMapper objectMapper
    ) {
        this.restTemplate = new RestTemplate();
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
    }

    public ApiResponse<AuctionListData> getAuctionsByCategory(int categoryId, int pageSize, int page) {
        String url = baseUrl + "/GetAuctionsByCategoryId";
        var request = new CategoryRequest(categoryId, pageSize, page);
        return post(url, request, new TypeReference<>() {});
    }

    public ApiResponse<AuctionDetail> getImmovablePropertyDetails(long auctionId) {
        String url = baseUrl + "/GetImmovablePropertyDetails";
        var request = new DetailRequest(auctionId);
        return post(url, request, new TypeReference<>() {});
    }

    private <T> T post(String url, Object body, TypeReference<T> typeRef) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Accept", "application/json");

        HttpEntity<Object> entity = new HttpEntity<>(body, headers);

        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

        try {
            return objectMapper.readValue(response.getBody(), typeRef);
        } catch (Exception e) {
            log.error("Failed to parse response from {}: {}", url, e.getMessage());
            throw new RuntimeException("Failed to parse API response", e);
        }
    }
}
