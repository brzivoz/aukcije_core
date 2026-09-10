# Issue #51 — readable results, rail details and table

## Delivered

- Category/locality-first cards with explicit missing values, auction-level starting
  price, absolute Europe/Belgrade end time + offset, secondary auction number and
  textual precision. Multiple viewport locations never imply a per-parcel price or
  an exhaustive legal sale scope.
- One selected-property/open/dismissed lifecycle. Full details remain in the rail,
  never over the inspected geometry. Explicit Map-only/Table opening reveals that
  rail; mode switches retain its connected controls. Back/Escape/outside dismissal
  retains selection. Unavailable properties keep their identity and auction-level
  description without substituting sibling geometry or a Google Maps link.
- Complete retained Description/ShortDescription on deliberate access, valuation,
  start/publication/end times, first-sale metadata and allowlisted eAukcija navigation.
  The separate single-ID API is bounded/read-only/private/no-store, with no executor,
  snapshots, internal evidence or geometry. Neither GeoJSON nor default table fragments
  download descriptions. See [field allowlist/privacy review](MAP_API.md#deliberate-local-auction-details-51).
- Source workflow labels localized independently of temporal scope and geometry
  verification; unknown codes are explicitly labelled. Source values/URLs unchanged.
- Wrapping 15px metadata with sufficient contrast, separate scrollable detail body
  and persistent 44px Back control. Five primary table columns; native access to
  valuation/status/first-sale columns, sticky headers, visible direction text/arrows
  and `aria-sort`. Full-description links also work without JS/available basemap.
- Keyed result buttons and equivalent table focus/scroll restoration retain drafts,
  property selection, sort/page, result scroll and secondary-column choice through
  refresh/modes. Auction-only URL/reload/history semantics remain unchanged.

## Verification

Passed **88 backend tests**, including the new `AuctionDetailsIntegrationTest` and
`AuctionPresentationTest`, existing map API/PostGIS/Thymeleaf/filter tests, and isolated
reruns of sync persistence/pipeline tests:

```sh
./gradlew test \
  --tests '*MapAuction*Test' \
  --tests '*AuctionDetailsIntegrationTest' --tests '*AuctionPresentationTest' \
  --tests '*AuctionControllerLocationPresentationTest' \
  --tests '*SharedAuctionFilterParserTest' \
  --tests '*SyncPersistenceIntegrationTest' --tests '*PipelineStatusRepositoryIntegrationTest'
```

Passed **53 Playwright tests** using real local PostGIS/basemap and the shared
localhost-only network guard:

```sh
./gradlew browserTest \
  --tests '*AuctionReadabilityBrowserTest' --tests '*AuctionMapDetailsBrowserTest' \
  --tests '*CompactWorkspaceBrowserTest' --tests '*UnenhancedWorkspaceBrowserTest' \
  --tests '*AuctionMapBrowserTest' --tests '*SharedAuctionFiltersBrowserTest' \
  --tests '*ParcelMapBrowserTest' --tests '*AuctionComparisonsBrowserTest' \
  --tests '*ParcelSizeFiltersBrowserTest'
```

The new cases cover long hostile-looking Cyrillic/Latin text, absent fields, unknown
source status, multi-location auction price scope, unmapped description access,
keyboard/touch/source/Back flow, delayed/failed detail responses, dismissed state,
property disappearance without sibling substitution, sort direction, sticky headers,
secondary fields and both table/list scroll through hidden refresh/mode restoration.
Native GET detail/Back access is exercised with JavaScript disabled. Comparison privacy
assertions still exclude raw before/after values from evidence; the deliberately opened
current user-facing description is a separate projection.

Desktop 1366×900, narrow 390×844 and 200%-equivalent 683×450 checks assert wrapping,
15px metadata, >=4.5:1 metadata contrast, 44px Back, no document/detail horizontal
overflow and no rail/map overlap. Screenshots:
`build/browser-test-results/evidence/issue-51-details-{1366,683,390}.png`.
New/changed ES modules pass `node --check`; `git diff --check` is clean.

### Full-suite limitation

An unfiltered `./gradlew test` attempt ran 657 tests: 612 passed, 4 skipped, 41 failed.
Two failures are missing pre-existing, untracked-in-HEAD corpus inputs
`corpus/property-references/no-reference-audit-v1/{evidence,manifest}.json`.
The other 39 were shared Postgres connection exhaustion (`too many clients already`)
in pipeline/sync persistence context startup. Those classes passed in the 88-test
isolated rerun above. The new isolated detail test releases its Spring context/pool
after the class. No unrelated corpus or pipeline changes are included, and a green
unfiltered suite is not claimed.
