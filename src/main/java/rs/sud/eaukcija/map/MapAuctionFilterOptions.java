package rs.sud.eaukcija.map;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import rs.sud.eaukcija.spatial.LocationPrecision;
import rs.sud.eaukcija.spatial.LocationPrecisionPresentation;

/** Canonical public map filters shared by request parsing and the rendered UI. */
public final class MapAuctionFilterOptions {

    private static final List<Option> STATUSES = List.of(
            new Option("InPrediction", "У најави"),
            new Option("Published", "Објављено"),
            new Option("Verification", "Провера"),
            new Option("Verified", "Проверено"));
    private static final List<Option> KINDS = List.of(
            option("Гаража"),
            option("Грађевинско земљиште"),
            option("Земљиште"),
            option("Кућа"),
            option("Локал"),
            option("Непокретности"),
            option("Објекат"),
            option("Остали пословни објекат"),
            option("Парцела"),
            option("Пољопривредно земљиште"),
            option("Стамбена зграда са више станова"),
            option("Стамбени објекат"),
            option("Шумско земљиште"));
    private static final List<Option> PRECISIONS = Arrays.stream(LocationPrecision.values())
            .filter(precision -> precision != LocationPrecision.NONE)
            .map(precision -> new Option(precision.name(), LocationPrecisionPresentation.labelSr(precision)))
            .toList();
    private static final Map<String, String> STATUS_BY_LOWERCASE = statusLookup();
    private static final Set<String> KIND_VALUES = values(KINDS);
    private static final Set<String> PRECISION_VALUES = values(PRECISIONS);

    private MapAuctionFilterOptions() {
    }

    public static List<Option> statuses() {
        return STATUSES;
    }

    public static List<Option> kinds() {
        return KINDS;
    }

    public static List<Option> precisions() {
        return PRECISIONS;
    }

    public static String canonicalStatus(String candidate) {
        return candidate == null ? null : STATUS_BY_LOWERCASE.get(candidate.toLowerCase(Locale.ROOT));
    }

    public static Set<String> kindValues() {
        return KIND_VALUES;
    }

    public static Set<String> precisionValues() {
        return PRECISION_VALUES;
    }

    private static Option option(String value) {
        return new Option(value, value);
    }

    private static Set<String> values(List<Option> options) {
        return options.stream().map(Option::value).collect(Collectors.toUnmodifiableSet());
    }

    private static Map<String, String> statusLookup() {
        Map<String, String> lookup = new LinkedHashMap<>();
        for (Option status : STATUSES) {
            lookup.put(status.value().toLowerCase(Locale.ROOT), status.value());
        }
        return Map.copyOf(lookup);
    }

    public record Option(String value, String label) {
    }
}
