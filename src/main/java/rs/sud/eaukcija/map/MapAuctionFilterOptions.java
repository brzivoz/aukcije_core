package rs.sud.eaukcija.map;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import rs.sud.eaukcija.spatial.LocationPrecision;
import rs.sud.eaukcija.spatial.LocationPrecisionPresentation;

/** Supported legacy catalogue seeds plus precision labels. Retained raw values are merged by AuctionFilterParser. */
public final class MapAuctionFilterOptions {

    private static final List<Option> STATUSES = List.of(
            new Option("InPrediction", "У најави"),
            new Option("Published", "Објављено"),
            new Option("Verification", "Провера"),
            new Option("Verified", "Проверено"),
            new Option("Closed", "Closed"));
    private static final List<Option> KINDS = List.of(
            option("Викендица"),
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
            .map(precision -> new Option(precision.name(), LocationPrecisionPresentation.labelSr(precision)))
            .toList();
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

    public static Set<String> precisionValues() {
        return PRECISION_VALUES;
    }

    private static Option option(String value) {
        return new Option(value, value);
    }

    private static Set<String> values(List<Option> options) {
        return options.stream().map(Option::value).collect(Collectors.toUnmodifiableSet());
    }

    public record Option(String value, String label) {
    }
}
