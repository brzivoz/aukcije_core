# Browser testing and frontend asset decisions

Issue #34 owns the shared browser foundation used by #25, #27, and #28. This
record fixes the decisions those issues must consume instead of reopening them.

## Browser automation: Playwright for Java

The browser suite uses Playwright for Java `1.61.0`, JUnit 5, and its
version-matched Chromium. It was selected because request routing can abort
external traffic before it leaves the browser, while tracing and full-page
screenshots provide useful CI failure evidence. Keeping the harness in Java also
lets it reuse the Spring Boot, Flyway, and Testcontainers PostGIS fixtures
without a second application launcher or package-manager lifecycle.

`browserTest` is a separate Gradle source set and task. It is intentionally not
wired into `test` or `check`: normal unit and integration work stays fast, while
browser execution is opt-in locally and an explicit CI job. `browserTest`
depends on `playwrightInstall`, so one command from a clean checkout downloads
the matching Chromium. CI adds `-PplaywrightWithDeps` to install Linux system
libraries too. Browser binaries live in ignored `.gradle/playwright-browsers`;
that directory is a versioned task output, so an unchanged local invocation is
offline and up to date while a Playwright-version change reinstalls it. GitHub's
Gradle dependency cache does not retain this project-local browser directory.
The installer writes a pin marker and replaces the generated directory when the
pin changes, so superseded browser builds do not accumulate.

Each browser test receives a fresh Playwright browser/context/page. On failure,
the harness writes `failure.png` and `trace.zip` below
`build/browser-test-results/artifacts/<class>/<test>/`; CI publishes that
directory only for a failed browser job. HTML and XML test reports are retained
for every run.

## Shared runtime and network fixtures

`PostgisBrowserFixture` boots the real Spring application on a random loopback
port against the same digest-pinned `postgis/postgis:18-3.6` Testcontainers image
used by integration tests. Flyway migrates the database, Hibernate validates it,
and every test starts from one deterministic auction row.

`LocalhostOnlyNetwork` routes every browser HTTP(S) request and separately
routes every WebSocket handshake. `localhost`, `127.0.0.1`, and IPv6 loopback
are connected; every other host is recorded and closed or aborted before a
public connection can be established. Browser-local `blob:` and `data:`
resources are classified lexically and resumed before invoking the JDK
protocol-handler registry. Chromium-style HTTP URLs with raw path brackets are
parsed without throwing; any URL that still cannot be parsed is recorded as
`<unparseable-url>` and aborted. WebSocket URLs are parsed without relying on
JDK `ws:`/`wss:` handlers, which do not exist. Tests assert the complete
contacted-host set as well as the absence of blocked hosts. Only host names are
retained, not complete URLs or query strings.

The HTTP negative control loads the real application page, injects an image
from `cdn.example.invalid` with reserved path characters, proves it was
blocked, and proves the fixture's final assertion would fail. A second control
completes a real handshake against an in-process loopback WebSocket endpoint,
then proves an external `wss:` attempt is recorded, closed, and fails the same
assertion. This is the single offline-network mechanism #25 and #27 must reuse.

## Frontend build: plain vendored ES modules

The application keeps a no-Node, no-bundler frontend. Browser code is plain
JavaScript/ES modules served by Spring Boot. A bundler would add a second build
toolchain without current tree-shaking, transpilation, or multi-entry needs;
MapLibre GL JS v6 publishes browser-ready modules, and PMTiles publishes a
self-contained browser build. Application glue remains an ES module; the
PMTiles browser build is loaded first as its pinned `globalThis.pmtiles`
namespace so no bare `fflate` package import or import-map toolchain is needed.

MapLibre GL JS, its CSS, and the PMTiles protocol library are vendored below
`src/main/resources/static/vendor/` by #25 because its replayable basemap render
is the first consumer. Styles,
workers, glyphs, sprites, map styles, PMTiles archives, and JavaScript imports
must all resolve through same-origin relative or root-relative URLs. No page may
load scripts, styles, fonts, icons, workers, tiles, telemetry, or source maps
from a CDN or other public host. Ordinary user-initiated navigation links, such
as the existing link to an auction on eaukcija.sud.rs, are not asset loads.

The active pins are MapLibre GL JS `6.1.0` and PMTiles JavaScript `4.4.0`.
`frontend-assets.lock.json` records the upstream package/release, license,
shipped files, sizes, and SHA-256 values; `FrontendAssetLockTest` rejects byte
or inventory drift. The lock also records the sole vendoring transform: trailing
`sourceMappingURL` comments are removed because source maps are not shipped, so
opening browser developer tools cannot generate same-origin `.map` 404s. #27
reuses these pins or upgrades them only through the review procedure below.

