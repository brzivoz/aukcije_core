package rs.sud.eaukcija.coarselocation;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import rs.sud.eaukcija.addressregistry.SerbianNameNormalizer;
import rs.sud.eaukcija.spatial.LocationPrecision;

/** Pure resolution ladder: selected #37 KO, settlement, municipality, then explicit NONE. */
final class CoarseLocationResolver {

    static final String RESOLVER = "structured-place-coarse-centroid";
    static final String RESOLVER_VERSION = "coarse-location-v1";
    static final String SOURCE_DATASET = "RGZ_ADDRESS_REGISTRY_CENTROID_EXTRACT";
    static final String REFERENCE_PARSER_VERSION = "coarse-structured-place-v1";
    static final String REFERENCE_CANONICAL_KEY = "structured-place";

    private final CentroidSnapshot snapshot;
    private final ObjectMapper objectMapper;

    CoarseLocationResolver(CentroidSnapshot snapshot, ObjectMapper objectMapper) {
        this.snapshot = snapshot;
        this.objectMapper = objectMapper;
    }

    Resolution resolve(Input input) {
        ObjectNode evidence = baseEvidence(input);
        String fingerprint = fingerprint(input, snapshot.version(), RESOLVER_VERSION);

        if ("MATCHED".equals(input.koStatus())) {
            CentroidSnapshot.Centroid ko = snapshot.koByCode().get(input.matchedKoCode());
            if (ko == null) {
                throw new CoarseLocationResolutionException(
                        "MATCHED_KO_CENTROID_MISSING",
                        "#37 selected KO " + input.matchedKoCode()
                                + " but the active #36 extract has no such KO centroid");
            }
            evidence.set("koTier", lookupEvidence(input.cadastral(), List.of(ko), null));
            return resolved(
                    fingerprint,
                    LocationPrecision.CADASTRAL_MUNICIPALITY,
                    "KO_MATCHED_FROM_STRUCTURED_PLACE: #37 selected one official KO identity",
                    ko,
                    evidence,
                    input.usedReviewedMunicipalityAlias());
        }

        evidence.set("koTier", lookupEvidence(input.cadastral(), List.of(), "UPSTREAM_KO_NOT_MATCHED"));
        MunicipalityContext municipalityContext = municipalityContext(input);

        String normalizedPlace = SerbianNameNormalizer.normalize(input.placeName());
        List<CentroidSnapshot.Centroid> settlementCandidates = normalizedPlace == null
                ? List.of()
                : snapshot.settlementsByNormalizedName().getOrDefault(normalizedPlace, List.of());
        List<CentroidSnapshot.Centroid> eligibleSettlements = settlementCandidates;
        String settlementRationale = "SETTLEMENT_EXACT_NAME";
        if (municipalityContext.code() != null) {
            eligibleSettlements = settlementCandidates.stream()
                    .filter(candidate -> candidate.municipalityCodes().contains(municipalityContext.code()))
                    .toList();
            settlementRationale = "SETTLEMENT_EXACT_NAME_WITH_MUNICIPALITY_CONTEXT";
        }
        evidence.set("settlementTier", lookupEvidence(
                input.placeName(), settlementCandidates,
                eligibleSettlements.size() == 1 ? null : "NO_UNAMBIGUOUS_SETTLEMENT_IDENTITY"));
        if (eligibleSettlements.size() == 1) {
            return resolved(
                    fingerprint,
                    LocationPrecision.SETTLEMENT,
                    settlementRationale + ": Place.Name selected one official settlement centroid",
                    eligibleSettlements.get(0),
                    evidence,
                    false);
        }

        CentroidSnapshot.Centroid municipality = null;
        String municipalityRationale = null;
        if (municipalityContext.code() != null) {
            municipality = snapshot.municipalityByCode().get(municipalityContext.code());
            if (municipality != null) {
                municipalityRationale = municipalityContext.fromStructuredKoEvidence()
                        ? "MUNICIPALITY_FROM_STRUCTURED_KO_CONTEXT"
                        : "MUNICIPALITY_EXACT_NAME";
            }
        }
        List<CentroidSnapshot.Centroid> municipalityCandidates = directMunicipalityCandidates(input.municipality());
        if (municipality == null && municipalityCandidates.size() == 1) {
            municipality = municipalityCandidates.get(0);
            municipalityRationale = "MUNICIPALITY_EXACT_NAME";
        }
        evidence.set("municipalityTier", lookupEvidence(
                input.municipality(), municipalityCandidates,
                municipality == null ? "NO_UNAMBIGUOUS_MUNICIPALITY_IDENTITY" : null));
        if (municipality != null) {
            return resolved(
                    fingerprint,
                    LocationPrecision.MUNICIPALITY,
                    municipalityRationale + ": structured evidence selected one official municipality centroid",
                    municipality,
                    evidence,
                    false);
        }

        ObjectNode selected = evidence.putObject("selected");
        selected.put("precision", LocationPrecision.NONE.name());
        selected.put("rationale", "NO_UNAMBIGUOUS_COARSE_CENTROID");
        return new Resolution(
                fingerprint,
                "NONE",
                LocationPrecision.NONE,
                "NO_UNAMBIGUOUS_COARSE_CENTROID: structured fields support no unique official centroid",
                null,
                evidence,
                false);
    }

