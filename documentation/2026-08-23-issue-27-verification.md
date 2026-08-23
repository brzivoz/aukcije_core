# Issue #27 precision-aware auction map verification

Date: 2026-08-23

Scope: usable MapLibre auction map in the existing Thymeleaf shell, local
PMTiles only, bounded viewport data, honest precision presentation, clustered
shared-centroid handling, safe selection/URL state, visible freshness, and
desktop/narrow browser evidence

## Acceptance evidence

| Contract | Evidence |
|---|---|
| Local basemap and bounded viewport loading | `auction-map.mjs` creates the #25 same-origin PMTiles map and requests only `/api/map/auctions` with its current WGS84 bounds, a `1000` limit, and allowlisted filters. `moveend` is debounced by 250 ms; scheduling another load aborts the active `AbortController`. Minimum zoom is calculated from actual canvas dimensions against the API's spherical area ceiling, recalculated on resize, and guarded again before fetch. |
| Loading, empty, error, and partial-limit states | The live region distinguishes all four states. Existing features remain rendered while a replacement request loads or fails. `truncated=true` has a persistent warning telling the user to zoom or filter; an empty successful response clears both sources and says why. Structured `400` field/detail text is shown without a false retry instruction, while network/`5xx` failures remain retryable. Errors switch both `role=alert` and `aria-live=assertive`. |
| Dense/shared points | Point features use MapLibre clustering through zoom 20. Activating a cluster reads its leaves and exposes every auction in a native-button sidebar list. The retained fixture proves three distinct auctions at one identical coordinate; no marker is silently hidden behind another. A stale cluster id produces a focused assertive explanation instead of an unhandled rejection. |
| Honest precision | Six shape-and-color combinations cover `PARCEL`, `ADDRESS`, `STREET`, `CADASTRAL_MUNICIPALITY`, `SETTLEMENT`, and `MUNICIPALITY`. The adjacent text legend repeats the meaning without relying on color. Coarse tiers explicitly say “center” and state that they are not addresses/parcels; polygon styling never promotes a centroid into a parcel. |
| Safe popup | Popup nodes are built with `textContent`/DOM methods, not HTML interpolation. The browser fixture renders `<img src=x onerror=...>` literally, proves no `img` exists and no handler ran, formats fixed RSD and Europe/Belgrade time (including a null fallback), localizes known status values, explains precision, and admits only the fixed numeric `https://eaukcija.sud.rs/#/aukcije/{id}` form with `rel="noopener noreferrer"`. |
| Keyboard and focus | The MapLibre canvas retains arrow/zoom keyboard behavior and a Serbian text alternative. Controls, filters, links, table overflow region, and native result/cluster buttons use a white/dark two-tone focus indicator; browser-computed contrast is at least 3:1 on white, filter, selection, popup, and primary-button surfaces. Enter-key selection opens the popup, updates URL state, repeats the safe source link before the remaining results, and moves focus directly to it. |
| URL state without raw source text | Map filters use namespaced `mapStatus`, `mapKind`, `mapPrecision`, `mapFrom`, and `mapTo` values validated against rendered select options or ISO dates. Selection stores only a positive numeric `auction` id. Invalid incoming values are removed; titles/descriptions/evidence never enter the URL. Reload restores an available selected auction. |
| Filter and time truth | `MapAuctionFilterOptions` is the single Java source for parser and rendered status/kind/precision values; browser and parser regressions compare/accept every option, while JavaScript validates that every precision has styling. The empty `from` field is accompanied by a visible statement that it defaults to auctions not yet ended at request time. |
| Version/freshness state | `/api/basemap/status` supplies the active immutable bundle. New no-store `/api/map/status` reads the latest completed coarse-location run and exposes its resolver/extract/checksum version, durable finish time, mapped count, and configurable stale state. Missing/stale/warning states remain visible. |
| Responsive list-map layout | At desktop width the legend/results sidebar and map are side by side. At 390 px they stack, retain a 390 px map, and have no document-level horizontal overflow; the legacy table is an explicitly labelled keyboard-scrollable region. |
| Offline proof and evidence | All three #27 browser tests reuse `LocalhostOnlyNetwork` and finish with contacted hosts exactly `[localhost]`, no blocked host, and no public map asset request. Every run writes `issue-27-auction-map-desktop.png`, `issue-27-auction-map-narrow.png`, and `issue-27-auction-map.json` under `build/browser-test-results/evidence/`. Production pages do not expose the browser-only `window.__auctionMap` hook. |

## Post-review remediation

