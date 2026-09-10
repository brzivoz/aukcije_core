package rs.sud.eaukcija.filter;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.util.MultiValueMap;
import rs.sud.eaukcija.history.SourceHistoryService;
import rs.sud.eaukcija.map.InvalidMapRequestException;

/** Canonical comparison criteria. Personal names and reviewed identities never enter this model/URL. */
public record ChangeCriteria(String since, Instant at, SourceHistoryService.Reference publication,
                             String kind, boolean liveBidding, Window window) {
    public record Window(SourceHistoryService.Frame upper, SourceHistoryService.Reference lower,
                         Instant lowerTime, String problem) { }
    public static final ChangeCriteria ANY = new ChangeCriteria("", null, null, "", false, null);
    public static final Set<String> PARAMETERS = Set.of("since", "sinceAt", "sinceLocal", "sinceOffset",
            "publication", "changeKind", "liveBidding");

    public static ChangeCriteria parse(Map<String, String> values) {
        String since = values.getOrDefault("since", "");
        if (since == null) since = "";
        if (!Set.of("", "24h", "7d", "date", "publication").contains(since))
            throw invalid("since", "Изаберите период промена.");
        String kind = values.get("changeKind");
        if (kind == null) kind = "";
        if (!Set.of("", "new", "updated").contains(kind)) throw invalid("changeKind", "Непозната врста промена.");
        String live = values.get("liveBidding");
        if (live != null && !Set.of("true", "false").contains(live)) throw invalid("liveBidding", "Очекивано true или false.");
        Instant at = null;
        SourceHistoryService.Reference ref = null;
        if (since.equals("date") || since.equals("publication")) {
            String civil = values.get("sinceLocal");
            String utc = values.get("sinceAt");
            if (since.equals("date") && civil != null) {
                try {
                    LocalDateTime local = LocalDateTime.parse(civil);
                    if (local.getYear() < 1 || local.getYear() > 9998) throw new IllegalArgumentException();
                    var offsets = AuctionFilters.ZONE.getRules().getValidOffsets(local);
                    if (offsets.isEmpty()) throw invalid("sinceLocal", "Ово време не постоји у Београду (прелаз на летње време). Изаберите друго време.");
                    String choice = values.get("sinceOffset");
                    if (offsets.size() > 1 && choice == null) throw invalid("sinceOffset", "Време се понавља. Изаберите UTC+02:00 или UTC+01:00 изричито.");
                    ZoneOffset offset = choice == null ? offsets.get(0) : ZoneOffset.of(choice);
                    if (!offsets.contains(offset)) throw invalid("sinceOffset", "Изабрани UTC помак не важи за ово време у Београду.");
                    at = local.toInstant(offset);
                } catch (java.time.DateTimeException e) { throw invalid("sinceLocal", "Унесите исправан датум и време у Београду."); }
                catch (IllegalArgumentException e) {
                    throw invalid("sinceLocal", "Унесите исправан датум и време у Београду.");
                }
                // Civil form values are the explicit draft. sinceAt is not a second query state.
            } else {
                try {
                    at = Instant.parse(utc);
                    int year = at.atOffset(ZoneOffset.UTC).getYear();
                    if (year < 1 || year > 9998) throw new IllegalArgumentException();
                }
                catch (RuntimeException e) { throw invalid("sinceAt", "Потребан је недвосмислен UTC тренутак поређења."); }
            }
            if (since.equals("publication")) ref = decode(values.get("publication"));
        }
        return new ChangeCriteria(since, at, ref, kind, "true".equals(live), null);
    }

    public boolean active() { return !since.isEmpty(); }
    public boolean usable() { return active() && window != null && window.problem() == null; }
    public ChangeCriteria resolved(Window value) { return new ChangeCriteria(since, at, publication, kind, liveBidding, value); }
    public ChangeCriteria kind(String value) { return new ChangeCriteria(since, at, publication, value, liveBidding, window); }
    public String label() {
        return switch (since) {
            case "24h" -> "Последња 24 сата";
            case "7d" -> "Последњих 7 дана";
            case "date" -> "Изабрани датум/време";
            case "publication" -> "Сачувана тачка поређења";
            default -> "Било када";
        };
    }
    public String kindLabel() { return kind.equals("new") ? "Само нове" : kind.equals("updated") ? "Само измењене" : "Нове и измењене"; }
    public String civilTime() { return at == null || !since.equals("date") ? "" : at.atZone(AuctionFilters.ZONE).toLocalDateTime().toString(); }
    public String offset() { return at == null || !since.equals("date") ? "" : at.atZone(AuctionFilters.ZONE).getOffset().toString(); }
    public void parameters(MultiValueMap<String, String> out) {
        if (!since.isEmpty()) out.set("since", since);
        if (at != null) out.set("sinceAt", at.toString());
        if (publication != null) out.set("publication", encode(publication));
        if (!kind.isEmpty()) out.set("changeKind", kind);
        if (liveBidding) out.set("liveBidding", "true");
    }
    public static String encode(SourceHistoryService.Reference ref) {
        return ref.lineage() + ":" + ref.sequence() + ":" + (ref.runId() == null ? "origin" : ref.runId());
    }
    public static SourceHistoryService.Reference decode(String text) {
        try {
            String[] parts = text.split(":", -1);
            if (parts.length != 3 || !parts[1].matches("[0-9]{1,18}")) throw new IllegalArgumentException();
            long sequence = Long.parseLong(parts[1]);
            UUID run = parts[2].equals("origin") ? null : UUID.fromString(parts[2]);
            if ((sequence == 0) != (run == null)) throw new IllegalArgumentException();
            return new SourceHistoryService.Reference(UUID.fromString(parts[0]), sequence, run);
        } catch (RuntimeException e) { throw invalid("publication", "Неисправна референца објављених података."); }
    }
    private static InvalidMapRequestException invalid(String field, String message) { return new InvalidMapRequestException(field, message); }
}
