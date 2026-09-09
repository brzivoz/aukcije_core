# Issue #47 — parcel visibility and usable clusters

## Delivered

- The bounded GeoJSON feature now includes a presentation-only `marker` Point.
  JTS derives it on the canonical geometry, never on its bounding-box centre.
  When that ordinary anchor is outside the viewport, the point comes from the
  visible intersection; even a boundary-only touch remains eligible. Polygon /
  MultiPolygon coordinates and canonical feature IDs are unchanged.
- Marker derivation happens after existing winner, bbox, precision and limit
  selection. No extra query, source acquisition, geometry buffer or pin for NONE.
  Current #33/RGZ eligibility and winner-before-bbox/precision SQL are unchanged.
- Overview markers/clusters below zoom 13, markers plus exact boundaries at
  13–17, lighter fill/full boundaries and smaller parcel cues at 17–20.
  Boundary tiling has zero simplification tolerance. Visibility does not depend
  on a sub-pixel fill rendering; marker/count symbols permit overlap.
- Separated clusters expand the camera. Coincident locations and terminal /
  non-progressing clusters offer native keyboard buttons. Geographic-group
  headings do not claim one shared location. The canvas supports Enter at its
  centre, in addition to the existing accessible results list.
- Hit testing deduplicates tiled fill, outline and marker representations. Near
  misses use a 12px screen-space query, never enlarged cadastral geometry.
  Selection resolves to the current full geometry by property ID. The selected
  property (not all siblings in its auction) retains a black ring, exact boundary
  outline and result highlight after details dismissal. Source revision guards
  reject stale worker choices; ineligible geometry is not restored on activation.
- UI legend/details explain that a parcel boundary is not necessarily the
  auctioned building footprint. Existing centroid precision labels remain intact.

See [MAP_API.md](MAP_API.md) and
[BROWSER_AND_FRONTEND.md](BROWSER_AND_FRONTEND.md#parcel-visibility-and-cluster-navigation-47).

## Verification

Final runs:

| Command | Result |
| --- | --- |
| `./gradlew test` | 535 tests: 531 passed, 4 skipped, 0 failed |
| `./gradlew browserTest` | 57 passed, 0 failed |
| `git diff --check` | Clean |

The first full unit/integration run had seven HTTP 404 failures in the existing
`BasemapAssetHttpIntegrationTest`. That class passed in isolation, and a complete
rerun passed without a basemap change. No cause is assigned to that transient
failure.

`ParcelVisibilityFixtures` supplies tiny, narrow, concave, holed and disjoint
MultiPolygon shapes to real PostGIS API and browser tests. Independent PostGIS
`ST_Covers` assertions verify the Java-derived point lies on both geometry and
viewport, including an edge-only touch; exact serialized geometry, stable IDs,
counts, genuine siblings/shared parcels, limits and NONE are asserted.

`ParcelMapBrowserTest` exercises actual MapLibre workers and rendering at zooms
7, 12.9, 13, 16.9, 17, 18, 19 and the supported maximum 20. It covers pointer
cluster expansion, Enter/Space chooser operation and focus, mixed-precision
coincidence, geographically distinct max-zoom clusters, overlapping boundaries,
selected-property identity after dismissal/refresh, screen-space near hits,
viewport-edge re-anchoring, and removal from all three sources after revocation.
A real 1001-property auction returns 1000 properties/markers, retains an explicit
limit warning, and offers exactly 1000 terminal-cluster choices, not 2000 marker
plus boundary choices. All browser cases finish with the shared localhost-only
network assertion. The complete automatic RGZ/source-refresh/shared-filter and
existing details/workspace suites also pass.

Retained screenshot:
`build/browser-test-results/evidence/issue-47-tiny-max-zoom.png`.
JUnit XML/HTML reports remain under `build/test-results/` and
`build/reports/tests/`.

The established defect was overview polygons lacking any point representation.
No explicit pre-existing application min/max parcel-layer cutoff was found, and
no separate genuine zoom-in disappearance was reproduced or attributed to a
renderer cause. The zoom-in matrix is regression coverage, not evidence for such
a claim. No public tile service, new frontend dependency, coordinate jitter or
bulk export was introduced.