The 2026-08-23 browser review was reproduced against the original delivery.
The follow-up closes all nine findings and the adjacent traps:

1. Responsive minimum zoom plus a pre-fetch area guard prevents deterministic
   oversized requests; a 1600 px real-browser/API test reaches minimum zoom.
2. Filter refreshes remain pending through style/startup unavailability and are
   replayed on `styledata`/`idle`; a failed map initialization can be retried.
3. The former yellow focus outline is replaced by a two-tone indicator and
   browser contrast calculations enforce the 3:1 non-text threshold.
4. Error transitions set both assertive role and live-region politeness.
5. Keyboard result activation moves directly to a repeated allowlisted link.
6. The selected polygon outline is ordered above every precision fill/line.
7. The server-rendered shell smoke dropped its premature network assertion.
8. Parser and template filter values share one catalog, with exact rendered
   option and parser-acceptance tests.
9. The otherwise invisible current-instant `from` default is stated in the UI.

RSD formatting no longer contains a tautological currency branch; a null time
has an explicit fallback; recoverable basemap errors no longer reject map load;
cluster-leaf rejection is visible; test diagnostics are property-gated out of
production; and popup source status uses the Serbian option label.

## Retained freshness boundary

The UI does not call the remote eAukcija source and does not guess freshness
from a browser fetch. A successful map-data version is backed by the existing
append-only coarse-resolution population report, whose insert commits only
after the population transaction succeeds. The default stale threshold is 24
hours and can be changed with `MAP_DATA_STALE_AFTER`.

If there is no completed population report, the page says that no successful
map refresh has been recorded. If the report is old, the page keeps its exact
timestamp and labels it stale. A rejected basemap pointer similarly leaves the
last-good version visible alongside a warning supplied by #25.

## Focused browser run

```text
./gradlew browserTest --tests rs.sud.eaukcija.browser.AuctionMapBrowserTest --no-daemon

AuctionMapBrowserTest > completeMapFlowIsAccessibleSafeClusteredUrlBackedAndResponsive() PASSED
AuctionMapBrowserTest > viewportRequestsCancelAndKeepPartialErrorAndEmptyStatesVisible() PASSED
AuctionMapBrowserTest > zoomingToResponsiveMinimumStaysWithinTheApiAreaContract() PASSED
BUILD SUCCESSFUL
```

The first test uses the production HTTP, PostGIS, GeoJSON, PMTiles, MapLibre,
URL, popup, accessibility, and responsive paths. The second replaces only the
viewport fetch inside the browser so delayed/aborted, style-deferred, invalid,
and failure responses are deterministic; the real page, map engine,
sources/layers, status endpoints, and local assets remain in use. The third
uses the production API at a wide responsive minimum. None can reach a
non-loopback host.

## Complete verification

```text
./gradlew clean check browserTest --no-daemon

basemapTest:  14 passed
test:         185 discovered, 181 passed, 4 explicit full-artifact/population skips
browserTest:    9 discovered,   9 passed, 0 skipped
failures:       0
errors:         0
BUILD SUCCESSFUL in 1m 24s
```

The JSON evidence manifest records the filename, size, and SHA-256 of both
screenshots for each run. Screenshot bytes and the manifest timestamp are
intentionally run-specific, so this record does not assert a stale fixed hash.
Both PNGs were visually inspected after the clean run.

The first exact-head CI run exposed that the pre-map shell smoke still waited
for global network idleness. That is no longer a valid completion boundary now
that the shell launches an optional asynchronous map. The shell smoke now waits
for `DOMContentLoaded` and asserts only its server-rendered boundary;
`AuctionMapBrowserTest` separately owns and proves map/network readiness,
viewport completion, and error states. The pre-remediation browser suite was
rerun after that hardening:

```text
./gradlew browserTest --no-daemon

browserTest: 8 discovered, 8 passed, 0 skipped
failures:    0
errors:      0
BUILD SUCCESSFUL in 23s
```

The post-review exact-head CI run then exposed two Linux-only completion races:
MapLibre's default popup auto-focus could overtake the keyboard focus transfer,
and the production shell test waited only for a canvas even when the absent
local basemap correctly ended initialization in the visible error state. Popup
auto-focus is now disabled in favor of the explicit keyboard destination,
keyboard-originated activation is tracked independently of browser-specific
`click.detail`, and the shell test waits for either a canvas or that terminal
error state before asserting that no test hook shipped. Both exact failing tests
passed in a focused rerun before the complete clean run recorded above.

The terminal exact-head CI result is added to GitHub closure evidence after
publication.
