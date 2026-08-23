# Issue #24 reproducible Serbia PMTiles verification

Date: 2026-08-23

Scope: dated Geofabrik source, pinned Planetiler/PMTiles build toolchain,
Serbia-only PMTiles v3, local style assets, provenance manifest, failure gates,
and an offline browser render

## Acceptance evidence

| Contract | Evidence |
|---|---|
| One clean command | `./basemap/build.sh` downloads/verifies inputs, verifies the Planetiler schema, builds, structurally validates, smoke-reads, writes the manifest, validates every manifest hash, and atomically moves the completed bundle into ignored `data/basemap/builds/` |
| Named/dated source | Geofabrik `serbia-260801.osm.pbf`, dated `2026-08-01`, 238,239,647 bytes; downloaded `.md5` and PBF both match MD5 `819df3db2e51321b87a1f032e72a4003`; PBF SHA-256 `626198923e7b9b189ac3e18530f8018e72ca9f36c9dd7abe0b1551e9b8ae4c4b` |
| Separate pinned toolchain | Application Java remains 17. Build uses Planetiler `0.10.2` / commit `0e5588c4a6e8c29a270a33afe8df62027d889604` in multi-arch image digest `sha256:cf32202dbc001a9ab4bc11534b642b13de3798179817da8558e567a3d13dd403`; validation uses go-pmtiles `1.31.2` / commit `a3e4951ea6a0477b784c27c1dcbfd9c130878c5a` with per-platform archive hashes |
| No mutable ancillary GIS sources | The custom Planetiler profile consumes only the reviewed Serbia PBF. It does not invoke the profiles that download mutable global coastline/Natural Earth files |
| PMTiles v3 and deterministic metadata | Official `pmtiles verify` passes; raw magic/version is v3; tile type/compression is MVT/gzip; the complete canonical metadata object is pinned to SHA-256 `7a8d2b5956a493997801bef15d2f95470611c31ddde0ee9878682a0019c977e2`. This pin intentionally includes Planetiler's namespaced image-build timestamp instead of pretending to reject that key |
| Serbia bounds and zooms | Exact header bounds `[18.8, 42.2, 23.1, 46.3]`, center `[20.95, 44.25, 6]`, zooms 3–14 |
| Expected layers | Exact set: `boundaries`, `buildings`, `landuse`, `places`, `pois`, `roads`, `water` |
| Low/mid/high smoke reads | Belgrade z5/17/11 = 45,049 bytes; z9/285/184 = 291,496 bytes; z14/9123/5907 = 94,326 bytes; every returned tile has a retained SHA-256 in the manifest |
| Local style assets | Style, 1x/2x sprite pairs, and Noto Sans glyph ranges 0–255, 256–511, 512–767, 768–1023, 1024–1279, and 8192–8447 are checksum-pinned. The added ranges cover Romanian comma-below letters and combining marks seen in regional names. The style renders the bundled `townspot` icon, and validation proves every referenced icon resolves in matching 1x/2x sprite indexes |
| Visible attribution | PMTiles metadata and style source both include linked `© OpenStreetMap contributors`; browser rendered it visibly with the exact `https://www.openstreetmap.org/copyright` link |
| Delivery-time offline render | MapLibre GL JS `6.1.0` + PMTiles JS `4.4.0` rendered the remediated final artifact at 1280×800 through a loopback range server. `loaded`, `styleLoaded`, and `tilesLoaded` were all true; 11 `place-icons` features rendered from `townspot`; render/console errors were empty; contacted hosts were exactly `127.0.0.1`; blocked hosts were empty. Screenshot SHA-256 is `806ff3ac47774c8c6667a07daf9e17e9de63230cb3f64ca3a50e60f527092aa0`. This is retained delivery evidence, not a repository-replayable test; #25 owns that multi-zoom browser test and #27 owns production UI render evidence, both reusing #34's localhost-only guard |
| Corrupt/mismatched failure | Regression tests mutate the source bytes and downloaded checksum independently and assert non-zero failure. The first real build also proved the structural gate was non-vacuous: `pmtiles verify` rejected header `MinZoom=0` because the first addressed tile was z3; the final contract is z3–14 |
| Retained manifest | Final local bundle `serbia-2026-08-01-b068f45b84e2`; command/config SHA-256 `b068f45b84e23c16edc2e3feb062869b1f6779034c8cbc0affbfaa172c10618d`; manifest contains a host-neutral command derived from the exact execution argv, source, tool, artifact, validation, style, attribution, and a complete 15-file inventory. It records `<uid>:<gid>`, `<source-cache>`, `<work>`, and `<basemap-config>` instead of the builder's identifiers and paths. `BASEMAP_PRINT_COMMAND=1` matched it exactly, and two exact-config generations produced manifest SHA-256 `5d83ae93636c8825af96aefcac60304ad86c46b4a63e801a8b0d5832ed930c00` |
| Self-healing cache/lock | A deliberately corrupted promoted sprite cache file failed hash/size verification, was removed automatically, and downloaded cleanly on rerun. The build also removed the observed stale `.extract-29943` directory. Two simultaneous processes then observed a synthetic lock owned by dead PID `999999`: the atomic recovery claimant rebuilt successfully and the other exited `2` without deleting the claimant's lock |

