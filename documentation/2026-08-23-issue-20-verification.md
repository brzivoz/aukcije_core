# Issue #20 spatial model and viewport verification

Date: 2026-08-23

Scope: canonical property references, parcel identity, geometry/provenance,
resolution history/current selection, and bounded PostGIS viewport reads.

## Persisted contract

Flyway migration `V7__spatial_resolution_model.sql` owns six responsibilities:

| Table | Contract |
|---|---|
| `parcel_identities` | One canonical identity per `(ko_code, canonical_parcel_number)`; parcel numbers remain text. |
| `property_references` | Many ordered raw/normalized references per auction, including parser/source evidence and optional canonical parcel identity. |
| `spatial_resolution_geometries` | Original source geometry and CRS plus a checked, derived WGS84 geometry; only `Point`, `Polygon`, and `MultiPolygon`. |
| `location_resolution_cache_records` | Versioned resolver/dataset/hash results reusable by later resolver implementations. |
| `location_resolution_attempts` | Append-only outcome, precision, candidate evidence, provenance, timing, confidence, and optional member-point count. |
| `current_location_resolutions` | The mutable pointer that supersedes a prior selected attempt without changing or deleting history. |

`LocationPrecision` is fixed as `PARCEL`, `ADDRESS`, `STREET`,
`CADASTRAL_MUNICIPALITY`, `SETTLEMENT`, `MUNICIPALITY`, or `NONE`.
A trigger permits only successful `RESOLVED` or explicit `NONE` attempts to be
selected. Failed later attempts therefore cannot replace a last valid result.

The geometry table checks source SRID equality, two-dimensional supported type,
non-empty input, original validity, WGS84 validity, and coordinate bounds.
`trg_spatial_resolution_geometry_derive_canonical` derives WGS84 once on normal
insert/update instead of trusting a caller-supplied value. `ST_MakeValid` is
accepted only when the original was invalid and a nonblank repair reason is
retained.

Canonical derivation is deliberately a trigger rather than a `CHECK` containing
`ST_Transform`. PostgreSQL accepts `ST_Transform` as immutable, but its result
can change with PROJ/grid versions; `COPY` rechecks constraints during restore.
`pg_dump` emits the user trigger in its post-data section, so historical
canonical coordinates restore byte-for-byte without a transform being coupled
to the destination host's PROJ installation. The backup/restore test retains an
EPSG:3909 source and verifies exact canonical EWKB preservation.

Centroid and bounds are never persisted. The repository derives the true
centroid, numeric `GeometryBounds`, and an additional `ST_PointOnSurface`
representative point. The representative point is the safe map-pin location
for concave and multipart polygons because it is guaranteed to lie on the
geometry.

`STREET` precision does not mean a road centerline. Downstream issue #23
explicitly defines that tier as a street-level representative point when the
street identity is unambiguous. A point with `STREET` precision is therefore
representable without widening V7 to linestrings or coercing a line into a
different geometry type.

## Viewport contract

`SpatialViewportRepository.findSelectedWithin` requires a finite,
non-wrapping WGS84 `BoundingBox` and a `1..5000` limit. It performs one query
over current selected resolutions, applies both the `&&` bbox operator and
`ST_Intersects`, orders by stable auction/reference fields, and never accepts or
adds a tenant predicate. Its exact `EXPLAIN` plan is exposed for verification.

The query path is backed by both
`idx_spatial_resolution_geometries_canonical(canonical_geometry)` and
`idx_location_resolution_attempts_geometry(geometry_id)`. Reverse-FK indexes
also cover cache-to-geometry and attempt-to-cache deletion checks. The plan test
loads 20,000 geometries, 100,000 append-only attempts, and 20,000 selections,
runs `ANALYZE`, and takes the exact query plan with default planner settings. It
requires both spatial and attempt-geometry indexes and rejects a sequential scan
of `location_resolution_attempts`.

## Reproducible verification

```bash
./gradlew clean test --no-daemon
git diff --check
```

The clean test run starts the digest-pinned PostgreSQL 18/PostGIS 3.6 image. It
proves both empty-database migration and an upgrade from the previous V6 head,
then starts the real application with Flyway enabled and Hibernate
`ddl-auto=validate`.

Hibernate validation covers the mapped JPA schema (`Auction`); V7 is
intentionally JDBC-owned and has no JPA entities. V7 coverage comes from direct
catalog, constraint, trigger, repository, plan, upgrade, and restore tests. The
green Hibernate startup is compatibility evidence, not a claim that Hibernate
inspected the six V7 tables.

Focused acceptance coverage is in:

- `SpatialResolutionSchemaIntegrationTest`
- `PostgisSchemaIntegrationTest`
- `DatabaseLifecycleIntegrationTest`
- `BoundingBoxTest`
- `ParcelIdentityNormalizerTest`

No live network is used.

## Latest local evidence

On 2026-08-23, `./gradlew clean test --no-daemon` completed successfully in 39
seconds: 125 tests discovered, 122 passed, zero failed/errors, and three
unrelated opt-in full-data tests skipped because their local official artifacts
were not configured. `git diff --check` also passed.
