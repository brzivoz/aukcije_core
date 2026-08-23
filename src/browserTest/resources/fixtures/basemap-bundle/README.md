# Compact browser-only basemap fixture

This is a lawful compact extract of the ignored #24 build
`serbia-2026-08-01-e82bacf6e754`, whose Serbia PMTiles SHA-256 is
`96fd233bd7f954c6e8085c41950d7663f402fb6bf99822fd31b7e0762a2e24b1`.
It exists only to replay #25's actual PMTiles/MapLibre HTTP path in CI without
committing the full 231,649,275-byte archive.

Pinned go-pmtiles `1.31.2` extracted bbox
`20.455,44.785,20.465,44.795` independently at zoom 5, 9, and 14, then merged
the three disjoint archives. `pmtiles verify` passes. The resulting
`serbia.pmtiles` is 684,775 bytes with SHA-256
`c7efb40b569a02d10a2482c3c5c9a6ce48ead39635ad5931ecee272eb0791585`.

The style, sprite pair, and six Noto glyph ranges are byte-identical to that
#24 build. `THIRD_PARTY_NOTICES.md` and both font/icon licenses are retained in
this directory. The underlying OpenStreetMap data is © OpenStreetMap
contributors and available under ODbL 1.0; the browser test requires the linked
copyright attribution to remain visible.
