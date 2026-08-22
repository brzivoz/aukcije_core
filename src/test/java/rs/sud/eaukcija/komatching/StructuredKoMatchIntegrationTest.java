package rs.sud.eaukcija.komatching;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import rs.sud.eaukcija.testsupport.PostgisTestContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class StructuredKoMatchIntegrationTest {

    @ServiceConnection(name = "postgresql")
    static final PostgreSQLContainer<?> POSTGIS = PostgisTestContainer.shared();

    private static final Path DICTIONARY = createDictionary();

    @DynamicPropertySource
    static void matcherProperties(DynamicPropertyRegistry registry) {
        registry.add("ko.structured-match.dictionary-directory", DICTIONARY::toString);
    }

    @Autowired
    private StructuredKoMatchService service;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void resetPopulation() {
        clearPopulation();
        insertAuction(37001, "  чајетина!!!", "Насеље А", "Општина А");
        insertAuction(37002, "ГРАД", "Насеље А", null);
        insertAuction(37003, "Caribrod", "Димитровград", "Димитровград");
        insertAuction(37004, "Cajetinaa", "Naselje A", "Opština A");
        insertAuction(37005, null, "Naselje A", "Opština A");
        insertAuction(37006, "GRAD", "Naselje B", "Opština B-grad");
    }

    @AfterEach
    void clearPopulation() {
        jdbc.update("DELETE FROM structured_ko_match_runs");
        jdbc.update("DELETE FROM auctions");
    }

    @Test
    void persistsPopulationResultsProvenanceCandidatesAndIdempotentReplay() {
        StructuredKoMatchService.RunResult first = service.run();

        assertThat(first.populationCount()).isEqualTo(6);
        assertThat(first.processedCount()).isEqualTo(6);
        assertThat(first.unchangedCount()).isZero();
        assertThat(first.matchedCount()).isEqualTo(3);
        assertThat(first.ambiguousCount()).isEqualTo(1);
        assertThat(first.notFoundCount()).isEqualTo(1);
        assertThat(first.invalidCount()).isEqualTo(1);
        assertThat(first.matchRatePercent()).isEqualByComparingTo("50.00");
        assertThat(first.normalizerVersion()).isEqualTo("serbian-name-v1");

        List<Map<String, Object>> persisted = jdbc.queryForList("""
                SELECT auction_id, status, method, matched_ko_code,
                       dictionary_version, dictionary_source_sha256,
                       normalizer_version, alias_dataset_version, alias_sha256,
                       municipality_alias_dataset_version, municipality_alias_sha256,
                       candidates::text AS candidates
                FROM auction_structured_ko_matches
                ORDER BY auction_id
                """);
        assertThat(persisted).hasSize(6);
        assertThat(persisted).extracting(row -> row.get("status"))
                .containsExactly("MATCHED", "AMBIGUOUS", "MATCHED", "NOT_FOUND", "INVALID", "MATCHED");
        assertThat(persisted).extracting(row -> row.get("method"))
                .containsExactly(
                        "EXACT_NORMALIZED_NAME", "EXACT_NORMALIZED_NAME", "REVIEWED_ALIAS",
                        "FUZZY_REVIEW", "NONE", "MUNICIPALITY_CONTEXT");
        assertThat(persisted.get(1).get("matched_ko_code")).isNull();
        assertThat(persisted.get(1).get("candidates").toString())
                .contains("300001", "300002", "municipalityContextMatch");
        assertThat(persisted.get(2).get("candidates").toString())
                .contains("caribrod-1930", "fixture-reviewer", "fixture://gazette/1930");
        assertThat(persisted.get(3).get("candidates").toString())
                .contains("editDistance", "similarityBasisPoints");
        assertThat(persisted.get(5).get("candidates").toString())
                .contains("opstina-b-grad", "Reviewed municipality fixture", "municipalityAliasReviews");
        assertThat(persisted).allSatisfy(row -> {
            assertThat(row.get("dictionary_version")).isEqualTo(first.dictionaryVersion());
            assertThat(row.get("dictionary_source_sha256")).isEqualTo(first.sourceGpkgSha256());
            assertThat(row.get("normalizer_version")).isEqualTo(first.normalizerVersion());
            assertThat(row.get("alias_dataset_version")).isEqualTo(first.aliasDatasetVersion());
            assertThat(row.get("alias_sha256")).isEqualTo(first.aliasSha256());
            assertThat(row.get("municipality_alias_dataset_version"))
                    .isEqualTo(first.municipalityAliasDatasetVersion());
            assertThat(row.get("municipality_alias_sha256")).isEqualTo(first.municipalityAliasSha256());
        });

        Instant originalResolvedAt = jdbc.queryForObject("""
                SELECT resolved_at FROM auction_structured_ko_matches WHERE auction_id = 37001
                """, OffsetDateTime.class).toInstant();
        String originalFingerprint = jdbc.queryForObject("""
                SELECT input_fingerprint FROM auction_structured_ko_matches WHERE auction_id = 37001
                """, String.class);

        StructuredKoMatchService.RunResult replay = service.run();

        assertThat(replay.processedCount()).isZero();
        assertThat(replay.unchangedCount()).isEqualTo(6);
        assertThat(replay.matchedCount()).isEqualTo(first.matchedCount());
        assertThat(jdbc.queryForObject("""
                SELECT resolved_at FROM auction_structured_ko_matches WHERE auction_id = 37001
                """, OffsetDateTime.class).toInstant()).isEqualTo(originalResolvedAt);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM structured_ko_match_runs", Integer.class)).isEqualTo(2);

        jdbc.update("UPDATE auctions SET place_name = ? WHERE id = ?", "Changed place", 37001L);
        StructuredKoMatchService.RunResult changed = service.run();

        assertThat(changed.processedCount()).isEqualTo(1);
        assertThat(changed.unchangedCount()).isEqualTo(5);
        assertThat(jdbc.queryForObject("""
                SELECT input_fingerprint FROM auction_structured_ko_matches WHERE auction_id = 37001
                """, String.class)).isNotEqualTo(originalFingerprint);
    }

    private void insertAuction(long id, String cadastral, String placeName, String municipality) {
        jdbc.update("""
                INSERT INTO auctions (id, cadastral, place_name, municipality, first_sale, details_fetched)
                VALUES (?, ?, ?, ?, false, true)
                """, id, cadastral, placeName, municipality);
    }

    private static Path createDictionary() {
        try {
            Path root = Files.createTempDirectory("structured-ko-it-");
            return KoDictionaryTestArtifact.create(root, new ObjectMapper().findAndRegisterModules());
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
