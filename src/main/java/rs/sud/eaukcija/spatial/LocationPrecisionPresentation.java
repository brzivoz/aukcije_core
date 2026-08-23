package rs.sud.eaukcija.spatial;

import java.util.EnumMap;
import java.util.Map;

/** Stable Serbian labels and honesty metadata shared by REST and Thymeleaf consumers. */
public final class LocationPrecisionPresentation {

    private static final Map<LocationPrecision, String> LABELS = labels();

    private LocationPrecisionPresentation() {
    }

    public static String labelSr(LocationPrecision precision) {
        return LABELS.get(precision);
    }

    public static boolean coarse(LocationPrecision precision) {
        return precision == LocationPrecision.CADASTRAL_MUNICIPALITY
                || precision == LocationPrecision.SETTLEMENT
                || precision == LocationPrecision.MUNICIPALITY;
    }

    private static Map<LocationPrecision, String> labels() {
        EnumMap<LocationPrecision, String> labels = new EnumMap<>(LocationPrecision.class);
        labels.put(LocationPrecision.PARCEL, "Парцела");
        labels.put(LocationPrecision.ADDRESS, "Адреса");
        labels.put(LocationPrecision.STREET, "Улица");
        labels.put(LocationPrecision.CADASTRAL_MUNICIPALITY, "Центар катастарске општине");
        labels.put(LocationPrecision.SETTLEMENT, "Центар насеља");
        labels.put(LocationPrecision.MUNICIPALITY, "Центар општине");
        labels.put(LocationPrecision.NONE, "Није лоцирано");
        return Map.copyOf(labels);
    }
}
