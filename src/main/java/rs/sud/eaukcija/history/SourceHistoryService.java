package rs.sud.eaukcija.history;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Bounded, read-only backend contract for #56. No historical geometry/search replay. */
@Service
@Profile("!local-h2")
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, noRollbackFor = SourceHistoryService.BoundaryException.class)
public class SourceHistoryService {
    public static final int MAX_PAGE = 200;
    private final JdbcTemplate jdbc;
    public SourceHistoryService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** All three coordinates are required: run UUID prevents restored-branch sequence reuse. */
    public record Reference(UUID lineage, long sequence, UUID runId) { }
    public record Frame(Reference publication, Instant publishedAt, Instant evaluatedAt,
                        Reference earliestPublication, Instant earliestPublishedAt, String coverage) { }
    public record Cursor(long sequence, long auctionId) { }
    public record Lifecycle(String transition, String fromState, String toState, String fromReason,
                            String toReason, Instant previousEndAt, Instant endAt,
                            Instant absenceEffectiveAt, Instant effectiveAt, Instant recordedAt) { }
    public record Activity(long auctionId, Reference publication, Instant publishedAt,
                           String contentDelta, String comparisonPolicy, String comparisonKind,
                           List<String> changedFields, Lifecycle lifecycle) { }
    public record Page(Frame upper, List<Activity> activities, Cursor next) { }
    public record Review(long auctionId, Reference revision, Instant evaluatedAt, String comparisonPolicy) { }
    public record Revision(long auctionId, Instant firstObservedAt, UUID firstObservedRunId,
                           Reference lastObserved, Instant lastObservedAt,
                           Reference lastSourceChange, Instant lastSourceChangeRecordedAt, Review review,
                           Instant endAt, Boolean endedByDate, Lifecycle lifecycle, String coverage) { }
    public record Comparison(long auctionId, Frame upper, Review review, Lifecycle lifecycle, SourceComparisonPolicy.Difference net,
                             long sourceActivityCount, long lifecycleActivityCount, boolean elapsedEnd,
                             Instant beforeEndAt, Instant afterEndAt, String coverage) { }

    public static final class BoundaryException extends IllegalArgumentException {
        private final String code;
        public BoundaryException(String code) { super(code); this.code = code; }
        public String code() { return code; }
    }

    /** Call inside the SAME repeatable-read transaction that reads displayed rows. */
    public Frame capture(Instant evaluatedAt) {
        if (evaluatedAt == null) throw new BoundaryException("INVALID_EVALUATION_INSTANT");
        UUID lineage = lineage();
        var latest = jdbc.query("SELECT * FROM source_publications WHERE run_id IN (SELECT id FROM sync_runs WHERE status='SUCCEEDED') ORDER BY publication_id DESC LIMIT 1",
                (r, n) -> new Frame(reference(r, lineage), instant(r, "published_at"), evaluatedAt,
                        null, null, null));
        Reference upper = latest.isEmpty() ? new Reference(lineage, 0, null) : latest.get(0).publication();
        return frame(upper, evaluatedAt);
    }

    /** Inclusive date lookup: all publications at the requested timestamp are included. */
    public Reference atOrBefore(Instant time) {
        if (time == null) throw new BoundaryException("INVALID_DATE_BOUNDARY");
        var result = jdbc.query("""
                SELECT * FROM source_publications WHERE published_at <= ?
                    AND run_id IN (SELECT id FROM sync_runs WHERE status='SUCCEEDED')
                ORDER BY published_at DESC, publication_id DESC LIMIT 1
                """, (r, n) -> reference(r, lineage()), databaseTime(time));
        if (result.isEmpty()) throw new BoundaryException("HISTORICAL_COVERAGE_UNAVAILABLE");
        return result.get(0);
    }

    public Page changes(Reference lower, Reference upper, Instant evaluatedAt, Cursor after, int limit) {
        return activityPage(null, lower, upper, evaluatedAt, after, limit);
    }

