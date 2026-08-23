package rs.sud.eaukcija.map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import rs.sud.eaukcija.spatial.BoundingBox;
import rs.sud.eaukcija.spatial.LocationSelectionSql;

class MapAuctionRepositoryUnitTest {

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void performsOneBoundedJdbcQuerySoFeatureHydrationCannotBecomeNPlusOne() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        MapAuctionRepository repository = new MapAuctionRepository(jdbc);
        MapAuctionRequest request = new MapAuctionRequest(
                new BoundingBox(18, 41, 24, 47), null, null, null,
                Instant.parse("2026-08-23T00:00:00Z"), null, 5000);

        assertThat(repository.findWithin(request)).isEmpty();

        verify(jdbc, times(1)).query(anyString(), any(RowMapper.class), any(Object[].class));
        verifyNoMoreInteractions(jdbc);
        assertThat(MapAuctionRepository.VIEWPORT_QUERY)
                .contains("geometry.canonical_geometry && viewport.bounds")
                .contains("ST_Intersects(geometry.canonical_geometry, viewport.bounds)")
                .contains("JOIN auctions")
                .contains("pr.extraction_status IN ('EXTRACTED', 'USER_CONFIRMED')")
                .doesNotContain("NO_STRUCTURED_REFERENCE")
                .contains("ORDER BY auction_id, md5(property_key)")
                .contains("LIMIT ?");
        assertThat(MapAuctionRepository.BEST_SELECTION_ORDER).isEqualTo(LocationSelectionSql.bestOrder(
                "location_precision", "reference_order", "completed_at", "resolution_attempt_id"));
        assertThat(MapAuctionRepository.VIEWPORT_QUERY).contains(MapAuctionRepository.BEST_SELECTION_ORDER);
    }

    @Test
    void requestObjectCannotRepresentAnUnboundedRead() {
        assertThatThrownBy(() -> new MapAuctionRequest(
                new BoundingBox(18, 41, 24, 47), null, null, null,
                Instant.parse("2026-08-23T00:00:00Z"), null, 5_001))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("limit must be between 1 and 5000");
    }
}
