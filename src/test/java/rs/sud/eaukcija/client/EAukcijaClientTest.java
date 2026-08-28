package rs.sud.eaukcija.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.Interceptor;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import rs.sud.eaukcija.client.EAukcijaApiTypes.AuctionDetail;
import rs.sud.eaukcija.client.EAukcijaApiTypes.AuctionListData;
import rs.sud.eaukcija.client.EAukcijaApiTypes.AuctionSummary;
import rs.sud.eaukcija.client.EAukcijaApiTypes.CategoryTree;
import rs.sud.eaukcija.snapshot.AuctionSourceSnapshotFactory;
import rs.sud.eaukcija.sync.persistence.SaleScope;
import rs.sud.eaukcija.testsupport.Fixtures;

class EAukcijaClientTest {

    private static final String API_PATH = "/WebApi.Proxy/api/EAukcija";
    private static final String USER_AGENT =
            "aukcije-core/0.0.1 (+https://github.com/brzivoz/aukcije_core/issues)";

    private MockWebServer server;
    private EAukcijaClientProperties properties;
    private FakeTiming timing;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        properties = new EAukcijaClientProperties();
        properties.setBaseUrl(server.url(API_PATH).uri());
        timing = new FakeTiming(Instant.parse("2026-08-24T10:00:00Z"));
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void parsesAListingPageAndSendsTheExactSourceRequestAndIdentity() throws Exception {
        server.enqueue(jsonFixture("eaukcija/auctions-by-category-page1.json"));

        EAukcijaCallResult<AuctionListData> result = client().getAuctionsByCategory(7, 3_000, 1);

        assertThat(result.attempts()).isEqualTo(1);
        assertThat(result.retries()).isZero();
        assertThat(result.data().totalCount()).isEqualTo(3);
        assertThat(result.data().auctions()).hasSize(3);
        assertThat(result.data().rejectedAuctions()).isEmpty();
        AuctionSummary first = result.data().auctions().get(0);
        assertThat(first.id()).isEqualTo(180466L);
        assertThat(first.auctionNumber()).isEqualTo("Н180466");
        assertThat(first.startingPrice()).isEqualByComparingTo(new BigDecimal("159600.00"));
        assertThat(first.currentPrice()).isNull();
        assertThat(first.maxOfferedPrice()).isNull();
        assertThat(result.sourceData().path("Auctions").get(0).path("Id").isIntegralNumber())
                .isTrue();
        assertThat(result.sourceData().path("Auctions").get(0)
                .path("StartingPrice").decimalValue().toPlainString())
                .isEqualTo("159600.00");
        assertThat(result.sourceData().path("Auctions").get(0).path("UnmappedFutureField").textValue())
                .isEqualTo("must be ignored, not fatal");

        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo(API_PATH + "/GetAuctionsByCategoryId");
        assertThat(request.getHeader("Accept")).isEqualTo("application/json");
        assertThat(request.getHeader("Content-Type")).startsWith("application/json");
        assertThat(request.getHeader("User-Agent")).isEqualTo(USER_AGENT);
        assertThat(request.getBody().readUtf8())
                .isEqualTo("{\"CategoryId\":7,\"ItemCount\":3000,\"PageCount\":1}");
    }

    @Test
    void parsesDetailsAndCarriesTheExactPreDtoDataForLaterMinimization() {
        server.enqueue(jsonFixture("eaukcija/immovable-property-detail.json"));

        EAukcijaCallResult<AuctionDetail> result =
                client().getImmovablePropertyDetails(180466L);
        AuctionDetail detail = result.data();

        assertThat(detail.id()).isEqualTo(180466L);
        assertThat(detail.estimatedPrice()).isEqualByComparingTo("228000.00");
        assertThat(detail.category().id()).isEqualTo(47);
        assertThat(detail.place().cadastral()).isEqualTo("Димитровград");
        assertThat(result.sourceData().path("Images").get(0).textValue())
                .contains("detail-redaction-sentinel");
        assertThat(result.sourceData().path("EstimatedPrice").decimalValue().toPlainString())
                .isEqualTo("228000.00");
    }