    public Page auctionChanges(long auctionId, Reference lower, Reference upper, Instant evaluatedAt, Cursor after, int limit) {
        if (auctionId < 1) throw new BoundaryException("INVALID_AUCTION_ID");
        return activityPage(auctionId, lower, upper, evaluatedAt, after, limit);
    }

    private Page activityPage(Long auctionId, Reference lower, Reference upper, Instant evaluatedAt, Cursor after, int limit) {
        validateRange(lower, upper);
        if (limit < 1 || limit > MAX_PAGE) throw new BoundaryException("INVALID_PAGE_SIZE");
        if (after != null && (after.sequence() <= lower.sequence() || after.sequence() > upper.sequence()
                || after.auctionId() < 1)) throw new BoundaryException("INVALID_CURSOR");
        var rows = jdbc.query("""
                WITH events AS (
                    (SELECT publication_id, auction_id FROM sync_run_auction_observations
                     WHERE publication_id > ? AND publication_id <= ?
                       AND (content_delta <> 'UNCHANGED' OR comparison_kind = 'BASELINE')
                       AND (?::bigint IS NULL OR auction_id = ?)
                       AND (publication_id, auction_id) > (?, ?)
                     ORDER BY publication_id, auction_id LIMIT ?)
                    UNION
                    (SELECT publication_id, auction_id FROM auction_lifecycle_transitions
                     WHERE publication_id > ? AND publication_id <= ?
                       AND (?::bigint IS NULL OR auction_id = ?)
                       AND (publication_id, auction_id) > (?, ?)
                     ORDER BY publication_id, auction_id LIMIT ?)
                ), page AS (
                    SELECT * FROM events ORDER BY publication_id, auction_id LIMIT ?
                )
                SELECT page.auction_id, p.*, o.content_delta, o.comparison_policy, o.comparison_kind,
                       o.changed_fields, t.transition_kind, t.from_state, t.to_state, t.from_reason, t.to_reason,
                       t.previous_end_at, t.retained_end_at, t.absence_effective_at, t.effective_at
                  FROM page JOIN source_publications p USING(publication_id)
                  LEFT JOIN sync_run_auction_observations o ON o.publication_id = page.publication_id
                        AND o.auction_id = page.auction_id
                  LEFT JOIN auction_lifecycle_transitions t ON t.publication_id = page.publication_id
                        AND t.auction_id = page.auction_id
                 ORDER BY page.publication_id, page.auction_id
                """, (r, n) -> new Activity(r.getLong("auction_id"), reference(r, upper.lineage()),
                instant(r, "published_at"), r.getString("content_delta"), r.getString("comparison_policy"),
                r.getString("comparison_kind"), fields(r), lifecycle(r)),
                lower.sequence(), upper.sequence(), auctionId, auctionId,
                after == null ? lower.sequence() : after.sequence(), after == null ? 0 : after.auctionId(), limit + 1,
                lower.sequence(), upper.sequence(), auctionId, auctionId,
                after == null ? lower.sequence() : after.sequence(), after == null ? 0 : after.auctionId(), limit + 1,
                limit + 1);
        boolean more = rows.size() > limit;
        List<Activity> page = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
        Activity last = page.isEmpty() ? null : page.get(page.size() - 1);
        return new Page(frame(upper, evaluatedAt), page, more ? new Cursor(last.publication().sequence(), last.auctionId()) : null);
    }

