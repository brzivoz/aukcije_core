package rs.sud.eaukcija.rgz;

import java.time.Instant;
import java.util.Objects;

/** First observed, verified service contract. Not a claim of a publisher dataset edition. */
public record RgzSourceContract(
        String sourceKey, String capabilitiesSha256, String schemaSha256, Instant observedAt) {
    public RgzSourceContract {
        for (String hash : new String[] {sourceKey, capabilitiesSha256, schemaSha256}) {
            if (hash == null || !hash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("invalid RGZ source contract hash");
            }
        }
        Objects.requireNonNull(observedAt);
    }
}
