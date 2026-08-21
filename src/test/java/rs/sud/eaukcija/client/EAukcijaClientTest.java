package rs.sud.eaukcija.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.math.BigDecimal;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import rs.sud.eaukcija.client.EAukcijaApiTypes.AuctionDetail;
import rs.sud.eaukcija.client.EAukcijaApiTypes.AuctionListData;
import rs.sud.eaukcija.client.EAukcijaApiTypes.AuctionSummary;
import rs.sud.eaukcija.testsupport.Fixtures;

/**
 * Unit coverage for the eAukcija HTTP client against recorded fixtures.
 *
 * <p>Every response is stubbed through {@link MockRestServiceServer}. The client
 * under test is constructed with the same {@link RestTemplate} the stub is bound
 * to, so a regression that reintroduced a live call would fail here rather than
 * quietly hitting the real API.
 */
class EAukcijaClientTest {

    private static final String BASE_URL = "https://eaukcija.test.invalid/WebApi.Proxy/api/EAukcija";

    private EAukcijaClient client;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        client = new EAukcijaClient(BASE_URL, new ObjectMapper(), restTemplate);
        server = MockRestServiceServer.bindTo(restTemplate).build();
    }

    @Test
    void parsesAListingPageAndSendsTheDocumentedRequestShape() {
        server.expect(requestTo(BASE_URL + "/GetAuctionsByCategoryId"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Accept", "application/json"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.CategoryId").value(7))
                .andExpect(jsonPath("$.ItemCount").value(10))
                .andExpect(jsonPath("$.PageCount").value(1))
                .andRespond(withSuccess(
                        Fixtures.read("eaukcija/auctions-by-category-page1.json"), MediaType.APPLICATION_JSON));

        var response = client.getAuctionsByCategory(7, 10, 1);

        assertThat(response.resultCode()).isEqualTo("OK");
        AuctionListData data = response.data();
        assertThat(data.totalCount()).isEqualTo(3);
        assertThat(data.auctions()).hasSize(3);

        AuctionSummary first = data.auctions().get(0);
        assertThat(first.id()).isEqualTo(180466L);
        assertThat(first.auctionNumber()).isEqualTo("Н180466");
        assertThat(first.startingPrice()).isEqualByComparingTo(new BigDecimal("159600.00"));
        assertThat(first.firstSale()).isTrue();
        assertThat(first.shortDescription()).contains("1572", "Димитровград");

        // Nulls in the source must stay null rather than becoming zero.
        assertThat(first.currentPrice()).isNull();
        assertThat(first.maxOfferedPrice()).isNull();
        assertThat(data.auctions().get(1).maxOfferedPrice()).isEqualByComparingTo(new BigDecimal("2695000.00"));

        server.verify();
    }

    @Test
    void ignoresUnknownFieldsSoANewSourceFieldIsNotABreakingChange() {
        server.expect(requestTo(BASE_URL + "/GetAuctionsByCategoryId"))
                .andRespond(withSuccess(
                        Fixtures.read("eaukcija/auctions-by-category-page1.json"), MediaType.APPLICATION_JSON));

        // The first fixture record carries UnmappedFutureField; parsing must succeed.
        assertThat(client.getAuctionsByCategory(7, 10, 1).data().auctions()).isNotEmpty();

        server.verify();
    }

    @Test
    void parsesADetailResponseIncludingItsNestedCategoryAndPlace() {
        server.expect(requestTo(BASE_URL + "/GetImmovablePropertyDetails"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.AuctionId").value(180466))
                .andRespond(withSuccess(
                        Fixtures.read("eaukcija/immovable-property-detail.json"), MediaType.APPLICATION_JSON));

        AuctionDetail detail = client.getImmovablePropertyDetails(180466L).data();

        assertThat(detail.id()).isEqualTo(180466L);
        assertThat(detail.estimatedPrice()).isEqualByComparingTo(new BigDecimal("228000.00"));
        assertThat(detail.bidStep()).isEqualByComparingTo(new BigDecimal("5000.00"));
        assertThat(detail.publicationDate()).isEqualTo("2026-02-24T10:30:00Z");
        assertThat(detail.executorName()).isEqualTo("Јавни извршитељ Петар Петровић");
        assertThat(detail.category().id()).isEqualTo(47);
        assertThat(detail.category().name()).isEqualTo("Земљиште");
        assertThat(detail.place().name()).isEqualTo("Димитровград");
        assertThat(detail.place().zipCode()).isEqualTo("18320");
        assertThat(detail.place().cadastral()).isEqualTo("Димитровград");

        server.verify();
    }

    @Test
    void surfacesAnApplicationLevelErrorEnvelopeWithoutThrowing() {
        server.expect(requestTo(BASE_URL + "/GetAuctionsByCategoryId"))
                .andRespond(withSuccess(Fixtures.read("eaukcija/error-response.json"), MediaType.APPLICATION_JSON));

        var response = client.getAuctionsByCategory(7, 10, 1);

        assertThat(response.resultCode()).isEqualTo("ERROR");
        assertThat(response.resultMessage()).isEqualTo("Грешка приликом обраде захтева");
        assertThat(response.data()).isNull();

        server.verify();
    }

    @Test
    void parsesAnEmptyPageAsAnEmptyListRatherThanNull() {
        server.expect(requestTo(BASE_URL + "/GetAuctionsByCategoryId"))
                .andRespond(withSuccess(Fixtures.read("eaukcija/empty-page.json"), MediaType.APPLICATION_JSON));

        AuctionListData data = client.getAuctionsByCategory(7, 10, 99).data();

        assertThat(data.totalCount()).isZero();
        assertThat(data.auctions()).isEmpty();

        server.verify();
    }

    @Test
    void propagatesATransportFailureInsteadOfReturningAnEmptyResult() {
        server.expect(requestTo(BASE_URL + "/GetAuctionsByCategoryId")).andRespond(withServerError());

        assertThatThrownBy(() -> client.getAuctionsByCategory(7, 10, 1))
                .isInstanceOf(org.springframework.web.client.HttpServerErrorException.class);

        server.verify();
    }

    @Test
    void reportsAMalformedBodyAsAParseFailure() {
        server.expect(requestTo(BASE_URL + "/GetAuctionsByCategoryId"))
                .andRespond(withSuccess("{ this is not json", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.getAuctionsByCategory(7, 10, 1))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to parse API response");

        server.verify();
    }
}
