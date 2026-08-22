package rs.sud.eaukcija;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;

import rs.sud.eaukcija.model.Auction;
import rs.sud.eaukcija.repository.AuctionRepository;
import rs.sud.eaukcija.repository.AuctionSpecifications;
import rs.sud.eaukcija.testsupport.Fixtures;
import rs.sud.eaukcija.testsupport.PostgisTestContainer;

/** Proves the migrated schema preserves the existing fixture/repository contract. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class AuctionRepositoryPostgisIntegrationTest {

    @ServiceConnection(name = "postgresql")
    static final PostgreSQLContainer<?> POSTGIS = PostgisTestContainer.shared();

    @Autowired
    private AuctionRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    private JsonNode fixture;

    @BeforeEach
    void loadFixture() throws IOException {
        repository.deleteAllInBatch();
        fixture = new ObjectMapper().readTree(Fixtures.read("auctions-sample.json"));
    }

    @AfterEach
    void clearDatabase() {
        repository.deleteAllInBatch();
    }

    @Test
    void cleanResyncDeduplicatesStableSourceIdsAndPreservesQueryFacets() {
        assertThat(fixture.size()).isEqualTo(86);
        List<Auction> uniqueAuctions = uniqueAuctions(fixture);
        assertThat(uniqueAuctions).hasSize(83);

        repository.saveAllAndFlush(uniqueAuctions);

        assertThat(repository.count()).isEqualTo(83);
        assertOrderedFixtureFacet(
                "municipalities",
                repository.findDistinctMunicipalities(),
                expectedDetailValues(fixture, "Municipality"),
                        "Бајина Башта", "Баточина", "Бачка Топола", "Богатић", "Земун", "Инђија", "Кањижа",
                        "Краљево-град", "Крупањ", "Лебане", "Мали Иђош", "Младеновац", "Нова Варош",
                        "Нови Кнежевац", "Обреновац", "Општина Чока", "Рековац", "Рума", "Севојно", "Сента",
                        "Смедеревска Паланка", "Сремска Митровица-град", "Тител", "Ћићевац", "Чукарица");
        assertOrderedFixtureFacet(
                "place names",
                repository.findDistinctPlaceNames(),
                expectedDetailValues(fixture, "Name"),
                        "Бешка", "Засавица И", "Земун", "Кијево", "Кленак", "Клење", "Лалиновац", "Љубинић",
                        "Мали Иђош", "Мартонош", "Мићуново", "Нови Кнежевац", "Придоли", "Пружатовац", "Рековац",
                        "Рогачица", "Салаш Црнобарски", "Санад", "Севојно", "Селевац", "Сента",
                        "Смедеревска Паланка", "Сремска Митровица", "Ставе", "Тадење", "Ћићевац", "Хртковци",
                        "Челице", "Чортановци", "Чукарица", "Шајкаш");
        assertOrderedFixtureFacet(
                "categories",
                repository.findDistinctCategories(),
                expectedCategoryValues(fixture),
                        "Гаража", "Грађевинско земљиште", "Кућа", "Локал", "Непокретности", "Објекат",
                        "Остали пословни објекат", "Парцела", "Пољопривредно земљиште",
                        "Стамбена зграда са више станова", "Стамбени објекат", "Шумско земљиште");
        assertOrderedFixtureFacet(
                "statuses",
                repository.findDistinctStatuses(),
                expectedRootValues(fixture, "Status"),
                "InPrediction", "Verification", "Verified");
        assertThat(repository.countByDetailsFetched(true)).isEqualTo(83);

        // The H2 transition is a clean re-sync, not a file conversion. Prove a
        // repeated truncate/reload produces the same canonical row set.
        repository.deleteAllInBatch();
        repository.saveAllAndFlush(uniqueAuctions(fixture));
        assertThat(repository.count()).isEqualTo(83);
    }

    @Test
    void pagedSpecificationMatchesTheControllerPathForCyrillicSearchAndNumericRange() {
        repository.saveAllAndFlush(uniqueAuctions(fixture));

        var specification = AuctionSpecifications.withFilters(
                "Кањижа",
                null,
                null,
                "Verified",
                new BigDecimal("160000.00"),
                new BigDecimal("170000.00"),
                true,
                "МАРТОНОШ");
        Page<Auction> page = repository.findAll(
                specification,
                PageRequest.of(0, 25, Sort.by("startingPrice").ascending()));

        assertThat(page.getTotalElements()).isOne();
        assertThat(page.getTotalPages()).isOne();
        assertThat(page.getContent()).extracting(Auction::getId).containsExactly(66391L);
        assertThat(page.getContent()).singleElement().satisfies(auction ->
                assertThat(auction.getStartingPrice()).isEqualByComparingTo("161700.00"));
    }

    @Test
    void concurrentPostgresUpsertsCannotCreateDuplicateSourceIds() throws Exception {
        int writers = 12;
        var executor = Executors.newFixedThreadPool(writers);
        var ready = new CountDownLatch(writers);
        var start = new CountDownLatch(1);
        List<Future<Integer>> writes = new ArrayList<>();

        try {
            for (int i = 0; i < writers; i++) {
                int writer = i;
                writes.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return jdbc.update("""
                            INSERT INTO auctions (id, auction_number, first_sale, details_fetched)
                            VALUES (?, ?, false, false)
                            ON CONFLICT (id) DO UPDATE
                            SET auction_number = EXCLUDED.auction_number
                            """, 1517L, "Н1517-" + writer);
                }));
            }
            ready.await();
            start.countDown();
            for (Future<Integer> write : writes) {
                assertThat(write.get()).isOne();
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(repository.count()).isOne();
        assertThat(repository.findById(1517L).orElseThrow().getAuctionNumber()).startsWith("Н1517-");
    }

    private static List<Auction> uniqueAuctions(JsonNode root) {
        Map<Long, Auction> byId = new LinkedHashMap<>();
        root.forEach(node -> byId.put(node.path("Id").asLong(), toAuction(node)));
        return new ArrayList<>(byId.values());
    }

    private static Auction toAuction(JsonNode node) {
        Auction auction = new Auction();
        auction.setId(node.path("Id").asLong());
        auction.setAuctionNumber(text(node, "AuctionNumber"));
        auction.setStartDate(instant(node, "StartDate"));
        auction.setEndDate(instant(node, "EndDate"));
        auction.setStartingPrice(decimal(node, "StartingPrice"));
        auction.setCurrentPrice(decimal(node, "CurrentPrice"));
        auction.setMaxOfferedPrice(decimal(node, "MaxOfferedPrice"));
        auction.setShortDescription(text(node, "ShortDescription"));
        auction.setStatus(text(node, "Status"));
        auction.setFirstSale(node.path("IsFirstSale").asBoolean());
        auction.setPropertyType(text(node, "PropertyType"));

        JsonNode detail = node.path("_detalji");
        auction.setPublicationDate(instant(detail, "PublicationDate"));
        auction.setEstimatedPrice(decimal(detail, "EstimatedPrice"));
        auction.setBidStep(decimal(detail, "BidStep"));
        auction.setDescription(text(detail, "Description"));
        auction.setExecutorName(text(detail, "ExecutorName"));
        auction.setCategoryName(text(detail.path("Category"), "Name"));
        auction.setPlaceName(text(detail.path("Place"), "Name"));
        auction.setPlaceZipCode(text(detail.path("Place"), "ZipCode"));
        auction.setMunicipality(text(detail.path("Place"), "Municipality"));
        auction.setCadastral(text(detail.path("Place"), "Cadastral"));
        auction.setDetailsFetched(!detail.isMissingNode() && !detail.isNull());
        return auction;
    }

    private static void assertOrderedFixtureFacet(
            String facet,
            List<String> actual,
            List<String> fixtureValues,
            String... expectedPostgresOrder) {
        assertThat(actual)
                .as("%s PostgreSQL order", facet)
                .containsExactly(expectedPostgresOrder);
        assertThat(actual)
                .as("%s values match the source fixture", facet)
                .containsExactlyInAnyOrderElementsOf(fixtureValues);
    }

    private static List<String> expectedDetailValues(JsonNode root, String field) {
        return values(root, node -> text(node.path("_detalji").path("Place"), field));
    }

    private static List<String> expectedCategoryValues(JsonNode root) {
        return values(root, node -> text(node.path("_detalji").path("Category"), "Name"));
    }

    private static List<String> expectedRootValues(JsonNode root, String field) {
        return values(root, node -> text(node, field));
    }

    private static List<String> values(JsonNode root, java.util.function.Function<JsonNode, String> extractor) {
        var values = new java.util.TreeSet<String>();
        root.forEach(node -> {
            String value = extractor.apply(node);
            if (value != null) {
                values.add(value);
            }
        });
        return new ArrayList<>(values);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Instant instant(JsonNode node, String field) {
        String value = text(node, field);
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.decimalValue();
    }
}