    /** Batch display metadata; upper is the frame captured with the displayed catalogue rows. */
    public List<Revision> revisions(List<Long> ids, Reference upper, Instant evaluatedAt) {
        validate(upper);
        if (ids == null || ids.size() > MAX_PAGE || ids.stream().anyMatch(id -> id == null || id < 1))
            throw new BoundaryException("INVALID_AUCTION_IDS");
        if (evaluatedAt == null) throw new BoundaryException("INVALID_EVALUATION_INSTANT");
        if (ids.isEmpty()) return List.of();
        return new NamedParameterJdbcTemplate(jdbc).query("""
                SELECT a.id, a.first_reliable_observed_at, a.first_reliable_sync_run_id,
                       o.publication_id observed_id, op.run_id observed_run, observed_run.category_tree_observed_at last_observed_at,
                       o.retained_end_at, c.publication_id changed_id, cp.run_id changed_run, cp.published_at changed_recorded_at,
                       rp.publication_id review_id, rp.run_id review_run, ro.comparison_policy review_policy,
                       t.transition_kind, t.from_state, t.to_state, t.from_reason, t.to_reason,
                       t.previous_end_at, t.retained_end_at lifecycle_end_at, t.absence_effective_at, t.effective_at,
                       tp.published_at
                  FROM auctions a
                  LEFT JOIN LATERAL (
                    SELECT * FROM sync_run_auction_observations WHERE auction_id = a.id AND publication_id <= :upper
                     ORDER BY publication_id DESC LIMIT 1
                  ) o ON true
                  LEFT JOIN source_publications op ON op.publication_id = o.publication_id
                  LEFT JOIN sync_runs observed_run ON observed_run.id = op.run_id
                  LEFT JOIN LATERAL (
                    SELECT publication_id FROM sync_run_auction_observations
                     WHERE auction_id = a.id AND publication_id <= :upper AND content_delta IN ('NEW','UPDATED')
                     ORDER BY publication_id DESC LIMIT 1
                  ) c ON true
                  LEFT JOIN source_publications cp ON cp.publication_id = c.publication_id
                  LEFT JOIN LATERAL (
                    SELECT max(publication_id) id FROM (
                      (SELECT publication_id FROM sync_run_auction_observations WHERE auction_id = a.id
                        AND publication_id <= :upper AND (content_delta <> 'UNCHANGED' OR comparison_kind = 'BASELINE')
                        ORDER BY publication_id DESC LIMIT 1)
                      UNION ALL
                      (SELECT publication_id FROM auction_lifecycle_transitions WHERE auction_id = a.id
                        AND publication_id <= :upper ORDER BY publication_id DESC LIMIT 1)
                    ) changes
                  ) revision ON true
                  LEFT JOIN source_publications rp ON rp.publication_id = revision.id
                  LEFT JOIN LATERAL (
                    SELECT comparison_policy FROM sync_run_auction_observations WHERE auction_id = a.id
                        AND publication_id <= revision.id ORDER BY publication_id DESC LIMIT 1
                  ) ro ON true
                  LEFT JOIN LATERAL (
                    SELECT * FROM auction_lifecycle_transitions WHERE auction_id = a.id AND publication_id <= :upper
                     ORDER BY publication_id DESC LIMIT 1
                  ) t ON true
                  LEFT JOIN source_publications tp ON tp.publication_id = t.publication_id
                 WHERE a.id IN (:ids) ORDER BY a.id
                """, Map.of("ids", ids, "upper", upper.sequence()), (r, n) -> {
            Reference observed = nullableReference(r, "observed_id", "observed_run", upper.lineage());
            Reference changed = nullableReference(r, "changed_id", "changed_run", upper.lineage());
            Reference revision = nullableReference(r, "review_id", "review_run", upper.lineage());
            Instant end = instant(r, "retained_end_at");
            return new Revision(r.getLong("id"), observed == null ? null : instant(r, "first_reliable_observed_at"),
                    observed == null ? null : r.getObject("first_reliable_sync_run_id", UUID.class), observed,
                    instant(r, "last_observed_at"), changed, instant(r, "changed_recorded_at"),
                    revision == null ? null : new Review(r.getLong("id"), revision, evaluatedAt, r.getString("review_policy")),
                    end, end == null ? null : !end.isAfter(evaluatedAt), lifecycle(r, "lifecycle_end_at"),
                    observed == null ? "UNKNOWN_BASELINE" : "RETAINED_SOURCE");
        });
    }