    @Test
    void exactMoneyFlowsFromHttpThroughDtoAndCanonicalSnapshotWithoutDoubleRounding() {
        String exactMoney = "12345678901234567.89";
        server.enqueue(json(Fixtures.read("eaukcija/auctions-by-category-page1.json")
                .replace("159600.00", exactMoney)));
        server.enqueue(json(Fixtures.read("eaukcija/immovable-property-detail.json")
                .replace("159600.00", exactMoney)));
        EAukcijaClient client = client();

        EAukcijaCallResult<AuctionListData> listing =
                client.getAuctionsByCategory(7, 3_000, 1);
        EAukcijaCallResult<AuctionDetail> detail =
                client.getImmovablePropertyDetails(180466L);
        var snapshot = new AuctionSourceSnapshotFactory(new ObjectMapper()).create(
                180466L,
                listing.sourceData().path("Auctions").get(0),
                detail.sourceData(),
                SaleScope.IMMOVABLE,
                Instant.parse("2026-08-25T08:00:00Z"),
                Instant.parse("2026-08-25T08:00:01Z"));

        assertThat(listing.data().auctions().get(0).startingPrice().toPlainString())
                .isEqualTo(exactMoney);
        assertThat(detail.data().startingPrice().toPlainString()).isEqualTo(exactMoney);
        assertThat(snapshot.canonicalPayload().path("listing")
                .path("StartingPrice").decimalValue().toPlainString())
                .isEqualTo(exactMoney);
        assertThat(snapshot.canonicalPayload().path("detail")
                .path("StartingPrice").decimalValue().toPlainString())
                .isEqualTo(exactMoney);
        assertThat(snapshot.canonicalPayload().toString())
                .contains("\"StartingPrice\":" + exactMoney)
                .doesNotContain(
                        "Thumbnail", "Images", "redaction-sentinel", "UnmappedFutureField");
    }

