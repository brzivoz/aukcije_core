package rs.sud.eaukcija.spatial;

/**
 * Honest precision vocabulary shared by every location resolver and map consumer.
 * Declaration order is the canonical strongest-to-weakest selection ladder.
 */
public enum LocationPrecision {
    PARCEL,
    ADDRESS,
    STREET,
    CADASTRAL_MUNICIPALITY,
    SETTLEMENT,
    MUNICIPALITY,
    NONE
}
