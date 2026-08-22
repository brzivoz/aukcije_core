# Issue #36 verification — Address Registry centroid extract

**Date:** 2026-08-22

**Issue:** [#36](https://github.com/brzivoz/aukcije_core/issues/36)

**Runtime:** Java 17; database-free filesystem publisher; PostGIS 3.6 used only
as the independent CRS oracle in the integration test

**Outcome:** clean full build and byte-identical unchanged replay passed

## Reviewed official input

The current official GPKG resource was downloaded to a quarantined path. No
source or generated artifact was committed.

| Property | Value |
|---|---|
| Dataset date used for the version | `2026-08-22` |
| ZIP bytes | 265,806,538 |
| ZIP SHA-256 | `3e601009bc1c540c83e2396af703a51713edb508d3fb220d5e6e36a52e4e0f15` |
| Archive member | `kucni_broj.gpkg` |
| GPKG bytes | 995,627,008 |
| GPKG SHA-256 | `ce983232d50cf445f0c71d45381e1d1d537450135b0b4be237c11c045229d3b3` |
| Layer / geometry / CRS | `kucni_broj` / `POINT` / `EPSG:25834` |
| Schema SHA-256 | `f5e76adbfcca49f4e2146f47e71ed7177e891915905d7a7874aba18c57d163be` |
| Official metadata | RGZ; weekly; `sodl`; source checksum/file size absent |

The source schema hash is unchanged from the 2026-08-21 #22 proof. The source
row population moved from 2,488,492 to 2,488,562, demonstrating why dataset
date plus hash are both part of the version identity.

## Full build and replay

The opt-in full test used the reviewed ZIP and pinned ZIP, GPKG, and schema
hashes:

```bash
ADDRESS_REGISTRY_CENTROID_FULL_SOURCE='<absolute reviewed ZIP>' \
ADDRESS_REGISTRY_CENTROID_FULL_SOURCE_DATE='2026-08-22' \
ADDRESS_REGISTRY_CENTROID_FULL_SOURCE_SHA256='3e601009bc1c540c83e2396af703a51713edb508d3fb220d5e6e36a52e4e0f15' \
ADDRESS_REGISTRY_CENTROID_FULL_GPKG_SHA256='ce983232d50cf445f0c71d45381e1d1d537450135b0b4be237c11c045229d3b3' \
ADDRESS_REGISTRY_CENTROID_FULL_SCHEMA_SHA256='f5e76adbfcca49f4e2146f47e71ed7177e891915905d7a7874aba18c57d163be' \
./gradlew test \
  --tests 'rs.sud.eaukcija.addressregistry.AddressRegistryCentroidFullExtractTest' \
  --no-daemon
```

Result after review hardening and exact output-hash assertions:
`BUILD SUCCESSFUL in 59s`.

| Metric | Clean build | Unchanged replay |
|---|---:|---:|
| Source / active / rejected rows | 2,488,562 / 2,488,562 / 0 | same |
| KO centroids | 4,497 | 4,497 |
| Settlement centroids | 4,717 | 4,717 |
| Municipality centroids | 168 | 168 |
| Duplicate-name groups, all / cross-municipality | 824 / 824 | 824 / 824 |
| Ambiguous-parent entries | 4 | 4 |
| Published bytes | 4,209,510 | byte-identical existing version |
| Clean total pipeline time | 26.856 s | — |
| Replay total pipeline time | — | 27.306 s |
| Outcome | `SUCCEEDED` | `UNCHANGED` |

The version is
`2026-08-22-ce983232d50cf445f0c71d45381e1d1d537450135b0b4be237c11c045229d3b3`.
The clean CLI proof independently completed in 27.143 seconds. It recorded the
download completion timestamp plus download, validation, extraction,
publication, and total durations in its per-run JSON.

## Deterministic published bytes

The final clean CLI artifact had these file hashes:

| File | SHA-256 |
|---|---|
| `centroids.ndjson` | `162112e9fb2cb6ae22ff0a9b922cabcf454c393243524a28598e541203d26c5b` |
| `report.json` | `fdaf757a4bf7acb22c8c44337ede3d467cc81c12cd56ee3fe7dac5d4466092c5` |
| `ATTRIBUTION.md` | `e680b02cd55403554e2820ce270bc86a0175a990e8c78ed5079a6e19b5af3179` |
| `manifest.json` | `d8876b6cea99e84101bc57879af7bfb0e31c624809649110cfc8042cd492fe45` |

The full test captured the first set, rebuilt from the same fixed source into a
new staging directory, compared every relative filename and byte, and returned
`UNCHANGED`. Runtime timestamps and durations live under `runs/`, outside the
immutable version, so evidence remains honest without making the data artifact
nondeterministic.

## Validation and report evidence

The successful full report records:

- exactly 4,497 KO, 4,717 settlement, and 168 municipality codes;
- zero rejected rows in this snapshot, and named successful gates for unique
  output codes, usable official names, active-row Serbia bounds, source/active
  row magnitude, and per-level centroid magnitude rather than hardcoded
  pseudo-measurements;
- 824 duplicate normalized-name groups (all 824 span municipalities in this
  snapshot), joined through either Cyrillic or Latin normalized forms, with
  every official and municipality code listed and an explicit cross-municipality flag;
- the four known KO entries carrying multiple municipality parents, as sorted
  parent-code arrays rather than an arbitrary selection; and
- member-point count on every centroid, ranging from small groups to thousands
  of official house-number points.

Every NDJSON entry repeats the immutable version, source date, and GPKG hash.
The manifest also traces the ZIP/GPKG/schema hashes, source row count, CRS,
canonical resource, publisher, license, update frequency, and content-file
hashes.

## Automated acceptance evidence

Focused fixture tests prove:

- exact official identifiers and Cyrillic/Latin names survive unchanged;
- KO→settlement and KO/settlement→municipality relationships survive, including
  multiple parents without duplicate output codes;
- member-point counts and deterministic WGS84 coordinates are emitted;
- the production EPSG:25834 inverse transform matches PostGIS within `1e-8`
  degrees on all committed fixtures;
- ZIP/GPKG/schema hashes, sizes, source count, CRS, canonical URL, license, and
  file hashes appear in the immutable manifest;
- duplicate-name groups and inactive/retired exclusion reasons appear in the
  deterministic report;
- same-municipality, missing-Latin, and municipality-level duplicate fixtures
  remain visible, while normalized-equivalent casing variants publish and list
  every raw variant;
- corrupt names, conflicting names for one code, implausible per-level counts,
  bad geometry/Serbia bounds, and immutable-byte changes fail closed; and
- a fresh never-before-built older snapshot fails with `SOURCE_DATE_DOWNGRADE`
  before publication and leaves `ACTIVE` unchanged;
- retired out-of-bounds geometry is reported as `RETIRED` and excluded before
  geometry evaluation, while the same condition on an active row remains fatal;
- abandoned staging is pruned under the publication lock, nested work/publish
  roots are rejected, and a failed refresh leaves `ACTIVE` untouched.

CI performs no live download. The large official ZIP/GPKG and generated
4.21 MB version stayed under `/tmp` for this proof and are excluded by the
repository's `data/` ignore rule in normal operation.
