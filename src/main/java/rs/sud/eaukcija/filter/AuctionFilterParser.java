package rs.sud.eaukcija.filter;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import rs.sud.eaukcija.map.InvalidMapRequestException;
import rs.sud.eaukcija.map.MapAuctionFilterOptions;
import rs.sud.eaukcija.repository.AuctionRepository;
import rs.sud.eaukcija.spatial.LocationPrecision;

/** The only URL/API validation and compatibility adapter, shared by all projections. */
@Component
public class AuctionFilterParser {
    public static final int MAX_MUNICIPALITIES = 256;
    private static final Set<String> FIELDS = Set.of("municipality", "placeName", "category", "status",
            "minPrice", "maxPrice", "firstSale", "search", "precision", "from", "to", "timeScope",
            "sortBy", "sortDir", "page", "auction");
    private static final Map<String, String> ALIASES = Map.of(
            "mapKind", "category", "kind", "category", "mapStatus", "status",
            "mapPrecision", "precision", "mapFrom", "from", "mapTo", "to");
    private final AuctionRepository repository;
    private final Clock clock;

    @Autowired
    public AuctionFilterParser(AuctionRepository repository) { this(repository, Clock.systemUTC()); }
    public AuctionFilterParser(AuctionRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public List<String> categories() {
        return catalogue(MapAuctionFilterOptions.kinds().stream().map(MapAuctionFilterOptions.Option::value).toList(),
                repository == null ? List.of() : repository.findDistinctCategories());
    }
    public List<String> statuses() {
        return catalogue(MapAuctionFilterOptions.statuses().stream().map(MapAuctionFilterOptions.Option::value).toList(),
                repository == null ? List.of() : repository.findDistinctStatuses());
    }
    public List<String> municipalities() {
        var names = new java.util.TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
        java.util.stream.Stream.concat(SerbiaMunicipalities.names().stream(),
                repository == null ? java.util.stream.Stream.empty() : repository.findDistinctMunicipalities().stream())
                .filter(AuctionFilterParser::safeLabel).forEach(name -> names.putIfAbsent(name, name));
        var collator = java.text.Collator.getInstance(Locale.forLanguageTag("sr-Cyrl-RS"));
        return names.values().stream().sorted(collator).toList();
    }
    public Map<String, List<String>> options() {
        return Map.of("category", categories(), "status", statuses(),
                "municipality", municipalities(),
                "placeName", repository == null ? List.of() : repository.findDistinctPlaceNames().stream().filter(AuctionFilterParser::safeLabel).toList());
    }
    public static boolean safeLabel(String value) {
        return value != null && !value.isBlank() && value.length() <= 255
                && value.codePoints().noneMatch(c -> Character.isISOControl(c)
                    || Character.getType(c) == Character.FORMAT || c == '<' || c == '>');
    }
    private static List<String> catalogue(List<String> supported, List<String> retained) {
        return java.util.stream.Stream.concat(supported.stream(), retained.stream())
                .filter(AuctionFilterParser::safeLabel).distinct().sorted().toList();
    }

    public AuctionFilters parse(MultiValueMap<String, String> input, Set<String> transportFields) {
        Map<String, String> values = new LinkedHashMap<>();
        List<String> municipalities = List.of();
        for (var entry : input.entrySet()) {
            String key = entry.getKey();
            if (!FIELDS.contains(key) && !ALIASES.containsKey(key) && !transportFields.contains(key))
                throw invalid(key, "unsupported query parameter");
            if (key.equals("municipality")) {
                municipalities = parseMunicipalities(entry.getValue());
                continue;
            }
            if (entry.getValue().size() != 1) throw invalid(key, "query parameter must occur exactly once");
            if (transportFields.contains(key)) continue;
            String field = ALIASES.getOrDefault(key, key);
            String value = normalize(field, entry.getValue().get(0));
            if (values.containsKey(field) && !java.util.Objects.equals(values.get(field), value))
                throw invalid(field, "conflicting canonical and legacy parameters");
            values.put(field, value);
        }
        String category = values.get("category");
        if (category != null && !categories().contains(category)) throw invalid("category", "unsupported retained category");
        String status = values.get("status");
        if (status != null) {
            String candidate = status;
            status = statuses().stream().filter(s -> s.equalsIgnoreCase(candidate)).findFirst()
                    .orElseThrow(() -> invalid("status", "unsupported retained source status"));
        }
        BigDecimal min = price("minPrice", values.get("minPrice"));
        BigDecimal max = price("maxPrice", values.get("maxPrice"));
        if (min != null && max != null && min.compareTo(max) > 0)
            throw invalid("maxPrice", "maxPrice must be at least minPrice (RSD)");
        Boolean first = null;
        if (values.get("firstSale") != null) {
            if (!Set.of("true", "false").contains(values.get("firstSale")))
                throw invalid("firstSale", "firstSale must be true or false");
            first = Boolean.valueOf(values.get("firstSale"));
        }
        LocationPrecision precision = null;
        if (values.get("precision") != null) {
            try { precision = LocationPrecision.valueOf(values.get("precision")); }
            catch (IllegalArgumentException e) { throw invalid("precision", "precision must be one of " + MapAuctionFilterOptions.precisionValues()); }
        }
        LocalDate from = date("from", values.get("from"));
        LocalDate to = date("to", values.get("to"));
        if (from != null && to != null && to.isBefore(from))
            throw invalid("to", "to must be the same as or later than from");
        // Old date-bearing API and map bookmarks explicitly opted into history.
        String scope = values.get("timeScope");
        if (scope == null) scope = from != null || to != null ? "all" : "not-ended";
        if (!Set.of("not-ended", "ended", "all").contains(scope))
            throw invalid("timeScope", "timeScope must be not-ended, ended or all");
        String sort = values.get("sortBy") == null ? "startingPrice" : values.get("sortBy");
        if (!AuctionFilters.SORT_COLUMNS.containsKey(sort)) throw invalid("sortBy", "unsupported sort field");
        String dir = values.get("sortDir") == null ? "asc" : values.get("sortDir");
        if (!Set.of("asc", "desc").contains(dir)) throw invalid("sortDir", "sortDir must be asc or desc");
        int page = (int) integer("page", values.get("page"), 0, 1_000_000, 0);
        Long selected = values.get("auction") == null ? null
                : integer("auction", values.get("auction"), 1, Long.MAX_VALUE, 0);
        return new AuctionFilters(municipalities, values.get("placeName"), category, status,
                min, max, first, values.get("search"), precision, from, to, scope, sort, dir, page, selected, clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    }

    private List<String> parseMunicipalities(List<String> rawValues) {
        if (rawValues.isEmpty() || rawValues.size() > MAX_MUNICIPALITIES)
            throw invalid("municipality", "select at most " + MAX_MUNICIPALITIES + " municipalities");
        var candidates = rawValues.stream().map(raw -> normalize("municipality", raw))
                .filter(java.util.Objects::nonNull).toList();
        if (candidates.isEmpty()) return List.of();
        var supported = new java.util.TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
        municipalities().forEach(name -> supported.put(name, name));
        var selected = new java.util.TreeSet<String>();
        for (String candidate : candidates) {
            String canonical = supported.get(candidate);
            if (canonical == null) throw invalid("municipality", "unsupported municipality");
            selected.add(canonical);
        }
        return List.copyOf(selected);
    }

    private static String normalize(String field, String raw) {
        if (raw == null) return null;
        int length = field.equals("search") ? 200 : 255;
        if (raw.length() > length || raw.codePoints().anyMatch(c -> Character.isISOControl(c)
                || Character.getType(c) == Character.FORMAT)) throw invalid(field, "invalid text or length (maximum " + length + ")");
        if (raw.isBlank()) return null;
        String value = raw.trim();
        if (Set.of("category", "status", "municipality", "placeName").contains(field) && !safeLabel(value))
            throw invalid(field, "unsafe raw label");
        if (field.equals("precision")) return value.toUpperCase(Locale.ROOT);
        if (Set.of("status", "sortDir").contains(field)) return value.toLowerCase(Locale.ROOT);
        return value;
    }
    private static BigDecimal price(String field, String value) {
        if (value == null) return null;
        if (!value.matches("[0-9]{1,17}(\\.[0-9]{1,2})?")) throw invalid(field, "price must be a non-negative RSD amount with at most two decimal places");
        return new BigDecimal(value);
    }
    private static LocalDate date(String field, String value) {
        if (value == null) return null;
        try {
            if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new DateTimeException("format");
            LocalDate result = LocalDate.parse(value);
            if (result.getYear() < 1 || result.getYear() > 9998) throw new DateTimeException("range");
            return result;
        } catch (DateTimeException e) { throw invalid(field, field + " must be an ISO date in YYYY-MM-DD form (0001..9998)"); }
    }
    private static long integer(String field, String value, long min, long max, long fallback) {
        if (value == null) return fallback;
        try {
            if (!value.matches("[0-9]{1,19}")) throw new NumberFormatException();
            long parsed = Long.parseLong(value);
            if (parsed < min || parsed > max) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException e) { throw invalid(field, "integer must be between " + min + " and " + max); }
    }
    private static InvalidMapRequestException invalid(String field, String detail) {
        return new InvalidMapRequestException(field, detail);
    }
}