    public Comparison compare(Review reviewed, Reference upper, Instant evaluatedAt) {
        if (reviewed == null || reviewed.auctionId() < 1 || reviewed.evaluatedAt() == null || evaluatedAt == null
                || evaluatedAt.isBefore(reviewed.evaluatedAt())) throw new BoundaryException("INVALID_REVIEW");
        validateRange(reviewed.revision(), upper);
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM sync_run_auction_observations WHERE auction_id = ?
                    AND publication_id = ? AND (content_delta <> 'UNCHANGED' OR comparison_kind = 'BASELINE')) OR EXISTS(
                    SELECT 1 FROM auction_lifecycle_transitions WHERE auction_id = ? AND publication_id = ?)
                """, Boolean.class, reviewed.auctionId(), reviewed.revision().sequence(), reviewed.auctionId(), reviewed.revision().sequence());
        if (!Boolean.TRUE.equals(exists)) throw new BoundaryException("UNKNOWN_AUCTION_REVISION");
        var before = snapshot(reviewed.auctionId(), reviewed.revision().sequence());
        var after = snapshot(reviewed.auctionId(), upper.sequence());
        boolean compatible = before != null && after != null
                && java.util.Objects.equals(before.policy, reviewed.comparisonPolicy())
                && SourceComparisonPolicy.supported(before.schema, before.minimization, before.policy)
                && SourceComparisonPolicy.supported(after.schema, after.minimization, after.policy);
        var difference = SourceComparisonPolicy.compare(before == null ? null : before.payload,
                after == null ? null : after.payload, compatible, before != null && after != null && before.hash.equals(after.hash));
        var counts = jdbc.queryForMap("""
                SELECT (SELECT count(*) FROM sync_run_auction_observations WHERE auction_id = ?
                    AND publication_id > ? AND publication_id <= ? AND content_delta IN ('NEW','UPDATED')) source_count,
                    (SELECT count(*) FROM auction_lifecycle_transitions WHERE auction_id = ?
                    AND publication_id > ? AND publication_id <= ?) lifecycle_count
                """, reviewed.auctionId(), reviewed.revision().sequence(), upper.sequence(),
                reviewed.auctionId(), reviewed.revision().sequence(), upper.sequence());
        Instant beforeEnd = before == null ? null : before.end;
        Instant afterEnd = after == null ? null : after.end;
        boolean elapsed = beforeEnd != null && beforeEnd.equals(afterEnd)
                && beforeEnd.isAfter(reviewed.evaluatedAt()) && !beforeEnd.isAfter(evaluatedAt);
        var current = revisions(List.of(reviewed.auctionId()), upper, evaluatedAt).get(0);
        return new Comparison(reviewed.auctionId(), frame(upper, evaluatedAt), current.review(), current.lifecycle(), difference,
                ((Number) counts.get("source_count")).longValue(), ((Number) counts.get("lifecycle_count")).longValue(),
                elapsed, beforeEnd, afterEnd, compatible ? "RETAINED_SOURCE" : "INCOMPATIBLE_OR_UNKNOWN_BASELINE");
    }

    private record Snapshot(String hash, com.fasterxml.jackson.databind.JsonNode payload, String schema,
                            String minimization, String policy, Instant end) { }
    private Snapshot snapshot(long auctionId, long upper) {
        var rows = jdbc.query("""
                SELECT s.*, o.retained_end_at, o.comparison_policy FROM (
                    SELECT * FROM sync_run_auction_observations WHERE auction_id = ? AND publication_id <= ?
                    ORDER BY publication_id DESC LIMIT 1
                ) o JOIN auction_source_snapshots s ON s.auction_id = o.auction_id
                    AND s.content_sha256 = o.source_snapshot_sha256
                """, (r, n) -> new Snapshot(r.getString("content_sha256"), SourceHistoryPublisher.parse(r.getString("canonical_payload")),
                r.getString("schema_version"), r.getString("minimization_policy_version"), r.getString("comparison_policy"),
                instant(r, "retained_end_at")), auctionId, upper);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Frame frame(Reference upper, Instant evaluatedAt) {
        if (evaluatedAt == null) throw new BoundaryException("INVALID_EVALUATION_INSTANT");
        Instant publishedAt = validate(upper);
        var earliest = jdbc.query("SELECT * FROM source_publications WHERE run_id IN (SELECT id FROM sync_runs WHERE status='SUCCEEDED') ORDER BY publication_id LIMIT 1",
                (r, n) -> new Frame(null, null, null, reference(r, upper.lineage()), instant(r, "published_at"), null));
        boolean legacy = Boolean.TRUE.equals(jdbc.queryForObject("SELECT legacy_coverage FROM source_history_lineage", Boolean.class));
        return new Frame(upper, publishedAt, evaluatedAt, earliest.isEmpty() ? null : earliest.get(0).earliestPublication(),
                earliest.isEmpty() ? null : earliest.get(0).earliestPublishedAt(), legacy ? "PARTIAL_PRE_HISTORY" : "COMPLETE_SINCE_EMPTY");
    }
    private UUID lineage() { return jdbc.queryForObject("SELECT lineage FROM source_history_lineage WHERE singleton", UUID.class); }
    /** Validates the full lineage/sequence/run coordinate without moving it. */
    public Instant validate(Reference ref) {
        if (ref == null || ref.lineage() == null || ref.sequence() < 0) throw new BoundaryException("INVALID_REFERENCE");
        if (!lineage().equals(ref.lineage())) throw new BoundaryException("FOREIGN_LINEAGE");
        if (ref.sequence() == 0 && ref.runId() == null) return null;
        var times = jdbc.query("SELECT published_at FROM source_publications WHERE publication_id = ? AND run_id = ? "
                        + "AND run_id IN (SELECT id FROM sync_runs WHERE status='SUCCEEDED')",
                (r, n) -> instant(r, "published_at"), ref.sequence(), ref.runId());
        if (times.isEmpty()) throw new BoundaryException("UNKNOWN_PUBLICATION");
        return times.get(0);
    }
    private void validateRange(Reference lower, Reference upper) {
        validate(lower); validate(upper);
        if (lower.sequence() > upper.sequence()) throw new BoundaryException("REVERSED_BOUNDARIES");
    }
    private static Reference reference(ResultSet r, UUID lineage) throws SQLException {
        return new Reference(lineage, r.getLong("publication_id"), r.getObject("run_id", UUID.class));
    }
    private static Reference nullableReference(ResultSet r, String id, String run, UUID lineage) throws SQLException {
        Long value = r.getObject(id, Long.class);
        return value == null ? null : new Reference(lineage, value, r.getObject(run, UUID.class));
    }
    private static List<String> fields(ResultSet r) throws SQLException {
        var array = r.getArray("changed_fields");
        return array == null ? List.of() : List.of((String[]) array.getArray());
    }
    private static Lifecycle lifecycle(ResultSet r) throws SQLException { return lifecycle(r, "retained_end_at"); }
    private static Lifecycle lifecycle(ResultSet r, String endColumn) throws SQLException {
        if (r.getString("transition_kind") == null) return null;
        return new Lifecycle(r.getString("transition_kind"), r.getString("from_state"), r.getString("to_state"),
                r.getString("from_reason"), r.getString("to_reason"), instant(r, "previous_end_at"),
                instant(r, endColumn), instant(r, "absence_effective_at"), instant(r, "effective_at"), instant(r, "published_at"));
    }
    private static Instant instant(ResultSet r, String name) throws SQLException {
        var value = r.getObject(name, OffsetDateTime.class); return value == null ? null : value.toInstant();
    }
    private static OffsetDateTime databaseTime(Instant instant) { return instant.atOffset(ZoneOffset.UTC); }
}
