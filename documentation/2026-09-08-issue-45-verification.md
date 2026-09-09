# Issue #45 — map-first desktop workspace

Local verification uses the seeded Java Playwright/PostGIS harness and compact
vendored basemap fixture, not the live portal or RGZ. No deployment or CI result
is claimed.

## Delivered

- Full desktop width with 16px gutters, a 350px collapsible results rail (320px
  on smaller desktops), and a viewport-filling map. No 1,200px container cap or
  fixed 580px map. Long result lists scroll without sizing the map.
- Native keyboard-operable Map + results, Map only and Table buttons expose the
  current mode with `aria-pressed` and an underline. The rail and one shared GET
  filter form expose their disclosure state. Mode changes neither navigate nor
  recreate the form/map; applied criteria, drafts, camera, selection, sort and
  pagination survive. Table selection reveals Map + results.
- Compact start/retry and persisted last-complete-success strip. Running stage
  and counts remain discoverable on the progress disclosure; last-attempt
  details, stages, schedule and operator controls expand on demand. Failures,
  live announcements and stale-map warnings with last-good time remain outside
  collapsed diagnostics, including in Table mode.
- Technical versions and extended shape/text precision legend are expandable.
  Coarse-location honesty and the single selected-feature precision/explanation
  stay visible in all modes. Escape restores disclosure-summary focus.
- One resize owner handles rails, filters, disclosures, selection and viewport
  changes, including changes during style load. The invisible Table-mode map
  retains its measured size and camera. Minimum zoom is recalculated and actual
  API requests remain below 1,000,000 km². Layout-only refreshes avoid a
  loading/ready height feedback loop and do not retry an error notice.
- Narrow/zoomed windows reflow; map precedes the narrow results list. Table
  horizontal scrolling stays inside its focusable region, including through
  result refreshes. Reduced motion and no-JavaScript GET fallback remain usable.

Advanced-filter/chip UX, mobile bottom sheets, richer result content, parcel
rendering and popup lifecycle changes are not implemented by this slice.

## Retained evidence

`AuctionMapBrowserTest` writes screenshots and measured map/container/rail/
attribution bounds to `build/browser-test-results/evidence/issue-45-*`:

| Viewport | Map dimensions (CSS px, rounded) | Rail | Map bottom |
|---|---:|---:|---:|
| 1366 × 768 | 964 × 322 | 350 | 750 |
| 1920 × 1080 | 1518 × 660 | 350 | 1062 |
| 2560 × 1080 | 2158 × 660 | 350 | 1062 |

All default controls and map bounds fit without initial scrolling; attribution
is within the map frame. Empty basemap bands on the widest screenshots reflect
the intentionally small offline tile fixture, not a canvas-sizing gap.

The same harness checks rail/mode/filter toggles, focus/keyboard behavior,
expanded legend and progress, 683×384 (200%-zoom-equivalent) and 390×844 reflow,
horizontal scrolling through refresh, actual API bbox areas after minimum-zoom
resizes, and absence of resize/request feedback loops. Shared-filter tests check
state preservation with historical criteria, page 1, sorting, selected coarse
location and unsaved municipality/search/precision edits. Refresh tests retain
the persisted workflow, stage/reload/second-tab and retry regressions; the
end-to-end refresh test still uses the real local coordinator. The existing
localhost-only guard remains active, including negative controls.

## Verification commands

```sh
./gradlew check browserTest
node --check src/main/resources/static/auction-workspace.mjs
node --check src/main/resources/static/auction-map.mjs
node --check src/main/resources/static/refresh-workflow.mjs
git diff --check
```

Final local run: **BUILD SUCCESSFUL (4m 11s)**. Java/unit/PostGIS: **530 passed,
4 skipped**, zero failures (534 total). The four existing full-registry and
current-population checks require opt-in local artifacts. Browser: **37 passed**,
zero failures/skips. Parser/corpus checks, module syntax and whitespace checks
also passed; the unchanged basemap check was up-to-date.

Reports: `build/reports/tests/test/` and `build/reports/tests/browserTest/`.
Successful screenshots/JSON are retained under `build/browser-test-results/evidence/`;
failures retain screenshots and traces under `build/browser-test-results/artifacts/`.
