package rs.sud.eaukcija.map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.util.UriComponentsBuilder;
import rs.sud.eaukcija.client.EAukcijaClient;
import rs.sud.eaukcija.rgz.RgzParcelClient;
import rs.sud.eaukcija.filter.*;
import rs.sud.eaukcija.history.*;
import rs.sud.eaukcija.testsupport.*;
import rs.sud.eaukcija.sync.persistence.SourcePublicationFixture;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class CatalogueChangesIntegrationTest {
    private static final String URL = PostgisTestContainer.createEmptyDatabase();
    private static final Instant T = Instant.parse("2026-08-24T10:00:00Z");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        var db = PostgisTestContainer.shared(); r.add("spring.datasource.url", () -> URL);
        r.add("spring.datasource.username", db::getUsername); r.add("spring.datasource.password", db::getPassword);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource ds;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager tx;
    @Autowired CatalogueChangesService changes;
    @Autowired SourceHistoryService history;
    @Autowired AuctionSearchRepository search;
    @Autowired MapAuctionService map;
    @Autowired AuctionResultsService results;
    @Autowired TestRestTemplate http;
    @MockitoBean EAukcijaClient source;
    @MockitoBean RgzParcelClient rgz;
    SourcePublicationFixture fixture;
    @BeforeEach void setup() { clear(); fixture = new SourcePublicationFixture(ds, json, tx); }
    @AfterEach void cleanup() { verifyNoInteractions(source, rgz); clear(); }
    private void clear() { jdbc.execute("TRUNCATE auctions, sync_runs, spatial_resolution_geometries CASCADE"); }
    private AuctionFilters filters(String query, Instant at) {
        return changes.prepare(new AuctionFilterParser(null, Clock.fixed(at, ZoneOffset.UTC))
                .parse(UriComponentsBuilder.fromUriString("/?" + query).build().getQueryParams(), java.util.Set.of()));
    }
    private String since(SourceHistoryService.Frame frame) {
        return "since=publication&publication=" + ChangeCriteria.encode(frame.publication()) + "&sinceAt=" + frame.evaluatedAt();
    }
    @Test void mondayPersistsThroughTuesdayUnchangedAndReversionWithDisjointBucketsAndSafeEvidence() throws Exception {
        var base = fixture.publish(T, fixture.candidate(1, "PRIVATE_A"), fixture.candidate(2, "PRIVATE_A"));
        fixture.publish(T.plusSeconds(86400), fixture.candidate(1, "PRIVATE_B"), fixture.candidate(2, "PRIVATE_A"), fixture.candidate(3, "PRIVATE_C"));
        fixture.publish(T.plusSeconds(86401), fixture.candidate(1, "PRIVATE_A"), fixture.candidate(2, "PRIVATE_A"), fixture.candidate(3, "PRIVATE_D"));
        var upper = fixture.publish(T.plusSeconds(172800), fixture.candidate(1, "PRIVATE_A"), fixture.candidate(2, "PRIVATE_A"), fixture.candidate(3, "PRIVATE_D"));
        var f = filters(since(base), upper.evaluatedAt());
        assertThat(search.page(f, search.count(f))).extracting(a -> a.getId()).containsExactly(1L, 3L);
        assertThat(search.count(f.withChanges(f.changes().kind("new")))).isOne();
        assertThat(search.count(f.withChanges(f.changes().kind("updated")))).isOne();
        var evidence = changes.display(List.of(1L, 2L, 3L), f);
        assertThat(evidence.get(1L).reverted()).isTrue();
        assertThat(evidence.get(1L).fields()).containsExactly("DESCRIPTION");
        assertThat(evidence.get(1L).sourceChanges()).isEqualTo(2);
        assertThat(evidence.get(3L).bucket()).isEqualTo("NEW");
        assertThat(evidence.get(3L).reverted()).isFalse();
        assertThat(evidence.get(2L).bucket()).isNull();
        assertThat(json.writeValueAsString(evidence)).doesNotContain("PRIVATE_", "canonical_payload", "description\":");
        assertThat(search.count(filters(since(base) + "&minPrice=101", upper.evaluatedAt()))).isZero();
        var request = new MapAuctionRequest(new rs.sud.eaukcija.spatial.BoundingBox(20, 44, 21, 45), f, 1);
        assertThat(map.findAuctions(request).counts().filteredAuctionCount()).isEqualTo(2);
        assertThat(map.findAuctions(request).counts().unmappedAuctionCount()).isEqualTo(2);
        assertThat(map.findAuctions(request).sourceFrame()).isEqualTo(upper);
        var model = results.model(f, null);
        assertThat(model).containsEntry("newCount", 1L).containsEntry("updatedCount", 1L).containsEntry("sourceFrame", upper);
        var response = http.getForEntity("/api/auctions/view?bbox=20,44,21,45&" + since(base), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        var view = json.readTree(response.getBody());
        assertThat(view.path("map").path("counts").path("filteredAuctionCount").asInt()).isEqualTo(2);
        assertThat(view.path("resultsHtml").asText()).contains("враћене", "data-source-frame", "Почетна цена").doesNotContain("PRIVATE_");
    }
    @Test void exactRepresentationAndUnchangedRefreshAreQuietLiveBiddingIsOptInAndDetailStatusDatesAreMeaningful() throws Exception {
        var base = fixture.publish(T, fixture.candidate(1, "A"), fixture.candidate(2, "A"), fixture.candidate(3, "A"));
        var reviewed = history.revisions(List.of(1L, 2L, 3L), base.publication(), T).stream().map(SourceHistoryService.Revision::review).toList();
        var upper = fixture.publish(T.plusSeconds(1), fixture.candidate(1, "A", "100.00", "10", SourcePublicationFixture.END, "Verified"),
                fixture.candidate(2, "A", "100", "11", SourcePublicationFixture.END, "Verified"), fixture.candidate(3, "A"));
        assertThat(search.count(filters(since(base), upper.evaluatedAt()))).isZero();
        assertThat(search.page(filters(since(base) + "&liveBidding=true", upper.evaluatedAt()), 1)).extracting(a -> a.getId()).containsExactly(2L);
        assertThat(changes.reviewed(reviewed, upper, false).values()).extracting(CatalogueChangesService.Evidence::reviewState).containsOnly("UNCHANGED");
        assertThat(changes.reviewed(reviewed, upper, true).get(2L).reviewState()).isEqualTo("CHANGED");
        assertThat(history.revisions(List.of(3L), upper.publication(), T).get(0).review()).isEqualTo(reviewed.get(2));
        var finalFrame = fixture.publish(T.plusSeconds(2), fixture.candidate(1, "B", "101", "10", SourcePublicationFixture.END.plusSeconds(60), "Completed"));
        assertThat(changes.display(List.of(1L), filters(since(base), finalFrame.evaluatedAt())).get(1L).fields())
                .contains("DESCRIPTION", "STARTING_PRICE", "SOURCE_STATUS", "END_DATE");
        // An application enrichment/normalization-only row update does not fabricate source activity.
        jdbc.update("UPDATE auctions SET short_description='application-only' WHERE id=3");
        assertThat(changes.reviewed(reviewed, finalFrame, false).get(3L).sourceChanges()).isZero();
    }
    @Test void perAuctionBaselinesIncludeElapsedEndAbsenceReopenAndNeverAcknowledgeUnseenPublications() throws Exception {
        var base = fixture.publish(T, fixture.candidate(1, "A", "100", "10", T.plusSeconds(10), "Verified"), fixture.candidate(2, "A"));
        var a = history.revisions(List.of(1L), base.publication(), T).get(0).review();
        var later = fixture.publish(T.plusSeconds(1), fixture.candidate(1, "B", "100", "10", T.plusSeconds(10), "Verified"), fixture.candidate(2, "B"));
        var b = history.revisions(List.of(2L), later.publication(), later.evaluatedAt()).get(0).review();
        var ending = history.capture(T.plusSeconds(10));
        assertThat(changes.reviewed(List.of(a, b), ending, false).get(1L).reviewState()).isEqualTo("CHANGED");
        assertThat(changes.reviewed(List.of(a, b), ending, false).get(1L).elapsedEnd()).isTrue();
        assertThat(changes.reviewed(List.of(a, b), ending, false).get(2L).reviewState()).isEqualTo("UNCHANGED");
        var displayed = history.revisions(List.of(1L), later.publication(), T.plusSeconds(1)).get(0).review();
        var elapsed = changes.reviewed(List.of(displayed), ending, false).get(1L);
        assertThat(elapsed.sourceChanges()).isZero(); assertThat(elapsed.elapsedEnd()).isTrue();
        assertThat(search.count(filters("search=not-matching", ending.evaluatedAt()))).isZero();
        assertThat(changes.reviewed(List.of(a), ending, false).get(1L).endedByDate()).isTrue();
        fixture.publish(T.plusSeconds(11)); var absent = fixture.publish(T.plusSeconds(12));
        assertThat(changes.reviewed(List.of(a, b), absent, false).get(2L).explanation()).contains("одсуство");
        var reopened = fixture.publish(T.plusSeconds(13), fixture.candidate(2, "B"));
        assertThat(changes.reviewed(List.of(b), reopened, false).get(2L).lifecycle().transition()).isEqualTo("REOPENED");
        // A request pinned to an older displayed frame cannot inspect the newer changes.
        assertThat(changes.reviewed(List.of(b), later, false).get(2L).reviewState()).isEqualTo("UNCHANGED");
    }
    @Test void unavailableForeignFutureLegacyCoverageAndRequestBoundsNeverClaimZeroChanges() throws Exception {
        jdbc.update("INSERT INTO auctions(id, end_date, details_fetched, first_sale) VALUES (1, '2099-01-01', false, false)");
        var base = fixture.publish(T, fixture.candidate(1, "legacy"));
        var upper = fixture.publish(T.plusSeconds(1), fixture.candidate(1, "legacy"));
        var origin = new SourceHistoryService.Reference(base.publication().lineage(), 0, null);
        var originFrame = new SourceHistoryService.Frame(origin, null, T, null, null, "PARTIAL_PRE_HISTORY");
        assertThat(search.count(filters(since(originFrame), upper.evaluatedAt()))).isZero();
        for (String query : List.of("since=date&sinceAt=2020-01-01T00:00:00Z", "since=date&sinceAt=2099-01-01T00:00:00Z",
                since(base).replace(base.publication().lineage().toString(), UUID.randomUUID().toString()),
                since(base).replace(base.publication().runId().toString(), UUID.randomUUID().toString()))) {
            var response = http.getForEntity("/?" + query, String.class);
            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).contains("НЕ нула промена", "data-auction-id=\"1\"");
            var geo = json.readTree(http.getForObject("/api/map/auctions?bbox=20,44,21,45&" + query, String.class));
            assertThat(geo.path("comparison").path("problem").isTextual()).isTrue();
            assertThat(geo.path("comparison").path("newCount").isNull()).isTrue();
            assertThat(geo.path("counts").path("filteredAuctionCount").asInt()).isOne();
        }
        var review = history.revisions(List.of(1L), upper.publication(), upper.evaluatedAt()).get(0).review();
        var foreign = new SourceHistoryService.Review(1, new SourceHistoryService.Reference(UUID.randomUUID(), review.revision().sequence(), review.revision().runId()), T, review.comparisonPolicy());
        assertThat(changes.reviewed(List.of(foreign), upper, false).get(1L).reviewState()).isEqualTo("UNAVAILABLE");
        assertThat(changes.reviewed(List.of(foreign), upper, false).get(1L).sourceChanges()).isNull();
        var missing = new SourceHistoryService.Review(999, review.revision(), T, review.comparisonPolicy());
        var unavailable = changes.reviewed(List.of(missing), upper, false).get(999L);
        assertThat(unavailable.reviewState()).isEqualTo("UNAVAILABLE");
        assertThat(unavailable.review()).isNull(); assertThat(unavailable.endAt()).isNull();
        assertThatThrownBy(() -> changes.reviewed(java.util.Collections.nCopies(201, review), upper, false)).isInstanceOf(InvalidMapRequestException.class);
        assertThatThrownBy(() -> changes.reviewed(List.of(review, review), upper, false)).isInstanceOf(InvalidMapRequestException.class);
        var response = http.postForEntity("/api/auctions/reviews", Map.of("frame", upper, "reviews", List.of(review)), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getCacheControl()).contains("no-store", "private");
    }
    @Test void rollingDurationsAndRepeatableReadCaptureStayPinnedDuringConcurrentPublication() throws Exception {
        var base = fixture.publish(T, fixture.candidate(1, "A"));
        var transaction = new org.springframework.transaction.support.TransactionTemplate(tx);
        transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.executeWithoutResult(s -> {
            var prepared = filters("since=24h", T.plusSeconds(86400));
            assertThat(prepared.changes().window().lower()).isEqualTo(base.publication());
            try (var executor = new AutoCloseableExecutor()) {
                executor.executor.submit(() -> { fixture.publish(T.plusSeconds(1), fixture.candidate(1, "B")); return null; }).get();
                assertThat(results.model(prepared, null)).containsEntry("sourceFrame", prepared.changes().window().upper());
                assertThat(search.count(prepared)).isZero();
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        assertThat(search.count(filters("since=24h", T.plusSeconds(86400)))).isOne();
    }
    @Test void migratedCatalogueWithoutPublicationsExplainsBootstrapAndDoesNotInventASevenDayBaseline() throws Exception {
        jdbc.update("INSERT INTO auctions(id, end_date, details_fetched, first_sale) VALUES (1, '2099-01-01', false, false)");
        var pending = filters("since=7d", T);
        var summary = changes.summary(pending);
        assertThat(pending.changes().window().upper().publication().sequence()).isZero();
        assertThat(pending.changes().window().upper().earliestPublication()).isNull();
        assertThat(summary.problem()).isEqualTo("HISTORICAL_COVERAGE_UNAVAILABLE");
        assertThat(summary.newCount()).isNull(); assertThat(summary.updatedCount()).isNull();
        assertThat(search.count(pending)).isOne();
        assertThat(summary.notice()).contains("Историја промена још није успостављена", "Освежи све податке",
                "поновно учитавање карте не успоставља историју", "најмање седам дана", "Моја тачка", "НЕ нула промена");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_publications", Long.class)).isZero();

        var first = fixture.publish(T.plusSeconds(1), fixture.candidate(1, "legacy"));
        var tooEarly = changes.summary(filters("since=7d", T.plusSeconds(2)));
        assertThat(tooEarly.problem()).isEqualTo("HISTORICAL_COVERAGE_UNAVAILABLE");
        assertThat(tooEarly.notice()).contains("Прва подржана публикација", "период почиње пре доступне историје",
                "Ново преузимање не може попунити ранију историју");
        // A checkpoint can be used immediately, without claiming coverage for the missing preceding week.
        assertThat(filters(since(first), T.plusSeconds(2)).changes().usable()).isTrue();
        assertThat(filters("since=7d", first.publishedAt().plusSeconds(7 * 86400)).changes().usable()).isTrue();
    }
    @Test void bootstrapLifecycleWithoutObservedSourceContentCannotOfferAnInvalidReviewAcknowledgement() throws Exception {
        jdbc.update("INSERT INTO auctions(id, end_date, details_fetched, first_sale) VALUES (1, '2020-01-01', false, false)");
        var first = fixture.publish(T, fixture.candidate(2, "source"));
        var legacy = history.revisions(List.of(1L), first.publication(), T).get(0);
        assertThat(legacy.lifecycle()).isNotNull();
        assertThat(legacy.review().comparisonPolicy()).isNull();
        var evidence = changes.display(List.of(1L, 2L), filters("timeScope=all", T));
        assertThat(evidence.get(1L).coverage()).isEqualTo("UNAVAILABLE");
        assertThat(evidence.get(1L).review()).isNull();
        assertThat(evidence.get(2L).review()).isNotNull();
        var html = http.getForObject("/?timeScope=all", String.class);
        assertThat(html).doesNotContain("data-review=\"{&quot;auctionId&quot;:1,");
    }
    @Test void policyMaintenanceIsNotAnUpdateAndItsCoverageGapCannotMasqueradeAsZeroActivity() throws Exception {
        var base = fixture.publish(T, true, fixture.candidate(1, "A"));
        var oldReview = history.revisions(List.of(1L), base.publication(), T).get(0).review();
        var upper = fixture.publish(T.plusSeconds(1), fixture.candidate(1, "A"));
        var f = filters(since(base), upper.evaluatedAt());
        assertThat(search.count(f)).isZero();
        var summary = changes.summary(f);
        assertThat(summary.newCount()).isZero(); assertThat(summary.updatedCount()).isZero();
        assertThat(summary.incompleteEvidence()).isTrue();
        assertThat(summary.notice()).contains("не доказују нулу активности", "политике");
        var review = changes.reviewed(List.of(oldReview), upper, false).get(1L);
        assertThat(review.reviewState()).isEqualTo("UNAVAILABLE");
        assertThat(review.sourceChanges()).isNull();
        assertThat(review.review().comparisonPolicy()).isEqualTo(SourceComparisonPolicy.VERSION);
    }
    @Test void publicationCommittingAfterEvaluationSelectionStillProducesAUsableExactDisplayedBoundary() throws Exception {
        var published = fixture.publish(T.plusSeconds(1), fixture.candidate(1, "A"));
        var displayed = history.capture(T); // asOf was selected just before the publication committed.
        var reviewed = history.revisions(List.of(1L), published.publication(), T).get(0).review();
        assertThat(filters(since(displayed), T.plusSeconds(2)).changes().usable()).isTrue();
        assertThat(changes.reviewed(List.of(reviewed), history.capture(T.plusSeconds(2)), false).get(1L).reviewState()).isEqualTo("UNCHANGED");
    }
    @Test void elapsedEndAlreadyAcknowledgedDoesNotBecomeANewChangeWhenLaterAudited() throws Exception {
        var base = fixture.publish(T, fixture.candidate(1, "A", "100", "10", T.plusSeconds(1), "Verified"));
        var review = history.revisions(List.of(1L), base.publication(), T.plusSeconds(2)).get(0).review();
        var audited = fixture.publish(T.plusSeconds(3), fixture.candidate(1, "A", "100", "10", T.plusSeconds(1), "Verified"));
        var result = changes.reviewed(List.of(review), audited, false).get(1L);
        assertThat(result.endedByDate()).isTrue();
        assertThat(result.sourceChanges()).isZero(); assertThat(result.lifecycleChanges()).isZero();
        assertThat(result.reviewState()).isEqualTo("UNCHANGED");
    }
    @Test void sixHundredIdentityFixtureUsesBoundedIndexedBatchesWithMapTableViewportAndLimitParity() throws Exception {
        var candidates = java.util.stream.IntStream.range(10000, 10600).mapToObj(id -> fixture.candidate(id, "A", "100", "10",
                id < 10020 ? T.minusSeconds(1) : SourcePublicationFixture.END, "Verified"))
                .toArray(rs.sud.eaukcija.sync.persistence.AuctionPromotionCandidate[]::new);
        var base = fixture.publish(T, candidates);
        var ids = java.util.stream.LongStream.range(10000, 10200).boxed().toList();
        var reviews = history.revisions(ids, base.publication(), T).stream().map(SourceHistoryService.Revision::review).toList();
        var updated = java.util.stream.IntStream.range(10000, 10200).mapToObj(id -> fixture.candidate(id, "B", "100", "10",
                id < 10020 ? T.minusSeconds(1) : SourcePublicationFixture.END, "Verified"))
                .toArray(rs.sud.eaukcija.sync.persistence.AuctionPromotionCandidate[]::new);
        var upper = fixture.publish(T.plusSeconds(1), updated);
        SourcePublicationFixture.point(jdbc, 10000, "POINT(20.46 44.79)");
        SourcePublicationFixture.point(jdbc, 10001, "POINT(20.47 44.79)");
        SourcePublicationFixture.point(jdbc, 10002, "POINT(22 44.79)");
        var f = filters(since(base) + "&timeScope=all&search=bezbedni&minPrice=100&maxPrice=100", upper.evaluatedAt());
        var collection = map.findAuctions(new MapAuctionRequest(new rs.sud.eaukcija.spatial.BoundingBox(20, 44, 21, 45), f, 1));
        assertThat(collection.counts()).isEqualTo(new MapAuctionRepository.Counts(200, 197, 2, 2));
        assertThat(collection.truncated()).isTrue(); assertThat(collection.numberReturned()).isOne();
        assertThat(collection.evidence()).containsOnlyKeys(10000L);
        assertThat(search.page(f, search.count(f)).getContent()).hasSize(25);
        assertThat(search.count(filters(since(base), upper.evaluatedAt()))).isEqualTo(180);
        long started = System.nanoTime();
        assertThat(changes.reviewed(reviews, upper, false).values()).hasSize(200)
                .extracting(CatalogueChangesService.Evidence::reviewState).containsOnly("CHANGED");
        System.out.println("issue56-batch-200-ms=" + (System.nanoTime() - started) / 1_000_000.0);
        var p = AuctionFilterSql.predicate(f);
        var plan = new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc).queryForList(
                "EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) WITH " + rs.sud.eaukcija.spatial.PublishableLocationSql.CTES
                    + " SELECT a.id FROM auctions a WHERE " + p.sql(), p.parameters(), String.class);
        System.out.println("issue56-600-membership-plan\n" + String.join("\n", plan));
        assertThat(String.join("\n", plan)).contains("Index");
        var headers = new org.springframework.http.HttpHeaders(); headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        assertThat(http.postForEntity("/api/auctions/reviews", new org.springframework.http.HttpEntity<>(" ".repeat(96 * 1024 + 1), headers), String.class)
                .getStatusCode().value()).isEqualTo(400);
        assertThat(http.postForEntity("/api/auctions/reviews", Map.of("frame", upper, "reviews", java.util.Collections.nCopies(201, reviews.get(0))), String.class)
                .getStatusCode().value()).isEqualTo(400);
    }
    private static class AutoCloseableExecutor implements AutoCloseable {
        final java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        public void close() { executor.shutdownNow(); }
    }
}
