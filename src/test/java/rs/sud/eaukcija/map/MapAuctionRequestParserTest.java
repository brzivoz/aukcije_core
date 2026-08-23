package rs.sud.eaukcija.map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import rs.sud.eaukcija.spatial.LocationPrecision;

class MapAuctionRequestParserTest {

    private static final Instant NOW = Instant.parse("2026-08-23T10:15:30Z");
    private final MapAuctionRequestParser parser =
            new MapAuctionRequestParser(Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void defaultsToCurrentlyRelevantAuctionsAndAThousandFeatures() {
        MapAuctionRequest request = parser.parse(parameters("bbox", "18,41,24,47"));

        assertThat(request.boundingBox().minLongitude()).isEqualTo(18);
        assertThat(request.boundingBox().maxLatitude()).isEqualTo(47);
        assertThat(request.endsAtOrAfter()).isEqualTo(NOW);
        assertThat(request.endsBefore()).isNull();
        assertThat(request.limit()).isEqualTo(1_000);
        assertThat(request.sourceStatus()).isNull();
        assertThat(request.propertyKind()).isNull();
        assertThat(request.precision()).isNull();
    }

    @Test
    void parsesAllowlistedFiltersAndBelgradeCalendarDayBoundaries() {
        MultiValueMap<String, String> parameters = parameters("bbox", "20.40,44.70,20.60,44.85");
        parameters.add("status", "verified");
        parameters.add("kind", "Парцела");
        parameters.add("precision", "parcel");
        parameters.add("from", "2026-10-25");
        parameters.add("to", "2026-10-25");
        parameters.add("limit", "5000");

        MapAuctionRequest request = parser.parse(parameters);

        assertThat(request.sourceStatus()).isEqualTo("Verified");
        assertThat(request.propertyKind()).isEqualTo("Парцела");
        assertThat(request.precision()).isEqualTo(LocationPrecision.PARCEL);
        // Serbia returns to UTC+1 during this local calendar day (a 25-hour day).
        assertThat(request.endsAtOrAfter()).isEqualTo(Instant.parse("2026-10-24T22:00:00Z"));
        assertThat(request.endsBefore()).isEqualTo(Instant.parse("2026-10-25T23:00:00Z"));
        assertThat(request.limit()).isEqualTo(5_000);
    }

    @Test
    void acceptsAnAuctionExactlyOnEachWgs84OuterEdge() {
        MapAuctionRequest request = parser.parse(parameters("bbox", "-180,-90,180,-89.99"));
        assertThat(request.boundingBox().minLongitude()).isEqualTo(-180);
        assertThat(request.boundingBox().maxLongitude()).isEqualTo(180);
    }

    @Test
    void rejectsMalformedOutOfRangeWrappingOrOversizedBounds() {
        assertInvalid("bbox", "20,44,21", "must contain");
        assertInvalid("bbox", "east,44,21,45", "finite decimal");
        assertInvalid("bbox", "NaN,44,21,45", "must be finite");
        assertInvalid("bbox", "20,-91,21,45", "latitude");
        assertInvalid("bbox", "181,44,182,45", "longitude");
        assertInvalid("bbox", "21,44,20,45", "minLongitude");
        assertInvalid("bbox", "20,45,21,44", "minLatitude");
        assertInvalid("bbox", "-20,-20,20,20", "area");
    }

    @Test
    void rejectsUnknownRepeatedAndNonAllowlistedFilters() {
        MultiValueMap<String, String> unknown = parameters("bbox", "18,41,24,47");
        unknown.add("search", "anything");
        assertThatThrownBy(() -> parser.parse(unknown))
                .isInstanceOf(InvalidMapRequestException.class)
                .hasMessage("unsupported query parameter");

        String oversizedField = "x".repeat(1_000) + "\u202e";
        MultiValueMap<String, String> oversized = parameters("bbox", "18,41,24,47");
        oversized.add(oversizedField, "anything");
        InvalidMapRequestException failure = catchThrowableOfType(
                InvalidMapRequestException.class, () -> parser.parse(oversized));
        assertThat(failure.field()).hasSize(64).doesNotContain("\u202e");

        MultiValueMap<String, String> repeated = parameters("bbox", "18,41,24,47");
        repeated.add("bbox", "19,42,20,43");
        assertThatThrownBy(() -> parser.parse(repeated))
                .isInstanceOf(InvalidMapRequestException.class)
                .hasMessage("query parameter must occur exactly once");

        assertOptionalInvalid("status", "Deleted", "status must be one of");
        assertOptionalInvalid("kind", "<script>alert(1)</script>", "category allowlist");
        assertOptionalInvalid("precision", "NONE", "precision must be one of");
    }

    @Test
    void rejectsMissingBoundsInvalidLimitsAndInvalidDateRanges() {
        assertThatThrownBy(() -> parser.parse(new LinkedMultiValueMap<>()))
                .isInstanceOf(InvalidMapRequestException.class)
                .hasMessage("bbox is required");
        assertOptionalInvalid("limit", "0", "between 1 and 5000");
        assertOptionalInvalid("limit", "5001", "between 1 and 5000");
        assertOptionalInvalid("limit", "1.5", "between 1 and 5000");
        assertOptionalInvalid("from", "23-08-2026", "YYYY-MM-DD");

        MultiValueMap<String, String> reversed = parameters("bbox", "18,41,24,47");
        reversed.add("from", "2026-08-24");
        reversed.add("to", "2026-08-23");
        assertThatThrownBy(() -> parser.parse(reversed))
                .isInstanceOf(InvalidMapRequestException.class)
                .hasMessage("to must be the same as or later than from");
    }

    private void assertInvalid(String field, String value, String message) {
        assertThatThrownBy(() -> parser.parse(parameters(field, value)))
                .isInstanceOf(InvalidMapRequestException.class)
                .hasMessageContaining(message);
    }

    private void assertOptionalInvalid(String field, String value, String message) {
        MultiValueMap<String, String> parameters = parameters("bbox", "18,41,24,47");
        parameters.add(field, value);
        assertThatThrownBy(() -> parser.parse(parameters))
                .isInstanceOf(InvalidMapRequestException.class)
                .hasMessageContaining(message);
    }

    private static MultiValueMap<String, String> parameters(String key, String value) {
        LinkedMultiValueMap<String, String> parameters = new LinkedMultiValueMap<>();
        parameters.add(key, value);
        return parameters;
    }
}
