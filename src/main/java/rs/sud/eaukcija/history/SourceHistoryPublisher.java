package rs.sud.eaukcija.history;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import rs.sud.eaukcija.snapshot.AuctionSourceCanonicalJson;
import rs.sud.eaukcija.sync.persistence.AuctionPromotionCandidate;

/** Called only inside the existing success-gated promotion transaction. No independent worker. */
public final class SourceHistoryPublisher {
    private final JdbcTemplate jdbc;
    public SourceHistoryPublisher(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Observation(long auctionId, String delta, SourceComparisonPolicy.Difference difference) { }
    public record Publication(long id, UUID runId, Instant recordedAt, Instant observedAt,
                              List<Observation> observations) { }
    private record Prior(String hash, JsonNode payload, String schema, String minimization, Long publication, String policy) { }

    public Publication prepare(UUID runId, Instant now, Instant observedAt, List<AuctionPromotionCandidate> candidates) {
        // Publication serialization is deliberately independent of UUID/start-time ordering.
        jdbc.queryForObject("SELECT lineage FROM source_history_lineage WHERE singleton FOR UPDATE", UUID.class);
        record Stamp(long id, Instant at) { }
        var stamp = jdbc.queryForObject("""
                INSERT INTO source_publications(run_id, published_at)
                SELECT id, greatest(?, started_at,
                    (SELECT max(published_at) FROM source_publications))
                  FROM sync_runs WHERE id = ? AND status = 'RUNNING'
                RETURNING publication_id, published_at
                """, (r, n) -> new Stamp(r.getLong("publication_id"), r.getObject("published_at", OffsetDateTime.class).toInstant()),
                time(now), runId);
        Map<Long, Prior> prior = new HashMap<>();
        var named = new NamedParameterJdbcTemplate(jdbc);
        for (int offset = 0; offset < candidates.size(); offset += 1000) {
            var ids = candidates.subList(offset, Math.min(offset + 1000, candidates.size())).stream()
                    .map(c -> c.auction().getId()).toList();
            named.query("""
                    SELECT a.id, a.last_observation_publication, o.comparison_policy,
                           s.content_sha256, s.canonical_payload::text, s.schema_version, s.minimization_policy_version
                      FROM auctions a LEFT JOIN auction_source_snapshots s
                        ON s.auction_id = a.id AND s.content_sha256 = a.current_source_snapshot_sha256
                      LEFT JOIN sync_run_auction_observations o ON o.auction_id = a.id
                        AND o.publication_id = a.last_observation_publication
                     WHERE a.id IN (:ids)
                    """, Map.of("ids", ids), r -> {
                String payload = r.getString("canonical_payload");
                prior.put(r.getLong("id"), new Prior(r.getString("content_sha256"),
                        payload == null ? null : parse(payload), r.getString("schema_version"),
                        r.getString("minimization_policy_version"), r.getObject("last_observation_publication", Long.class),
                        r.getString("comparison_policy")));
            });
        }
        List<Observation> observations = new ArrayList<>();
        for (var candidate : candidates) {
            var snapshot = candidate.sourceSnapshot();
            Prior old = prior.get(snapshot.auctionId());
            boolean equal = old != null && snapshot.contentSha256().equals(old.hash());
            String delta = old == null ? "NEW" : old.hash() == null ? "BASELINE" : equal ? "UNCHANGED" : "UPDATED";
            boolean compatible = SourceComparisonPolicy.supported(snapshot.schemaVersion(), snapshot.minimizationPolicyVersion(),
                    SourceComparisonPolicy.VERSION) && (old == null || old.hash() == null
                    || SourceComparisonPolicy.supported(old.schema(), old.minimization(), SourceComparisonPolicy.VERSION));
            var difference = SourceComparisonPolicy.compare(
                    old == null ? null : old.payload(), snapshot.canonicalPayload(), compatible, equal);
            boolean policyChanged = old != null && old.policy() != null && !SourceComparisonPolicy.VERSION.equals(old.policy());
            if (equal && (old.publication() == null || policyChanged) && compatible) {
                difference = new SourceComparisonPolicy.Difference(SourceComparisonPolicy.VERSION, "BASELINE", List.of());
            } else if (policyChanged) {
                difference = new SourceComparisonPolicy.Difference(SourceComparisonPolicy.VERSION, "UNSUPPORTED", List.of());
            }
            observations.add(new Observation(snapshot.auctionId(), delta, difference));
        }
        return new Publication(stamp.id(), runId, stamp.at(), observedAt, List.copyOf(observations));
    }

    public void publish(Publication publication, Duration grace) {
        jdbc.batchUpdate("""
                UPDATE sync_run_auction_observations o SET publication_id = ?, content_delta = ?,
                    comparison_policy = ?, comparison_kind = ?, changed_fields = ?, retained_end_at = a.end_date
                  FROM auctions a WHERE o.run_id = ? AND o.auction_id = ? AND a.id = o.auction_id
                """, publication.observations(), 1000, (s, o) -> {
            s.setLong(1, publication.id()); s.setString(2, o.delta());
            s.setString(3, o.difference().policy()); s.setString(4, o.difference().kind());
            s.setArray(5, s.getConnection().createArrayOf("text", o.difference().fields().toArray()));
            s.setObject(6, publication.runId()); s.setLong(7, o.auctionId());
        });
        jdbc.update("""
                UPDATE auctions a SET
                    first_reliable_observed_at = coalesce(first_reliable_observed_at, ?),
                    first_reliable_sync_run_id = CASE WHEN first_reliable_observed_at IS NULL THEN ?
                        ELSE first_reliable_sync_run_id END,
                    last_observation_publication = ?,
                    last_source_change_publication = CASE WHEN o.content_delta IN ('NEW','UPDATED')
                        THEN ? ELSE last_source_change_publication END,
                    review_publication = CASE WHEN o.content_delta <> 'UNCHANGED' OR o.comparison_kind = 'BASELINE'
                        THEN ? ELSE review_publication END,
                    first_absent_at = NULL, first_absence_publication = NULL,
                    absence_closed_effective_at = NULL
                  FROM sync_run_auction_observations o WHERE o.run_id = ? AND o.auction_id = a.id
                """, time(publication.observedAt()), publication.runId(), publication.id(), publication.id(),
                publication.id(), publication.runId());
        jdbc.update("""
                UPDATE auctions SET absence_closed_effective_at = first_absent_at + (? * interval '1 millisecond')
                 WHERE last_absence_sync_run_id = ? AND absence_count >= 2
                   AND first_absence_publication < ? AND absence_closed_effective_at IS NULL
                   AND first_absent_at + (? * interval '1 millisecond') <= ?
                """, grace.toMillis(), publication.runId(), publication.id(), grace.toMillis(), time(publication.recordedAt()));
        jdbc.update("""
                INSERT INTO auction_lifecycle_transitions(auction_id, publication_id, transition_kind,
                    from_state, to_state, from_reason, to_reason, previous_end_at, retained_end_at,
                    absence_effective_at, effective_at)
                WITH desired AS (
                    SELECT a.*, CASE
                        WHEN end_date <= ? AND absence_closed_effective_at IS NOT NULL THEN 'END_DATE_AND_ABSENCE'
                        WHEN end_date <= ? THEN 'END_DATE'
                        WHEN absence_closed_effective_at IS NOT NULL THEN 'ABSENCE'
                        WHEN end_date IS NULL THEN 'UNKNOWN' ELSE 'FUTURE_END' END AS reason
                    FROM auctions a WHERE last_observation_publication = ? OR last_absence_sync_run_id = ?
                ), states AS (
                    SELECT *, CASE WHEN reason IN ('END_DATE','ABSENCE','END_DATE_AND_ABSENCE') THEN 'ENDED'
                        WHEN reason = 'FUTURE_END' THEN 'NOT_ENDED' ELSE 'UNKNOWN' END AS state
                    FROM desired
                )
                SELECT id, ?, CASE
                    WHEN lifecycle_state <> 'ENDED' AND state = 'ENDED' THEN 'CLOSED'
                    WHEN lifecycle_state = 'ENDED' AND state = 'NOT_ENDED' THEN 'REOPENED'
                    WHEN lifecycle_end_at IS DISTINCT FROM end_date THEN 'END_TIME_CHANGED'
                    ELSE 'REASON_CHANGED' END,
                    lifecycle_state, state, lifecycle_reason, reason, lifecycle_end_at, end_date,
                    absence_closed_effective_at,
                    CASE WHEN reason IN ('END_DATE','END_DATE_AND_ABSENCE') THEN end_date
                         WHEN reason = 'ABSENCE' THEN absence_closed_effective_at ELSE ? END
                  FROM states WHERE lifecycle_state <> state OR lifecycle_reason <> reason
                     OR lifecycle_end_at IS DISTINCT FROM end_date
                """, time(publication.recordedAt()), time(publication.recordedAt()), publication.id(), publication.runId(),
                publication.id(), time(publication.recordedAt()));
        jdbc.update("""
                UPDATE auctions a SET lifecycle_state = t.to_state, lifecycle_reason = t.to_reason,
                    lifecycle_end_at = t.retained_end_at, review_publication = t.publication_id,
                    closed_recorded_at = CASE WHEN t.to_state = 'ENDED' AND t.from_state <> 'ENDED'
                        THEN ? ELSE a.closed_recorded_at END,
                    closed_effective_at = CASE WHEN t.to_state = 'ENDED' THEN t.effective_at
                        ELSE a.closed_effective_at END,
                    closed_reason = CASE WHEN t.to_state = 'ENDED' THEN t.to_reason ELSE a.closed_reason END,
                    reopened_recorded_at = CASE WHEN t.transition_kind = 'REOPENED'
                        THEN ? ELSE a.reopened_recorded_at END,
                    reopened_reason = CASE WHEN t.transition_kind = 'REOPENED' THEN
                        CASE WHEN t.previous_end_at IS DISTINCT FROM t.retained_end_at THEN 'END_TIME_CHANGED'
                             ELSE 'ABSENCE_CLEARED' END ELSE a.reopened_reason END
                  FROM auction_lifecycle_transitions t WHERE t.publication_id = ? AND t.auction_id = a.id
                """, time(publication.recordedAt()), time(publication.recordedAt()), publication.id());
        jdbc.update("""
                UPDATE source_publications p SET
                    absent_count = (SELECT count(*) FROM auctions WHERE last_absence_sync_run_id = p.run_id),
                    closed_count = (SELECT count(*) FROM auction_lifecycle_transitions
                        WHERE publication_id = p.publication_id AND transition_kind = 'CLOSED'),
                    reopened_count = (SELECT count(*) FROM auction_lifecycle_transitions
                        WHERE publication_id = p.publication_id AND transition_kind = 'REOPENED')
                 WHERE p.publication_id = ?
                """, publication.id());
    }

    public static JsonNode parse(String payload) {
        try { return AuctionSourceCanonicalJson.readTree(payload); }
        catch (Exception invalid) { throw new IllegalStateException("Invalid retained source evidence"); }
    }
    private static OffsetDateTime time(Instant value) { return value == null ? null : value.atOffset(ZoneOffset.UTC); }
}
