package rs.sud.eaukcija.komatching;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import rs.sud.eaukcija.addressregistry.SerbianNameNormalizer;

/** Pure, deterministic matcher for the structured eAukcija place fields. */
final class StructuredKoMatcher {

    static final String MATCHER_VERSION = "structured-ko-match-v2";
    static final int DEFAULT_FUZZY_CANDIDATE_LIMIT = 5;
    static final int MIN_FUZZY_SIMILARITY_BASIS_POINTS = 7_000;

    enum Status {
        MATCHED,
        AMBIGUOUS,
        NOT_FOUND,
        INVALID
    }

    enum Method {
        EXACT_CODE,
        EXACT_NORMALIZED_NAME,
        REVIEWED_ALIAS,
        MUNICIPALITY_CONTEXT,
        FUZZY_REVIEW,
        NONE
    }

    record Input(long auctionId, String cadastral, String placeName, String municipality) {
    }

    record CandidateMunicipality(String code, String nameCyrillic, String nameLatin) {
    }

    record AliasEvidence(
            String id,
            String name,
            String kind,
            String provenance,
            String sourceReference,
            String reviewer,
            String reviewedAt) {
    }

    record MunicipalityAliasEvidence(
            String id,
            String municipalityCode,
            String name,
            String normalizedName,
            String provenance,
            String sourceReference,
            String reviewer,
            String reviewedAt) {
    }

    record Candidate(
            int rank,
            String koCode,
            String officialNameCyrillic,
            String officialNameLatin,
            List<CandidateMunicipality> municipalities,
            String matchedNormalizedName,
            String matchKind,
            List<AliasEvidence> aliasReviews,
            List<MunicipalityAliasEvidence> municipalityAliasReviews,
            boolean municipalityIdentityCollision,
            List<String> collidingMunicipalityCodes,
            boolean municipalityContextMatch,
            boolean placeContextMatch,
            Integer editDistance,
            Integer similarityBasisPoints) {
    }

    record Match(
            long auctionId,
            String inputFingerprint,
            Status status,
            Method method,
            String rationale,
            String matchedKoCode,
            List<Candidate> candidates) {
    }

    private final KoDictionarySnapshot dictionary;
    private final int fuzzyCandidateLimit;

    StructuredKoMatcher(KoDictionarySnapshot dictionary, int fuzzyCandidateLimit) {
        this.dictionary = dictionary;
        this.fuzzyCandidateLimit = fuzzyCandidateLimit;
    }

