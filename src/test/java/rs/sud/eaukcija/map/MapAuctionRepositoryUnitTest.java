package rs.sud.eaukcija.map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowMapper;
import rs.sud.eaukcija.spatial.BoundingBox;
import rs.sud.eaukcija.spatial.LocationSelectionSql;

class MapAuctionRepositoryUnitTest {
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void performsOneBoundedJdbcQuerySoFeatureHydrationCannotBecomeNPlusOne() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(any(PreparedStatementCreator.class), any(RowMapper.class))).thenReturn(List.of());
        MapAuctionRepository repository = new MapAuctionRepository(jdbc);
        MapAuctionRequest request = MapAuctionRepositoryTestAccess.request(
                new BoundingBox(18, 41, 24, 47), null, null, null,
                Instant.parse("2026-08-23T00:00:00Z"), null, 5000);
        assertThat(repository.findWithin(request)).isEmpty();
        verify(jdbc).query(any(PreparedStatementCreator.class), any(RowMapper.class));
        verifyNoMoreInteractions(jdbc);
        assertThat(MapAuctionRepository.query(request))
                .contains("geometry.canonical_geometry && viewport.bounds", "ST_Intersects(geometry.canonical_geometry, viewport.bounds)")
                .contains("JOIN auctions", "pr.extraction_status IN ('EXTRACTED', 'USER_CONFIRMED')")
                .contains("RGZ_WFS_PARCEL", "upstream_ko_match_input_fingerprint")
                .doesNotContain("NO_STRUCTURED_REFERENCE")
                .contains("ORDER BY a.id, md5(w.property_key)", "LIMIT :featureLimit")
                .contains(LocationSelectionSql.bestOrder("competitor.location_precision", "competitor.reference_order",
                        "competitor.completed_at", "competitor.resolution_attempt_id"));
    }
    @Test
    void requestObjectCannotRepresentAnUnboundedRead() {
        assertThatThrownBy(() -> MapAuctionRepositoryTestAccess.request(
                new BoundingBox(18, 41, 24, 47), null, null, null,
                Instant.parse("2026-08-23T00:00:00Z"), null, 5_001))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("limit must be between 1 and 5000");
    }
}