Every vendored dependency addition or upgrade must be one reviewed change that:

1. selects an exact upstream release, never a range or `latest` tag;
2. places files under `vendor/<package>/<version>/` and preserves the upstream
   license beside them;
3. records every shipped file's SHA-256, package/version, upstream release URL,
   and license in `vendor/frontend-assets.lock.json`;
4. updates imports and deletes the superseded version in the same commit; and
5. runs `browserTest`, including the localhost-only assertion, before merge.

The lock file was created with #25's first real consumer; no unshipped files or
unverifiable checksums are predeclared. #27 reuses those bytes directly from
the main Thymeleaf page through `auction-map.mjs`; it adds no package manager,
remote runtime asset, or second frontend build.

## Fate of the Thymeleaf UI

`AuctionController` and `index.html` remain the Thymeleaf product shell. #44
pulls shared-filter correctness forward from #28: one `AuctionFilters` model,
`AuctionFilterParser` compatibility/validation adapter, and `AuctionFilterSql`
predicate drive table, map and counts. `auction-filters.html` is the only form;
`auction-map.mjs` keeps applied canonical URL state separate from draft controls.
The old `map*` names exist only as server-side compatibility aliases.
`municipality-select.mjs` progressively enhances native details/checkboxes with
local Cyrillic/Latin option search, a clear-choice action and Escape/outside-click
closing. Checkbox drafts remain in the form, not a second filter model; native
GET/FormData and canonical links use repeated `municipality` parameters. The
packaged RGZ name catalogue requires no live lookup or imported geometry.

`/api/auctions/view` atomically returns bounded GeoJSON and the escaped table
fragment at one cutoff/snapshot. Refreshes replace only results, merge updated
retained options without losing drafts, and use sequence/abort guards. URL
history uses pushState for edits/selection and handles popstate; canonicalization
uses replaceState. Pages, sorts and selected IDs remain independent of criteria.
The default scope is visibly `not-ended`; dates intersect scope, not a hidden
current-time lower bound. See [the user/URL contract](SHARED_FILTERS.md).

Raw category/status options and validators consume safe retained values plus
`MapAuctionFilterOptions` legacy seeds. Precision includes `NONE` for unmapped
auctions, but the map-style assertion deliberately excludes it: NONE must never
invent a pin. #28 extends these same models for normalized taxonomy, KO and
richer counterpart/camera navigation; it must not introduce another filter form.

## Map-first desktop workspace (#45)

`auction-workspace.mjs` owns presentation only: native pressed buttons switch
**Карта + резултати**, **Само карта**, and **Табела**; separate disclosure buttons
collapse the results rail or the one shared form. It does not write URL criteria,
submit drafts, recreate MapLibre, or replace filter controls. Modes start at
Map + results on page load; switching them does not add history entries. Table
selection returns to Map + results. Without JavaScript the native GET form and
server-rendered table remain available.

`auction-workspace.css` uses modest gutters and a 350px results rail (320px on
smaller desktops), with the map flexing into the remaining viewport. The same
selected-feature summary originally sat above the map, including its textual
precision/explanation; #54 below moves it into the rail and provides a compact
reopen control. Dismissal still hides the summary together with details. Full legend and technical versions are a native details
panel with Escape-to-summary focus restoration. Smaller/zoomed windows reflow
vertically, map before results; the table has its own focusable horizontal scroll
region. This is not the separate mobile bottom-sheet or advanced-filter/chip UX.

The refresh strip keeps one-action start/retry, live announcements and persisted
last complete success outside its progress/operator disclosure. Running progress
includes the current stage/count on its visible summary; failures and stale-map
warnings (with last-good map time) never depend on opening diagnostics.

One `ResizeObserver` on the map container handles rail, filter, selection and
viewport changes (`trackResize: false` avoids competing with MapLibre 6's
throttled observer). Initial resize also catches layout changes during style
load. Layout-only follow-up requests retain the status text instead of creating
a loading/ready height-feedback loop; resizing an error notice does not retry a
rejected request. Table fragment refreshes retain horizontal scroll and keyboard
focus. Size containment prevents long result lists from enlarging the map.
The table-mode map is invisible but remains laid out at its existing dimensions,
so shared-view requests retain the camera/bbox rather than using a zero-sized
container. Every resize recalculates the existing requestable minimum zoom;
every request still passes the 1,000,000 km² ceiling guard. A safety-required
minimum-zoom increase is the only camera change caused by a mode/size change.
Attribution remains within the map frame.

Browser coverage measures default map/rail/container/attribution bounds at
1366×768, 1920×1080 and 2560×1080, retains screenshots and JSON measurements under
`build/browser-test-results/evidence/issue-45-*`, exercises keyboard modes,
rail/filter toggles, disclosures, retained drafts/sort/page/selection/camera,
reduced motion, 200%-zoom-equivalent reflow, no-JavaScript GET fallback, and
actual resized minimum-zoom API responses under the localhost-only guard.

