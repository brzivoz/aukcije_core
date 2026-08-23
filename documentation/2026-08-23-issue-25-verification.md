# Issue #25 local basemap serving verification

Date: 2026-08-23

Scope: validated immutable-bundle activation, same-origin PMTiles/style/sprite/
glyph serving, HTTP Range and conditional cache semantics, pinned MapLibre
protocol integration, rollback safety, and localhost-only multi-zoom proof

## Acceptance evidence

| Contract | Evidence |
|---|---|
| Same application origin and correct media types | `BasemapAssetController` serves `/basemap/serbia.pmtiles` as `application/vnd.pmtiles`, style/sprite JSON as `application/json`, sprites as `image/png`, and glyphs as `application/x-protobuf`. Every response identifies `X-Basemap-Version`; the browser contacted exactly `localhost` for HTML, modules, worker, style, archive, sprites, and glyphs. |
| Complete PMTiles byte serving | Seven real-HTTP integration tests cover complete GET/HEAD, prefix, bounded, open-ended, overlong-end, and suffix ranges; malformed, reversed, zero-suffix, and unsatisfiable ranges; RFC-correct `200` fallback for satisfiable multi-ranges and unknown units; matching and weak `If-None-Match`; matching/stale `If-Range`; and 32 concurrent independent range reads. Responses prove `Accept-Ranges: bytes`, exact `206`/`Content-Range`/`Content-Length`, shaped `416 bytes */size`, and `304` without a body. |
| Stable strong ETag and cache policy | Every manifest-recorded asset uses `"sha256-<file hash>"`, never a weak or process-local value. The active alias uses `Cache-Control: public, max-age=0, must-revalidate`, allowing conditional reuse without hiding a pointer change behind freshness. |
| Manifest/hash startup and activation gate | `BasemapArtifactValidator` requires manifest schema/build-id agreement, a complete exact inventory, safe relative paths, regular non-symlink files, every recorded size/hash, PMTiles v3 magic, the reviewed same-origin style contract, and linked visible OSM attribution. `BasemapArtifactRegistry` validates `ACTIVE` synchronously at startup. |
| Atomic no-downtime activation | `activateBasemap` serializes activation, validates before mutation, fsyncs a temporary pointer, requires an atomic replacement of the regular `ACTIVE` file, then fsyncs its containing directory for rename durability. The running watcher hashes candidates in the background and swaps one immutable in-memory snapshot only after success. A corrupt candidate left both the pointer and last-good snapshot unchanged; a focused concurrent test performed 20 swaps while 500 reads observed only the two complete build ids. |
| Active version and health | `GET /api/basemap/status` returns `200 AVAILABLE` with active/pointer version, archive hash/size, timestamps, and no filesystem paths. `checkedAt` advances on unchanged polls. With no valid snapshot, request threads fast-fail `503 UNAVAILABLE` instead of joining a watcher rehash; when a changed pointer is rejected the old active snapshot remains healthy with a sanitized warning. Rejected fingerprints are intentionally retried only after atomic pointer republication. |
| Same-origin MapLibre PMTiles configuration | Pinned MapLibre GL JS `6.1.0` and PMTiles JS `4.4.0` are served below `static/vendor/`; every shipped byte and license is locked by SHA-256 and exact inventory. Unshipped source-map references are stripped and recorded as a vendoring transform. `basemap-map.mjs` checks the retained root-relative style contract, registers `pmtiles`, defaults to `/basemap/` even from a nested page path, enforces the current origin, and expands only the absolute URLs MapLibre 6 requires. |
| Accompanying notices | The validated third-party notice, Noto OFL, and Tangram MIT text are served with strong ETags from `/basemap/THIRD_PARTY_NOTICES.md` and `/basemap/licenses/**`; the license filename route is a two-file allowlist. |
| Offline browser zoom/pan render | The production endpoint served a real compact PMTiles v3 fixture through eight `206` responses, all with the same strong ETag. MapLibre rendered 8,256 features at z5, 15,775 at z9, 12,447 at z14, then 12,540 after a z14 pan; `areTilesLoaded()` was true at every state. A second map also loaded after the page path changed to `/auctions/map`, proving the default asset root stays `/basemap/`. Contacted hosts were exactly `[localhost]`; blocked hosts and map errors were empty. |
| OSM attribution | The non-compact MapLibre attribution control remained visible, contained `OpenStreetMap contributors`, and exposed the exact `https://www.openstreetmap.org/copyright` link. |
| Operational activation and rollback | `documentation/BASEMAP_SERVING_OPERATIONS.md` documents configuration, activation, status/range probes, zero-downtime pointer pickup, same-command rollback, failure recovery, and safe old-build retirement. |

## Production artifact activation

The actual ignored #24 bundle passed the #25 activation validator and was
atomically selected locally:

```text
./gradlew activateBasemap \
  --args='--version serbia-2026-08-01-e82bacf6e754'

Activated basemap serbia-2026-08-01-e82bacf6e754
size:   231,649,275 bytes
sha256: 96fd233bd7f954c6e8085c41950d7663f402fb6bf99822fd31b7e0762a2e24b1
```

`data/basemap/ACTIVE` is ignored runtime state and contains that exact immutable
build id. The application does not copy or rewrite the 232 MB archive.

## Browser fixture and retained evidence

The committed browser fixture is not a synthetic PMTiles header. It was
extracted from the final #24 archive with pinned go-pmtiles `1.31.2`, retaining
the tiles intersecting bbox `20.455,44.785,20.465,44.795` at zooms 5, 9, and 14,
then structurally verified and merged. It is 684,775 bytes with SHA-256
`c7efb40b569a02d10a2482c3c5c9a6ce48ead39635ad5931ecee272eb0791585`.
It carries the real style, sprite pairs, all six Noto glyph ranges, third-party
notice, and font/icon licenses, but omits the rest of Serbia so CI remains small
and deterministic.

Every green run retains:

```text
build/browser-test-results/evidence/issue-25-local-basemap.png
build/browser-test-results/evidence/issue-25-local-basemap.json
```

The current screenshot is 811,507 bytes, SHA-256
`564f02976538a0cd7136bb4cf80a9b3b689f6c36d89051e12ed0fa9b1264ac81`.
The JSON records all four render states, all ranged response headers, exact host
sets, the attribution URL, and the screenshot size/hash. CI publishes this
directory on success in `browser-test-report`; failures separately retain the
Playwright screenshot and trace.

## Fresh local verification

```text
./gradlew clean check browserTest --no-daemon

basemapTest:  14 passed
test:         180 discovered, 176 passed, 4 explicit full-artifact/population skips
browserTest:    6 discovered,   6 passed, 0 skipped
failures:       0
errors:         0
```

The Java count includes the 7 HTTP range tests, 6 activation tests, and 1
vendored-asset lock test. The browser count includes the new real-PMTiles map
test plus #34's existing positive/negative HTTP, WebSocket, policy, and cleanup
controls. A second unchanged `./gradlew check browserTest --no-daemon` was fully
up to date and `BUILD SUCCESSFUL`; `git diff --check`, CI YAML parsing, and the
frontend lock JSON parse were clean.
