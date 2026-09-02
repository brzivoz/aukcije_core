# Extracted KO matching operations

Issue [#33](https://github.com/brzivoz/aukcije_core/issues/33) matches every
current non-structured `PropertyReference.rawKo`/`normalizedKo` pair from #19
against the active immutable #14 dictionary. The production `KO_MATCHING`
enrichment stage refreshes the auction's structured #37 result first and then
runs this matcher. The standalone population command does the same in one
transaction.

## Decision order and shared normalization

The matcher applies this order:

1. exact official six-digit KO code;
2. one exact normalized official name or reviewed KO alias;
3. one exact-name candidate after constraining duplicates by the source
   `Place.Municipality`, including reviewed municipality equivalences; and
4. bounded edit-distance candidates for human review only.

Step 4 never selects a KO. Duplicate official names remain `AMBIGUOUS` unless
the municipality denotes exactly one official municipality and leaves exactly
one candidate. Missing or unusable names are `INVALID`. A non-exact query with
review candidates is `NOT_FOUND`, not matched to the highest-ranked candidate.

Both dictionary publication and query matching call
`SerbianNameNormalizer.normalize`. The V18 result retains both #19's
`source_normalized_ko` and the matcher's `query_normalized_ko`; if they differ,
the result is `INVALID` with `NORMALIZED_KO_CONTRACT_MISMATCH`. This fails
closed if parser and matcher normalization ever drift.

Aliases are accepted only from the checksum-validated #14 review artifact.
Candidate JSON embeds the alias id, kind, provenance, source reference,
reviewer, and review date. There are no parser/resolver one-off name rules.

Each result also retains `ko_provenance`:

- `TEXT_EXTRACTED` means the reference's KO came from a `КО ...` text match in
  the current #19 extraction set;
- `STRUCTURED_FALLBACK` means a non-KO text reference inherited
  `Place.Cadastral` because no text KO was extracted; and
- `UNRESOLVED` means #19 could attribute no KO context.

Provenance participates in the fingerprint. `AGREES` therefore means only that
the two compared values resolve to the same code; it is evidence that text
confirms the structured value only when `ko_provenance = 'TEXT_EXTRACTED'`.
For a structured fallback, agreement is expected self-consistency and is
reported separately.

## Structured/text reconciliation

Every extracted result snapshots the current #37 status, method, rationale,
input fingerprint, structured source fields, selected code, and dictionary
provenance. Reconciliation is explicit:

| Reconciliation | Result |
|---|---|
| `AGREES` | Both sides uniquely select the same code; the reference is `MATCHED`. |
| `CONFLICT` | Both sides uniquely select different codes; the reference is `AMBIGUOUS`, method `STRUCTURED_CONFLICT`, and neither code is selected. |
| `TEXT_ONLY` | Text uniquely matches while structured evidence is unresolved; the text code is selected. |
| `STRUCTURED_ONLY` | Structured evidence matches but the text reference does not; no code is copied into the text reference. |
| `BOTH_UNRESOLVED` | Neither side uniquely matches; the text status remains authoritative for this reference. |

This policy covers the genuine #32 conflicts such as structured `СЈЕНИЦА`
versus extracted `Урсуле`: a downstream parcel resolver must join the current
#33 result and require `status = 'MATCHED'`; it must not choose either side of a
`CONFLICT`.

For a non-reviewed reference, the selected code is copied to
`property_references.ko_code`. When a current result changes or becomes
unresolved, an incompatible `parcel_identity_id` is detached before the code
is changed; prior location-attempt and geometry evidence remains retained.
`user_reviewed` references are never modified by automatic matching, although
their independent automatic result remains available for audit.

## Persistence and idempotency

Flyway V18 owns:

- `property_reference_ko_match_results`: immutable results keyed by reference
  and input fingerprint, including raw/normalized query, final and text-only
  decisions, candidates, reconciliation evidence, exact dictionary/source
  hashes, alias review hashes, and `resolved_at`;
- `current_property_reference_ko_matches`: the only replaceable per-reference
  pointer;
- `extracted_ko_match_runs` and `extracted_ko_match_run_results`: immutable
  population reports and their ordered exact result membership, including
  source-provenance population/matched totals for text versus fallback, and
  reconciliation counts split by source provenance; and
- `property_reference_ko_match_observations`: immutable links from production
  enrichment run items to the exact results they consumed.

The fingerprint includes reference identity, auction identity, raw and #19
normalized KO, source place/municipality, matcher version, shared matcher
fingerprint, current structured-result fingerprint/status/method/code, KO
provenance, and all
dictionary/normalizer/alias versions and hashes. Identical inputs reuse the
immutable result and leave `resolved_at` plus the current pointer's
`selected_at` unchanged. A changed reference, normalizer, alias set,
dictionary, or structured reconciliation input creates a new result and moves
only the current pointer.

Historical references may retain a current #33 pointer after a new extraction
set is selected. Operational and downstream reads must start from
`current_property_reference_extractions`, then join its memberships and the
current #33 pointer; this preserves old evidence without treating it as live.

Issue #33 does not delete or rewrite `current_location_resolutions`. Future
#21/#23 parcel-resolution code must treat the current #33 pointer as part of a
selected parcel resolution's validity: if that reference is no longer
`MATCHED`, a previously selected `PARCEL` result must be superseded or
invalidated even when `matchExtractedKo` runs outside the enrichment pipeline.
Pipeline stage ordering alone is not a sufficient guard. Under
`STRUCTURED_ONLY`, the text reference intentionally has no KO code; #21/#23
must not copy the separate auction-level #37 code into it.

## Run and inspect

Build and review the active dictionary first. With the explicit PostgreSQL
profile and ignored secret already configured:

```bash
export SPRING_PROFILES_ACTIVE=dev
export AUKCIJE_DB_PASSWORD="$(tr -d '\r\n' < .secrets/postgres-password)"
export KO_STRUCTURED_MATCH_DICTIONARY_DIRECTORY="$PWD/data/address-registry-ko-dictionary"
./gradlew matchExtractedKo
```

The command first refreshes #37, then acquires the #33 population advisory
lock. Artifact/provenance drift, a missing current structured row, or any
database error rolls back both phases. Its JSON reports processed/unchanged
counts, all four statuses, conflict and reconciliation counts,
`overallMatchRatePercent`, separate text/fallback match rates, both run IDs,
and exact artifact versions.

Payload-minimized inspection queries:

```sql
SELECT result.ko_provenance, result.status, result.method,
       result.reconciliation_status, count(*)
FROM current_property_reference_extractions extraction
JOIN property_reference_extraction_memberships member
  ON member.extraction_run_id = extraction.extraction_run_id
JOIN current_property_reference_ko_matches current_match
  ON current_match.reference_id = member.reference_id
JOIN property_reference_ko_match_results result
  ON result.reference_id = current_match.reference_id
 AND result.input_fingerprint = current_match.input_fingerprint
GROUP BY result.ko_provenance, result.status, result.method,
         result.reconciliation_status
ORDER BY result.ko_provenance, result.status, result.method,
         result.reconciliation_status;

SELECT result.auction_id, result.reference_id, result.ko_provenance,
       result.source_normalized_ko,
       result.text_matched_ko_code, result.structured_matched_ko_code,
       result.rationale, result.candidates, result.reconciliation_evidence
FROM current_property_reference_extractions extraction
JOIN property_reference_extraction_memberships member
  ON member.extraction_run_id = extraction.extraction_run_id
JOIN current_property_reference_ko_matches current_match
  ON current_match.reference_id = member.reference_id
JOIN property_reference_ko_match_results result
  ON result.reference_id = current_match.reference_id
 AND result.input_fingerprint = current_match.input_fingerprint
WHERE result.status <> 'MATCHED'
ORDER BY result.auction_id, member.reference_order;

SELECT finished_at, population_count, processed_count, unchanged_count,
       matched_count, ambiguous_count, not_found_count, invalid_count,
       conflict_count, text_extracted_count, text_extracted_matched_count,
       structured_fallback_count, structured_fallback_matched_count,
       unresolved_ko_provenance_count, method_counts, reconciliation_counts,
       reconciliation_by_ko_provenance
FROM extracted_ko_match_runs
ORDER BY finished_at DESC;
```

No command or test contacts eAukcija, RGZ, or an online geocoder. Candidate and
reconciliation evidence contains KO/place names and official artifact
provenance only; it does not copy descriptions, auction-party data, source
payloads, credentials, or session state.

## Recovery

- `STRUCTURED_KO_PROVENANCE_STALE`: rerun `matchExtractedKo`; do not edit
  result rows or Flyway history.
- dictionary checksum/manifest failure: restore or republish the last reviewed
  #14 artifact and retry.
- ambiguous/conflicting rows: review the retained candidates and source
  evidence. Add a reviewed, versioned alias only through #14's alias dataset;
  never patch parser or matcher code for one name.
- a bad deployment: roll back application code normally. V18 evidence remains
  valid and immutable; a later matcher version creates new results rather than
  rewriting it.