    private Resolution resolved(
            String fingerprint,
            LocationPrecision precision,
            String rationale,
            CentroidSnapshot.Centroid centroid,
            ObjectNode evidence,
            boolean usedMunicipalityAlias) {
        ObjectNode selected = evidence.putObject("selected");
        selected.put("precision", precision.name());
        selected.put("level", centroid.level().name());
        selected.put("officialCode", centroid.officialCode());
        selected.put("nameCyrillic", centroid.nameCyrillic());
        putNullable(selected, "nameLatin", centroid.nameLatin());
        selected.put("memberPointCount", centroid.memberPointCount());
        selected.put("longitude", centroid.longitude());
        selected.put("latitude", centroid.latitude());
        selected.put("rationale", rationale.substring(0, rationale.indexOf(':')));
        return new Resolution(
                fingerprint,
                "RESOLVED",
                precision,
                rationale,
                centroid,
                evidence,
                usedMunicipalityAlias);
    }

    private ObjectNode baseEvidence(Input input) {
        ObjectNode evidence = objectMapper.createObjectNode();
        ArrayNode order = evidence.putArray("resolutionOrder");
        order.add("CADASTRAL_MUNICIPALITY");
        order.add("SETTLEMENT");
        order.add("MUNICIPALITY");
        order.add("NONE");
        ObjectNode source = evidence.putObject("structuredPlace");
        putNullable(source, "cadastral", input.cadastral());
        putNullable(source, "placeName", input.placeName());
        putNullable(source, "municipality", input.municipality());
        ObjectNode ko = evidence.putObject("structuredKoMatch");
        ko.put("status", input.koStatus() == null ? "MISSING" : input.koStatus());
        putNullable(ko, "method", input.koMethod());
        putNullable(ko, "rationale", input.koRationale());
        putNullable(ko, "matchedKoCode", input.matchedKoCode());
        putNullable(ko, "dictionaryVersion", input.dictionaryVersion());
        putNullable(ko, "dictionarySourceSha256", input.dictionarySourceSha256());
        ko.put("usedReviewedMunicipalityAlias", input.usedReviewedMunicipalityAlias());
        ko.set("candidates", input.koCandidates() == null
                ? objectMapper.createArrayNode()
                : input.koCandidates().deepCopy());
        evidence.put("extractVersion", snapshot.version());
        evidence.put("extractSourceSha256", snapshot.sourceGpkgSha256());
        evidence.put("resolverVersion", RESOLVER_VERSION);
        return evidence;
    }

