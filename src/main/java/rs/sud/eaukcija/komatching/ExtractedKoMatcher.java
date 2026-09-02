package rs.sud.eaukcija.komatching;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import rs.sud.eaukcija.addressregistry.SerbianNameNormalizer;
import rs.sud.eaukcija.enrichment.EnrichmentHashing;

/** Deterministic #33 matcher and structured-versus-text reconciliation policy. */
final class ExtractedKoMatcher {

    static final String MATCHER_VERSION = "extracted-ko-match-v1";

    enum Method {
        EXACT_CODE,
        EXACT_NORMALIZED_NAME,
        REVIEWED_ALIAS,
        MUNICIPALITY_CONTEXT,
        FUZZY_REVIEW,
        STRUCTURED_CONFLICT,
        NONE
    }

    enum Reconciliation {
        AGREES,
        CONFLICT,
        TEXT_ONLY,
        STRUCTURED_ONLY,
        BOTH_UNRESOLVED
    }

    enum KoProvenance {
        TEXT_EXTRACTED,
        STRUCTURED_FALLBACK,
        UNRESOLVED
    }

    record Input(
            UUID referenceId,
            long auctionId,
            String rawKo,
            String normalizedKo,
            String placeName,
            String municipality,
            KoProvenance koProvenance) {

        Input {
            Objects.requireNonNull(referenceId, "referenceId");
            Objects.requireNonNull(koProvenance, "koProvenance");
            if (auctionId <= 0) {
                throw new IllegalArgumentException("auctionId must be positive");
            }
        }
    }

    record StructuredEvidence(
            String inputFingerprint,
            StructuredKoMatcher.Status status,
            StructuredKoMatcher.Method method,
            String rationale,
            String matchedKoCode,
            String sourceCadastral,
            String sourcePlaceName,
            String sourceMunicipality,
            String dictionaryVersion,
            String dictionarySourceSha256,
            String normalizerVersion,
            String aliasDatasetVersion,
            String aliasSha256,
            String municipalityAliasDatasetVersion,
            String municipalityAliasSha256) {

        StructuredEvidence {
            Objects.requireNonNull(inputFingerprint, "inputFingerprint");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(rationale, "rationale");
        }

        boolean matched() {
            return status == StructuredKoMatcher.Status.MATCHED && matchedKoCode != null;
        }
    }

    record Match(
            UUID referenceId,
            long auctionId,
            String inputFingerprint,
            String queryNormalizedKo,
            KoProvenance koProvenance,
            StructuredKoMatcher.Status status,
            Method method,
            String rationale,
            String matchedKoCode,
            StructuredKoMatcher.Status textStatus,
            StructuredKoMatcher.Method textMethod,
            String textMatchedKoCode,
            Reconciliation reconciliation,
            List<StructuredKoMatcher.Candidate> candidates,
            StructuredEvidence structuredEvidence) {
    }

    private final KoDictionarySnapshot dictionary;
    private final StructuredKoMatcher sharedMatcher;

    ExtractedKoMatcher(KoDictionarySnapshot dictionary, int fuzzyCandidateLimit) {
        this.dictionary = dictionary;
        this.sharedMatcher = new StructuredKoMatcher(dictionary, fuzzyCandidateLimit);
    }

    Match match(Input input, StructuredEvidence structured) {
        Objects.requireNonNull(structured, "structured");
        StructuredKoMatcher.Input sharedInput = StructuredKoMatcher.Input.extracted(
                input.auctionId(), input.referenceId().toString(), input.rawKo(),
                input.placeName(), input.municipality());
        StructuredKoMatcher.Match text = sharedMatcher.match(sharedInput);
        String normalizedBySharedContract = SerbianNameNormalizer.normalize(input.rawKo());
        if (!Objects.equals(normalizedBySharedContract, input.normalizedKo())) {
            text = new StructuredKoMatcher.Match(
                    input.auctionId(),
                    text.inputFingerprint(),
                    StructuredKoMatcher.Status.INVALID,
                    StructuredKoMatcher.Method.NONE,
                    "NORMALIZED_KO_CONTRACT_MISMATCH: PropertyReference.normalizedKo does not equal the shared "
                            + SerbianNameNormalizer.CONTRACT_VERSION + " result",
                    null,
                    List.of());
        }

        String fingerprint = fingerprint(input, text.inputFingerprint(), structured);
        StructuredKoMatcher.Status finalStatus = text.status();
        Method finalMethod = Method.valueOf(text.method().name());
        String finalRationale = text.rationale();
        String finalKoCode = text.matchedKoCode();
        Reconciliation reconciliation;

        if (text.status() == StructuredKoMatcher.Status.MATCHED && structured.matched()) {
            if (text.matchedKoCode().equals(structured.matchedKoCode())) {
                reconciliation = Reconciliation.AGREES;
                finalRationale = text.rationale()
                        + "; AGREES_WITH_STRUCTURED: extracted and structured evidence resolve to the same KO";
            } else {
                reconciliation = Reconciliation.CONFLICT;
                finalStatus = StructuredKoMatcher.Status.AMBIGUOUS;
                finalMethod = Method.STRUCTURED_CONFLICT;
                finalKoCode = null;
                finalRationale = "STRUCTURED_TEXT_CONFLICT: extracted KO candidate "
                        + text.matchedKoCode() + " disagrees with structured KO candidate "
                        + structured.matchedKoCode() + "; neither candidate was selected";
            }
        } else if (text.status() == StructuredKoMatcher.Status.MATCHED) {
            reconciliation = Reconciliation.TEXT_ONLY;
        } else if (structured.matched()) {
            reconciliation = Reconciliation.STRUCTURED_ONLY;
        } else {
            reconciliation = Reconciliation.BOTH_UNRESOLVED;
        }

        return new Match(
                input.referenceId(),
                input.auctionId(),
                fingerprint,
                normalizedBySharedContract,
                input.koProvenance(),
                finalStatus,
                finalMethod,
                finalRationale,
                finalKoCode,
                text.status(),
                text.method(),
                text.matchedKoCode(),
                reconciliation,
                text.candidates(),
                structured);
    }

    String fingerprint(Input input, StructuredEvidence structured) {
        StructuredKoMatcher.Input sharedInput = StructuredKoMatcher.Input.extracted(
                input.auctionId(), input.referenceId().toString(), input.rawKo(),
                input.placeName(), input.municipality());
        return fingerprint(input, StructuredKoMatcher.fingerprint(sharedInput, dictionary), structured);
    }

    private static String fingerprint(
            Input input,
            String sharedMatcherFingerprint,
            StructuredEvidence structured) {
        return EnrichmentHashing.sha256(
                MATCHER_VERSION,
                input.referenceId().toString(),
                Long.toString(input.auctionId()),
                input.rawKo(),
                input.normalizedKo(),
                input.placeName(),
                input.municipality(),
                input.koProvenance().name(),
                sharedMatcherFingerprint,
                structured.inputFingerprint(),
                structured.status().name(),
                structured.method().name(),
                structured.matchedKoCode());
    }
}
