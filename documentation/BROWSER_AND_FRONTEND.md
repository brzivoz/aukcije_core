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

The current `AuctionController` and `index.html` remain the product shell. #27
extends that page in place with the precision-aware map and a list/map layout;
it does not replace Thymeleaf with a client-side application. #28 then evolves
the same shell into the broader daily list-map workflow. The live #27 contract
owns map-specific URL state now: only allowlisted status/kind/precision values,
ISO dates, and a numeric auction id are written. #28 retains ownership of
unifying the legacy table's larger filter/search contract, navigation, and
daily workflow across list and map. This keeps one controller/view contract and
prevents the two issues from each building half of a replacement UI.

The parser and page consume one `MapAuctionFilterOptions` catalog for status,
kind, and precision values. The browser regression compares every rendered
option to that server catalog and checks that the JavaScript precision-style
keys match it, turning drift into a test/init failure instead of a silent API
`400`.

## Auction map evidence

`AuctionMapBrowserTest` stages the same real compact PMTiles v3 fixture as the
basemap smoke, boots the complete application, and seeds PostGIS with one
polygon plus point locations at every declared precision. Three records share
one exact centroid and remain a cluster through the test zoom; activating it
opens a keyboard-accessible list instead of hiding stacked auctions.

The browser suite also proves DOM-safe rendering of hostile-looking title text,
the fixed eAukcija link allowlist and `noopener noreferrer`, selection restore
from a numeric URL id, keyboard focus transfer to the selected auction's safe
link, computed focus-indicator contrast of at least 3:1 on every tested surface,
selected-polygon layer order, localized status/date fallbacks, and a 390 px
layout with no document-level horizontal overflow. A controlled browser-fetch
boundary delays one viewport response, pans and zooms, observes `AbortSignal`
cancellation, forces a filter submit while the style is unavailable, and then
exercises field-aware `400`, retryable `503`, partial-limit, retained-data
error, and empty states. A third test uses a 1600 px viewport, zooms to the
calculated responsive minimum, and proves the real API request remains below
its area ceiling. All three finish with the shared localhost-only assertion.

Cluster-leaf failures are visible and assertive rather than unhandled. Basemap
resource errors are recorded as warnings but do not reject the initial map-load
promise; only failure to reach `load` before the timeout tears initialization
down. Submitting filters during startup/style work records a pending refresh
which `styledata`/`idle` replays. A failed initialization can likewise be
retried by applying the filters.

Every green run retains desktop and narrow screenshots plus a JSON evidence
manifest under `build/browser-test-results/evidence/issue-27-*`; CI publishes
that directory in `browser-test-report`. Browser-only diagnostics are enabled
by the test runtime property and are absent from the production `window`.
The legacy shell smoke waits for `DOMContentLoaded`, because its boundary is
the server-rendered list, and no longer makes a racy map-network claim. Map and
localhost completion are deliberately owned by `AuctionMapBrowserTest`, which
waits for explicit ready and viewport states rather than global network
idleness.
