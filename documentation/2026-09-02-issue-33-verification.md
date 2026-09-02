# Issue #33 verification — 2026-09-02

## Outcome

Extracted cadastral-municipality matching is implemented in the production
`KO_MATCHING` enrichment stage and as the standalone `matchExtractedKo` task.
It consumes the current issue-#19 reference set, the active immutable issue-#14
dictionary, and the current issue-#37 structured result. Both structured and
extracted names use the same `SerbianNameNormalizer` and matching ladder.

Flyway V18 adds immutable per-reference decisions, current pointers, population
runs and memberships, source-provenance/split reconciliation metrics, and
production-run observations. A uniquely matched code
is copied only to a non-reviewed reference. Fuzzy, duplicate, malformed, and
structured/text-conflicting inputs do not auto-select a KO.

## Acceptance evidence

| Contract | Evidence |
|---|---|
| Shared normalization | Dictionary names, aliases, structured queries, and extracted queries all call `SerbianNameNormalizer.normalize`. The extracted matcher also compares issue-#19's retained `normalizedKo` to the shared normalizer and records `INVALID` / `NORMALIZED_KO_CONTRACT_MISMATCH` if they drift. |
| Deterministic decision order | `ExtractedKoMatcher` delegates to `StructuredKoMatcher`: exact six-digit code, unique exact normalized official name or reviewed alias, then municipality-constrained exact match. Bounded fuzzy candidates are review evidence only and never select a code. |
| KO-source provenance and useful metrics | Every result records whether its KO was `TEXT_EXTRACTED`, inherited as `STRUCTURED_FALLBACK`, or `UNRESOLVED`, and that value participates in the fingerprint. Runs split source population, matched counts/rates, and every reconciliation status by provenance, so fallback self-agreement is not reported as text confirmation. |
| Honest statuses and conflicts | Every result records `MATCHED`, `AMBIGUOUS`, `NOT_FOUND`, or `INVALID`, plus method, safe rationale, candidates, and resolution time. Different unique structured/text codes produce `AMBIGUOUS` / `STRUCTURED_CONFLICT`, retain both decisions and select neither. |
| Versioned provenance and aliases | Each result retains dictionary version, manifest/source/index hashes, source date, normalizer version, KO and municipality alias versions/hashes, and structured result fingerprint. Candidate evidence retains reviewed alias id, kind, provenance, source reference, reviewer, and review date. |
| Immutable persistence | V18 owns append-only result, run, run-membership, and enrichment-observation tables; only the per-reference current pointer is replaceable. Database checks bind status/method/code/reconciliation shapes and immutability triggers reject update/delete. |
| Minimal reprocessing | The fingerprint covers reference and auction identity, raw/normalized KO, place context, matcher/normalizer/dictionary/alias inputs, and the structured reconciliation input. An identical replay reuses the result and preserves both `resolved_at` and `selected_at`; any relevant version/input change creates a new result. |
| Reviewed data and downstream safety | Automatic writes never modify `user_reviewed` references. When an automatic current decision changes, incompatible parcel identity is detached before the KO code changes, while historical location evidence remains. Downstream consumers must join the current issue-#19 set and require a current issue-#33 `MATCHED` result. |
| Operational reporting | `matchExtractedKo` refreshes issue #37 first under the established worker-lock order and reports processed/unchanged, status, method, reconciliation, conflict, and match-rate counts with exact artifact versions. The production stage records the exact consumed result once per enrichment item. |

## Reviewed fixtures and quality gate

The committed synthetic review fixture covers Cyrillic and Latin forms,
diacritics, punctuation and spacing, exact codes, duplicate names with and
without municipality context, reviewed historical aliases, missing and
malformed names, normalization drift, and a genuine structured/text conflict.
It contains no auction-party or personal data.

The frozen held-out gate evaluates the 37 extracted KO labels from issue #19 as
an aggregate only. All 37 resolve to the reviewed exact code and zero resolve
to a wrong exact code. The regression also requires every result's method to be
one of the exact code/name/alias/context methods; a future non-exact `MATCHED`
method cannot inflate the count:

| Held-out labels | Correct exact matches | Exact-match false positives | Unresolved |
|---:|---:|---:|---:|
| 37 | 37 | 0 | 0 |

The held-out test exposes only these aggregate counts to prevent case-by-case
tuning against the frozen split.

## Verification

No verification command contacted eAukcija, RGZ, or an online geocoder.
PostgreSQL checks used the pinned local `postgis/postgis:18-3.6` Testcontainers
image.

```text
./gradlew test \
  --tests ExtractedKoMatcherTest \
  --tests ExtractedKoHeldOutQualityTest \
  --tests StructuredKoMatcherTest --no-daemon
BUILD SUCCESSFUL

./gradlew test \
  --tests ExtractedKoMatchIntegrationTest \
  --tests PostgisSchemaIntegrationTest --no-daemon
BUILD SUCCESSFUL

./gradlew clean check --no-daemon
BUILD SUCCESSFUL in 1m 49s
439 Java tests: 435 passed, 4 existing opt-in full-dataset tests skipped
14/14 offline basemap contract tests passed

./gradlew browserTest --no-daemon
BUILD SUCCESSFUL in 54s
24 browser tests: 24 passed

git diff --check
clean
```

No issue-#33 matcher, fixture, migration, persistence, reprocessing,
production-stage, held-out, or browser regression was skipped. The four Java
skips are the existing opt-in complete official-dataset population suites.

The reviewed invalid fixtures pin distinct rationale prefixes for missing KO,
malformed KO, and retained-normalization drift, so a regression cannot exchange
those branches while still satisfying only `INVALID` / `NONE`.

## Operations

The command, matching and reconciliation policy, payload-minimized inspection
queries, provenance model, privacy boundary, and recovery procedure are in
[extracted KO matching operations](EXTRACTED_KO_MATCH_OPERATIONS.md).

This is local implementation evidence only. It does not claim a commit, push,
remote CI result, or GitHub issue closure.
