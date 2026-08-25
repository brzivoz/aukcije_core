# Issue #22 verification — full Address Registry import

**Date:** 2026-08-22

**Issue:** [#22](https://github.com/brzivoz/aukcije_core/issues/22)

**Runtime:** Java 17, PostgreSQL 18.6/PostGIS 3.6 disposable Testcontainer

**Outcome:** full import and unchanged replay passed

## Reviewed input

The retained artifact from spike #32 was used without a live CI download:

| Property | Value |
|---|---|
| Source date | `2026-08-21` |
| Layer | `kucni_broj` |
| Geometry / CRS | `POINT` / `EPSG:25834` |
| GPKG bytes | 995,225,600 |
| GPKG SHA-256 | `b78cdb490df67acd1507a6484b39cca477c04da40ee6b824f36742315d39c84e` |
| Import schema SHA-256 | `f5e76adbfcca49f4e2146f47e71ed7177e891915905d7a7874aba18c57d163be` |

The official data.gov.rs metadata names resource
`be7c80e3-206b-46af-b31d-4b9f6ae596f9`, publisher RGZ, weekly frequency, and
license `sodl` (Srpska licenca za otvorene podatke). Its checksum field is null,
so the operator workflow pins a reviewed local artifact hash before import.

## Full runtime result

The opt-in test ran:

```bash
ADDRESS_REGISTRY_FULL_GPKG='<absolute reviewed GPKG path>' \
ADDRESS_REGISTRY_FULL_GPKG_SHA256='b78cdb490df67acd1507a6484b39cca477c04da40ee6b824f36742315d39c84e' \
ADDRESS_REGISTRY_FULL_SOURCE_DATE='2026-08-21' \
ADDRESS_REGISTRY_FULL_WORK_DIR='/tmp' \
./gradlew test \
  --tests 'rs.sud.eaukcija.addressregistry.AddressRegistryFullImportIntegrationTest' \
  --no-daemon
```

Result after the review fixes: `BUILD SUCCESSFUL in 3m 57s`.

| Metric | Clean import | Unchanged replay |
|---|---:|---:|
| Source rows | 2,488,492 | 2,488,492 |
| Imported active rows | 2,488,492 | existing snapshot reused |
| Inactive / retired / rejected | 0 / 0 / 0 | 0 / 0 / 0 |
| Unnormalized nonblank parcels | 0 | 0 |
| Multi-point KO+parcel identities | 182,989 | 182,989 |
| Ambiguous parent identities | 4 | 4 |
| Centroid rows | 9,382 | existing snapshot reused |
| Staging/hash time | 2.806 s | 3.293 s |
| Schema/CRS/count validation | 0.320 s | 0.102 s |
| Batched PostGIS load/transform | 197.351 s | 0 s |
| Centroid build | 20.452 s | 0 s |
| Post-commit retention | 0.003 s | 0 s |
| Total importer time | 224.594 s | 3.404 s |

The clean import's `sourceSha256`, `gpkgSha256`, source row count, and
multi-point parcel count reproduce spike #32. The replay returned `UNCHANGED`
with the same snapshot id and did not duplicate rows.

This disposable database began with no retained snapshots, so its 3 ms
retention phase performed pointer locking/accounting but no 2.49-million-row
cascade delete. It is not presented as a steady-state deletion benchmark.
Production runs retain that separate cost in the append-only
`address_registry_retention_jobs.duration_millis`, outside the already
committed promotion transaction. The legacy import-row `retention_millis`
column remains null because the import row is terminal before retention starts.

## Centroid and ambiguity report

The three levels sum exactly to the 9,382 promoted centroid rows:

| Level | Official ids / centroids |
|---|---:|
| KO | 4,497 |
| Settlement | 4,717 |
| Municipality | 168 |

Exact source Latin names repeat across municipalities by design: 395 KO-name
groups and 413 settlement-name groups. Examples include `NOVO SELO` (14 KO ids
in 14 municipalities), `SLATINA` (12/12), and `KAMENICA` (10/10). Lookup must
therefore use official ids and administrative context, never a globally unique
name assumption.

Four KO ids have a consistent official name but two parent municipality ids:

| KO id | Name | Parent municipality ids |
|---|---|---|
| `719161` | БРЗАН / BRZAN | `70076`, `71056` |
| `729795` | НИШ "БУБАЊ" / NIŠ "BUBANJ" | `71323`, `71331` |
| `729809` | НИШ "ЋЕЛЕ КУЛА" / NIŠ "ĆELE KULA" | `71331`, `71323` |
| `735892` | ПЛОЧНИК / PLOČNIK | `70998`, `70688` |

Their KO centroids retain the id and names, set `municipality_id` to null,
record `parent_variant_count = 2`, and contribute four to
`ambiguous_parent_identities`. This makes the source ambiguity visible and
deterministic. A conflicting name for one official id remains fatal.

## Automated acceptance evidence

The offline committed fixture suite proves:

- exact source ids and Cyrillic/Latin names survive unchanged;
- PostGIS transforms EPSG:25834 coordinates to the expected WGS84 points;
- ZIP and direct-GPKG staging retain both hashes and the archive member;
- bad source/GPKG checksum, missing schema, schema fingerprint, CRS, row-count,
  active-row fraction, malformed geometry, Serbia bounds, required normalized
  values, and duplicate source keys fail before promotion;
- a failed refresh leaves the prior snapshot and pointer active;
- an upstream status-vocabulary change cannot promote an empty snapshot;
- unchanged re-import does not duplicate content;
- current and previous snapshots survive configurable post-commit retention,
  and a forced retention failure cannot roll back promotion;
- explicit rollback atomically swaps current and previous;
- Cyrillic `Ђ/ђ` and Latin `Đ/đ` normalize to the same keys;
- parcel `/sub` separators and house-number separators survive normalization,
  while nonblank parcels rejected by the grammar are counted;
- ambiguous parents are recorded without an arbitrary parent choice.

The full artifact test is opt-in and never downloads from a live service. The
normal suite creates the same GPKG shape from
`src/test/resources/fixtures/address-registry/points.json` and runs against the
same PostGIS image as production.

Final clean regression command:

```bash
./gradlew clean test --no-daemon
```

Result: `BUILD SUCCESSFUL in 31s`; 63 tests passed and the one opt-in full
artifact test was skipped by design because the large-file environment variable
was absent. The full artifact test result above was executed separately against
the reviewed local GPKG.
