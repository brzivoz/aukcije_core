# Reproducible Serbia PMTiles bundle

One command downloads the dated, checksum-pinned Geofabrik Serbia extract,
generates a Serbia-only PMTiles v3 archive in a digest-pinned Planetiler Java 21
container, downloads checksum-pinned local glyphs/sprites, validates the bundle,
and retains a provenance manifest beside it:

```bash
./basemap/build.sh
```

The Spring application is unchanged on Java 17. Java 21 exists only inside the
pinned Planetiler build image. The host prerequisites are Docker, Bash, curl,
Python 3, `tar`, and `unzip`; no host JDK, Maven, Node, GIS database, or
public tile service is used.

## Output and resources

Ignored output is written below `data/basemap/`:

- `cache/` retains the 227 MiB source, the Geofabrik checksum, the verified
  PMTiles CLI, and small style assets for repeat builds.
- `work/` contains disposable Planetiler scratch data while a build runs.
- `builds/serbia-2026-08-01-<config-hash>/` is the immutable validated bundle.

The build needs about 4 GiB of available RAM and 3 GiB of free disk at peak.
`BASEMAP_JAVA_HEAP` (default `3g`) and `BASEMAP_THREADS` (default `4`) can be
lowered for a constrained host, at the cost of build time. The custom profile
uses only the named Serbia PBF; it deliberately avoids Planetiler profiles that
silently download mutable global Natural Earth/coastline inputs.

The bundle contains `serbia.pmtiles`, `style.json`, local Noto Sans glyph ranges
for Serbian Latin and Cyrillic, 1x/2x sprites, full asset licenses,
`THIRD_PARTY_NOTICES.md`, `validation-report.json`, and `build-manifest.json`.
Large generated files are ignored and must not be committed.

The style's same-origin root is `/basemap`: serve the bundle files below that
path so the retained PMTiles, sprite, and glyph paths stay deployment-portable.
Issue #25 owns the production range/ETag endpoint and atomic activation.
When passing the parsed style object to MapLibre GL JS 6, expand the leading
`/basemap` sprite and glyph paths against `window.location.origin`; MapLibre's
sprite parser requires an absolute URL even though the retained artifact must
remain deployment-portable. Expand the PMTiles source to
`pmtiles://${window.location.origin}/basemap/serbia.pmtiles` at the same point.
The issue #24 browser proof used exactly that same-origin transformation and
blocked every non-loopback request.

## Validation contract

The build fails before Planetiler if the dated PBF is truncated, corrupted, has
the wrong size, differs from Geofabrik's downloaded MD5, or differs from the
pinned SHA-256. Downloads use temporary files and are promoted only after their
hash and size pass.

After generation, the pinned official PMTiles CLI verifies archive structure.
The repository validator additionally requires the PMTiles v3 magic/version,
MVT/gzip header, exact Serbia bounds, zoom 3–14, the seven expected layers,
stable allowlisted metadata, non-empty Belgrade tile reads at zooms 5, 9, and
14, local-only style URLs, valid sprite pairs, Serbian Latin/Cyrillic glyph
ranges, visible OpenStreetMap attribution, and exact manifest/file hashes.

Run the cheap pipeline regressions without downloading or generating the real
artifact:

```bash
./gradlew basemapTest
```

## Regeneration and cleanup

`./basemap/build.sh` is idempotent: an existing build ID is revalidated and
returned without rewriting it. A changed profile, style, lock, script, or tool
pin produces a new config hash and therefore a new immutable output directory.
The effective heap and thread settings also participate in that hash, so a
different build command cannot silently reuse an existing manifest.

To reclaim only disposable work, remove a specific directory below
`data/basemap/work/`. To force a source/tool redownload, remove the specific
file below `data/basemap/cache/`. To retire an old artifact, remove that exact
directory below `data/basemap/builds/` only after issue #25's active pointer no
longer references it. Never delete the whole shared `data/` tree.

## Attribution and distribution

The style source attribution is
`© OpenStreetMap contributors` with a copyright link, so MapLibre's attribution
control can display it visibly. Keep the attribution control enabled. The
bundle manifest records the named source URL/date/hash, build tool/image,
command/config hash, output hash/size/bounds, smoke reads, asset hashes, and the
ODbL requirement. See `THIRD_PARTY_NOTICES.md` and the retained license files
before publishing or distributing a produced work or derived database.
