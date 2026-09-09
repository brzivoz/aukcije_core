# Issue #46 — predictable map-details dismissal

## Implemented contract

- Auction identity, the current GeoJSON property ID, transient details visibility
  and the return-focus trigger are separate state. Dismissal changes only
  visibility: URL criteria, selected auction/property and highlighting remain.
  Both the popup and `#map-selection` summary hide on dismissal, including
  summary controls; refreshes keep both hidden until explicitly reopened.
- Blank-map/outside clicks dismiss in document capture, before feature/button
  opening handlers; inside content/source clicks do not dismiss. Escape and
  MapLibre's localized **Затвори детаље аукције** × use the same dismissal state.
  The close target is 44×44 px. Explicit result/table/feature/reopen activation
  opens the correct property without an opening-click dismissal race.
- Refresh updates open popup/summary controls in place rather than removing
  focused nodes. Keyboard opening focuses the safe source link or labelled
  article; Escape/× restore a connected visible trigger or matching-result/map
  fallback. Pointer click-away does not reclaim focus. Native municipality and
  explanatory disclosures retain their own Escape handling.
- Viewport requests, periodic updates, source-refresh completion and source/layer
  redraws cannot reopen dismissed details. Unavailable selected properties are
  retained, not replaced by another property of the same auction.
- MapLibre tiling was observed coercing a polygon ID from `34002:hash` to
  `34002`. Sources now explicitly promote the existing API ID via a local
  `mapFeatureId` property, and selection reads full viewport geometry rather
  than clipped tile geometry. No server identity or query contract changes.
- Reload/copied URLs and back/forward restore auction selection with details
  **closed**. Only `auction` is shareable; no popup/property DOM/open-state
  parameter or history entry is added by dismissal/reopening. User, frontend
  and API documentation describe this separately from filter restoration.

## Browser acceptance coverage

`AuctionMapDetailsBrowserTest` adds eight fixture-driven Playwright flows using
real PostGIS, the compact local basemap and the shared localhost-only guard:

| Area | Evidence |
|---|---|
| Pointer opening/dismissal | Actual map polygon, result and table clicks; same/other property activation; content/source clicks; blank-map and filter-control click-away; explicit reopen |
| Keyboard/accessibility | Enter/Space, Escape/×, 44px localized close control, safe-link/article focus, connected result/canvas return focus and hidden-table/summary-trigger fallback |
| Background focus | Change the real auction price, await periodic response and prove focused popup link/× and summary reopen nodes remain connected |
| Dismissed refresh stability | Every dismissal method followed by actual pan/zoom and periodic view responses, completion-listener requests, source `setData` and layer redraw; popup and summary stay hidden while URL, property identity and highlights are retained |
| Follow-up layout | eAukcija and Google Maps links have separate lines with a measured gap at desktop and 390px widths; switching map features must not shift the map before hit testing |
| Missing property | Filter out one property while a sibling remains, then pan outside/back; no substitution or automatic reopening |
| URL restoration | Dismissal and open-state reloads, copied link, back/forward, canonical query inventory and retained auction selection |
| Safety/native controls | Literal hostile title, rejected script/wrong-origin/path/query/auction source URLs, municipality checkbox drafts and Escape/outside-click focus, no external asset traffic |

The unsafe-link test decorates only the link field of a real response to exercise
otherwise-unreachable defensive rendering. Viewport, periodic and property
selection tests do not replace GeoJSON with canned responses.

`RefreshEndToEndBrowserTest` also dismisses a selected result and runs a second
normal local fixture source → enrichment → map workflow. It waits for the real
production completion event and next shared-view response, verifies two durable
successful runs, and proves details stay closed without stealing refresh-button
focus. Existing map/cluster keyboard, responsive, precision, XSS, shared-filter,
URL, accessibility and network regressions remain in the suite.

## Verification

```sh
node --check src/main/resources/static/auction-map.mjs
git diff --check
./gradlew check browserTest --no-daemon
```

Original full-check result: **BUILD SUCCESSFUL**.

- Unit/integration suite: **530 passed**, 4 optional private-input tests skipped,
  zero failures/errors (534 discovered). Basemap/corpus/parser gates passed.
- Browser suite: **44 passed**, zero failures/errors/skips, including all seven
  new details flows and the second real source-refresh workflow.
- JavaScript syntax and whitespace checks passed.

Earlier attempts were interrupted by a concurrent Gradle daemon stop and a
transient Ryuk startup failure. No tests or network protections were disabled;
the final serialized rerun completed successfully.

Follow-up for summary dismissal and link layout:

```sh
./gradlew browserTest --no-daemon
```

**45 passed**, zero failures/errors/skips, including all eight details flows.
Closing details now hides `#map-selection` through refreshes, with focus falling
back from hidden summary controls to the matching result. Source/Google Maps
links have separate lines at desktop and phone widths. Map hit testing and the
active cluster chooser remain stable when the summary collapses/resizes the map.
JavaScript syntax and whitespace checks also passed.

Reports: `build/reports/tests/test/` and `build/reports/tests/browserTest/`.
Existing desktop/narrow screenshot evidence remains under
`build/browser-test-results/evidence/issue-27-*` and `issue-45-*`.

Full keyed result reconciliation, a new durable property identity, replacement
modals/drawers and operator cancellation semantics remain outside this issue.
