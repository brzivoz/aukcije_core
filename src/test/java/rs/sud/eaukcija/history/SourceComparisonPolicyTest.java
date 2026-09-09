package rs.sud.eaukcija.history;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import org.junit.jupiter.api.Test;
import rs.sud.eaukcija.snapshot.AuctionSourceCanonicalJson;

class SourceComparisonPolicyTest {
    @Test void exactMoneyRepresentationsAreNotSubstantiveButStartingPricesAreNotBiddingNoise() throws Exception {
        assertThat(diff("StartingPrice", "100.0", "\"100.00\"").kind()).isEqualTo("REPRESENTATION_ONLY");
        assertThat(diff("StartingPrice", "100", "101").fields()).containsExactly("STARTING_PRICE");
        assertThat(diff("StartingPrice", "100", "101").kind()).isEqualTo("SUBSTANTIVE");
        assertThat(diff("EstimatedPrice", "100", "101").kind()).isEqualTo("SUBSTANTIVE");
        assertThat(diff("CurrentPrice", "100", "101").kind()).isEqualTo("LIVE_BIDDING_ONLY");
        assertThat(diff("MaxOfferedPrice", "100", "101").kind()).isEqualTo("LIVE_BIDDING_ONLY");
    }
    @Test void textStatusLocationAndDateChangesAreNotNormalizedOrExported() throws Exception {
        for (String field : List.of("Description", "ShortDescription", "Status", "EndDate", "StartDate",
                "Category", "Place", "PropertyType", "PublicationDate", "IsFirstSale", "ExecutorName")) {
            var result = diff(field, "\"legal text\"", "\"legal  text\"");
            assertThat(result.kind()).isEqualTo("SUBSTANTIVE");
            assertThat(result.fields()).hasSize(1);
            assertThat(result.toString()).doesNotContain("legal");
        }
    }
    @Test void unsupportedPolicyCannotGuessDifferencesEvenWithEqualHashes() throws Exception {
        var payload = AuctionSourceCanonicalJson.readTree("{\"listing\":{},\"detail\":{}}");
        assertThat(SourceComparisonPolicy.compare(payload, payload, false, true).kind()).isEqualTo("UNSUPPORTED");
        assertThat(SourceComparisonPolicy.supported("future", "public-auction-fields-v1", SourceComparisonPolicy.VERSION)).isFalse();
        assertThat(SourceComparisonPolicy.supported("eaukcija-listing-detail-v1", "public-auction-fields-v1", "future")).isFalse();
        assertThat(SourceComparisonPolicy.compare(payload, payload, true, true).kind()).isEqualTo("UNCHANGED");
    }
    private SourceComparisonPolicy.Difference diff(String field, String before, String after) throws Exception {
        return SourceComparisonPolicy.compare(
                AuctionSourceCanonicalJson.readTree("{\"listing\":{},\"detail\":{\"" + field + "\":" + before + "}}"),
                AuctionSourceCanonicalJson.readTree("{\"listing\":{},\"detail\":{\"" + field + "\":" + after + "}}"), true, false);
    }
}
