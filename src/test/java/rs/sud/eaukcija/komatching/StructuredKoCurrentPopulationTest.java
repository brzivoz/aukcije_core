package rs.sud.eaukcija.komatching;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import rs.sud.eaukcija.testsupport.PostgisApplication;
import rs.sud.eaukcija.testsupport.PostgisTestContainer;

/** Opt-in measurement over the retained #32 population and official #14 artifact. */
@EnabledIfEnvironmentVariable(named = "KO_STRUCTURED_MATCH_FULL_CORPUS", matches = ".+")
class StructuredKoCurrentPopulationTest {

    @Test
    void reportsAndIdempotentlyReplaysTheCurrentPopulation() throws Exception {
        Path corpusFile = Path.of(requiredEnvironment("KO_STRUCTURED_MATCH_FULL_CORPUS"));
        Path dictionary = Path.of(requiredEnvironment("KO_STRUCTURED_MATCH_FULL_DICTIONARY"));
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        JsonNode corpus = objectMapper.readTree(corpusFile.toFile());
        assertThat(corpus.isArray()).isTrue();

        PostgreSQLContainer<?> postgis = PostgisTestContainer.shared();
        String databaseUrl = PostgisTestContainer.createEmptyDatabase();
        try (ConfigurableApplicationContext context = PostgisApplication.start(
                databaseUrl,
                postgis.getUsername(),
                postgis.getPassword(),
                "--ko.structured-match.dictionary-directory=" + dictionary.toAbsolutePath().normalize())) {
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

            StructuredKoMatchService service = context.getBean(StructuredKoMatchService.class);
            StructuredKoMatchService.RunResult first = service.run();
            StructuredKoMatchService.RunResult replay = service.run();

            assertThat(corpus).hasSize(589);
            assertThat(first.populationCount()).isEqualTo(589);
            assertThat(first.populationCount()).isEqualTo(
                    first.matchedCount() + first.ambiguousCount() + first.notFoundCount() + first.invalidCount());
            assertThat(first.processedCount()).isEqualTo(589);
            assertThat(first.matchedCount()).isEqualTo(566);
            assertThat(first.ambiguousCount()).isEqualTo(21);
            assertThat(first.notFoundCount()).isEqualTo(2);
            assertThat(first.invalidCount()).isZero();
            assertThat(first.matchRatePercent()).isEqualByComparingTo("96.10");
            assertThat(first.sourceGpkgSha256())
                    .isEqualTo("ce983232d50cf445f0c71d45381e1d1d537450135b0b4be237c11c045229d3b3");
            assertThat(first.aliasSha256())
                    .isEqualTo("cfa87e561054de6535f211db7b5546b0a4f13a196fbe5231a5cf5ac1a728c1ed");
            assertThat(first.methodCounts()).containsEntry("EXACT_NORMALIZED_NAME", 453L)
                    .containsEntry("MUNICIPALITY_CONTEXT", 134L)
                    .containsEntry("FUZZY_REVIEW", 2L);
            assertThat(replay.processedCount()).isZero();
            assertThat(replay.unchangedCount()).isEqualTo(589);
            assertThat(replay.matchedCount()).isEqualTo(first.matchedCount());
            assertThat(replay.ambiguousCount()).isEqualTo(first.ambiguousCount());
            assertThat(replay.notFoundCount()).isEqualTo(first.notFoundCount());
            assertThat(replay.invalidCount()).isEqualTo(first.invalidCount());
            System.out.println("FULL_STRUCTURED_KO_MATCH=" + objectMapper.writeValueAsString(first));
            System.out.println("FULL_STRUCTURED_KO_REPLAY=" + objectMapper.writeValueAsString(replay));
            System.out.println("FULL_STRUCTURED_KO_UNRESOLVED=" + objectMapper.writeValueAsString(
                    jdbc.queryForList("""
                            SELECT auction_id, source_cadastral, source_place_name, source_municipality,
                                   status, method, rationale,
                                   (SELECT jsonb_agg(candidate->>'koCode' ORDER BY candidate->>'koCode')
                                      FROM jsonb_array_elements(candidates) candidate) AS candidate_codes
                            FROM auction_structured_ko_matches
                            WHERE status <> 'MATCHED'
                            ORDER BY auction_id
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
