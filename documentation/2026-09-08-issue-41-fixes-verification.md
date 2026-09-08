# Issue #41/#21 review corrections — 2026-09-08

Requested follow-up to the [local review](2026-09-08-issue-41-review.md): update
the outdated building-layer decision and fix the P1 and both P2 runtime findings.

## Changes

- **Decision:** the #41 record, sanitized source evidence, verifier, roadmap,
  and #42 disposition now retain the successful 2026-09-08 object schema and its
  candidate identity/geometry fields. The timeout-based not-feasible closure is
  withdrawn. #42 remains open pending join, footprint geometry, whitelist,
  dataset, and building-access verification. No building records were fetched.
- **P1 — retry discovery:** ordinary enrichment discovers unhandled RGZ `ERROR`
  attempts independently of a completed fallback result. It requires the current
  eligible #33 reference and configured feature/dataset. Terminal cache-backed
  attempts retire the pending work; stale KO errors do not reschedule it.
  Removing the kill switch resumes discovery without explicit replay or a
  version change. Quota-deferred identities are ordered before recently
  attempted failures, preventing repeated failures from monopolizing the ceiling.
  Generic permanent stage errors and exhausted stage-failure budgets still stop
  work; finer-tier retries do not overwrite the last-valid fallback.
- **P2 — disabled cache:** cache lookup precedes network gating. Another eligible
  reference can reuse the configured dataset's cached identity with fetching
  disabled, even with empty current metadata pins. Misses make no client call
  and consume no quota. Cache provenance comes from the original fetch.
- **P2 — cache identity:** V21 introduces `rgz_parcel_cache_keys`, keyed by the
  feature/dataset/KO/parcel fingerprint. Capability/schema hashes and resolver
  versions no longer cause re-fetches. The migration binds the first legacy
  terminal record and preserves duplicate records and their history. The binding
  has a composite foreign key to the matching cache record/fingerprint. A changed
  dataset remains a new identity.
- Resolver implementation version is now `rgz-parcel-v2`. V19/V20 were not
  rewritten; V21 applies through the normal Flyway upgrade path. No application
  database migration or runtime activation was performed by this change.

## Regression evidence

Tracked `RgzParcelResolutionIntegrationTest` coverage now exercises ordinary
candidate discovery, the real parcel/final-selection stages, processor
transaction boundaries, and the run ledger. Extraction/matching are seeded
before these tests; this is not a claim of the remaining full WFS-to-browser
acceptance workflow.

Tests cover transport/server/CRS failures after a successful coarse fallback;
terminal fallback retry across more than three ordinary runs; quota fairness;
kill-switch removal without version changes; stale KO retirement; terminal
negative-cache retirement; generic permanent/capped stage failures; disabled
cache reuse; changed metadata pins with original provenance; and cache records
from an older resolver version. `RgzCacheIdentityMigrationTest` upgrades a V20
fixture containing duplicate cache metadata versions without rewriting history.

Verification completed:

```bash
python3 spike/issue-41/verify.py
python3 -O spike/issue-41/verify.py
./gradlew test browserTest --no-daemon
git diff --check
```

- Java: **476 passed, 4 optional full-dataset/current-population tests skipped**.
- Browser: **24 passed**, including the localhost-only network guard.
- Original three off-tree review probes: **3 passed** unchanged against the
  corrected runtime; normal test-class compilation restored afterward.
- Both offline decision verifiers and whitespace checks passed.

The four skipped tests require optional official/full local datasets or the
current local auction population; no full-dataset verification is claimed.
No live RGZ calls, source downloads, credentials, runtime configuration changes,
or GitHub edits were needed for these corrections.

## Remaining scope at this review

Follow-up: [#21 verification](2026-09-08-issue-21-verification.md) subsequently
covers the full fixture workflow and operator kill-switch visibility described
below. The access/billing/building caveats remain unchanged.

This does not close #41/#21/#42. GitHub scope reconciliation, a complete building
contract decision, current dataset activation policy, and #21's full fixture
WFS → ordinary refresh → rendered polygon proof/operator kill-switch visibility
remain separate review items. The existing #26/#27 map renderer already supports
parcel polygons; #21 remains the next issue for that end-to-end workflow.
