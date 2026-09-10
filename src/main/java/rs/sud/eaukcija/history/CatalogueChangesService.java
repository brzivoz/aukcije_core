package rs.sud.eaukcija.history;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import rs.sud.eaukcija.filter.AuctionFilters;
import rs.sud.eaukcija.filter.ChangeCriteria;
import rs.sud.eaukcija.map.InvalidMapRequestException;

/** Shared comparison resolution and bounded safe evidence. Never exports snapshots or description values. */
@Service
@Profile("!local-h2")
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class CatalogueChangesService {
    public static final int MAX_REVIEWS = 200;
    private static final java.time.format.DateTimeFormatter DISPLAY_TIME = java.time.format.DateTimeFormatter
            .ofPattern("dd.MM.yyyy. HH:mm XXX").withZone(AuctionFilters.ZONE);
    private final SourceHistoryService history;
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;
    public CatalogueChangesService(SourceHistoryService history, JdbcTemplate jdbc, ObjectMapper json) {
        this.history = history; this.jdbc = new NamedParameterJdbcTemplate(jdbc); this.json = json;
    }
    public record Evidence(long auctionId, SourceHistoryService.Review review, String bucket, List<String> fields,
                           boolean reverted, Long sourceChanges, Long biddingChanges, Long lifecycleChanges,
                           boolean elapsedEnd, SourceHistoryService.Lifecycle lifecycle, Instant endAt,
                           Boolean endedByDate, String coverage, String reviewState) {
        @com.fasterxml.jackson.annotation.JsonProperty public String badge() { return "NEW".equals(bucket) ? "Нова" : "UPDATED".equals(bucket) ? "Измењена" : ""; }
        @com.fasterxml.jackson.annotation.JsonProperty public String reasons() {
            return fields.stream().map(CatalogueChangesService::fieldLabel).distinct().collect(Collectors.joining(", "));
        }
        @com.fasterxml.jackson.annotation.JsonProperty public String explanation() {
            var parts = new ArrayList<String>();
            if ("NEW".equals(bucket)) parts.add("Први поуздан успешан налаз ове апликације; није датум објаве на еАукцији.");
            if (!fields.isEmpty()) parts.add(reasons() + ".");
            if (reverted) parts.add("У међувремену је било промена; крајње вредности су враћене на почетне.");
            if (sourceChanges != null && sourceChanges > 0 && !reverted) parts.add("Изворни садржај је промењен. Безбедни детаљи: називи поља; вредности нису изложене.");
            if (biddingChanges != null && biddingChanges > 0) parts.add("Лицитирање: промене само текуће цене (одвојено од садржаја).");
            if (elapsedEnd) parts.add("Истекао је познати рок завршетка; није нова измена извора.");
            if (lifecycleChanges != null && lifecycleChanges > 0) {
                parts.add("Забележена промена животног циклуса.");
                if (lifecycle != null) {
                    if (!Objects.equals(lifecycle.previousEndAt(), lifecycle.endAt()))
                        parts.add("Последња забележена промена рока (Београд): " + displayTime(lifecycle.previousEndAt())
                                + " → " + displayTime(lifecycle.endAt()) + ".");
                    if (lifecycle.fromReason().contains("ABSENCE") && !lifecycle.toReason().contains("ABSENCE"))
                        parts.add("Аукција је поново виђена у извору; одсуство више није разлог затварања.");
                    if (lifecycle.transition().equals("REOPENED"))
                        parts.add("Познати рок је поново у будућности; ово није потврда да је лицитирање отворено.");
                }
            }
            if (lifecycle != null && lifecycle.toReason().contains("ABSENCE")) parts.add("Задржани подаци: одсуство из потпуних успешних преузимања; није правни исход продаје.");
            if (Boolean.TRUE.equals(endedByDate)) parts.add("Завршена по познатом року.");
            if (!"RETAINED_SOURCE".equals(coverage)) parts.add("Докази поређења нису доступни или нису компатибилни. Сачувану потврду можете обрисати или поново прегледати подржане приказане податке.");
            return String.join(" ", parts);
        }
    }
    private static String displayTime(Instant time) { return time == null ? "Непознат рок" : DISPLAY_TIME.format(time); }
    public static String fieldLabel(String code) {
        return switch (code) {
            case "STARTING_PRICE" -> "Почетна цена"; case "ESTIMATED_PRICE" -> "Процењена цена";
            case "START_DATE" -> "Почетак"; case "END_DATE" -> "Завршетак"; case "SOURCE_STATUS" -> "Изворни статус";
            case "PROPERTY_TYPE" -> "Врста непокретности"; case "CATEGORY" -> "Категорија";
            case "STRUCTURED_PLACE" -> "Локација"; case "DESCRIPTION", "SHORT_DESCRIPTION" -> "Опис";
            case "AUCTION_NUMBER" -> "Број аукције"; case "FIRST_SALE" -> "Прва продаја";
            case "BID_STEP" -> "Корак понуде"; case "SOURCE_PUBLICATION_DATE" -> "Изворни датум објаве";
            case "EXECUTOR" -> "Извршитељ"; case "CURRENT_PRICE", "MAX_OFFERED_PRICE" -> "Текућа понуда";
            default -> "Непознато поље";
        };
    }
    public AuctionFilters prepare(AuctionFilters filters) {
        if (filters.changes().window() != null) {
            if (!history.capture(filters.asOf()).equals(filters.changes().window().upper()))
                throw new SourceHistoryService.BoundaryException("DISPLAY_PUBLICATION_CHANGED");
            return filters;
        }
        var frame = history.capture(filters.asOf());
        var c = filters.changes();
        SourceHistoryService.Reference lower = null;
        Instant at = c.at();
        String problem = null;
        if (c.active()) {
            if (c.since().equals("24h")) at = filters.asOf().minusSeconds(86400);
            if (c.since().equals("7d")) at = filters.asOf().minusSeconds(7 * 86400);
            try {
                if (at == null || at.isAfter(frame.evaluatedAt())) throw new SourceHistoryService.BoundaryException("FUTURE_BOUNDARY");
                lower = c.publication() == null ? history.atOrBefore(at) : c.publication();
                history.validate(lower);
                // Publication order and temporal evaluation are independent coordinates (#11).
                // A promotion may commit between selecting asOf and acquiring the MVCC snapshot.
                if (lower.sequence() > frame.publication().sequence())
                    throw new SourceHistoryService.BoundaryException("FUTURE_BOUNDARY");
            } catch (SourceHistoryService.BoundaryException e) { problem = e.code(); }
        }
        return filters.withChanges(c.resolved(new ChangeCriteria.Window(frame, lower, at, problem)));
    }
    public String notice(AuctionFilters filters) {
        var c = filters.changes();
        if (!c.active()) return "";
        if (c.window().problem() != null) {
            String reason = switch (c.window().problem()) {
                case "FOREIGN_LINEAGE" -> "Тачка припада другој бази података.";
                case "UNKNOWN_PUBLICATION" -> "Тачка није у доступној историји, нпр. после враћања базе.";
                case "FUTURE_BOUNDARY" -> "Тачка/време поређења су у будућности у односу на приказ.";
                default -> "Нема упоредиве историје за изабрану основу.";
            };
            Instant earliest = c.window().upper().earliestPublishedAt();
            if (earliest == null && c.window().problem().equals("HISTORICAL_COVERAGE_UNAVAILABLE"))
                return "Историја промена још није успостављена. Каталог из ранијих преузимања остаје доступан: ово су обични резултати, НЕ нула промена. "
                        + "Покрените ‘Освежи све податке’ и сачекајте успешно преузимање извора; поновно учитавање карте не успоставља историју. "
                        + "Затим користите ‘Користи ове податке као моју тачку поређења’ за наредне промене. "
                        + "Ранији период се не реконструише накнадно; ‘Последњих 7 дана’ захтева основу стару најмање седам дана. "
                        + "До тада изаберите ‘Било када’ или, после успешног преузимања, ‘Моја тачка’.";
            String recovery = c.window().problem().equals("HISTORICAL_COVERAGE_UNAVAILABLE")
                    ? "Затражени период почиње пре доступне историје. Изаберите датум од прве подржане публикације или сачувајте ‘Моју тачку’ за наредне промене. "
                        + "Ново преузимање не може попунити ранију историју; за обично прегледање изаберите ‘Било када’."
                    : "Изаберите доступан датум/тачку или обришите критеријум; тачка није померена.";
            return "Поређење није доступно (" + c.window().problem() + "). " + reason
                    + " Приказани су обични резултати, НЕ нула промена. "
                    + (earliest == null ? "Још нема забележене историје промена. " : "Прва подржана публикација (Београд): " + displayTime(earliest) + ". ")
                    + recovery;
        }
        return "Промене се укрштају са садашњим критеријумима; ово није историјски приказ. Завршене аукције су искључене под ‘Нису завршене’. "
                + ("PARTIAL_PRE_HISTORY".equals(c.window().upper().coverage()) ? "Старија историја је непотпуна; успостављање старе основе није нова аукција. " : "")
                + "Нула резултата значи да нема квалификованих промена у овом пресеку, не да је све прегледано.";
    }

    public record Summary(String since, String kind, boolean liveBidding, SourceHistoryService.Reference lower,
                          Instant lowerTime, String problem, String notice, Long newCount, Long updatedCount,
                          boolean incompleteEvidence) { }
    public Summary summary(AuctionFilters filters) {
        var c = filters.changes();
        if (!c.active()) return null;
        Long newCount = null, updatedCount = null;
        if (c.usable()) {
            newCount = c.kind().equals("updated") ? 0 : count(filters.withChanges(c.kind("new")));
            updatedCount = c.kind().equals("new") ? 0 : count(filters.withChanges(c.kind("updated")));
        }
        boolean gaps = c.usable() && !c.kind().equals("new") && Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM sync_run_auction_observations WHERE publication_id > :lower
                    AND publication_id <= :upper AND comparison_kind IN ('BASELINE','UNSUPPORTED') AND content_delta <> 'NEW')
                """, Map.of("lower", c.window().lower().sequence(), "upper", c.window().upper().publication().sequence()), Boolean.class));
        String note = notice(filters) + (gaps ? " У овом периоду постоје непокривене основе или промене политике поређења. "
                + "Бројеви обухватају само доказане квалификоване промене, не доказују нулу активности. За обично прегледање изаберите ‘Било када’." : "");
        return new Summary(c.since(), c.kind(), c.liveBidding(), c.window().lower(), c.window().lowerTime(),
                c.window().problem(), note, newCount, updatedCount, gaps);
    }
    private long count(AuctionFilters filters) {
        var p = rs.sud.eaukcija.filter.AuctionFilterSql.predicate(filters);
        return jdbc.queryForObject("WITH " + rs.sud.eaukcija.spatial.PublishableLocationSql.CTES
                + " SELECT count(*) FROM auctions a WHERE " + p.sql(), p.parameters(), Long.class);
    }

    /** Map limits remain bounded (<=5000); history reads are batches of <=200, never per-card queries. */
    public Map<Long, Evidence> display(List<Long> ids, AuctionFilters filters) {
        var c = filters.changes();
        var frame = c.window().upper();
        var lower = c.usable() ? c.window().lower() : frame.publication();
        var at = c.usable() ? c.window().lowerTime() : frame.evaluatedAt();
        List<SourceHistoryService.Review> inputs = ids.stream().distinct()
                .map(id -> new SourceHistoryService.Review(id, lower, at, SourceComparisonPolicy.VERSION)).toList();
        if (inputs.size() > 5025) throw new IllegalArgumentException("Display limit exceeded");
        Map<Long, Evidence> output = new LinkedHashMap<>();
        for (int start = 0; start < inputs.size(); start += MAX_REVIEWS)
            output.putAll(batch(inputs.subList(start, Math.min(start + MAX_REVIEWS, inputs.size())), frame, c.liveBidding(), false));
        return output;
    }

    /** Uses retained evidence at the displayed upper, not today's auction rows or client acknowledgement time. */
    public Map<Long, Evidence> reviewed(List<SourceHistoryService.Review> reviews, SourceHistoryService.Frame frame, boolean live) {
        if (reviews == null || reviews.size() > MAX_REVIEWS || frame == null || frame.publication() == null
                || frame.evaluatedAt() == null || frame.evaluatedAt().isAfter(Instant.now()))
            throw invalid("Највише 200 потврда и исправан приказани оквир су обавезни.");
        var current = history.capture(frame.evaluatedAt());
        history.validate(frame.publication());
        if (frame.evaluatedAt().isBefore(Instant.parse("0001-01-01T00:00:00Z"))) throw invalid("Неисправно време приказа.");
        if (frame.publication().sequence() > current.publication().sequence()) throw invalid("Непознати приказани подаци.");
        if (reviews.stream().anyMatch(r -> r == null || r.auctionId() < 1 || r.revision() == null
                || r.revision().lineage() == null || r.revision().sequence() < 0 || r.evaluatedAt() == null
                || r.evaluatedAt().isBefore(Instant.parse("0001-01-01T00:00:00Z"))
                || r.evaluatedAt().isAfter(Instant.parse("9998-12-31T23:59:59Z"))
                || r.comparisonPolicy() == null || r.comparisonPolicy().length() > 80)
                || reviews.stream().map(SourceHistoryService.Review::auctionId).distinct().count() != reviews.size())
            throw invalid("Неисправне или поновљене потврде.");
        return batch(reviews, frame, live, true);
    }

    private Map<Long, Evidence> batch(List<SourceHistoryService.Review> inputs, SourceHistoryService.Frame frame,
                                      boolean live, boolean validateReview) {
        if (inputs.isEmpty()) return Map.of();
        var revisions = history.revisions(inputs.stream().map(SourceHistoryService.Review::auctionId).toList(),
                frame.publication(), frame.evaluatedAt()).stream().collect(Collectors.toMap(SourceHistoryService.Revision::auctionId, r -> r));
        var rows = inputs.stream().map(r -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("auction_id", r.auctionId()); row.put("lower_id", r.revision().sequence());
            row.put("lower_run", r.revision().runId()); row.put("lower_lineage", r.revision().lineage());
            row.put("evaluated_at", r.evaluatedAt().toString()); row.put("policy", r.comparisonPolicy());
            return row;
        }).toList();
        String input;
        try { input = json.writeValueAsString(rows); } catch (JsonProcessingException e) { throw new IllegalStateException(e); }
        var result = jdbc.query(BATCH_SQL, Map.of("input", input, "upper", frame.publication().sequence(), "lineage", frame.publication().lineage()), (r, n) -> {
            long id = r.getLong("auction_id");
            var revision = revisions.get(id);
            var before = r.getString("before_payload") == null ? null : SourceHistoryPublisher.parse(r.getString("before_payload"));
            var after = r.getString("after_payload") == null ? null : SourceHistoryPublisher.parse(r.getString("after_payload"));
            // Bootstrap can audit a retained legacy lifecycle without observing any source revision.
            // Such a row is not reviewable; do not hand the browser a null-policy acknowledgement.
            var currentReview = revision != null && after != null && supported(r, "after") ? revision.review() : null;
            if (currentReview != null && currentReview.comparisonPolicy() == null) currentReview = null;
            boolean compatible = after != null && supported(r, "after")
                    && (before == null && !validateReview || before != null && supported(r, "before"))
                    && r.getLong("unsupported_count") == 0;
            boolean valid = !validateReview || r.getBoolean("valid_revision")
                    && r.getLong("lower_id") <= frame.publication().sequence()
                    && !time(r, "evaluated_at").isAfter(frame.evaluatedAt())
                    && Objects.equals(r.getString("policy"), r.getString("before_policy"));
            String coverage = revision != null && revision.review() != null && compatible && valid ? "RETAINED_SOURCE" : "UNAVAILABLE";
            long substantive = r.getLong("substantive_count"), bidding = r.getLong("bidding_count"), lifecycle = r.getLong("lifecycle_count");
            var net = SourceComparisonPolicy.compare(before, after, compatible, Objects.equals(r.getString("before_hash"), r.getString("after_hash")));
            Instant beforeEnd = time(r, "before_end"), afterEnd = time(r, "after_end");
            boolean elapsed = beforeEnd != null && beforeEnd.equals(afterEnd) && beforeEnd.isAfter(time(r, "evaluated_at"))
                    && !beforeEnd.isAfter(frame.evaluatedAt());
            boolean changed = substantive > 0 || live && bidding > 0;
            String bucket = validateReview ? null : r.getLong("new_count") > 0 ? "NEW" : before != null && changed ? "UPDATED" : null;
            String reviewState = !coverage.equals("RETAINED_SOURCE") ? "UNAVAILABLE" : !validateReview ? "NEVER_REVIEWED"
                    : changed || lifecycle > 0 || elapsed ? "CHANGED" : "UNCHANGED";
            List<String> fields = List.of((String[]) r.getArray("fields").getArray());
            if (validateReview && !coverage.equals("RETAINED_SOURCE"))
                return new Evidence(id, currentReview, null, List.of(), false,
                        null, null, null, false, revision == null ? null : revision.lifecycle(), afterEnd,
                        afterEnd == null ? null : !afterEnd.isAfter(frame.evaluatedAt()), coverage, "UNAVAILABLE");
            return new Evidence(id, currentReview, bucket, fields,
                    substantive > 0 && before != null && compatible && net.fields().isEmpty(), substantive, bidding, lifecycle, elapsed,
                    revision == null ? null : revision.lifecycle(), afterEnd,
                    afterEnd == null ? null : !afterEnd.isAfter(frame.evaluatedAt()), coverage, reviewState);
        });
        return result.stream().collect(Collectors.toMap(Evidence::auctionId, e -> e, (a, b) -> a, LinkedHashMap::new));
    }
    private static boolean supported(ResultSet r, String prefix) throws SQLException {
        return SourceComparisonPolicy.supported(r.getString(prefix + "_schema"), r.getString(prefix + "_minimization"), r.getString(prefix + "_policy"));
    }
    private static Instant time(ResultSet r, String name) throws SQLException {
        var time = r.getObject(name, OffsetDateTime.class); return time == null ? null : time.toInstant();
    }
    private static InvalidMapRequestException invalid(String message) { return new InvalidMapRequestException("reviews", message); }

    private static final String BATCH_SQL = """
            WITH requested AS (SELECT * FROM jsonb_to_recordset(CAST(:input AS jsonb)) AS r(
                auction_id bigint, lower_id bigint, lower_run uuid, lower_lineage uuid, evaluated_at timestamptz, policy text))
            SELECT r.*, b.source_snapshot_sha256 before_hash, bs.canonical_payload before_payload,
                bs.schema_version before_schema, bs.minimization_policy_version before_minimization, b.comparison_policy before_policy,
                b.retained_end_at before_end, a.source_snapshot_sha256 after_hash, ss.canonical_payload after_payload,
                ss.schema_version after_schema, ss.minimization_policy_version after_minimization, a.comparison_policy after_policy,
                a.retained_end_at after_end, counts.*,
                ARRAY(SELECT DISTINCT field FROM sync_run_auction_observations o CROSS JOIN LATERAL unnest(o.changed_fields) field
                    WHERE o.auction_id=r.auction_id AND o.publication_id > r.lower_id AND o.publication_id <= :upper
                    AND o.comparison_kind IN ('SUBSTANTIVE','LIVE_BIDDING_ONLY') ORDER BY field) fields,
                (r.lower_lineage=:lineage AND EXISTS (SELECT 1 FROM source_publications p JOIN sync_runs s ON s.id=p.run_id
                    WHERE p.publication_id=r.lower_id AND p.run_id=r.lower_run AND s.status='SUCCEEDED')
                    AND (EXISTS (SELECT 1 FROM sync_run_auction_observations o WHERE o.auction_id=r.auction_id
                        AND o.publication_id=r.lower_id AND (o.content_delta <> 'UNCHANGED' OR o.comparison_kind='BASELINE'))
                    OR EXISTS (SELECT 1 FROM auction_lifecycle_transitions t WHERE t.auction_id=r.auction_id AND t.publication_id=r.lower_id))) valid_revision,
                (SELECT count(*) FROM auction_lifecycle_transitions t WHERE t.auction_id=r.auction_id
                    AND t.publication_id > r.lower_id AND t.publication_id <= :upper
                    AND NOT (t.transition_kind='CLOSED' AND t.to_reason='END_DATE'
                        AND t.retained_end_at IS NOT DISTINCT FROM b.retained_end_at
                        AND t.previous_end_at IS NOT DISTINCT FROM t.retained_end_at
                        AND t.retained_end_at <= r.evaluated_at)) lifecycle_count
            FROM requested r
            LEFT JOIN LATERAL (SELECT * FROM sync_run_auction_observations o WHERE o.auction_id=r.auction_id
                AND o.publication_id <= r.lower_id ORDER BY publication_id DESC LIMIT 1) b ON true
            LEFT JOIN auction_source_snapshots bs ON bs.auction_id=r.auction_id AND bs.content_sha256=b.source_snapshot_sha256
            LEFT JOIN LATERAL (SELECT * FROM sync_run_auction_observations o WHERE o.auction_id=r.auction_id
                AND o.publication_id <= :upper ORDER BY publication_id DESC LIMIT 1) a ON true
            LEFT JOIN auction_source_snapshots ss ON ss.auction_id=r.auction_id AND ss.content_sha256=a.source_snapshot_sha256
            CROSS JOIN LATERAL (SELECT count(*) FILTER (WHERE comparison_kind='SUBSTANTIVE') substantive_count,
                count(*) FILTER (WHERE comparison_kind='LIVE_BIDDING_ONLY') bidding_count,
                count(*) FILTER (WHERE content_delta='NEW') new_count,
                count(*) FILTER (WHERE comparison_kind IN ('BASELINE','UNSUPPORTED') AND content_delta <> 'NEW') unsupported_count
                FROM sync_run_auction_observations o WHERE o.auction_id=r.auction_id AND o.publication_id > r.lower_id
                    AND o.publication_id <= :upper) counts
            ORDER BY r.auction_id
            """;
}