    Match match(Input input) {
        String fingerprint = fingerprint(input, dictionary);
        if (input.cadastral() == null || input.cadastral().isBlank()) {
            return unresolved(input, fingerprint, Status.INVALID, Method.NONE,
                    "MISSING_CADASTRAL: structured Place.Cadastral is null or blank", List.of());
        }
        String normalizedCadastral = normalizeQuery(input.cadastral());
        if (normalizedCadastral == null) {
            return unresolved(input, fingerprint, Status.INVALID, Method.NONE,
                    "MALFORMED_CADASTRAL: structured Place.Cadastral has no usable letters or digits", List.of());
        }

        KoDictionarySnapshot.KoEntry exactCode = dictionary.entriesByCode().get(input.cadastral().trim());
        if (exactCode != null) {
            Candidate candidate = candidate(
                    1, exactCode, null, null, List.of(), input,
                    municipalityContext(input.municipality()), null, null);
            return matched(input, fingerprint, Method.EXACT_CODE,
                    "EXACT_CODE: Place.Cadastral exactly equals an official KO code", candidate);
        }

        List<KoDictionarySnapshot.IndexCandidate> exact = dictionary.normalizedIndex().get(normalizedCadastral);
        if (exact != null) {
            MunicipalityContext municipalityContext = municipalityContext(input.municipality());
            List<Candidate> candidates = candidates(exact, normalizedCadastral, input, municipalityContext, null);
            if (exact.size() == 1) {
                KoDictionarySnapshot.IndexCandidate hit = exact.get(0);
                Method method = hit.officialName() ? Method.EXACT_NORMALIZED_NAME : Method.REVIEWED_ALIAS;
                String rationale = hit.officialName()
                        ? "EXACT_NORMALIZED_NAME: the shared normalizer produced one official KO candidate"
                        : "REVIEWED_ALIAS: the shared normalizer produced one candidate through reviewed alias data";
                return matched(input, fingerprint, method, rationale, candidates.get(0));
            }

            List<Candidate> municipalityMatches = candidates.stream()
                    .filter(Candidate::municipalityContextMatch)
                    .toList();
            if (municipalityContext.normalizedName() != null && municipalityMatches.size() == 1) {
                Candidate selected = municipalityMatches.get(0);
                String rationale = selected.municipalityAliasReviews().isEmpty()
                        ? "MUNICIPALITY_CONTEXT: duplicate normalized KO name reduced to one candidate by the "
                                + "structured municipality"
                        : "MUNICIPALITY_CONTEXT_REVIEWED_ALIAS: duplicate normalized KO name reduced to one "
                                + "candidate by a reviewed municipality equivalence";
                return new Match(
                        input.auctionId(), fingerprint, Status.MATCHED, Method.MUNICIPALITY_CONTEXT,
                        rationale,
                        selected.koCode(), candidates);
            }
            String rationale;
            if (municipalityContext.normalizedName() == null) {
                rationale = "AMBIGUOUS_NAME: normalized KO name has multiple candidates and municipality is missing";
            } else if (municipalityContext.identityCollision()) {
                String collidingCodes = String.join(", ", municipalityContext.collidingMunicipalityCodes());
                rationale = municipalityContext.reviews().isEmpty()
                        ? "AMBIGUOUS_MUNICIPALITY_IDENTITY_COLLISION: the structured municipality name denotes "
                                + "official municipalities " + collidingCodes + " and cannot select a candidate"
                        : "AMBIGUOUS_MUNICIPALITY_ALIAS_COLLISION: reviewed municipality aliases and official names "
                                + "denote municipalities " + collidingCodes + " and cannot select a candidate";
            } else if (municipalityMatches.isEmpty()) {
                rationale = "AMBIGUOUS_MUNICIPALITY_MISMATCH: normalized KO name has multiple candidates and none "
                        + "matches the structured municipality";
            } else {
                rationale = "AMBIGUOUS_WITHIN_MUNICIPALITY: normalized KO name still has multiple candidates after "
                        + "structured municipality filtering";
            }
            return unresolved(input, fingerprint, Status.AMBIGUOUS,
                    municipalityContext.normalizedName() == null
                            ? Method.EXACT_NORMALIZED_NAME : Method.MUNICIPALITY_CONTEXT,
                    rationale, candidates);
        }

        List<Candidate> fuzzy = fuzzyCandidates(normalizedCadastral, input);
        if (fuzzy.isEmpty()) {
            return unresolved(input, fingerprint, Status.NOT_FOUND, Method.NONE,
                    "NOT_FOUND: normalized Place.Cadastral is absent from the exact index and no fuzzy candidate "
                            + "meets the 70% review-similarity floor",
                    List.of());
        }
        return unresolved(input, fingerprint, Status.NOT_FOUND, Method.FUZZY_REVIEW,
                "FUZZY_REVIEW_ONLY: ranked edit-distance candidates are retained for human review and were not "
                        + "auto-selected",
                fuzzy);
    }

    static String normalizeQuery(String value) {
        return SerbianNameNormalizer.normalize(value);
    }

