# Issue #56 verification — 2026-09-09

Implemented all three stages over #11's retained successful-publication contract:

1. Shared server-side Changes since criteria, rolling durations, Belgrade civil
   input with explicit DST handling, canonical UTC links, disjoint New/Updated
   membership/counts and safe textual evidence.
2. Explicit browser-local checkpoints and frozen Previous visit boundaries,
   captured from displayed source frames rather than status/enrichment versions.
3. Exact per-auction review references, changed/unchanged/unavailable feedback,
   and a separately labelled relaxed-scope reviewed-auction panel.

## Verification runs

Final full command:

```sh
./gradlew --continue comparisonStorageTest test browserTest --console=plain
```

| Suite | Result |
|---|---|
| Dependency-free Node storage tests | 7 passed |
| Full Java/PostGIS suite | 648 total: 642 passed, 4 skipped, 2 failures below |
| Full offline Playwright suite | 68 passed, no failures/skips |
| `git diff --check` | clean |

The aggregate command exits nonzero **only** because this checkout lacks
`corpus/property-references/no-reference-audit-v1/evidence.json` and
`manifest.json`. `NoReferenceAuditCorpusTest` fails its dynamic-test initialization
and inventory test with FileNotFoundException. This pre-existing checkout
limitation is also recorded in [#11 verification](2026-09-09-issue-11-verification.md)
and [#57 verification](2026-09-09-issue-57-verification.md). No corpus was fabricated,
relaxed, downloaded or skipped to make the suite green.

Focused runs also pass:

```sh
./gradlew comparisonStorageTest test \
  --tests '*CatalogueChangesIntegrationTest' \
  --tests '*ChangesSinceParserTest' \
  --tests '*MapAuctionServiceTest' \
  browserTest --tests '*AuctionComparisonsBrowserTest'
```

Reports: `build/reports/tests/{test,browserTest}/`, XML under
`build/test-results/{test,browserTest}/`; browser failure artifacts use the existing
harness. All browser traffic is fixture-driven and localhost-only; the comparison
integration suite also verifies no source/RGZ client interactions.

## Evidence exercised

- Monday changes after unchanged Tuesday; A → B → A; new-then-updated identities
  remain New and are not falsely labelled reverted without a baseline.
- Detail-only, starting-price, source-status/date changes; numeric representation
  equality; live-price-only opt-in; unchanged fetches; enrichment-only row changes;
  policy maintenance is not an update and has an explicit coverage warning.
- Elapsed end with the same publication, already-acknowledged effective end later
  audited without becoming another unseen change, absence and reopening.
- Two different review baselines, re-acknowledgement/clear, unavailable identities,
  foreign/unknown/future references, pre-history coverage and bounded request bodies.
- Repeatable-read concurrency; publication may commit after choosing the temporal
  evaluation instant without invalidating the independently ordered reference.
- Browser rejection of mismatched table/map frames; checkpoint actions retain the
  older actually displayed publication, even across failed/in-flight refreshes.
- Review activation begun on old content cannot acknowledge newer content after
  DOM replacement. Ordinary details show the auction's own review reasons, including
  reverted activity; incomplete review responses are unavailable, not zero changes.
- First visit, frozen reload/session/tab behavior, out-of-order displays, blocked
  storage, corruption and explicit recovery, no silent eviction at 200 reviews.
- UTC native no-JS GET, chips/removal/reset, copied links/history, draft-safe refresh,
  visible keyboard error targets and narrow layouts. Existing shell/selection,
  sorting/pagination, freshness, parcel and map-limit regressions pass.

## Existing-catalogue bootstrap follow-up

The reported Last 7 days warning was reproduced against the running local app:
970 auctions, eight older SUCCEEDED syncs (latest 2026-09-09 01:00:19 UTC), and
zero ordered `source_publications`. All those successful syncs preceded V29.
The diagnosis used read-only SQL and GET requests; no live source refresh was
triggered and no historical publication was fabricated.

The notice now explicitly instructs a successful **Освежи све податке** to start
history, distinguishes it from map reload, recommends a checkpoint for subsequent
changes, and explains that a seven-day baseline cannot be reconstructed by a
refresh. A first source refresh enables checkpoints immediately, not retroactive
seven-day coverage. Legacy lifecycle-only rows without observed source content
also no longer expose invalid/null-policy review acknowledgements.

Verification: `./gradlew test --tests '*CatalogueChangesIntegrationTest' --tests
'*ChangesSinceParserTest'` — **14 passed**, including first-publication bootstrap,
seven-day lower-boundary coverage and unavailable legacy review controls.

## Representative PostGIS read measurements

`CatalogueChangesIntegrationTest` uses real promotion/success gates and a
600-auction fixture (20 ended, 580 future-ended). An update to 200 identities gives
200 table matches, 197 unmapped, two viewport features, one outside the viewport,
and one returned feature at `limit=1`; normal Not ended still returns 180.

On the final local digest-pinned PostGIS 18/3.6 run:

- 200-review batch: **16.08 ms** (representative, not a benchmark guarantee).
- Filter EXPLAIN ANALYZE execution: **2.95 ms**; indexed starting-price scan plus
  `idx_source_observations_new`, `idx_source_observations_revision` and
  `idx_source_observations_meaningful` probes.
- V30 also adds the sparse comparison-gap index used for policy/baseline warnings.

History metadata is batched at <=200 identities; map limits remain <=5000 features
(browser request 1000), table hydration remains 25 rows. Reviews use a read-only
POST with <=200 distinct identities and a <=96 KiB body checked before parsing.
No history N+1, full-catalogue browser filter, shared review mutation, raw snapshot/
description export, accounts or historical geometry replay is introduced.

See [API/coverage/privacy semantics](MAP_API.md#changes-since-and-reviewed-revisions-56)
and [storage/visit/reset semantics](BROWSER_AND_FRONTEND.md#comparisons-and-local-review-state-56).
