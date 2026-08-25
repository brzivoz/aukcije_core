package rs.sud.eaukcija.enrichment;

import java.time.Instant;

public record EnrichmentBacklogMeasure(long count, Instant oldestPendingSince) {
}