    @Test
    void commonPropertyDetailsUseTheDistinctSourceEndpointAndSameStrictEnvelope() throws Exception {
        server.enqueue(jsonFixture("eaukcija/common-property-detail.json"));

        AuctionDetail detail = client().getCommonPropertyDetails(280466L).data();

        assertThat(detail.id()).isEqualTo(280466L);
        assertThat(detail.propertyType()).isEqualTo("CommonProperties");
        assertThat(detail.category().id()).isEqualTo(121);
        assertThat(detail.place()).isNull();
        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.getPath()).isEqualTo(API_PATH + "/GetCommonPropertyDetails");
        assertThat(request.getHeader("User-Agent")).isEqualTo(USER_AGENT);
        assertThat(request.getBody().readUtf8()).isEqualTo("{\"AuctionId\":280466}");
    }

    @Test
    void fetchesValidatesCanonicalizesAndHashesTheCategoryTree() throws Exception {
        server.enqueue(jsonFixture("eaukcija/categories.json"));

        CategoryTree tree = client().getCategories().data();

        assertThat(tree.roots()).extracting(node -> node.value()).containsExactly(2, 7, 8);
        assertThat(tree.roots().get(1).children())
                .extracting(node -> node.value())
                .containsExactly(47, 48, 49);
        assertThat(tree.roots().get(2).children())
                .extracting(node -> node.value())
                .containsExactly(121, 124, 135);
        assertThat(tree.canonicalJson()).startsWith("[{\"value\":2,");
        assertThat(tree.canonicalJson()).doesNotContain("ResultCode", "ResultMessage");
        assertThat(tree.canonicalSha256()).isEqualTo(sha256(tree.canonicalJson()));

        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(request.getPath()).isEqualTo(API_PATH + "/GetCategories");
        assertThat(request.getBody().readUtf8()).isEqualTo("{}");
    }

    @Test
    void rejectsTheOldFabricatedOkResultCodeInsteadOfSelfValidatingTheFixture() {
        String body = Fixtures.read("eaukcija/empty-page.json")
                .replace("\"ResultCode\": \"0\"", "\"ResultCode\": \"OK\"");
        server.enqueue(json(body));

        assertThatThrownBy(() -> client().getAuctionsByCategory(7, 3_000, 1))
                .isInstanceOfSatisfying(EAukcijaClientException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(EAukcijaErrorCode.APPLICATION_ERROR);
                    assertThat(failure.attempts()).isEqualTo(1);
                });
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void applicationErrorsAreTerminalAndContainNoSourceMessageOrPayload() {
        server.enqueue(jsonFixture("eaukcija/error-response.json"));

        assertThatThrownBy(() -> client().getAuctionsByCategory(7, 3_000, 1))
                .isInstanceOfSatisfying(EAukcijaClientException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(EAukcijaErrorCode.APPLICATION_ERROR);
                    assertThat(failure.bodySha256()).hasSize(64);
                    assertThat(failure.getCause()).isNull();
                    assertThat(failure.getMessage())
                            .doesNotContain("password", "do-not-log", "token", "Петар");
                });
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void invalidJsonIsTerminalRedactedAndNotRetried() {
        server.enqueue(json("{\"ResultCode\":\"0\",\"Data\":password=secret"));

        assertThatThrownBy(() -> client().getAuctionsByCategory(7, 3_000, 1))
                .isInstanceOfSatisfying(EAukcijaClientException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(EAukcijaErrorCode.INVALID_JSON);
                    assertThat(failure.getMessage()).doesNotContain("password", "secret");
                });
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void retriesRetryableHttpStatusWithDeterministicFullJitter() {
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(jsonFixture("eaukcija/empty-page.json"));

        EAukcijaCallResult<AuctionListData> result = client().getAuctionsByCategory(7, 3_000, 1);

        assertThat(result.attempts()).isEqualTo(2);
        assertThat(result.retries()).isEqualTo(1);
        assertThat(timing.sleeps()).containsExactly(Duration.ofMillis(500));
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    void honorsDeltaSecondsRetryAfterAndPausesTheSharedRateGate() {
        server.enqueue(new MockResponse()
                .setResponseCode(429)
                .setHeader("Retry-After", "2"));
        server.enqueue(jsonFixture("eaukcija/empty-page.json"));

        EAukcijaCallResult<AuctionListData> result = client().getAuctionsByCategory(7, 3_000, 1);

        assertThat(result.attempts()).isEqualTo(2);
        assertThat(timing.sleeps()).containsExactly(Duration.ofSeconds(2));
    }

    @Test
    void honorsRfc1123RetryAfterDate() {
        server.enqueue(new MockResponse()
                .setResponseCode(503)
                .setHeader("Retry-After", "Mon, 24 Aug 2026 10:00:03 GMT"));
        server.enqueue(jsonFixture("eaukcija/empty-page.json"));

        client().getAuctionsByCategory(7, 3_000, 1);

        assertThat(timing.sleeps()).containsExactly(Duration.ofSeconds(3));
    }

    @Test
    void malformedRetryAfterFallsBackToJitter() {
        server.enqueue(new MockResponse()
                .setResponseCode(429)
                .setHeader("Retry-After", "not-a-delay"));
        server.enqueue(jsonFixture("eaukcija/empty-page.json"));

        client().getAuctionsByCategory(7, 3_000, 1);

        assertThat(timing.sleeps()).containsExactly(Duration.ofMillis(500));
    }

    @Test
    void excessiveRetryAfterFailsLaterCallsBeforeSleepingOrSending() {
        server.enqueue(new MockResponse()
                .setResponseCode(429)
                .setHeader("Retry-After", "121"));
        server.enqueue(jsonFixture("eaukcija/empty-page.json"));
        EAukcijaClient sharedClient = client();

        assertThatThrownBy(() -> sharedClient.getAuctionsByCategory(7, 3_000, 1))
                .isInstanceOfSatisfying(EAukcijaClientException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(EAukcijaErrorCode.RATE_LIMITED);
                    assertThat(failure.attempts()).isEqualTo(1);
                });
        assertThat(server.getRequestCount()).isEqualTo(1);

        assertThatThrownBy(() -> sharedClient.getAuctionsByCategory(7, 3_000, 1))
                .isInstanceOfSatisfying(EAukcijaClientException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(EAukcijaErrorCode.RATE_LIMITED);
                    assertThat(failure.endpoint()).isEqualTo("auctions-by-category");
                    assertThat(failure.httpStatus()).isNull();
                    assertThat(failure.attempts()).isEqualTo(1);
                });

        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(timing.sleeps()).isEmpty();

        timing.advance(Duration.ofSeconds(2));
        sharedClient.getAuctionsByCategory(7, 3_000, 1);

        assertThat(server.getRequestCount()).isEqualTo(2);
        assertThat(timing.sleeps()).containsExactly(Duration.ofSeconds(119));
    }

    @Test
    void enormousRetryAfterIsSaturatedAndNeverOverflowsOrSleeps() {
        server.enqueue(new MockResponse()
                .setResponseCode(503)
                .setHeader("Retry-After", "999999999999999999999999999999999999999999"));
        EAukcijaClient sharedClient = client();

        assertThatThrownBy(sharedClient::getCategories)
                .isInstanceOfSatisfying(EAukcijaClientException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(EAukcijaErrorCode.RATE_LIMITED);
                    assertThat(failure.httpStatus()).isEqualTo(503);
                    assertThat(failure.attempts()).isOne();
                });
        assertThatThrownBy(sharedClient::getCategories)
                .isInstanceOfSatisfying(EAukcijaClientException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(EAukcijaErrorCode.RATE_LIMITED);
                    assertThat(failure.httpStatus()).isNull();
                    assertThat(failure.attempts()).isOne();
                });
        // OkHttp's defensive replacement is about 68 years. Advancing beyond
        // that proves the source's much larger original value remained in the
        // shared gate instead of being shortened to the transport workaround.
        timing.advance(Duration.ofDays(36_500));
        assertThatThrownBy(sharedClient::getCategories)
                .isInstanceOfSatisfying(EAukcijaClientException.class, failure ->
                        assertThat(failure.code()).isEqualTo(EAukcijaErrorCode.RATE_LIMITED));

        assertThat(server.getRequestCount()).isOne();
        assertThat(timing.sleeps()).isEmpty();
    }

    @Test
    void doesNotRetryNonRetryableHttpStatusOrFollowRedirects() {
        server.enqueue(new MockResponse()
                .setResponseCode(302)
                .setHeader("Location", server.url("/outside")));

        assertThatThrownBy(() -> client().getCategories())
                .isInstanceOfSatisfying(EAukcijaClientException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(EAukcijaErrorCode.HTTP_STATUS);
                    assertThat(failure.httpStatus()).isEqualTo(302);
                });
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void enforcesAReadTimeoutAndKeepsDistinctTransportTimeouts() {
        properties.setConnectTimeout(Duration.ofSeconds(1));
        properties.setReadTimeout(Duration.ofMillis(50));
        properties.setCallTimeout(Duration.ofSeconds(2));
        properties.setMaxAttempts(1);
        server.enqueue(jsonFixture("eaukcija/empty-page.json")
                .setHeadersDelay(150, TimeUnit.MILLISECONDS));
        EAukcijaClient client = client();

        assertThatThrownBy(() -> client.getAuctionsByCategory(7, 3_000, 1))
                .isInstanceOfSatisfying(EAukcijaClientException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(EAukcijaErrorCode.TIMEOUT);
                    assertThat(failure.attempts()).isEqualTo(1);
                });

        assertThat(client.transportForTesting().connectTimeoutMillis()).isEqualTo(1_000);
        assertThat(client.transportForTesting().readTimeoutMillis()).isEqualTo(50);
        assertThat(client.transportForTesting().callTimeoutMillis()).isEqualTo(2_000);
    }

    @Test
    void applicationShutdownCancelsAnInFlightCallWithoutRetrying() throws Exception {
        properties.setConnectTimeout(Duration.ofSeconds(1));
        properties.setReadTimeout(Duration.ofSeconds(30));
        properties.setCallTimeout(Duration.ofSeconds(30));
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        EAukcijaClient client = client();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<EAukcijaCallResult<AuctionListData>> call = executor.submit(
                    () -> client.getAuctionsByCategory(7, 3_000, 1));
            assertThat(server.takeRequest(2, TimeUnit.SECONDS)).isNotNull();

            client.prepareForShutdown();

            assertThatThrownBy(() -> call.get(2, TimeUnit.SECONDS))
                    .isInstanceOfSatisfying(ExecutionException.class, failure ->
                            assertThat(failure.getCause())
                                    .isInstanceOfSatisfying(EAukcijaClientException.class, clientFailure -> {
                                        assertThat(clientFailure.code())
                                                .isEqualTo(EAukcijaErrorCode.INTERRUPTED);
                                        assertThat(clientFailure.attempts()).isOne();
                                    }));
            assertThat(server.getRequestCount()).isOne();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void retriesATransportTimeoutThenSucceeds() {
        properties.setMaxAttempts(2);
        AtomicInteger calls = new AtomicInteger();
        Interceptor firstAttemptTimesOut = chain -> {
            if (calls.getAndIncrement() == 0) {
                throw new SocketTimeoutException("synthetic timeout");
            }
            return chain.proceed(chain.request());
        };
        server.enqueue(jsonFixture("eaukcija/empty-page.json"));

        EAukcijaCallResult<AuctionListData> result = client(firstAttemptTimesOut)
                .getAuctionsByCategory(7, 3_000, 1);

        assertThat(result.attempts()).isEqualTo(2);
        assertThat(result.retries()).isEqualTo(1);
        assertThat(calls).hasValue(2);
        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(timing.sleeps()).containsExactly(Duration.ofMillis(500));
    }

    @Test
    void exhaustsTimeoutRetriesWithOnlyASafeError() {
        properties.setMaxAttempts(2);
        Interceptor alwaysTimesOut = chain -> {
            throw new SocketTimeoutException("synthetic timeout password=secret");
        };

        assertThatThrownBy(() -> client(alwaysTimesOut).getAuctionsByCategory(7, 3_000, 1))
                .isInstanceOfSatisfying(EAukcijaClientException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(EAukcijaErrorCode.TIMEOUT);
                    assertThat(failure.attempts()).isEqualTo(2);
                    assertThat(failure.getCause()).isNull();
                    assertThat(failure.getMessage()).doesNotContain("password", "secret");
                });
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void rejectsMissingOrWrongDetailIdentityAndInvalidTimestamps() {
        server.enqueue(json("{\"ResultCode\":\"0\",\"ResultMessage\":\"OK\",\"Data\":null}"));
        assertFailureCode(
                () -> client().getImmovablePropertyDetails(180466L),
                EAukcijaErrorCode.INVALID_ENVELOPE);

        server.enqueue(json(Fixtures.read("eaukcija/immovable-property-detail.json")
                .replaceFirst("\"Id\": 180466", "\"Id\": 999999")));
        assertFailureCode(
                () -> client().getImmovablePropertyDetails(180466L),
                EAukcijaErrorCode.INVALID_DATA);

        server.enqueue(json(Fixtures.read("eaukcija/immovable-property-detail.json")
                .replace("2026-03-10T07:00:00Z", "not-an-instant")));
        assertFailureCode(
                () -> client().getImmovablePropertyDetails(180466L),
                EAukcijaErrorCode.INVALID_DATA);

        server.enqueue(json(Fixtures.read("eaukcija/immovable-property-detail.json")
                .replaceFirst("\"Id\": 47", "\"Id\": 0")));
        assertFailureCode(
                () -> client().getImmovablePropertyDetails(180466L),
                EAukcijaErrorCode.INVALID_DATA);
    }

    @Test
    void rejectsDetailValuesThatCannotBeStoredExactlyBeforePromotion() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode valid = (ObjectNode) mapper.readTree(
                Fixtures.read("eaukcija/immovable-property-detail.json"));

        ObjectNode overlong = valid.deepCopy();
        ((ObjectNode) overlong.get("Data")).put("Description", "x".repeat(4_001));
        server.enqueue(json(mapper.writeValueAsString(overlong)));
        assertFailureCode(
                () -> client().getImmovablePropertyDetails(180466L),
                EAukcijaErrorCode.INVALID_DATA);

        ObjectNode nul = valid.deepCopy();
        ((ObjectNode) nul.get("Data")).put("ExecutorName", "safe-prefix\0secret-suffix");
        server.enqueue(json(mapper.writeValueAsString(nul)));
        assertThatThrownBy(() -> client().getImmovablePropertyDetails(180466L))
                .isInstanceOfSatisfying(EAukcijaClientException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(EAukcijaErrorCode.INVALID_DATA);
                    assertThat(failure.getMessage()).doesNotContain("secret-suffix");
                });

        ObjectNode excessiveIntegerDigits = valid.deepCopy();
        ((ObjectNode) excessiveIntegerDigits.get("Data"))
                .put("EstimatedPrice", new BigDecimal("9".repeat(37)));
        server.enqueue(json(mapper.writeValueAsString(excessiveIntegerDigits)));
        assertFailureCode(
                () -> client().getImmovablePropertyDetails(180466L),
                EAukcijaErrorCode.INVALID_DATA);

        ObjectNode lossyScale = valid.deepCopy();
        ((ObjectNode) lossyScale.get("Data"))
                .put("BidStep", new BigDecimal("0.001"));
        server.enqueue(json(mapper.writeValueAsString(lossyScale)));
        assertFailureCode(
                () -> client().getImmovablePropertyDetails(180466L),
                EAukcijaErrorCode.INVALID_DATA);
    }

    @Test
    void returnsRedactedRejectionsForInvalidPositiveIdListingRowsAndKeepsValidRows()
            throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode listing = (ObjectNode) mapper.readTree(
                Fixtures.read("eaukcija/auctions-by-category-page1.json"));
        ObjectNode data = (ObjectNode) listing.get("Data");
        ArrayNode auctions = (ArrayNode) data.get("Auctions");
        ObjectNode valid = auctions.get(0).deepCopy();
        ObjectNode lossyMoney = auctions.get(0).deepCopy();
        lossyMoney.put("Id", 180467L);
        lossyMoney.put("StartingPrice", new BigDecimal("159600.001"));
        ObjectNode overlong = auctions.get(1).deepCopy();
        overlong.put("Id", 179416L);
        overlong.put("AuctionNumber", "redaction-sentinel-" + "x".repeat(256));
        ObjectNode invalidTimestamp = auctions.get(2).deepCopy();
        invalidTimestamp.put("Id", 181467L);
        invalidTimestamp.put("StartDate", "invalid-timestamp-redaction-sentinel");
        auctions.removeAll();
        auctions.add(valid);
        auctions.add(lossyMoney);
        auctions.add(overlong);
        auctions.add(invalidTimestamp);
        data.put("TotalCount", 4);

        String body = mapper.writeValueAsString(listing);
        server.enqueue(json(body));
        server.enqueue(json(body));

        AuctionListData first = client().getAuctionsByCategory(7, 3_000, 1).data();
        AuctionListData repeated = client().getAuctionsByCategory(7, 3_000, 1).data();

        assertThat(first.totalCount()).isEqualTo(4);
        assertThat(first.auctions()).extracting(AuctionSummary::id)
                .containsExactly(180466L);
        assertThat(first.rejectedAuctions())
                .extracting(rejected -> rejected.auctionId())
                .containsExactly(180467L, 179416L, 181467L);
        assertThat(first.rejectedAuctions().get(0).sourceRowSha256())
                .isEqualTo(AuctionSummaryFingerprint.sha256(
                        mapper.treeToValue(lossyMoney, AuctionSummary.class)));
        assertThat(first.rejectedAuctions())
                .allSatisfy(rejected -> {
                    assertThat(rejected.errorCode()).isEqualTo(EAukcijaErrorCode.INVALID_DATA);
                    assertThat(rejected.sourceRowSha256()).matches("[0-9a-f]{64}");
                });
        assertThat(first.rejectedAuctions()).isEqualTo(repeated.rejectedAuctions());
        assertThat(first.rejectedAuctions().toString())
                .doesNotContain("redaction-sentinel", "159600.001", "invalid-timestamp");
    }

    @Test
    void keepsNullAndNonpositiveIdListingRowsFatal() throws Exception {
        server.enqueue(json("""
                {"ResultCode":"0","ResultMessage":"OK","Data":{"TotalCount":1,"Auctions":[null]}}
                """));
        assertFailureCode(
                () -> client().getAuctionsByCategory(7, 3_000, 1),
                EAukcijaErrorCode.INVALID_DATA);

        server.enqueue(json("""
                {"ResultCode":"0","ResultMessage":"OK","Data":{"TotalCount":1,"Auctions":[
                  {"Id":0,"AuctionNumber":"N0",
                   "StartDate":"2026-03-10T07:00:00Z","EndDate":"2026-03-10T11:00:00Z"}
                ]}}
                """));

        assertFailureCode(
                () -> client().getAuctionsByCategory(7, 3_000, 1),
                EAukcijaErrorCode.INVALID_DATA);
    }

    @Test
    void rejectsInvalidListingAndCategoryShapes() {
        server.enqueue(json("""
                {"ResultCode":"0","ResultMessage":"OK","Data":{"TotalCount":-1,"Auctions":[]}}
                """));
        assertFailureCode(
                () -> client().getAuctionsByCategory(7, 3_000, 1),
                EAukcijaErrorCode.INVALID_DATA);

        server.enqueue(json("""
                {"ResultCode":"0","ResultMessage":"OK","Data":{"Auctions":[]}}
                """));
        assertFailureCode(
                () -> client().getAuctionsByCategory(7, 3_000, 1),
                EAukcijaErrorCode.INVALID_DATA);

        server.enqueue(json(Fixtures.read("eaukcija/categories.json")
                .replace("\"value\": \"8\"", "\"value\": \"9\"")));
        assertFailureCode(client()::getCategories, EAukcijaErrorCode.INVALID_DATA);
    }

    @Test
    void preservesDuplicateRawRowsBeyondTheReportedUniqueTotalForOrchestration() {
        server.enqueue(json("""
                {"ResultCode":"0","ResultMessage":"OK","Data":{"TotalCount":1,"Auctions":[
                  {"Id":180466,"AuctionNumber":"N180466",
                   "StartDate":"2026-03-10T07:00:00Z","EndDate":"2026-03-10T11:00:00Z"},
                  {"Id":180466,"AuctionNumber":"N180466",
                   "StartDate":"2026-03-10T07:00:00Z","EndDate":"2026-03-10T11:00:00Z"}
                ]}}
                """));

        AuctionListData page = client().getAuctionsByCategory(7, 3_000, 1).data();

        assertThat(page.totalCount()).isOne();
        assertThat(page.auctions()).extracting(AuctionSummary::id)
                .containsExactly(180466L, 180466L);
    }

    @Test
    void rejectsNonJsonEmptyAndOversizedBodiesWithoutEchoingThem() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/html")
                .setBody("<html>password=secret</html>"));
        assertFailureCode(client()::getCategories, EAukcijaErrorCode.INVALID_CONTENT_TYPE);

        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(""));
        assertFailureCode(client()::getCategories, EAukcijaErrorCode.INVALID_ENVELOPE);

        properties.setMaxResponseBytes(1_024);
        server.enqueue(json("{\"ResultCode\":\"0\",\"Data\":\""
                + "A".repeat(2_000)
                + "password=secret\"}"));
        assertThatThrownBy(() -> client().getCategories())
                .isInstanceOfSatisfying(EAukcijaClientException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(EAukcijaErrorCode.BODY_TOO_LARGE);
                    assertThat(failure.getMessage()).doesNotContain("password", "secret");
                });
    }

    @Test
    void appliesTheConfiguredRateToEveryPhysicalCall() {
        server.enqueue(jsonFixture("eaukcija/empty-page.json"));
        server.enqueue(jsonFixture("eaukcija/empty-page.json"));
        EAukcijaClient client = client();

        client.getAuctionsByCategory(7, 3_000, 1);
        client.getAuctionsByCategory(8, 3_000, 1);

        assertThat(timing.sleeps()).containsExactly(Duration.ofMillis(500));
    }

    @Test
    void boundsConcurrentPhysicalCallsWithAFairSharedPermit() throws Exception {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                int nowActive = active.incrementAndGet();
                maximum.accumulateAndGet(nowActive, Math::max);
                try {
                    if (firstEntered.getCount() > 0) {
                        firstEntered.countDown();
                        releaseFirst.await(2, TimeUnit.SECONDS);
                    }
                    return jsonFixture("eaukcija/categories.json");
                } finally {
                    active.decrementAndGet();
                }
            }
        });
        properties.setMaxConcurrency(1);
        EAukcijaClient client = client();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<CategoryTree> first = executor.submit(() -> client.getCategories().data());
            assertThat(firstEntered.await(1, TimeUnit.SECONDS)).isTrue();
            Future<CategoryTree> second = executor.submit(() -> client.getCategories().data());

            Thread.sleep(75);
            assertThat(server.getRequestCount()).isEqualTo(1);
            releaseFirst.countDown();

            assertThat(first.get(2, TimeUnit.SECONDS).roots()).isNotEmpty();
            assertThat(second.get(2, TimeUnit.SECONDS).roots()).isNotEmpty();
            assertThat(maximum).hasValue(1);
            assertThat(server.getRequestCount()).isEqualTo(2);
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void retriesAnAbruptTransportDisconnect() {
        properties.setMaxAttempts(2);
        AtomicInteger calls = new AtomicInteger();
        Interceptor firstAttemptDisconnects = chain -> {
            if (calls.getAndIncrement() == 0) {
                throw new IOException("synthetic disconnect password=secret");
            }
            return chain.proceed(chain.request());
        };
        server.enqueue(jsonFixture("eaukcija/empty-page.json"));

        EAukcijaCallResult<AuctionListData> result = client(firstAttemptDisconnects)
                .getAuctionsByCategory(7, 3_000, 1);

        assertThat(result.attempts()).isEqualTo(2);
        assertThat(result.retries()).isEqualTo(1);
        assertThat(calls).hasValue(2);
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    private EAukcijaClient client() {
        return client(List.of());
    }

    private EAukcijaClient client(Interceptor interceptor) {
        return client(List.of(interceptor));
    }

    private EAukcijaClient client(List<Interceptor> interceptors) {
        return new EAukcijaClient(
                properties,
                new ObjectMapper(),
                timing,
                inclusiveMaximum -> inclusiveMaximum,
                true,
                interceptors);
    }

    private static MockResponse jsonFixture(String path) {
        return json(Fixtures.read(path));
    }

    private static MockResponse json(String body) {
        return new MockResponse()
                .setHeader("Content-Type", "application/json; charset=utf-8")
                .setBody(body);
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static void assertFailureCode(ThrowingCall call, EAukcijaErrorCode code) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(EAukcijaClientException.class,
                        failure -> assertThat(failure.code()).isEqualTo(code));
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run() throws Exception;
    }

    private static final class FakeTiming implements EAukcijaTiming {
        private final Instant initialWallTime;
        private final List<Duration> sleeps = new ArrayList<>();
        private long nanoTime;

        private FakeTiming(Instant initialWallTime) {
            this.initialWallTime = initialWallTime;
        }

        @Override
        public synchronized long nanoTime() {
            return nanoTime;
        }

        @Override
        public synchronized Instant now() {
            return initialWallTime.plusNanos(nanoTime);
        }

        @Override
        public synchronized void sleep(Duration duration) {
            sleeps.add(duration);
            nanoTime += duration.toNanos();
        }

        private synchronized List<Duration> sleeps() {
            return List.copyOf(sleeps);
        }

        private synchronized void advance(Duration duration) {
            nanoTime += duration.toNanos();
        }
    }
}
