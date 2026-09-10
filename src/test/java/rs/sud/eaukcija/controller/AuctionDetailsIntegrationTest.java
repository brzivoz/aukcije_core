package rs.sud.eaukcija.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import rs.sud.eaukcija.testsupport.PostgisTestContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@org.springframework.test.annotation.DirtiesContext // This isolated database/context is never reused; release its pool.
class AuctionDetailsIntegrationTest {
    private static final String JDBC_URL = PostgisTestContainer.createEmptyDatabase();
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        var db = PostgisTestContainer.shared();
        registry.add("spring.datasource.url", () -> JDBC_URL);
        registry.add("spring.datasource.username", db::getUsername);
        registry.add("spring.datasource.password", db::getPassword);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper json;
    private static final String DESCRIPTION = "<script>window.__xss=true</script>\n" + "Љубиње Čačak ".repeat(280) + " КРАЈ ОПИСА";

    @BeforeEach void seed() {
        jdbc.execute("TRUNCATE auctions CASCADE");
        jdbc.update("""
                INSERT INTO auctions(id, auction_number, description, short_description, status, category_name,
                    starting_price, estimated_price, end_date, first_sale, details_fetched, executor_name, listing_fingerprint)
                VALUES (51, 'Н51', ?, ?, 'Verified', 'Кућа', 12345.67, 45678.90, '2099-08-28T11:00:00Z', true, true,
                    'PRIVATE_EXECUTOR', repeat('f',64))
                """, DESCRIPTION, "<img src=https://evil.invalid/x> Кратак опис");
    }
    @Test void singleLocalProjectionIsCompleteBoundedAndExcludesInternalAndPersonalFields() throws Exception {
        var response = http.getForEntity("/api/auctions/51/details", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store, private");
        var body = json.readTree(response.getBody());
        assertThat(body.path("description").asText()).isEqualTo(DESCRIPTION);
        assertThat(body.path("startingPrice").decimalValue()).isEqualByComparingTo("12345.67");
        assertThat(body.path("endTime").asText()).isEqualTo("2099-08-28T11:00:00Z");
        assertThat(body.path("statusLabel").asText()).isEqualTo("Проверено на извору");
        assertThat(body.path("detailUrl").asText()).isEqualTo("https://eaukcija.sud.rs/#/aukcije/51");
        assertThat(body.size()).isEqualTo(15);
        assertThat(response.getBody()).hasSizeLessThan(60000).doesNotContain("PRIVATE_EXECUTOR", "fingerprint", "candidate", "payload", "geometry", "snapshot");
        assertThat(jdbc.queryForObject("SELECT status FROM auctions WHERE id=51", String.class)).isEqualTo("Verified");
        // The normal map/table projection does not pre-download descriptions.
        var view = http.getForEntity("/api/auctions/view?bbox=20.2,44.6,20.8,44.9", String.class);
        assertThat(view.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(view.getBody()).doesNotContain("КРАЈ ОПИСА", "evil.invalid", "PRIVATE_EXECUTOR");
    }
    @Test void htmlFallbackEscapesCompleteDescriptionAndPreservesCanonicalNavigation() {
        var response = http.getForEntity("/auctions/51?timeScope=all&page=2&sortBy=startingPrice&sortDir=desc&search=njiva", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store, private");
        assertThat(response.getBody()).contains("&lt;script&gt;", "КРАЈ ОПИСА", "Београд", "13:00 +02:00", "page=2", "search=njiva", "sortDir=desc", "noopener noreferrer")
                .doesNotContain("<script>", "<img ", "PRIVATE_EXECUTOR");
    }
    @Test void missingMetadataAndUnknownSourceValuesStayExplicitWithoutGuessing() throws Exception {
        jdbc.update("UPDATE auctions SET description=NULL, short_description=NULL, category_name=NULL, starting_price=NULL, end_date=NULL, status=? WHERE id=51", "Odd<script>\u202e");
        var body = json.readTree(http.getForObject("/api/auctions/51/details", String.class));
        assertThat(body.path("description").isNull()).isTrue();
        assertThat(body.path("endTime").isNull()).isTrue();
        assertThat(body.path("municipality").isNull()).isTrue();
        assertThat(body.path("category").asText()).isEqualTo("Категорија није наведена");
        assertThat(body.path("statusLabel").asText()).isEqualTo("Непознат изворни статус: Odd<script>");
        assertThat(jdbc.queryForObject("SELECT status FROM auctions WHERE id=51", String.class)).endsWith("\u202e");
        assertThat(http.getForObject("/auctions/51", String.class)).contains("Непознат завршетак", "Цена није наведена", "Пун опис није наведен");
    }
    @Test void rejectsInvalidAndUnboundedIdentitiesAndReturnsPrivateNotFound() {
        for (String id : new String[]{"0", "-1", "01", "99999999999999999999999", "abc", "1,2"}) {
            assertThat(http.getForEntity("/api/auctions/" + id + "/details", String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        var missing = http.getForEntity("/api/auctions/52/details", String.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(missing.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store, private");
    }
}
