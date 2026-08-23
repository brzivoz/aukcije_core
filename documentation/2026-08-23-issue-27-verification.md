# Issue #27 precision-aware auction map verification

Date: 2026-08-23

Scope: usable MapLibre auction map in the existing Thymeleaf shell, local
PMTiles only, bounded viewport data, honest precision presentation, clustered
shared-centroid handling, safe selection/URL state, visible freshness, and
desktop/narrow browser evidence

## Acceptance evidence

| Contract | Evidence |
|---|---|
| Local basemap and bounded viewport loading | `auction-map.mjs` creates the #25 same-origin PMTiles map and requests only `/api/map/auctions` with its current WGS84 bounds, a `1000` limit, and allowlisted filters. `moveend` is debounced by 250 ms; scheduling another load aborts the active `AbortController`. |
| Loading, empty, error, and partial-limit states | The live region distinguishes all four states. Existing features remain rendered while a replacement request loads or fails. `truncated=true` has a persistent warning telling the user to zoom or filter; an empty successful response clears both sources and says why. |
| Dense/shared points | Point features use MapLibre clustering through zoom 20. Activating a cluster reads its leaves and exposes every auction in a native-button sidebar list. The retained fixture proves three distinct auctions at one identical coordinate; no marker is silently hidden behind another. |
| Honest precision | Six shape-and-color combinations cover `PARCEL`, `ADDRESS`, `STREET`, `CADASTRAL_MUNICIPALITY`, `SETTLEMENT`, and `MUNICIPALITY`. The adjacent text legend repeats the meaning without relying on color. Coarse tiers explicitly say “center” and state that they are not addresses/parcels; polygon styling never promotes a centroid into a parcel. |
| Safe popup | Popup nodes are built with `textContent`/DOM methods, not HTML interpolation. The browser fixture renders `<img src=x onerror=...>` literally, proves no `img` exists and no handler ran, formats RSD and Europe/Belgrade time, explains precision, and admits only the fixed numeric `https://eaukcija.sud.rs/#/aukcije/{id}` form with `rel="noopener noreferrer"`. |
| Keyboard and focus | The MapLibre canvas retains arrow/zoom keyboard behavior and a Serbian text alternative; controls, filters, links, table overflow region, and native result/cluster buttons have a high-contrast visible focus ring. A real Enter-key selection opens the popup and changes URL selection state. |
| URL state without raw source text | Map filters use namespaced `mapStatus`, `mapKind`, `mapPrecision`, `mapFrom`, and `mapTo` values validated against rendered select options or ISO dates. Selection stores only a positive numeric `auction` id. Invalid incoming values are removed; titles/descriptions/evidence never enter the URL. Reload restores an available selected auction. |
| Version/freshness state | `/api/basemap/status` supplies the active immutable bundle. New no-store `/api/map/status` reads the latest completed coarse-location run and exposes its resolver/extract/checksum version, durable finish time, mapped count, and configurable stale state. Missing/stale/warning states remain visible. |
| Responsive list-map layout | At desktop width the legend/results sidebar and map are side by side. At 390 px they stack, retain a 390 px map, and have no document-level horizontal overflow; the legacy table is an explicitly labelled keyboard-scrollable region. |
| Offline proof and evidence | Both #27 browser tests reuse `LocalhostOnlyNetwork` and finish with contacted hosts exactly `[localhost]`, no blocked host, and no public map asset request. Every run writes `issue-27-auction-map-desktop.png`, `issue-27-auction-map-narrow.png`, and `issue-27-auction-map.json` under `build/browser-test-results/evidence/`. |

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
BUILD SUCCESSFUL
```

The first test uses the production HTTP, PostGIS, GeoJSON, PMTiles, MapLibre,
URL, popup, and responsive paths. The second replaces only the viewport fetch
inside the browser so delayed/aborted and failure responses are deterministic;
the real page, map engine, sources/layers, status endpoints, and local assets
remain in use. Neither test can reach a non-loopback host.

## Complete verification

```text
./gradlew clean check browserTest --no-daemon

basemapTest:  14 passed
test:         184 discovered, 180 passed, 4 explicit full-artifact/population skips
browserTest:    8 discovered,   8 passed, 0 skipped
failures:       0
errors:         0
BUILD SUCCESSFUL in 1m 22s
```

Fresh successful artifacts from that run:

```text
issue-27-auction-map-desktop.png  829,478 bytes
  sha256 fb91cdc76864779b94ac7fb97eb7ae79d2e913d37b3b93e8d946b3ab8df2bc9e
issue-27-auction-map-narrow.png    310,013 bytes
  sha256 d39530ee8a20948ef21eaea7be2cae4035a5303ef469907130cb1f6565803a43
issue-27-auction-map.json
  sha256 e03486d7f49d8d16722d100e9eca1c6524ff9cd7638d86dc399a6c3f1fe4fda8
```

Both PNGs were visually inspected after the clean run. The terminal exact-head
CI result is added to GitHub closure evidence after publication.
