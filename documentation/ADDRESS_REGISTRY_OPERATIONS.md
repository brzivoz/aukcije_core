# Address Registry snapshot operations

Issue [#22](https://github.com/brzivoz/aukcije_core/issues/22) imports the
official Serbian Address Registry house-number GeoPackage into immutable
PostGIS snapshots. It is a batch operator action, not an application-startup
job. A refresh downloads or copies into isolated staging, validates the whole
source contract, streams active rows, builds centroids, and changes the active
pointer only in the same successful database transaction.

## Official source and attribution

- Dataset: [Adresni registar](https://data.gov.rs/sr/datasets/adresni-registar/)
- House-number GPKG resource:
  [be7c80e3-206b-46af-b31d-4b9f6ae596f9](https://data.gov.rs/sr/datasets/r/be7c80e3-206b-46af-b31d-4b9f6ae596f9)
- Publisher: Republički geodetski zavod (RGZ)
- Dataset license identifier: `sodl` — Srpska licenca za otvorene podatke
- Declared update frequency: weekly

Retain those four attribution fields with any export or derived artifact. The
data.gov.rs API currently reports `checksum: null` for this resource. Therefore
the import deliberately requires an operator-approved SHA-256: download to a
quarantine path, record and review its byte size/hash and named snapshot date,
then give that fixed file and hash to the importer. Do not derive the expected
hash from the importer's own staged copy.

The canonical download endpoint is:

```text
https://download.geosrbija.rs/download-api/opendata-proxy/export?category=ar&layer=kucni_broj_ar&geometry=true&fileName=kucni_br_gpkg&format=gpkg
```

## Capacity and preflight

The 2026-08-21 spike measured a 265,811,831-byte ZIP, a 995,225,600-byte GPKG,
2,488,492 source points, and a 116.1-second local SQLite load/index pass. Its
peak retained files occupied 2.36 GiB. Reserve **at least 4 GiB of free staging
space** plus PostgreSQL capacity for the current and previous snapshots. The
importer checks staging free space before copying anything.

The default fail-closed limits are:

| Gate | Default |
|---|---:|
| Source CRS | EPSG:25834 |
| Geometry | two-dimensional `POINT` |
| Source rows | 2,000,000–3,500,000 |
| Minimum active fraction | 90% of inspected source rows |
| GPKG size | at most 2 GiB |
| Working free space | at least 4 GiB |
| JDBC batch | 5,000 rows |
| Retained snapshots | 3, never fewer than 2 |

The inspected source schema and all required Cyrillic/Latin name and identifier
columns are fingerprinted. Set `ADDRESS_REGISTRY_IMPORT_EXPECTED_SCHEMA_SHA256`
when an approved deployment pins the exact schema. Even without that optional
pin, a missing required column, changed layer, non-point geometry, or wrong CRS
fails before PostgreSQL loading begins.

## Import a reviewed snapshot

Start the database as documented in
[DATABASE_OPERATIONS.md](DATABASE_OPERATIONS.md). Download the artifact outside
the repository and calculate its SHA-256:

```bash
mkdir -p /tmp/aukcije-ar-review
curl --fail --location \
  'https://download.geosrbija.rs/download-api/opendata-proxy/export?category=ar&layer=kucni_broj_ar&geometry=true&fileName=kucni_br_gpkg&format=gpkg' \
  --output /tmp/aukcije-ar-review/kucni_br_gpkg.zip
shasum -a 256 /tmp/aukcije-ar-review/kucni_br_gpkg.zip
ls -l /tmp/aukcije-ar-review/kucni_br_gpkg.zip
```

After the date, size, and hash are reviewed, run the non-web import command.
Use an absolute `file:` URI so the reviewed download cannot change underneath a
second network request:

```bash
export SPRING_PROFILES_ACTIVE=dev
export AUKCIJE_DB_PASSWORD="$(tr -d '\r\n' < .secrets/postgres-password)"
export ADDRESS_REGISTRY_IMPORT_SOURCE_URI='file:///tmp/aukcije-ar-review/kucni_br_gpkg.zip'
export ADDRESS_REGISTRY_IMPORT_SOURCE_DATE='2026-08-21'
export ADDRESS_REGISTRY_IMPORT_EXPECTED_SHA256='<reviewed ZIP SHA-256>'
./gradlew importAddressRegistry
```

For an already extracted GPKG, use its `file:` URI and its SHA-256. For a ZIP,
`ADDRESS_REGISTRY_IMPORT_EXPECTED_GPKG_SHA256` may additionally pin the member
hash. `ADDRESS_REGISTRY_IMPORT_CANONICAL_URL` should only be changed when the
official data.gov.rs resource identity changes after review.

The command returns JSON with source/GPKG/schema hashes, byte and row counts,
active/inactive/retired counts, parcel-normalization loss, centroid count,
phase durations (including retention), current and previous snapshot ids, and
retained-snapshot count. Progress is logged every 100,000 source rows. A
non-zero process result means no promotion occurred.

Configuration is validated before an import-run id is created. Therefore a
missing source date/hash or an invalid safety limit is rejected as an operator
invocation error and intentionally creates no `address_registry_import_runs`
row. Once configuration is valid, every staging, validation, load, and
promotion failure is recorded.

## Status, evidence, and unchanged imports

Show the active/previous pointer without source inputs:

```bash
export SPRING_PROFILES_ACTIVE=dev
export AUKCIJE_DB_PASSWORD="$(tr -d '\r\n' < .secrets/postgres-password)"
export ADDRESS_REGISTRY_IMPORT_ACTION=STATUS
./gradlew importAddressRegistry
```

Every attempt is retained in `address_registry_import_runs`; every complete
snapshot in `address_registry_snapshots`. Useful evidence queries are:

```sql
SELECT * FROM address_registry_active_snapshot;

SELECT id, source_date, source_sha256, gpkg_sha256, schema_sha256,
       source_bytes, gpkg_bytes, source_row_count, imported_row_count,
       inactive_source_row_count, retired_source_row_count,
       duplicate_parcel_identities, unnormalized_parcel_rows,
       ambiguous_parent_identities, imported_at
FROM address_registry_snapshots
ORDER BY imported_at DESC;

SELECT action, outcome, started_at, finished_at, snapshot_id,
       download_millis, validation_millis, load_millis,
       centroid_millis, total_millis AS import_millis, error_code
FROM address_registry_import_runs
ORDER BY started_at DESC;

SELECT import_run_id, outcome, started_at, finished_at,
       duration_millis AS retention_millis, retained_snapshot_count, error_code
FROM address_registry_retention_jobs
ORDER BY finished_at DESC;
```

Re-importing the same validated GPKG while it is active returns `UNCHANGED` and
does not duplicate points or centroids. A retained but inactive hash is refused;
use explicit rollback rather than disguising a downgrade as a refresh.

## Atomic failure and rollback

The importer first acquires a PostgreSQL session advisory lock on a dedicated
connection, before creating its `RUNNING` row. It holds that lease throughout
download, staging, GeoPackage validation, point loading, and the terminal run
update. The point load, active-fraction gate, centroid build, validation, and
pointer change remain one PostgreSQL transaction inside that lease. Checksum,
schema, CRS, source/active-row-count, malformed/null geometry, required-value,
duplicate source-key, conflicting official names, or Serbia-bounds failures
abort the transaction. The previous active pointer and all of its rows remain
intact. A concurrent CLI action fails early as `IMPORT_ALREADY_RUNNING` and is
retained as a terminal attempt without duplicating the download.
If lease release reports a failure after the terminal update, the importer
logs only `IMPORT_LOCK_RELEASE_FAILED`; it does not replace the persisted
outcome or turn a committed success into a failing CLI exit. Successful imports
still proceed to the separately recorded retention phase.

Rollback atomically swaps current and previous:

```bash
export ADDRESS_REGISTRY_IMPORT_ACTION=ROLLBACK
./gradlew importAddressRegistry
```

Rollback refuses to run when no previous good snapshot exists. It does not
delete either side. A later import may rotate them again.

Retention runs in a separate transaction only after promotion commits and the
session lease is released, so a
large cascading delete cannot extend or roll back the promotion transaction.
It reacquires the advisory lock and re-reads the pointer `FOR UPDATE` before
choosing deletions. A cleanup failure is logged, leaves the successful snapshot
active, and is retried by a later successful import or can be handled by an
operator. The default keeps three complete snapshots.
`ADDRESS_REGISTRY_IMPORT_RETAINED_SNAPSHOTS` is configurable but cannot be
lower than two, and cleanup always explicitly keeps the current active and
previous ids before deleting anything else. `retention_millis` makes the
steady-state deletion cost visible separately from the promotion phases in
`address_registry_retention_jobs`. The legacy nullable
`address_registry_import_runs.retention_millis` column is no longer written:
promotion evidence becomes terminal before post-commit retention starts. The
operator status API joins a successful retention duration to the import-phase
duration so its reported total matches the importer's returned total.

On application startup, recovery first tries the same advisory-lock key. Any
live importer holds its session lease from before `RUNNING` through terminal
update, so recovery cannot touch it during download or validation. Only when
the key is free can an abandoned `RUNNING` row be finalized as `FAILED` with
`IMPORT_PROCESS_RESTARTED`. The active snapshot pointer is not changed.

## Source-row treatment

- A row is active only when `retired` is null/blank and the Cyrillic or Latin
  `vrsta_stanja` normalizes exactly to `AKTIVAN`. Other rows contribute to
  source/inactive/retired metrics but are not promoted into lookup tables. At
  least `ADDRESS_REGISTRY_IMPORT_MINIMUM_ACTIVE_FRACTION` (default `0.90`) of
  inspected rows must be active, preventing a changed upstream status or
  retirement vocabulary from promoting an empty or implausibly sparse
  snapshot.
- Null or malformed geometry, source primary key/fid, KO id/name, settlement
  id/name, or municipality id/name aborts the snapshot. Optional street,
  house-number, and parcel fields remain null and are excluded from the
  corresponding partial indexes.
- Duplicate source `fid` or `primary_key` aborts the snapshot. Multiple active
  house numbers for the same KO+parcel identity are valid, preserved as
  separate points, indexed, and counted in `duplicate_parcel_identities`.
- `broj_dela_parcele` is retained in `parcel_part` as the source's
  building/object-part ordinal. It is never appended to `broj_parcele` and is
  never presented as a cadastral sub-parcel.
- A nonblank `broj_parcele` outside the accepted `digits` or `digits/digits`
  grammar remains preserved as source evidence, receives no normalized join
  key, and increments `unnormalized_parcel_rows` in both snapshot and run
  metrics.
- Official identifiers and Cyrillic/Latin names are retained byte-for-byte as
  text. Separate normalized keys support matching without replacing source
  evidence.
- If one KO or settlement id appears under more than one municipality while its
  official names remain consistent, the centroid keeps the id/name but leaves
  `municipality_id` null, records `parent_variant_count`, and increments the
  snapshot's `ambiguous_parent_identities`; it never chooses a parent by row
  order. Conflicting names for one official id still abort promotion.
- WGS84 points are produced by PostGIS
  `ST_Transform(..., 25834, 4326)`. KO, settlement, and municipality point
  centroids are tied to the same immutable snapshot id and hash and carry their
  member-point count.
