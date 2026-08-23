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
| PMTiles v3 and deterministic metadata | Official `pmtiles verify` passes; raw magic/version is v3; tile type/compression is MVT/gzip; canonical metadata SHA-256 is pinned to `7a8d2b5956a493997801bef15d2f95470611c31ddde0ee9878682a0019c977e2` and volatile metadata keys are rejected |
| Serbia bounds and zooms | Exact header bounds `[18.8, 42.2, 23.1, 46.3]`, center `[20.95, 44.25, 6]`, zooms 3–14 |
| Expected layers | Exact set: `boundaries`, `buildings`, `landuse`, `places`, `pois`, `roads`, `water` |
| Low/mid/high smoke reads | Belgrade z5/17/11 = 45,049 bytes; z9/285/184 = 291,496 bytes; z14/9123/5907 = 94,326 bytes; every returned tile has a retained SHA-256 in the manifest |
| Local style assets | Style, 1x/2x sprite pairs, and Noto Sans glyph ranges 0–255, 256–511, 1024–1279, and 8192–8447 are checksum-pinned. Validator rejects CDN/public-tile URLs and checks all style source layers against archive metadata |
| Visible attribution | PMTiles metadata and style source both include linked `© OpenStreetMap contributors`; browser rendered it visibly with the exact `https://www.openstreetmap.org/copyright` link |
| Real offline render | MapLibre GL JS `6.1.0` + PMTiles JS `4.4.0` rendered the final archive at 1280×800 through a loopback range server. `loaded`, `styleLoaded`, and `tilesLoaded` were all true; render/console errors were empty; contacted hosts were exactly `127.0.0.1`; blocked hosts were empty. Screenshot SHA-256: `40a5e4a8b64d3e4c0df0b17061494f7412fba17f8f0075be41ed823244d83059` |
| Corrupt/mismatched failure | Regression tests mutate the source bytes and downloaded checksum independently and assert non-zero failure. The first real build also proved the structural gate was non-vacuous: `pmtiles verify` rejected header `MinZoom=0` because the first addressed tile was z3; the final contract is z3–14 |
| Retained manifest | Final local bundle `serbia-2026-08-01-468527622180`; command/config SHA-256 `468527622180dcc5f652bebcf27e0c6aed54a4f65e771354c5d6d8525c91fc8a`; manifest contains source, tool, command, artifact, validation, style, attribution, and per-file hashes. Independent generations proved deterministic manifest construction; the final manifest SHA-256 is `906e6d0ec3523d6c497a26c52ff3d211ff2f7948fab3725f558de00f94e690f8` |

## Final artifact

The final ignored artifact is:

```text
data/basemap/builds/serbia-2026-08-01-468527622180/
  serbia.pmtiles              231,649,275 bytes
  build-manifest.json
  validation-report.json
  style.json
  glyphs/Noto Sans Regular/{0-255,256-511,1024-1279,8192-8447}.pbf
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
`data/basemap/evidence/issue-24-render-smoke.png` (1,316,868 bytes). The render
requested the PMTiles archive through HTTP 206 ranges and fetched both local
sprite files plus all four local glyph blocks. Because MapLibre GL JS 6 requires
an absolute sprite URL after a JSON style is parsed, the validation consumer
expanded the artifact's root-relative `/basemap` sprite/glyph paths and PMTiles
URL against the current loopback origin. Issue #25 must perform the same
same-origin expansion when it mounts this bundle; no public origin is involved.

## Automated regressions

The fast, network-free pipeline tests are part of `test` and therefore the
existing CI command:

```text
python3 -m unittest discover -s basemap/tests -p 'test_*.py' -v
8 tests, OK

./gradlew basemapTest
8 tests, OK
```

They cover exact source acceptance, corrupt bytes, mismatched remote checksum,
PMTiles v3, exact bounds/zoom/layers, three smoke reads, immutable URLs/hashes,
local style assets, sprite/glyph presence, metadata pinning, and rejection of
an external runtime asset.

## Operations and cleanup

The generated artifact, source/tool cache, scratch data, and render evidence
remain ignored under `data/basemap/`; no large file is committed. The build
documents 4 GiB RAM and 3 GiB peak disk expectations, cache behavior,
regeneration, targeted work/cache cleanup, and old-artifact retirement. The
superseded bootstrap bundle and the deliberately rejected z0-header work tree
were deleted after the final exact-config artifact passed; both were generated
data recoverable by rerunning the command.
