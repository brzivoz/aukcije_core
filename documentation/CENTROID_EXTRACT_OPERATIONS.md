# Address Registry centroid extract operations

Issue [#36](https://github.com/brzivoz/aukcije_core/issues/36) derives the
small KO, settlement, and municipality centroid artifact used by coarse map
resolution. It reads the same official GeoPackage as the full #22 importer,
but it does **not** import or persist the roughly 2.5 million house-number
rows. The database-free operator command streams the source once, retains only
the administrative aggregates, validates the complete result, and changes the
filesystem `ACTIVE` pointer only after an immutable version is ready.

## Official source and attribution

- Dataset: [Adresni registar](https://data.gov.rs/sr/datasets/adresni-registar/)
- House-number GPKG resource:
  [be7c80e3-206b-46af-b31d-4b9f6ae596f9](https://data.gov.rs/sr/datasets/r/be7c80e3-206b-46af-b31d-4b9f6ae596f9)
- Publisher: Republički geodetski zavod (RGZ)
- Dataset license identifier: `sodl` — Srpska licenca za otvorene podatke
- Declared update frequency: weekly

The published manifest and `ATTRIBUTION.md` retain these fields. The official
metadata currently publishes neither a checksum nor a file size, so an
operator must quarantine the download, review its date/size, and calculate the
expected SHA-256 independently before this command may run.

The canonical download endpoint is:

```text
https://download.geosrbija.rs/download-api/opendata-proxy/export?category=ar&layer=kucni_broj_ar&geometry=true&fileName=kucni_br_gpkg&format=gpkg
```

## Published contract

The default publish root is the git-ignored
`data/address-registry-centroids/` directory:

```text
data/address-registry-centroids/
├── ACTIVE
├── versions/
│   └── <dataset-date>-<full-gpkg-sha256>/
│       ├── manifest.json
│       ├── centroids.ndjson
│       ├── report.json
│       └── ATTRIBUTION.md
└── runs/
    └── <started-epoch-millis>-<run-id>.json
```

`centroids.ndjson` is ordered by `KO`, `SETTLEMENT`, `MUNICIPALITY`, then
official code. Every entry carries the extract version, dataset date, GPKG
hash, exact official code and Cyrillic/Latin names, member-point count,
WGS84 coordinates rounded to seven decimal places, and the applicable
settlement/municipality relationships. Multiple official parents remain a
sorted array; the publisher never chooses one by row order.

`manifest.json` records the canonical source, ZIP and GPKG sizes/hashes,
archive member, source row count, schema fingerprint, source/target CRS,
license, per-level counts, and hashes of every other immutable file.
`report.json` records centroid counts, every duplicate normalized-name group,
whether each group spans municipalities, all matching normalized Cyrillic and
Latin forms, raw name variants, ambiguous parents, excluded source rows by
reason, and the fail-closed gates the published artifact passed. The
per-attempt run report records the source URI, download completion time,
artifact size, phase durations, outcome, and a stable error code/message.

The version directory name uses the dataset date and complete GPKG SHA-256.
Existing version bytes are never overwritten. A replay rebuilds the complete
candidate, compares every byte, and returns `UNCHANGED` only when all files
match. A mismatch is an `IMMUTABLE_VERSION_CONFLICT`.

## Capacity and fail-closed defaults

The input still contains the full official GeoPackage even though the output
is small. The 2026-08-22 proof used a 253.5 MiB ZIP and a 949.5 MiB GPKG.
Reserve at least 2 GiB in the staging filesystem; the publisher checks before
copying.

| Gate | Default |
|---|---:|
| Source geometry / CRS | two-dimensional `POINT` / `EPSG:25834` |
| Target CRS | `EPSG:4326` |
| Source rows | 2,000,000–3,500,000 |
| Minimum active fraction | 90% |
| KO centroids | 3,500–6,000 |
| Settlement centroids | 3,500–7,000 |
| Municipality centroids | 100–300 |
| GPKG size | at most 2 GiB |
| Staging free space | at least 2 GiB |
| SQLite fetch size | 5,000 rows |
| Serbia sanity envelope | 18.0–23.5° E, 41.5–46.5° N |

The strict source schema fingerprint covers the ordered column definition and
GeoPackage geometry metadata. `ADDRESS_REGISTRY_CENTROID_EXTRACT_EXPECTED_SCHEMA_SHA256`
may pin it exactly after review.

## Build a reviewed snapshot

Download outside the repository and calculate the ZIP hash:

```bash
mkdir -p /tmp/aukcije-ar-centroid-review
curl --fail --location \
  'https://download.geosrbija.rs/download-api/opendata-proxy/export?category=ar&layer=kucni_broj_ar&geometry=true&fileName=kucni_br_gpkg&format=gpkg' \
  --output /tmp/aukcije-ar-centroid-review/kucni_br_gpkg.zip
shasum -a 256 /tmp/aukcije-ar-centroid-review/kucni_br_gpkg.zip
ls -l /tmp/aukcije-ar-centroid-review/kucni_br_gpkg.zip
```

After review, run the database-free Gradle task with an absolute `file:` URI:

```bash
export ADDRESS_REGISTRY_CENTROID_EXTRACT_SOURCE_URI='file:///tmp/aukcije-ar-centroid-review/kucni_br_gpkg.zip'
export ADDRESS_REGISTRY_CENTROID_EXTRACT_SOURCE_DATE='2026-08-22'
export ADDRESS_REGISTRY_CENTROID_EXTRACT_EXPECTED_SHA256='<reviewed ZIP SHA-256>'
export ADDRESS_REGISTRY_CENTROID_EXTRACT_EXPECTED_GPKG_SHA256='<reviewed GPKG SHA-256, when known>'
export ADDRESS_REGISTRY_CENTROID_EXTRACT_EXPECTED_SCHEMA_SHA256='<reviewed schema SHA-256, when pinned>'
./gradlew buildAddressRegistryCentroids
```

The first reviewed run may omit the GPKG/schema pins because those values are
only knowable after the ZIP member is inspected. The result and immutable
manifest print both. Review them, then require both pins on replay and on the
production publication. Never calculate the expected ZIP hash from the
publisher's own staged copy.

Override `ADDRESS_REGISTRY_CENTROID_EXTRACT_WORK_DIRECTORY` and
`ADDRESS_REGISTRY_CENTROID_EXTRACT_PUBLISH_DIRECTORY` when the defaults do not
have sufficient space or the published root belongs on a managed volume.

## Status and failure recovery

Status requires no source file, network, profile, or database:

```bash
export ADDRESS_REGISTRY_CENTROID_EXTRACT_ACTION=STATUS
./gradlew buildAddressRegistryCentroids
```

Publication uses a filesystem lock, builds in a sibling staging directory,
atomically moves the complete version, then atomically replaces `ACTIVE`.
Checksum, schema, CRS, row-count, status-vocabulary/active-fraction, required
code/name, conflicting-name, coordinate, per-level count, or immutable-byte
failures leave the old `ACTIVE` contents unchanged. A fully written version
whose pointer update was interrupted is safe to retry; a version older than
the active dataset date is rejected before either a new or retained version is
moved or activated, so a fresh build cannot silently downgrade `ACTIVE`.

Status and retirement fields are evaluated before row geometry. An inactive
row contributes `INACTIVE_STATUS` or `RETIRED` evidence but can never reach a
centroid, so malformed or legacy out-of-bounds geometry on that row does not
block publication. Geometry parsing, transformation, and Serbia bounds remain
fatal gates for every active member row, matching the full #22 import boundary.

Official-name consistency is compared through the shared Serbian normalizer.
Raw casing/spacing variants with the same normalized identity are retained in
the report and a deterministic raw form is published; genuinely different
normalized names for one official code still fail closed. Duplicate reporting
uses both script forms and includes same-municipality KO/settlement ambiguity
and municipality-level ambiguity, not only groups spanning municipalities.

The pipeline keeps all small immutable versions. Operators may archive an old
inactive directory under their storage policy, but must never edit a published
version or remove the version named by `ACTIVE`. A failed attempt has a
`FAILED` run report where the publish root was writable.

Work and publish roots must be separate sibling trees; either containing the
other is rejected. On every build the publisher acquires its filesystem lock
and removes abandoned `.staging/version-*` content from an interrupted JVM
before creating the current staging directory.

## Reproducibility tests

CI uses only committed tiny GeoPackage fixtures and never downloads live data.
The focused tests cover exact identifiers/names, administrative relationships,
duplicate names, invalid/null names, conflicting codes, source/count/geometry
gates, fresh-version downgrade refusal, inactive-geometry exclusion,
deterministic replay, immutable conflicts, staging cleanup, path containment,
and failure-safe activation.
The CRS integration test cross-checks the production EPSG:25834→4326 inverse
projection against PostGIS.

An operator can opt into the retained full-snapshot proof:

```bash
ADDRESS_REGISTRY_CENTROID_FULL_SOURCE='/absolute/path/kucni_br_gpkg.zip' \
ADDRESS_REGISTRY_CENTROID_FULL_SOURCE_DATE='2026-08-22' \
ADDRESS_REGISTRY_CENTROID_FULL_SOURCE_SHA256='<reviewed ZIP SHA-256>' \
ADDRESS_REGISTRY_CENTROID_FULL_GPKG_SHA256='<reviewed GPKG SHA-256>' \
ADDRESS_REGISTRY_CENTROID_FULL_SCHEMA_SHA256='<reviewed schema SHA-256>' \
./gradlew test \
  --tests 'rs.sud.eaukcija.addressregistry.AddressRegistryCentroidFullExtractTest' \
  --no-daemon
```

That test builds the complete version twice and asserts byte-identical hashes
for all four published files. The source and generated large/runtime artifacts
remain outside Git.