## Final artifact

The final ignored artifact is:

```text
data/basemap/builds/serbia-2026-08-01-b068f45b84e2/
  serbia.pmtiles              231,649,275 bytes
  build-manifest.json
  validation-report.json
  style.json
  glyphs/Noto Sans Regular/{0-255,256-511,512-767,768-1023,1024-1279,8192-8447}.pbf
  sprites/light{,@2x}.{json,png}
  licenses/{Noto-OFL-1.1.txt,Tangram-Icons-MIT.md}
  THIRD_PARTY_NOTICES.md
```

`serbia.pmtiles` SHA-256 is
`96fd233bd7f954c6e8085c41950d7663f402fb6bf99822fd31b7e0762a2e24b1`.
Multiple independent generations after the zoom correction produced that same
231,649,275-byte archive and hash. A subsequent unchanged
`./basemap/build.sh` revalidated the complete existing manifest and returned
the same immutable directory without rewriting it.

The browser screenshot is retained locally at ignored path
`data/basemap/evidence/issue-24-render-smoke-remediated.png` (882,114 bytes).
The render made 11 PMTiles range requests, fetched the local sprite pair and
glyph assets, and rendered 11 `townspot` features. Because MapLibre GL JS 6 requires
an absolute sprite URL after a JSON style is parsed, the validation consumer
expanded the artifact's root-relative `/basemap` sprite/glyph paths and PMTiles
URL against the current loopback origin. Issue #25 must perform the same
same-origin expansion and retain the replayable localhost-only render when it
mounts this bundle; #27 consumes that contract. No public origin is involved.

## Automated regressions

The fast, network-free pipeline tests are part of `check` and therefore the CI
command, while ordinary Java `test` no longer requires host Python:

```text
./gradlew basemapTest
12 tests, OK

./gradlew check
Java/PostGIS plus basemap tests
```

They cover exact source acceptance, corrupt bytes, mismatched remote checksum,
PMTiles v3, exact bounds/zoom/layers, three smoke reads, immutable URLs/hashes,
local style assets, all six glyph ranges, resolution of the active sprite icon,
canonical namespaced-metadata pinning, complete manifest inventory, behavioral
host-neutral command/manifest equality and drift rejection, and rejection of an
external runtime asset. The Gradle task tees the real unittest output into
`build/basemap-test/result.txt`; its unchanged rerun was `UP-TO-DATE`, and CI
uploads that transcript rather than a constant marker.

## Operations and cleanup

The generated artifact, source/tool cache, scratch data, and render evidence
remain ignored under `data/basemap/`; no large file is committed. The build
documents 4 GiB RAM and 3 GiB peak disk expectations, self-healing invalid
cache behavior, partial/extraction cleanup, atomic claimed/re-read stale-lock
recovery, regeneration, targeted work/cache cleanup, and old-artifact
retirement. The superseded bootstrap bundle and the deliberately rejected
z0-header work tree were deleted after the final exact-config artifact passed;
both were generated data recoverable by rerunning the command.
