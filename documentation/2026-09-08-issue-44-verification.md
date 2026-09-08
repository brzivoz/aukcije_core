# Issue #44 shared-filter verification

Local implementation verified on 2026-09-08. This is not a claim of live portal
or RGZ verification, deployment, or terminal CI status.

## Delivered contract

- One `AuctionFilters` state, `AuctionFilterParser` compatibility/validation
  adapter, and parameterized `AuctionFilterSql` predicate replace the unrelated
  JPA/table and map models. `auction-filters.html` is the only filter form.
- Explicit not-ended / ended / all, UTC microsecond cutoff, Belgrade calendar
  boundaries and null end times; raw category/status remain independent.
- Shared retained/supported category/status options (including `Викендица` and
  `Closed`), literal Cyrillic/Latin search over number/short/full description,
  RSD bounds, first-sale, precision and safe stable sorting/pagination.
- `PublishableLocationSql` chooses eligible canonical-property winners before
  bbox/precision. Current #33 parcel gates, structured fallbacks, genuine
  multi-property locations and unmapped auctions remain honest.
- `/api/auctions/view` updates table, map/sidebar, counts, selection, options
  and catalogue statistics in one repeatable-read snapshot. The previous
  independent `refreshCatalogueWithoutReload` writer was removed.
- Applied URL state is separate from drafts; historical aliases, sort/page,
  selection, copy/reload/back/forward and background/source refresh preserve
  unrelated state. A rejected draft retains the last valid URL/results and its
  form values instead of becoming a repeatedly retried canonical query.
- V24 supplies immutable Serbian search functions, `pg_trgm` GIN and
  lowercase-status indexes. Filtering never calls sources, RGZ or enrichment.

## Acceptance evidence

| Regression | Evidence |
|---|---|
| Original unprefixed URL, defaults and historical aliases | `SharedAuctionFilterParserTest`, `SharedAuctionFiltersBrowserTest` |
| Per-filter and combined membership; literal/diacritic/digraph search, RSD and first-sale | `MapAuctionRepositoryIntegrationTest` real PostGIS fixtures |
| 179415-equivalent past/stale-status/no-snapshots KO point, strict equality and nullable end times | `sharedTemporalScopesHandleLegacy179415BoundaryAndUnknownEndWithoutSourceSnapshots` plus browser fixture |
| Both DST transitions, inclusive start/exclusive next-day end, same-day/inverted/contradictory ranges | Shared parser and real PostGIS DST/temporal tests |
| Duplicate and genuine properties; off-screen parcel suppresses on-screen lower-tier duplicate | Map repository integration and bounded GiST plan regression |
| Revoked #33 premise restores fallback, excludes parcel in both table and map without another enrichment run | `RgzAutomaticEnrichmentIntegrationTest.standaloneKoConflictImmediatelyRestoresTheMapFallbackWithoutAnotherEnrichmentRun` |
| Unknown/NONE, viewport/feature/auction counts, truncation and selection reasons | Map repository/service/controller integration tests |
| Sort/page/reset, selection, reload/copy/history, automatic/source refresh and unsaved drafts | Four `SharedAuctionFiltersBrowserTest` flows against real PostGIS/local basemap |
| Late successful response cannot overwrite rapid edits; retained 400/503/loading/empty/partial views | Shared browser rapid-edit regression and existing map fetch/cancellation matrix |
| Geometry/privacy/limit/current-parcel and offline guard regressions | Full Java/PostGIS and browser suites, including automatic-parcel and localhost-only tests |

## Commands and results

```sh
./gradlew check browserTest
git diff --check
```

- Build successful: 3m 49s.
- Java/unit/PostGIS: **531 tests**, **527 passed**, **4 skipped**, zero failures.
  The existing full-registry import/extract and current-population KO/coarse
  artifact-dependent tests are opt-in and skipped without their local inputs.
- Browser: **31 passed**, zero skipped/failures, using the existing loopback-only
  network guard. No new external test service or frontend dependency.
- `check` also verified the property-reference corpus; unchanged basemap checks
  were up-to-date. Existing vendored geometry/privacy/asset checks passed.
- Reports: `build/reports/tests/test/`, `build/reports/tests/browserTest/`;
  browser evidence: `build/browser-test-results/evidence/`.

README, MAP_API, frontend documentation and the shared user/URL guide now state
the new defaults. Dated #26/#27 evidence is explicitly marked superseded where
it described dual filters. #28's GitHub body and repository roadmap were
updated to retain normalized taxonomy/KO and richer list–map navigation as its
remaining scope, consuming #44 rather than building another filter model.

Historical source-snapshot backfill, new parcel acquisition for old auctions,
#11 lifecycle auditing, and optional shared spatial-search UX are not claimed
as implemented by this slice.
