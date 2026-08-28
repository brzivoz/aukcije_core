package rs.sud.eaukcija.snapshot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import rs.sud.eaukcija.sync.persistence.SaleScope;
import rs.sud.eaukcija.testsupport.Fixtures;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuctionSourceSnapshotFactoryTest {

    private static final Instant LISTING_FETCHED_AT = Instant.parse("2026-08-25T08:00:00Z");
    private static final Instant DETAIL_FETCHED_AT = Instant.parse("2026-08-25T08:00:01Z");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AuctionSourceSnapshotFactory factory =
            new AuctionSourceSnapshotFactory(objectMapper);
    private final AuctionSourceSnapshotReplayParser replayParser =
            new AuctionSourceSnapshotReplayParser(objectMapper);

    @Test
    void goldenFixturePreservesAllowedTypesValuesAndReplaysWithoutANetworkClient() throws Exception {
        JsonNode listing = listing();
        JsonNode detail = detail();

        AuctionSourceSnapshot snapshot = factory.create(
                180466L, listing, detail, SaleScope.IMMOVABLE,
                LISTING_FETCHED_AT, DETAIL_FETCHED_AT);

        JsonNode storedListing = snapshot.canonicalPayload().path("listing");
        JsonNode storedDetail = snapshot.canonicalPayload().path("detail");
        assertThat(storedListing.path("AuctionNumber").textValue()).isEqualTo("Н180466");
        assertThat(storedListing.path("StartingPrice").isNumber()).isTrue();
        assertThat(storedListing.path("StartingPrice").decimalValue().toPlainString())
                .isEqualTo("159600.00");
        assertThat(snapshot.canonicalPayload().toString())
                .contains("\"StartingPrice\":159600.00");
        assertThat(storedListing.path("CurrentPrice").isNull()).isTrue();
        assertThat(storedListing.path("IsFirstSale").isBoolean()).isTrue();
        assertThat(storedDetail.path("Place").path("Cadastral").textValue())
                .isEqualTo("Димитровград");
        assertThat(storedDetail.path("ExecutorName").textValue())
                .isEqualTo("Јавни извршитељ Петар Петровић");
        assertThat(snapshot.sourceStartAt()).isEqualTo("2026-03-10T07:00:00Z");
        assertThat(snapshot.sourceEndAt()).isEqualTo("2026-03-10T11:00:00Z");
        assertThat(snapshot.sourcePublicationAt()).isEqualTo("2026-02-24T10:30:00Z");
        assertThat(snapshot.fetchedAt()).isEqualTo(DETAIL_FETCHED_AT);

        var replayed = replayParser.parse(snapshot.canonicalPayload());
        assertThat(replayed.listing().id()).isEqualTo(180466L);
        assertThat(replayed.listing().startingPrice()).isEqualByComparingTo("159600.00");
        assertThat(replayed.detail().description()).contains("катастарска парцела број 1572");
        assertThat(replayed.detail().place().cadastral()).isEqualTo("Димитровград");
    }

    @Test
    void canonicalHashIgnoresJsonKeyOrderAndExcludedBinaryOrUnreviewedFields() throws Exception {
        JsonNode listing = listing();
        JsonNode detail = detail();
        AuctionSourceSnapshot baseline = factory.create(
                180466L, listing, detail, SaleScope.IMMOVABLE,
                LISTING_FETCHED_AT, DETAIL_FETCHED_AT);

        ObjectNode reorderedListing = reverseFields((ObjectNode) listing);
        ObjectNode reorderedDetail = reverseFields((ObjectNode) detail);
        reorderedListing.put("Thumbnail", "different-binary-value");
        reorderedListing.put("ThumbnailType", "image/png");
        reorderedListing.put("UnmappedFutureField", "not-yet-reviewed");
        reorderedListing.put("Authorization", "transport-secret");
        reorderedDetail.putArray("Images").add("different-detail-image");
        reorderedDetail.put("AccessToken", "transport-token");

        AuctionSourceSnapshot reordered = factory.create(
                180466L, reorderedListing, reorderedDetail, SaleScope.IMMOVABLE,
                LISTING_FETCHED_AT, DETAIL_FETCHED_AT);

        assertThat(reordered.contentSha256()).isEqualTo(baseline.contentSha256());
        assertThat(reordered.canonicalPayload().toString())
                .doesNotContain(
                        "Thumbnail", "Images", "binary", "UnmappedFutureField",
                        "Authorization", "AccessToken", "transport-secret");
        assertThat(reordered.minimizationPolicyVersion())
                .isEqualTo(AuctionSourceSnapshotFactory.MINIMIZATION_POLICY_VERSION);
    }

    @Test
    void changedAllowedDetailCreatesANewContentAddress() throws Exception {
        AuctionSourceSnapshot baseline = factory.create(
                180466L, listing(), detail(), SaleScope.IMMOVABLE,
                LISTING_FETCHED_AT, DETAIL_FETCHED_AT);
        ObjectNode changed = (ObjectNode) detail().deepCopy();
        changed.put("Description", "Коригован јавни опис непокретности");

        AuctionSourceSnapshot correction = factory.create(
                180466L, listing(), changed, SaleScope.IMMOVABLE,
                LISTING_FETCHED_AT, DETAIL_FETCHED_AT.plusSeconds(60));

        assertThat(correction.contentSha256()).isNotEqualTo(baseline.contentSha256());
        assertThat(correction.canonicalPayload().path("detail").path("Description").textValue())
                .isEqualTo("Коригован јавни опис непокретности");
    }

    @Test
    void oneFixedCanonicalSerializerIgnoresInjectedPrettyPrinting() throws Exception {
        AuctionSourceSnapshot baseline = factory.create(
                180466L, listing(), detail(), SaleScope.IMMOVABLE,
                LISTING_FETCHED_AT, DETAIL_FETCHED_AT);
        ObjectMapper prettyMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        AuctionSourceSnapshot prettyConfigured = new AuctionSourceSnapshotFactory(prettyMapper).create(
                180466L, listing(), detail(), SaleScope.IMMOVABLE,
                LISTING_FETCHED_AT, DETAIL_FETCHED_AT);

        assertThat(prettyConfigured.contentSha256()).isEqualTo(baseline.contentSha256());
    }

    @Test
    void currentSnapshotRejectsPayloadThatDoesNotMatchItsStoredAddress() throws Exception {
        AuctionSourceSnapshot snapshot = factory.create(
                180466L, listing(), detail(), SaleScope.IMMOVABLE,
                LISTING_FETCHED_AT, DETAIL_FETCHED_AT);

        assertThatThrownBy(() -> new CurrentAuctionSourceSnapshot(
                snapshot.auctionId(),
                "0".repeat(64),
                snapshot.canonicalPayload(),
                snapshot.detailEndpoint(),
                snapshot.detailFetchedAt()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not address canonicalPayload");
    }

    @Test
    void rejectsNullMalformedMismatchedOversizedAndOverdeepRecords() throws Exception {
        assertThatThrownBy(() -> factory.create(
                180466L, listing(), null, SaleScope.IMMOVABLE,
                LISTING_FETCHED_AT, DETAIL_FETCHED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("detailSource");

        ObjectNode mismatched = (ObjectNode) detail().deepCopy();
        mismatched.put("Id", 999L);
        assertThatThrownBy(() -> factory.create(
                180466L, listing(), mismatched, SaleScope.IMMOVABLE,
                LISTING_FETCHED_AT, DETAIL_FETCHED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("detail Id");

        ObjectNode oversized = (ObjectNode) detail().deepCopy();
        oversized.put("Description", "x".repeat(AuctionSourceSnapshotFactory.MAX_CANONICAL_BYTES));
        assertThatThrownBy(() -> factory.create(
                180466L, listing(), oversized, SaleScope.IMMOVABLE,
                LISTING_FETCHED_AT, DETAIL_FETCHED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximum bytes");

        ObjectNode overdeep = (ObjectNode) detail().deepCopy();
        ObjectNode cursor = overdeep.putObject("UnknownContainer");
        for (int depth = 0; depth < AuctionSourceSnapshotFactory.MAX_SOURCE_RECORD_DEPTH; depth++) {
            cursor = cursor.putObject("nested");
        }
        assertThatThrownBy(() -> factory.create(
                180466L, listing(), overdeep, SaleScope.IMMOVABLE,
                LISTING_FETCHED_AT, DETAIL_FETCHED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximum JSON depth");

        ObjectNode listingSchemaDrift = (ObjectNode) listing().deepCopy();
        listingSchemaDrift.putObject("Status").put("value", "Verified");
        assertThatThrownBy(() -> factory.create(
                180466L, listingSchemaDrift, detail(), SaleScope.IMMOVABLE,
                LISTING_FETCHED_AT, DETAIL_FETCHED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Status must be a scalar or null");

        ObjectNode detailSchemaDrift = (ObjectNode) detail().deepCopy();
        detailSchemaDrift.putArray("Description").add("unexpected-container");
        assertThatThrownBy(() -> factory.create(
                180466L, listing(), detailSchemaDrift, SaleScope.IMMOVABLE,
                LISTING_FETCHED_AT, DETAIL_FETCHED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Description must be a scalar or null");
    }

    private JsonNode listing() throws Exception {
        return AuctionSourceCanonicalJson.readTree(
                        Fixtures.read("eaukcija/auctions-by-category-page1.json"))
                .path("Data").path("Auctions").get(0);
    }

    private JsonNode detail() throws Exception {
        return AuctionSourceCanonicalJson.readTree(
                        Fixtures.read("eaukcija/immovable-property-detail.json"))
                .path("Data");
    }

    private ObjectNode reverseFields(ObjectNode source) {
        ArrayList<String> names = new ArrayList<>();
        source.fieldNames().forEachRemaining(names::add);
        Collections.reverse(names);
        ObjectNode reversed = objectMapper.createObjectNode();
        names.forEach(name -> reversed.set(name, source.get(name).deepCopy()));
        return reversed;
    }
}