## Compact desktop chrome (#54)

The #45 geometry and shared-state guarantees remain; #54 replaces its remaining
vertical stack. `index.html` puts compact refresh/last-good status in the header,
with catalogue statistics inside the operator disclosure. Warnings and running
progress remain visible. The redundant visual map heading is screen-reader-only;
a concise coarse-location cue and legend disclosure remain visible.

`auction-workspace.mjs` reparents the **same** form once into `#workspace-views`.
Its side column starts closed, while map counts/warnings keep the full workspace
width, so opening filters cannot rewrap them and shift the map vertically.
`eaukcija.workspace.v1.*` local-storage keys remember only presentation booleans;
storage failure is harmless. Native disclosures own Escape before the outer panel,
which returns focus to its toggle. Validation reveals the offending control.
Small/zoomed windows reflow the form and retain a usable map/table scroll region.

`auction-filter-presentation.mjs` is a read-only projection of the shared view's
last usable query, not a second filter model. It creates DOM-safe applied chips,
advanced-field counts and dirty-state feedback from native controls. Removal/reset
callbacks go through `auction-map.mjs` and its existing canonical navigation path.
Unrelated drafts survive chip removal, refresh and panel/mode changes; chips only
change after accepted view responses. Unchanged polls do not recreate focused chips.
RSD normalization is lexical, preserving the server's 17-digit decimal contract.

The healthy count paragraph is now a compact disclosure with explicit auction /
property totals and returned/unmapped/outside breakdown. Warnings remain outside;
a separate view retry never triggers acquisition. Existing-result loading retains
height and uses a quiet indicator. Result refresh retains the rail's scroll offset.

Selection summary/details live in the rail. Map-only uses a small **Избор** overlay
and transports the same details article into MapLibre's popup on explicit opening.
Table-mode reopening reveals Map + results. Both transports reuse #46's state,
source-link allowlist, dismissal and focus return; periodic updates retain connected
controls. Late cluster responses are discarded after another selection/dismissal.
This does not add #51's richer auction descriptions/projections or #48's broader
keyed result reconciliation, nor #53's mobile bottom-sheet workflow.

`CompactWorkspaceBrowserTest` measures map bounds and retains `issue-54-*` screenshots
and JSON under `build/browser-test-results/evidence/`. It covers defaults, preferences,
chips/drafts/history, invalid hidden fields, rail/Map-only details, stationary map
position through panel/selection/refresh, exceptional selections, retry, storage
denial and narrow/zoom-equivalent layouts. Existing #45/#46/#44 suites retain
resize/minimum-zoom, precision, hostile-text/link safety, source refresh, keyboard,
reduced-motion, no-JavaScript and localhost-only coverage. See the
[verification record](2026-09-09-issue-54-verification.md).

## Transient map details (#46)

