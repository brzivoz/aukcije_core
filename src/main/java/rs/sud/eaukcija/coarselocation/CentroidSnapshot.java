package rs.sud.eaukcija.coarselocation;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Fully validated in-memory view of one active immutable #36 extract. */
record CentroidSnapshot(
        String version,
        LocalDate sourceDate,
        String sourceGpkgSha256,
        Map<String, Centroid> koByCode,
        Map<String, Centroid> settlementByCode,
        Map<String, Centroid> municipalityByCode,
        Map<String, List<Centroid>> settlementsByNormalizedName,
        Map<String, List<Centroid>> municipalitiesByNormalizedName) {

    enum Level {
        KO,
        SETTLEMENT,
        MUNICIPALITY
    }

    record Centroid(
            Level level,
            String officialCode,
            String nameCyrillic,
            String nameLatin,
            List<String> settlementCodes,
            List<String> municipalityCodes,
            long memberPointCount,
            double longitude,
            double latitude) {
    }
}