    static String fingerprint(Input input, KoDictionarySnapshot dictionary) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            add(digest, MATCHER_VERSION);
            add(digest, Long.toString(input.auctionId()));
            add(digest, input.cadastral());
            add(digest, input.placeName());
            add(digest, input.municipality());
            add(digest, dictionary.normalizerVersion());
            add(digest, dictionary.aliasDatasetVersion());
            add(digest, dictionary.aliasSha256());
            add(digest, dictionary.municipalityAliasSha256());
            add(digest, dictionary.version());
            add(digest, dictionary.sourceGpkgSha256());
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM has no SHA-256 implementation", e);
        }
    }

    private List<Candidate> fuzzyCandidates(String normalizedQuery, Input input) {
        if (fuzzyCandidateLimit == 0) {
            return List.of();
        }
        Map<String, FuzzyHit> bestByKo = new LinkedHashMap<>();
        MunicipalityContext municipalityContext = municipalityContext(input.municipality());
        for (Map.Entry<String, List<KoDictionarySnapshot.IndexCandidate>> row
                : dictionary.normalizedIndex().entrySet()) {
            String indexedName = row.getKey();
            int distance = levenshtein(normalizedQuery, indexedName);
            for (KoDictionarySnapshot.IndexCandidate indexCandidate : row.getValue()) {
                FuzzyHit proposed = new FuzzyHit(indexedName, indexCandidate, distance);
                bestByKo.merge(indexCandidate.koCode(), proposed, StructuredKoMatcher::betterFuzzyHit);
            }
        }
        Comparator<FuzzyHit> order = Comparator
                .comparingInt(FuzzyHit::distance)
                .thenComparing((FuzzyHit hit) -> !municipalityContext.matches(
                        dictionary.entriesByCode().get(hit.candidate().koCode())))
                .thenComparing((FuzzyHit hit) -> !placeMatches(
                        dictionary.entriesByCode().get(hit.candidate().koCode()), input.placeName()))
                .thenComparing(hit -> hit.candidate().koCode());
        List<FuzzyHit> hits = bestByKo.values().stream()
                .filter(hit -> similarityBasisPoints(normalizedQuery, hit.normalizedName(), hit.distance())
                        >= MIN_FUZZY_SIMILARITY_BASIS_POINTS)
                .sorted(order)
                .limit(fuzzyCandidateLimit)
                .toList();
        List<Candidate> candidates = new ArrayList<>();
        for (int index = 0; index < hits.size(); index++) {
            FuzzyHit hit = hits.get(index);
            int similarity = similarityBasisPoints(normalizedQuery, hit.normalizedName(), hit.distance());
            candidates.add(candidate(
                    index + 1,
                    dictionary.entriesByCode().get(hit.candidate().koCode()),
                    hit.normalizedName(),
                    hit.candidate(),
                    hit.candidate().aliasIds(),
                    input,
                    municipalityContext,
                    hit.distance(),
                    similarity));
        }
        return List.copyOf(candidates);
    }

    private static int similarityBasisPoints(String query, String candidate, int distance) {
        int maximumLength = Math.max(query.length(), candidate.length());
        return maximumLength == 0
                ? 10_000
                : Math.max(0, 10_000 - (distance * 10_000 / maximumLength));
    }

    private List<Candidate> candidates(
            List<KoDictionarySnapshot.IndexCandidate> indexCandidates,
            String matchedNormalizedName,
            Input input,
            MunicipalityContext municipalityContext,
            Integer distance) {
        List<Candidate> candidates = new ArrayList<>();
        for (int index = 0; index < indexCandidates.size(); index++) {
            KoDictionarySnapshot.IndexCandidate indexCandidate = indexCandidates.get(index);
            candidates.add(candidate(
                    index + 1,
                    dictionary.entriesByCode().get(indexCandidate.koCode()),
                    matchedNormalizedName,
                    indexCandidate,
                    indexCandidate.aliasIds(),
                    input,
                    municipalityContext,
                    distance,
                    null));
        }
        return List.copyOf(candidates);
    }

    private Candidate candidate(
            int rank,
            KoDictionarySnapshot.KoEntry entry,
            String matchedNormalizedName,
            KoDictionarySnapshot.IndexCandidate indexCandidate,
            List<String> aliasIds,
            Input input,
            MunicipalityContext municipalityContext,
            Integer editDistance,
            Integer similarityBasisPoints) {
        List<AliasEvidence> aliasEvidence = aliasIds.stream()
                .map(dictionary.aliasesById()::get)
                .map(alias -> new AliasEvidence(
                        alias.id(), alias.name(), alias.kind(), alias.provenance(), alias.sourceReference(),
                        alias.reviewer(), alias.reviewedAt().toString()))
                .toList();
        List<MunicipalityAliasEvidence> municipalityAliasEvidence = municipalityContext.reviews().stream()
                .filter(alias -> entry.municipalities().stream()
                        .anyMatch(municipality -> municipality.code().equals(alias.municipalityCode())))
                .map(alias -> new MunicipalityAliasEvidence(
                        alias.id(), alias.municipalityCode(), alias.name(), alias.normalizedName(),
                        alias.provenance(), alias.sourceReference(), alias.reviewer(), alias.reviewedAt().toString()))
                .toList();
        String matchKind;
        if (indexCandidate == null) {
            matchKind = "OFFICIAL_CODE";
        } else if (indexCandidate.officialName() && !aliasIds.isEmpty()) {
            matchKind = "OFFICIAL_NAME_AND_REVIEWED_ALIAS";
        } else if (indexCandidate.officialName()) {
            matchKind = "OFFICIAL_NAME";
        } else {
            matchKind = "REVIEWED_ALIAS";
        }
        return new Candidate(
                rank,
                entry.code(),
                entry.officialNameCyrillic(),
                entry.officialNameLatin(),
                entry.municipalities().stream()
                        .map(municipality -> new CandidateMunicipality(
                                municipality.code(), municipality.nameCyrillic(), municipality.nameLatin()))
                        .toList(),
                matchedNormalizedName,
                matchKind,
                aliasEvidence,
                municipalityAliasEvidence,
                municipalityContext.identityCollision(),
                municipalityContext.collidingMunicipalityCodes(),
                municipalityContext.matches(entry),
                placeMatches(entry, input.placeName()),
                editDistance,
                similarityBasisPoints);
    }

    private MunicipalityContext municipalityContext(String rawMunicipality) {
        String normalized = normalizeQuery(rawMunicipality);
        if (normalized == null) {
            return new MunicipalityContext(null, Set.of(), List.of(), List.of());
        }
        List<KoDictionarySnapshot.MunicipalityAliasReview> reviews =
                dictionary.municipalityAliasesByNormalizedName().getOrDefault(normalized, List.of());
        TreeSet<String> resolvedCodes = new TreeSet<>(
                dictionary.municipalityCodesByNormalizedName().getOrDefault(normalized, List.of()));
        resolvedCodes.addAll(reviews.stream()
                .map(KoDictionarySnapshot.MunicipalityAliasReview::municipalityCode)
                .collect(Collectors.toSet()));
        if (resolvedCodes.size() > 1) {
            return new MunicipalityContext(normalized, Set.of(), reviews, List.copyOf(resolvedCodes));
        }
        return new MunicipalityContext(normalized, Set.copyOf(resolvedCodes), reviews, List.of());
    }

    private static boolean placeMatches(KoDictionarySnapshot.KoEntry entry, String rawPlace) {
        String normalized = normalizeQuery(rawPlace);
        if (normalized == null) {
            return false;
        }
        return entry.settlements().stream().anyMatch(settlement -> settlement.normalizedNames().contains(normalized));
    }

    private static FuzzyHit betterFuzzyHit(FuzzyHit left, FuzzyHit right) {
        Comparator<FuzzyHit> comparator = Comparator
                .comparingInt(FuzzyHit::distance)
                .thenComparing((FuzzyHit hit) -> !hit.candidate().officialName())
                .thenComparing(FuzzyHit::normalizedName);
        return comparator.compare(left, right) <= 0 ? left : right;
    }

    private static Match matched(
            Input input, String fingerprint, Method method, String rationale, Candidate candidate) {
        return new Match(
                input.auctionId(), fingerprint, Status.MATCHED, method, rationale,
                candidate.koCode(), List.of(candidate));
    }

    private static Match unresolved(
            Input input,
            String fingerprint,
            Status status,
            Method method,
            String rationale,
            List<Candidate> candidates) {
        return new Match(
                input.auctionId(), fingerprint, status, method, rationale, null, List.copyOf(candidates));
    }

    private static int levenshtein(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= left.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int substitution = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(
                        Math.min(current[j - 1] + 1, previous[j] + 1),
                        previous[j - 1] + substitution);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
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

    private record FuzzyHit(
            String normalizedName,
            KoDictionarySnapshot.IndexCandidate candidate,
            int distance) {
    }

    private record MunicipalityContext(
            String normalizedName,
            Set<String> eligibleMunicipalityCodes,
            List<KoDictionarySnapshot.MunicipalityAliasReview> reviews,
            List<String> collidingMunicipalityCodes) {

        /**
         * True when the structured municipality name denotes more than one official
         * municipality, whether through official names alone or together with reviewed
         * aliases. No candidate is eligible while it holds.
         */
        private boolean identityCollision() {
            return !collidingMunicipalityCodes.isEmpty();
        }

        private boolean matches(KoDictionarySnapshot.KoEntry entry) {
            return entry.municipalities().stream()
                    .anyMatch(municipality -> eligibleMunicipalityCodes.contains(municipality.code()));
        }
    }
}
