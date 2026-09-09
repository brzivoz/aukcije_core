# Issue #54 — compact above-map workspace

Follow-up to #45, built on the committed #46 selection/dismissal lifecycle.
No backend filter/API, vendored asset, framework or dependency changes.

## Delivered

- Healthy refresh status and last complete success share the header with the
  one-action refresh/retry controls. Catalogue counts and operator diagnostics
  are disclosed on demand; running stage/count, errors and staleness remain visible.
- The single native form starts collapsed. Its desktop side column takes width,
  not height, from the view. Search/time/location/category/price come first;
  status/precision/date/first-sale are under **Још филтера** with an applied count.
- Applied chips remain visible with the default **Нису завршене** time scope.
  Dirty-state feedback is visible even while the form is closed. Chip removal
  preserves unrelated drafts, sort and selection; Reset retains #44 semantics.
  Decimal RSD values are never rounded through JavaScript `Number` for chips or
  dirty comparison. Presentation preferences, not criteria/drafts, persist locally.
- Native/server validation reveals the offending panel/disclosure and field.
  A denied presentation-storage operation does not break the workspace.
- Healthy map counts use one compact disclosure, explicitly distinguishing
  auction/property totals, returned counts and unmapped/outside-view auctions.
  Retryable view failures have a local-view retry action, not an enrichment action.
  Precision honesty remains visible; the repeated visual heading and long prose
  no longer occupy full-width rows above the map.
- Selection summary/details live in the rail, with only one full selection
  surface visible at a time. Map-only uses a small reopen/reason control and the
  same details article in a popup. Source links, explicit precision, dismissal,
  focus return and selected-property identity retain the #46 contract.
- Background refresh/panel/selection operations retain map height. Rail scrolling
  survives refreshes even while Map-only/Table hides its DOM. Late cluster results
  cannot replace a newer explicit selection or reopen dismissed content.

## Measured desktop bounds

Standard CSS viewport, healthy settled state, disclosures closed. Coordinates
exclude browser chrome. Both sizes retain visible attribution and no document-level
horizontal overflow.

| Viewport | Map top | Map height | Share of viewport height | Map width |
|---|---:|---:|---:|---:|
| 1366 × 768 | 156 px | 600 px | 78.1% | 980 px |
| 1920 × 1080 | 156 px | 912 px | 84.4% | 1534 px |

The top remains unchanged when opening/closing the side form, selecting a property,
opening/closing rail or Map-only details, and refreshing existing results. Warnings
and narrow/zoomed reflow can legitimately consume extra space; controls and warnings
are not clipped to enforce the normal-state height target.

Retained local #45 baseline evidence showed map tops of 428.3125 / 402.3125 px and
heights of 321.6875 / 659.6875 px at these sizes. Those historical screenshots use
#45's six-property fixture, while the focused #54 screenshots use one property;
they compare shell layout, not acquisition coverage. The baseline copy is under
`build/browser-test-results/evidence/issue-54-before/`.

Current reproducible evidence under `build/browser-test-results/evidence/`:

- `issue-54-workspace-{1366x768,1920x1080}.png`
- `issue-54-filters-{1366x768,1920x1080}.png`
- `issue-54-details-{1366x768,1920x1080}.png`
- `issue-54-workspace-bounds.json`

## Verification

```sh
./gradlew test
./gradlew browserTest
node --check src/main/resources/static/auction-map.mjs
node --check src/main/resources/static/auction-workspace.mjs
node --check src/main/resources/static/auction-filter-presentation.mjs
git diff --check
```

- Java suite: **530 passed, 4 skipped, 0 failed** (534 total). The skipped tests
  are the existing full Address Registry import/extract and private current-population
  KO/coarse-location gates, not browser tests.
- Browser suite: **52 passed, 0 skipped, 0 failed**.
- Syntax/whitespace checks pass.

`CompactWorkspaceBrowserTest` covers defaults, bounds, preferences, one-form/one-map
identity, applied vs draft state, chips including municipality drafts and 17-digit
RSD bounds, history/reset, hidden-field validation, stationary map position, rail
and Map-only detail focus/dismissal, exceptional selections, retry, storage denial,
and 683×384 zoom-equivalent / 390×844 reflow.

Existing browser suites retain real source-to-map refresh/retry, all precision
styles, partial/stale/error states, cluster activation and late-response races,
160-result scrolling through hidden modes, keyboard/focus/link/XSS contracts,
no-JavaScript GET filtering, reduced motion, and real resized viewport requests
under the 1,000,000 km² API ceiling. Every browser test retains the shared
localhost-only network guard; there are no new external asset requests.

## Related ownership

This implements #54's compact shell and required #50/#51/#52 integration behavior,
not those issues' unrelated richer descriptions, table redesign or every actionable
empty-state/navigation workflow. It retains one query/selection contract. #48's
broader keyed reconciliation, #49 navigation controls, and #53 mobile sheets remain
separate work.
