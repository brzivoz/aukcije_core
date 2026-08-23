package rs.sud.eaukcija.coarselocation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import rs.sud.eaukcija.komatching.StructuredKoMatchService;
import rs.sud.eaukcija.testsupport.PostgisApplication;
import rs.sud.eaukcija.testsupport.PostgisTestContainer;

/** Opt-in proof over the retained 589-auction corpus and active official #36/#14 artifacts. */
@EnabledIfEnvironmentVariable(named = "COARSE_LOCATION_FULL_CORPUS", matches = ".+")
class CoarseLocationCurrentPopulationTest {

    @Test
    void resolvesAndIdempotentlyReplaysEveryCurrentAuctionAfterFreshKoMatching() throws Exception {
        Path corpusFile = Path.of(requiredEnvironment("COARSE_LOCATION_FULL_CORPUS"));
        Path dictionary = Path.of(requiredEnvironment("COARSE_LOCATION_FULL_DICTIONARY"));
        Path centroids = Path.of(requiredEnvironment("COARSE_LOCATION_FULL_CENTROIDS"));
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        JsonNode corpus = objectMapper.readTree(corpusFile.toFile());
        assertThat(corpus.isArray()).isTrue();

        PostgreSQLContainer<?> postgis = PostgisTestContainer.shared();
        String databaseUrl = PostgisTestContainer.createEmptyDatabase();
        try (ConfigurableApplicationContext context = PostgisApplication.start(
                databaseUrl,
                postgis.getUsername(),
                postgis.getPassword(),
                "--ko.structured-match.dictionary-directory=" + dictionary.toAbsolutePath().normalize(),
                "--coarse.location.centroid-directory=" + centroids.toAbsolutePath().normalize())) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            for (JsonNode auction : corpus) {
                JsonNode place = auction.path("_detalji").path("Place");
                jdbc.update("""
                        INSERT INTO auctions (
                            id, cadastral, place_name, municipality, first_sale, details_fetched
                        ) VALUES (?, ?, ?, ?, false, true)
                        """,
                        auction.path("Id").asLong(),
                        nullableText(place, "Cadastral"),
                        nullableText(place, "Name"),
                        nullableText(place, "Municipality"));
            }

            StructuredKoMatchService.RunResult ko =
                    context.getBean(StructuredKoMatchService.class).run();
            CoarseLocationResolutionService service =
                    context.getBean(CoarseLocationResolutionService.class);
            CoarseLocationResolutionService.RunResult first = service.run();
            CoarseLocationResolutionService.RunResult replay = service.run();

            assertThat(corpus).hasSize(589);
            assertThat(ko.matchedCount()).isEqualTo(587);
            assertThat(ko.ambiguousCount()).isZero();
            assertThat(ko.notFoundCount()).isEqualTo(2);
            assertThat(first.populationCount()).isEqualTo(589);
            assertThat(first.processedCount()).isEqualTo(589);
            assertThat(first.tierCounts())
                    .containsEntry("CADASTRAL_MUNICIPALITY", 587L)
                    .containsEntry("SETTLEMENT", 2L)
                    .containsEntry("MUNICIPALITY", 0L)
                    .containsEntry("NONE", 0L);
            assertThat(first.municipalityAliasKoCount()).isEqualTo(21);
            assertThat(first.structuredKoStatusCounts())
                    .containsEntry("MATCHED", 587L)
                    .containsEntry("AMBIGUOUS", 0L)
                    .containsEntry("NOT_FOUND", 2L)
                    .containsEntry("INVALID", 0L)
                    .containsEntry("MISSING", 0L);
            assertThat(first.extractSourceSha256())
                    .isEqualTo("ce983232d50cf445f0c71d45381e1d1d537450135b0b4be237c11c045229d3b3");
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM current_location_resolutions", Integer.class)).isEqualTo(589);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM location_resolution_attempts
                     WHERE location_precision IN ('PARCEL', 'ADDRESS', 'STREET')
                    """, Integer.class)).isZero();
            assertThat(replay.processedCount()).isZero();
            assertThat(replay.unchangedCount()).isEqualTo(589);
            assertThat(replay.tierCounts()).isEqualTo(first.tierCounts());
            System.out.println("FULL_COARSE_LOCATION_KO_MATCH=" + objectMapper.writeValueAsString(ko));
            System.out.println("FULL_COARSE_LOCATION_RESOLUTION=" + objectMapper.writeValueAsString(first));
            System.out.println("FULL_COARSE_LOCATION_REPLAY=" + objectMapper.writeValueAsString(replay));
            System.out.println("FULL_COARSE_LOCATION_SETTLEMENT_FALLBACKS=" + objectMapper.writeValueAsString(
                    jdbc.queryForList("""
                            SELECT reference.auction_id,
                                   attempt.location_precision,
                                   attempt.source_feature_id,
                                   attempt.member_point_count,
                                   attempt.confidence_reason
                              FROM location_resolution_attempts attempt
                              JOIN property_references reference ON reference.id = attempt.property_reference_id
                             WHERE attempt.location_precision <> 'CADASTRAL_MUNICIPALITY'
                             ORDER BY reference.auction_id
                            """)));
        }
    }

    private static String nullableText(JsonNode parent, String name) {
        JsonNode value = parent.path(name);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required when the current-population test is enabled");
        }
        return value;
    }
}
