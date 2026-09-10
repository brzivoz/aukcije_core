package rs.sud.eaukcija.map;

import static org.assertj.core.api.Assertions.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.web.util.UriComponentsBuilder;
import rs.sud.eaukcija.filter.AuctionFilterParser;
import rs.sud.eaukcija.filter.AuctionFilters;

class ChangesSinceParserTest {
    private AuctionFilters parse(String query) {
        return new AuctionFilterParser(null, Clock.fixed(Instant.parse("2026-09-09T12:00:00Z"), ZoneOffset.UTC))
                .parse(UriComponentsBuilder.fromUriString("/?" + query).build().getQueryParams(), Set.of());
    }
    @Test void belgradeCivilDateBecomesUnambiguousUtcAndLinksNeverCarryCivilOrPersonalState() {
        var filters = parse("since=date&sinceLocal=2026-08-24T12:30&changeKind=updated&liveBidding=true&search=njiva&page=2&auction=7");
        assertThat(filters.changes().at()).isEqualTo(Instant.parse("2026-08-24T10:30:00Z"));
        assertThat(filters.query()).contains("sinceAt=2026-08-24T10%3A30%3A00Z", "since=date", "changeKind=updated")
                .doesNotContain("sinceLocal", "sinceOffset");
        assertThat(filters.sortUrl("endDate")).contains("sinceAt=", "auction=7", "page=2");
        assertThat(filters.resetUrl()).doesNotContain("since", "changeKind", "liveBidding").contains("page=0", "auction=7");
        assertThat(parse("since=24h").query()).contains("since=24h").doesNotContain("sinceAt");
        assertThat(parse("since=7d").changes().at()).isNull();
    }
    @Test void gapsAreRejectedAndOverlapsRequireAnExplicitValidOffset() {
        assertThatThrownBy(() -> parse("since=date&sinceLocal=2026-03-29T02:30")).hasMessageContaining("не постоји");
        assertThatThrownBy(() -> parse("since=date&sinceLocal=2026-10-25T02:30")).hasMessageContaining("понавља");
        assertThat(parse("since=date&sinceLocal=2026-10-25T02:30&sinceOffset=+02:00").changes().at())
                .isEqualTo(Instant.parse("2026-10-25T00:30:00Z"));
        assertThat(parse("since=date&sinceLocal=2026-10-25T02:30&sinceOffset=+01:00").changes().at())
                .isEqualTo(Instant.parse("2026-10-25T01:30:00Z"));
        assertThatThrownBy(() -> parse("since=date&sinceLocal=2026-08-24T12:30&sinceOffset=+01:00")).hasMessageContaining("не важи");
    }
    @Test void invalidAndPersonalInputsCannotBecomeCanonicalServerCriteria() {
        for (String query : new String[]{"since=previous", "since=checkpoint", "since=date", "since=date&sinceAt=nonsense",
                "since=date&sinceAt=-1000000000-01-01T00:00:00Z", "since=publication&sinceAt=2026-08-24T12:00:00Z&publication=unknown",
                "changeKind=all", "liveBidding=yes", "since=24h&since=7d", "reviews=1,2"})
            assertThatThrownBy(() -> parse(query)).isInstanceOf(InvalidMapRequestException.class);
    }
}
