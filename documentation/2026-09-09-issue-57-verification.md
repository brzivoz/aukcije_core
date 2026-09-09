# Issue #57 — shared individual parcel-size filters

## Implemented contract

One `parcelSize` criterion extends `AuctionFilters`, its parser/URL helpers and
the existing native shared GET form. Default/blank means All sizes; the only
nonblank values are `under-8`, `8-15`, `over-15`. Invalid/repeated values receive
the same field-specific 400 on `/`, `/api/map/auctions` and `/api/auctions/view`.

The presets compare exact PostgreSQL `numeric` square metres: `< 800`, inclusive
`800..1500`, `> 1500`. One ar is 100 m². Only positive JSON-number area from the
current eligible RGZ attempt is used; absent/null/malformed/non-positive evidence
is unknown, not zero. The area is the whole individual cadastral parcel, never
floor area, footprint, an ownership-share fraction or the sum of a lot. Category
is independent. Serbian control/help and the shared/API/frontend guides explain
these limits and incomplete coverage. General search remains literal.

Canonical winners, current extraction/KO eligibility and suppression of coarse
fallbacks precede size/precision/viewport predicates. An auction qualifies once
when any winning parcel matches; map results include only matching properties.
Size and precision use one shared property predicate, including counts, selection
explanations and table tier labels. Genuine properties keep existing feature IDs;
duplicate references do not inflate results. No schema migration, raw evidence
export, new source request, enrichment trigger or backfill is introduced.

## Fixture-driven regression coverage

- `SharedAuctionFilterParserTest`: default/blank, all presets, canonical round
  trips, Serbian active captions, sort/page/reset/selection helpers, unsupported
  and repeated values (including identical/blanks), literal `< 8ar` search.
- `ParcelSizeFilterIntegrationTest`: real migrated PostGIS and all three HTTP
  routes. Exact 800/1500 boundaries and adjacent fractional values with 18
  decimal places; very small/large numbers; null/missing/malformed JSON, numeric
  strings, booleans, arrays, objects, zero/negative values and no location.
  Multi-parcel/duplicate/shared-parcel auctions, stable IDs, no sums/share division,
  non-RGZ area-looking evidence, same-property precision and combined
  category/time/municipality/place/status/price/first-sale/search/date predicates.
  Selected replacements and historical losers, off-screen winners, unknown current
  area, suppressed centroids, revoked/missing KO and extraction premises, review
  references and an unrelated historical cache success. Counts, table pagination,
  feature limits and every selection explanation are covered. Source/RGZ client
  mocks have **zero interactions**; reads leave attempts/cache evidence unchanged.
- `SpatialResolutionSchemaIntegrationTest`: existing topology/publication and
  GiST tests plus all three parcel-size query plans described below.
- `ParcelSizeFiltersBrowserTest`: real PostGIS/local basemap, one form, keyboard
  native-select type-ahead/Enter Apply, all presets/All sizes, chips/removal/reset,
  drafts through periodic and source-completion refresh, sort/page/selection,
  copied links/reload/back-forward, panning without restricting the global table,
  and keyboard-accessible help/control bounds at 390px.
- `UnenhancedWorkspaceBrowserTest`: all presets and All sizes through native GET
  with JavaScript disabled; page reset with sort/selection retained, unknown-area
  inclusion and narrow layout. Browser network traffic uses the existing
  localhost-only guard. No live coverage counts are acceptance expectations.

## Query plans and schema decision

The plan fixture has 20,000 background geometries with five historical attempts
each, the existing spatial edge cases and 60 sized/current RGZ parcels (20 per
band) with real extraction/KO selection constraints. No planner knobs force an
index. `EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)` records map, table-page and global
auction-count reads for all three bands under:

```text
build/reports/parcel-size-plans/{under-8,8-15,over-15}-{map,table,count}.txt
```

The map keeps `idx_spatial_resolution_geometries_canonical`,
`idx_location_resolution_attempts_geometry`, inclusive `ST_Intersects`, stable
ordering and `LIMIT + 1`. Only eleven features are hydrated for a ten-feature
request. Each band has twenty global/viewport auctions before limiting. Winners
are not a global materialized geometry load; competitor reads stay auction-local.
Table pages are limited to 25 IDs before bulk entity hydration; counts return
scalars, not catalogue/evidence payloads.

A representative local emulated PostgreSQL 18/PostGIS 3.6 run measured roughly
1.65–1.87 ms map execution and 334–388 ms global count/table execution on that larger
fixture. These are observations, **not timing assertions or production SLAs**.
The global reads inspect current eligibility/winners, with most buffer work in
indexed selection/competitor lookups rather than JSON conversion. For the v1
catalogue and bounded spatial path, retaining the existing JSON evidence is
preferable to introducing another stored projection/index or backfill. Revisit
indexing if larger current populations make these aggregate reads a bottleneck;
never push size into winner competition to obtain a faster but incorrect result.

## Verification commands

```bash
./gradlew test --tests '*SharedAuctionFilterParserTest' \
  --tests '*ParcelSizeFilterIntegrationTest' --tests '*MapAuctionRepository*Test' \
  --tests '*AuctionRepositoryPostgisIntegrationTest' --tests '*SpatialResolutionSchemaIntegrationTest'
./gradlew browserTest --tests '*ParcelSizeFiltersBrowserTest' --tests '*UnenhancedWorkspaceBrowserTest'
./gradlew check browserTest --continue
git diff --check
```

The focused Java/PostGIS and browser regressions pass. Final full-run results:

- **Java/PostGIS:** 619 cases, **613 passed**, four opt-in cases skipped, two
  failures. The only failures are the unchanged `NoReferenceAuditCorpusTest`
  cases: this checkout lacks `corpus/property-references/no-reference-audit-v1/`
  `evidence.json` and `manifest.json`. That audit corpus is also absent from
  HEAD's tracked tree. No #57 test fails, and no unavailable fixture is fabricated,
  skipped or weakened to claim a green full check. The four normal opt-in skips
  are the full official import/extract and mutable current-population probes.
- **Browser:** all **62 Chromium cases passed**, with no skips/failures, including
  the native GET tests and full existing workspace/map/source-refresh suites.
- Frozen v1 corpus/parser gates and the basemap verification task passed (the
  unchanged basemap task was up to date). `git diff --check` passed.

`./gradlew check browserTest --continue` consequently exits nonzero solely for
those two missing-audit-corpus failures; it is **not** reported as a successful
full `check`. The final local log is `tmp/issue-57-final-verification.log`.
Gradle's XML/HTML reports are under `build/test-results/{test,browserTest}/` and
`build/reports/tests/{test,browserTest}/`. New fixture application contexts are
closed after their classes so their connection pools do not accumulate in the
shared PostGIS test server.
