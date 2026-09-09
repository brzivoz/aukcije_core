package rs.sud.eaukcija.filter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import rs.sud.eaukcija.spatial.LocationPrecision;

/** Canonical shared criteria and navigation. asOf belongs to an evaluation, never a bookmark. */
public record AuctionFilters(
        List<String> municipalities, String placeName, String category, String status,
        BigDecimal minPrice, BigDecimal maxPrice, Boolean firstSale, String search,
        LocationPrecision precision, ParcelSize parcelSize, LocalDate from, LocalDate to, String timeScope,
        String sortBy, String sortDir, int page, Long auction, Instant asOf) {

    public AuctionFilters {
        municipalities = municipalities == null ? List.of() : List.copyOf(municipalities);
    }

    public static final ZoneId ZONE = ZoneId.of("Europe/Belgrade");
    public static final Map<String, String> SORT_COLUMNS = Map.of(
            "id", "a.id", "auctionNumber", "a.auction_number", "startingPrice", "a.starting_price",
            "estimatedPrice", "a.estimated_price", "endDate", "a.end_date", "startDate", "a.start_date");

    public Instant endsAtOrAfter() { return from == null ? null : from.atStartOfDay(ZONE).toInstant(); }
    public Instant endsBefore() { return to == null ? null : to.plusDays(1).atStartOfDay(ZONE).toInstant(); }
    public String timeScopeLabel() {
        return switch (timeScope) {
            case "ended" -> "Завршене";
            case "all" -> "Све";
            default -> "Нису завршене";
        };
    }

    public MultiValueMap<String, String> parameters() {
        MultiValueMap<String, String> values = new LinkedMultiValueMap<>();
        municipalities.forEach(value -> values.add("municipality", value));
        put(values, "placeName", placeName);
        put(values, "category", category); put(values, "status", status);
        put(values, "minPrice", minPrice); put(values, "maxPrice", maxPrice);
        put(values, "firstSale", firstSale); put(values, "search", search);
        put(values, "precision", precision); put(values, "parcelSize", parcelSize == null ? null : parcelSize.value());
        put(values, "from", from); put(values, "to", to);
        put(values, "timeScope", timeScope); put(values, "sortBy", sortBy); put(values, "sortDir", sortDir);
        put(values, "page", page); put(values, "auction", auction);
        return values;
    }

    public Map<String, String> activeCriteria() {
        Map<String, String> labels = Map.ofEntries(
                Map.entry("municipality", "Општине"), Map.entry("placeName", "Место"),
                Map.entry("category", "Изворна категорија"), Map.entry("status", "Изворни статус"),
                Map.entry("minPrice", "Мин. РСД"), Map.entry("maxPrice", "Макс. РСД"),
                Map.entry("firstSale", "Прва продаја"), Map.entry("search", "Претрага"),
                Map.entry("precision", "Прецизност"), Map.entry("parcelSize", "Површина парцеле"),
                Map.entry("from", "Завршетак од"), Map.entry("to", "Завршетак до"));
        Map<String, String> active = new LinkedHashMap<>();
        parameters().forEach((key, entries) -> {
            String value = String.join(", ", entries);
            if (labels.containsKey(key)) active.put(labels.get(key), key.equals("firstSale")
                    ? (value.equals("true") ? "Да" : "Не") : key.equals("precision")
                    ? rs.sud.eaukcija.spatial.LocationPrecisionPresentation.labelSr(precision)
                    : key.equals("parcelSize") ? parcelSize.label() : value);
        });
        return active;
    }
    public String query() { return query(parameters()); }
    public String pageUrl(int target) {
        MultiValueMap<String, String> values = parameters();
        values.set("page", Integer.toString(target));
        return "/?" + query(values);
    }
    public String sortUrl(String field) {
        MultiValueMap<String, String> values = parameters();
        values.set("sortBy", field);
        values.set("sortDir", field.equals(sortBy) && sortDir.equals("asc") ? "desc" : "asc");
        return "/?" + query(values);
    }
    public String resetUrl() {
        MultiValueMap<String, String> values = new LinkedMultiValueMap<>();
        values.set("timeScope", "not-ended"); values.set("sortBy", sortBy); values.set("sortDir", sortDir);
        values.set("page", "0"); put(values, "auction", auction);
        return "/?" + query(values);
    }
    private static void put(MultiValueMap<String, String> values, String key, Object value) {
        if (value != null) values.set(key, value.toString());
    }
    private static String query(MultiValueMap<String, String> values) {
        return values.entrySet().stream().flatMap(entry -> entry.getValue().stream().map(value ->
                java.net.URLEncoder.encode(entry.getKey(), java.nio.charset.StandardCharsets.UTF_8) + "="
                + java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8)))
                .collect(java.util.stream.Collectors.joining("&"));
    }
}