    private MunicipalityContext municipalityContext(Input input) {
        List<String> upstreamCodes = municipalityContextCodes(input);
        if (upstreamCodes.size() == 1) {
            return new MunicipalityContext(upstreamCodes.iterator().next(), true);
        }
        List<CentroidSnapshot.Centroid> direct = directMunicipalityCandidates(input.municipality());
        return direct.size() == 1
                ? new MunicipalityContext(direct.get(0).officialCode(), false)
                : new MunicipalityContext(null, false);
    }

    private static List<String> municipalityContextCodes(Input input) {
        java.util.TreeSet<String> upstreamCodes = new java.util.TreeSet<>();
        JsonNode candidates = input.koCandidates();
        if (candidates != null && candidates.isArray()) {
            for (JsonNode candidate : candidates) {
                if (!candidate.path("municipalityContextMatch").asBoolean(false)) {
                    continue;
                }
                JsonNode municipalities = candidate.path("municipalities");
                if (municipalities.isArray()) {
                    for (JsonNode municipality : municipalities) {
                        JsonNode code = municipality.path("code");
                        if (code.isTextual() && !code.asText().isBlank()) {
                            upstreamCodes.add(code.asText());
                        }
                    }
                }
            }
        }
        return List.copyOf(upstreamCodes);
    }

    private List<CentroidSnapshot.Centroid> directMunicipalityCandidates(String rawMunicipality) {
        String normalized = SerbianNameNormalizer.normalize(rawMunicipality);
        return normalized == null
                ? List.of()
                : snapshot.municipalitiesByNormalizedName().getOrDefault(normalized, List.of());
    }

    private ObjectNode lookupEvidence(
            String rawValue,
            List<CentroidSnapshot.Centroid> candidates,
            String rejection) {
        ObjectNode lookup = objectMapper.createObjectNode();
        putNullable(lookup, "rawValue", rawValue);
        putNullable(lookup, "normalizedValue", SerbianNameNormalizer.normalize(rawValue));
        ArrayNode codes = lookup.putArray("candidateCodes");
        candidates.stream()
                .map(CentroidSnapshot.Centroid::officialCode)
                .sorted()
                .forEach(codes::add);
        putNullable(lookup, "rejection", rejection);
        return lookup;
    }

    static String fingerprint(Input input, String extractVersion, String resolverVersion) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            add(digest, input.cadastral());
            add(digest, input.placeName());
            add(digest, input.municipality());
            add(digest, input.koStatus());
            add(digest, input.koMethod());
            add(digest, input.koRationale());
            add(digest, input.matchedKoCode());
            add(digest, String.join(",", municipalityContextCodes(input)));
            add(digest, extractVersion);
            add(digest, resolverVersion);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM has no SHA-256 implementation", e);
        }
    }

    private static void add(MessageDigest digest, String value) {
        if (value == null) {
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(-1).array());
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static void putNullable(ObjectNode node, String name, String value) {
        if (value == null) {
            node.putNull(name);
        } else {
            node.put(name, value);
        }
    }

    record Input(
            long auctionId,
            String cadastral,
            String placeName,
            String municipality,
            String koStatus,
            String koMethod,
            String koRationale,
            String matchedKoCode,
            String dictionaryVersion,
            String dictionarySourceSha256,
            JsonNode koCandidates) {

        boolean usedReviewedMunicipalityAlias() {
            return koRationale != null && koRationale.startsWith("MUNICIPALITY_CONTEXT_REVIEWED_ALIAS:");
        }
    }

    record Resolution(
            String inputFingerprint,
            String status,
            LocationPrecision precision,
            String rationale,
            CentroidSnapshot.Centroid centroid,
            JsonNode candidateEvidence,
            boolean usedMunicipalityAlias) {
    }

    private record MunicipalityContext(String code, boolean fromStructuredKoEvidence) {
    }
}
