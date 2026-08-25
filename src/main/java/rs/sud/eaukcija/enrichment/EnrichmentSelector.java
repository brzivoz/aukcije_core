package rs.sud.eaukcija.enrichment;

import java.util.Objects;

public record EnrichmentSelector(EnrichmentSelectorType type, String value) {

    public static EnrichmentSelector none() {
        return new EnrichmentSelector(EnrichmentSelectorType.NONE, null);
    }

    public EnrichmentSelector {
        Objects.requireNonNull(type, "type");
        if (type == EnrichmentSelectorType.NONE) {
            if (value != null) {
                throw new IllegalArgumentException("NONE selector must not have a value");
            }
        } else if (value == null || value.isBlank() || value.length() > 160) {
            throw new IllegalArgumentException("replay selector value must contain at most 160 characters");
        } else {
            value = value.trim();
        }
    }

    public boolean explicitReplay() {
        return type != EnrichmentSelectorType.NONE;
    }
}