`auction-map.mjs` keeps `selectedAuctionId`/the existing GeoJSON
`selectedFeatureId` separate from `detailsOpen`, `detailsDismissed` and the
return-focus trigger. A restored URL may show a selection-only summary; explicit
dismissal hides both the popup and `#map-selection`, including summary controls,
until another explicit selection or URL restoration. Background refresh must
respect that dismissed state for both surfaces.
The GeoJSON sources promote that existing string ID through a local
`mapFeatureId` property, preventing tile conversion from truncating a
`34001:hash` ID to auction number `34001`; this adds no API/URL field.
Only explicit feature/table/summary activation opens details. URL restoration
(reload, copied links, popstate) restores the auction with details closed, never
serializes temporary DOM/visibility state, and still explains unavailable
selections. The [user and keyboard contract](SHARED_FILTERS.md#selection-and-map-details-46)
defines this separately from filter/history restoration.

A capture-phase document click handler dismisses outside the popup/retained
selection content **before** MapLibre or button opening handlers run; the same
opening event cannot immediately dismiss the new popup. One map hit test chooses
the topmost feature across overlapping layers. Outside-click dismissal defers
summary layout collapse until after that event's hit test: hiding the summary
in capture would shift the map beneath the original pointer coordinates.
Escape respects already-handled
native disclosure keys. The localized built-in × records dismissal; internal
popup teardown for missing geometry does not masquerade as user dismissal.
Pointer click-away never returns focus. Escape/× use connected visible triggers
or the matching result/canvas fallback; keyboard opening targets the safe popup
source link or labelled article, without a focus trap.

Refresh updates popup and summary content in place with DOM/text methods,
revalidating links with the unchanged fixed eAukcija allowlist. It neither
recreates focused popup controls nor focuses them on background updates.
Dismissed details remain closed through real source `setData`, layer redraws,
viewport requests, periodic updates and source-refresh completion. This is a
scoped popup/summary lifecycle fix, not full keyed result reconciliation or a
new durable property-identity contract.

`AuctionMapDetailsBrowserTest` covers pointer map/list/table and keyboard
activation, switching properties within an auction, inside/source-link clicks,
outside/blank-map clicks, Escape/×, hidden-summary focus fallback, 44px localized
close targets, separately stacked source/Google Maps links on desktop/phone,
focus through actual data changes, unavailable property restoration, reload/copied URLs and
back/forward. It uses real PostGIS and periodic/viewport responses; the complete
source-to-map browser flow additionally dismisses details before a second normal
source refresh. Municipality, XSS/link allowlisting, accessibility and the shared
localhost-only network guard remain covered.

## Shared-filter evidence

`SharedAuctionFiltersBrowserTest` uses real PostGIS, the local basemap and the
localhost-only guard. A legacy 179415-equivalent KO point has a past end time,
stale `InPrediction`, and no source snapshots. Tests cover historical aliases,
category/scope/precision/search/RSD-price/first-sale combinations, sorting,
paging, reset, selection, reload/copied URLs and back/forward. Automatic and
source-refresh completion preserve historical dates, page, selection and draft
controls; invalid filters retain the last usable view. API/PostGIS tests cover
missing/equal end times, both Belgrade DST transitions, multi-property counts,
and an off-screen parcel winner suppressing its on-screen lower-tier duplicate.

## Auction map evidence

`AuctionMapBrowserTest` stages the same real compact PMTiles v3 fixture as the
basemap smoke, boots the complete application, and seeds PostGIS with one
polygon plus point locations at every declared precision. Three records share
one exact centroid and remain a cluster through the test zoom; activating it
opens a keyboard-accessible list instead of hiding stacked auctions.

The browser suite also proves DOM-safe rendering of hostile-looking title text,
the fixed eAukcija link allowlist and `noopener noreferrer`, selection restore
from a numeric URL id (details initially closed), keyboard focus transfer to the
popup's safe source link, computed focus-indicator contrast of at least 3:1 on every tested surface,
selected-polygon layer order, localized status/date fallbacks, and a 390 px
layout with no document-level horizontal overflow. A controlled browser-fetch
boundary delays one viewport response, pans and zooms, observes `AbortSignal`
cancellation, forces a filter submit while the style is unavailable, and then
exercises field-aware `400`, retryable `503`, partial-limit, retained-data
error, and empty states. Viewport refreshes are coalesced with a 250 ms
fallback, but a settled MapLibre `idle` event may replay the pending refresh
earlier; the observable contract is therefore at most 250 ms rather than a
fixed 250 ms delay. A wide-viewport test zooms to the calculated responsive
minimum and proves the real API request remains below its area ceiling. All map
tests finish with the shared localhost-only assertion.

The auction-map consumer disables pitch and rotation for mouse, touch, and
keyboard while leaving pinch zoom enabled; the shared local-basemap factory
remains neutral. This keeps this map's camera two-dimensional and makes the
responsive minimum-zoom area estimate exact. Cluster-leaf failures are visible
and assertive rather than unhandled. Runtime MapLibre errors are classified by
source: auction GeoJSON failures never blame the basemap, and transient
source-attributed warnings are removed only after that same source reports a
recovered loaded state. A keyless resource warning stays visible until reload,
because an unrelated `styledata` event is not evidence that it recovered.
Recoverable tile/resource errors do not reject initial loading, while a style
or basemap-source definition failure rejects immediately instead of waiting for
the 30-second safety timeout. Nonfatal resource errors emitted before `load`
are forwarded to the same classifier, so the warning has no startup blind spot.
Submitting filters during startup/style work records a pending refresh which
`styledata`/`idle` replays. A failed
initialization can likewise be retried by applying the filters.

The precision catalog assertion has an application-contract error message; it
never directs an operator to repair PMTiles. The RSD `Intl.NumberFormat` is
constructed once per module load rather than once per rendered auction.

Every green run retains desktop and narrow screenshots plus a JSON evidence
manifest under `build/browser-test-results/evidence/issue-27-*`; CI publishes
that directory in `browser-test-report`. Browser-only diagnostics are enabled
by the test runtime property and are absent from the production `window`.
The legacy shell smoke waits for `DOMContentLoaded`, because its boundary is
the server-rendered list, and no longer makes a racy map-network claim. Map and
localhost completion are deliberately owned by `AuctionMapBrowserTest`, which
waits for explicit ready and viewport states rather than global network
idleness.
