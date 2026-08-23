package rs.sud.eaukcija.basemap;

import java.time.Instant;

/** Sanitized operator-visible state; filesystem paths are intentionally omitted. */
public record BasemapStatus(
        boolean healthy,
        String state,
        String activeVersion,
        String pointerVersion,
        String artifactSha256,
        Long artifactSizeBytes,
        Instant activeSince,
        Instant checkedAt,
        String warning) {
}
