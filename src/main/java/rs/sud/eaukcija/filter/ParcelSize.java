package rs.sud.eaukcija.filter;

/** Whole individual RGZ cadastral parcel area, never floor area, shares or lot totals. */
public enum ParcelSize {
    UNDER_8("under-8", "< 8 ar"),
    BETWEEN_8_AND_15("8-15", "8–15 ar"),
    OVER_15("over-15", "> 15 ar");

    private final String value;
    private final String label;

    ParcelSize(String value, String label) { this.value = value; this.label = label; }
    public String value() { return value; }
    public String label() { return label; }
}
