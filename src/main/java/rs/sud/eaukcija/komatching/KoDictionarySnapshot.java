package rs.sud.eaukcija.komatching;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Validated in-memory view of one immutable #14 dictionary version. */
record KoDictionarySnapshot(
        String version,
        LocalDate sourceDate,
        String sourceGpkgSha256,
        String normalizerVersion,
        String aliasDatasetVersion,
        String aliasSha256,
        String municipalityAliasSha256,
        Map<String, KoEntry> entriesByCode,
        Map<String, List<IndexCandidate>> normalizedIndex,
        Map<String, List<String>> municipalityCodesByNormalizedName,
        Map<String, AliasReview> aliasesById,
        Map<String, MunicipalityAliasReview> municipalityAliasesById,
        Map<String, List<MunicipalityAliasReview>> municipalityAliasesByNormalizedName) {

    record KoEntry(
            String code,
            String officialNameCyrillic,
            String officialNameLatin,
            List<String> normalizedNames,
            List<Municipality> municipalities,
            List<Settlement> settlements) {
    }

    record Municipality(
            String code,
            String nameCyrillic,
            String nameLatin,
            List<String> normalizedNames,
            List<String> aliasIds) {
    }

    record Settlement(
            String code,
            String nameCyrillic,
            String nameLatin,
            List<String> normalizedNames,
            List<String> municipalityCodes) {
    }

    record IndexCandidate(
            String koCode,
            List<String> municipalityCodes,
            boolean officialName,
            List<String> aliasIds) {
    }

    record AliasReview(
            String id,
            String koCode,
            String name,
            String normalizedName,
            String kind,
            String provenance,
            String sourceReference,
            String reviewer,
            LocalDate reviewedAt) {
    }

    record MunicipalityAliasReview(
            String id,
            String municipalityCode,
            String name,
            String normalizedName,
            String provenance,
            String sourceReference,
            String reviewer,
            LocalDate reviewedAt) {
    }
}
