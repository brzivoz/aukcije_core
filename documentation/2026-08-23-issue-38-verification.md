# Issue #38 verification — coarse auction locations

**Date:** 2026-08-23

**Issue:** [#38](https://github.com/brzivoz/aukcije_core/issues/38)

**Runtime:** Java 17; PostgreSQL 18/PostGIS 3.6; Flyway V8; active official
#36 centroid extract; post-#39 #14 dictionary and freshly rerun #37 population

**Outcome:** all 589 retained auctions received an honest selected tier:
587 KO centroids and two settlement centroids, with zero municipality or
`NONE` fallbacks in this population and no parcel/address/street claims

## Active source and current population

| Property | Value |
|---|---|
| Population | 589 auctions from the complete retained #32 corpus |
| #37 result | 587 `MATCHED`, 0 `AMBIGUOUS`, 2 `NOT_FOUND`, 0 `INVALID` |
| Reviewed municipality-alias KO selections | 21 |
| Extract version | `2026-08-22-ce983232d50cf445f0c71d45381e1d1d537450135b0b4be237c11c045229d3b3` |
| Official GPKG SHA-256 | `ce983232d50cf445f0c71d45381e1d1d537450135b0b4be237c11c045229d3b3` |
| Resolver | `structured-place-coarse-centroid` / `coarse-location-v1` |

The population proof runs #37 freshly before #38. It therefore consumes the
republished format-2 dictionary and the eight #39 municipality equivalences,
not the earlier 21-row ambiguity result. The resolver also checks that every
persisted #37 row and the active centroid extract trace to the same official
GPKG hash before writing anything.

## Measured tier distribution

| `LocationPrecision` | Count | Percent |
|---|---:|---:|
| `CADASTRAL_MUNICIPALITY` | 587 | 99.66% |
| `SETTLEMENT` | 2 | 0.34% |
| `MUNICIPALITY` | 0 | 0.00% |
| `NONE` | 0 | 0.00% |
| `PARCEL` / `ADDRESS` / `STREET` | 0 | 0.00% |
| Total | 589 | 100.00% |

All 21 rows that #39 resolved through reviewed municipality aliases reach the
KO tier and are counted separately in the run report. No suffix stripping or
per-KO workaround exists in #38.

The only two non-KO results are auctions `180244` and `180245`. Their #37 KO
status remains review-only `NOT_FOUND` for `БУНУШЕВЦЕ`, so the resolver does
not silently upgrade fuzzy KO candidate `711209`. Both fall through to
settlement `Врање` (official code `711306`) after #37's reviewed `Врање-град`
municipality context confirms the same official parent; the chosen centroid
carries `14,725` official member points. This distinguishes a defensible
settlement fallback from a guessed KO.

The first full resolver pass processed 589 rows in 942 ms. The immediate
identical replay processed zero, counted 589 unchanged, retained the same tier
distribution, and completed in 375 ms. The preceding fresh #37 pass processed
589 rows in 451 ms and reproduced 587/0/2/0.

## Persisted and failure-safe contract

Every auction owns a current `STRUCTURED_LOCATION` property reference and one
selected `RESOLVED` attempt in the #20 model. Resolved centroid attempts carry:

- exact coarse precision and rationale;
- selected official level/code/name and WGS84 point;
- the #36 extract version and official GPKG hash;
- full #37 status/method/rationale/candidate and dictionary evidence;
- the source fields and every tier considered; and
- the selected centroid's `member_point_count`.

Cache records are separate from append-only attempts. A source or version
change creates a new attempt and moves the current pointer, leaving old
evidence intact. A checksum or #37/#36 source-hash mismatch fails before
mutation. Integration tests also select a later `ADDRESS` attempt, rerun the
coarse resolver after a source change, and prove the address remains current
while the new coarse attempt remains in history.

## API and UI evidence

`GET /api/locations/{auctionId}` exposes both the machine enum and a stable
Serbian label, plus the explicit `coarse` flag, coordinate/member count,
rationale, resolver/source versions, and time. `NONE` is a real response with
null coordinates; `404` means no selected attempt exists.

The Thymeleaf list renders the same precision label. Coarse results have a
separate badge and the visible Serbian notice that the centroid is an
approximate area location, not an address, street, or parcel. Tests render the
template and exercise the JSON contract rather than checking label helpers
alone.

## Automated verification

Focused tests cover:

- KO-only, settlement-only, municipality-only, ambiguous-KO fallthrough, and
  no-match fixtures;
- Cyrillic/Latin and diacritic normalization through the shared
  `serbian-name-v1` implementation;
- duplicate settlement names constrained only by #37 municipality evidence;
- no output of `PARCEL`, `ADDRESS`, or `STREET`;
- active pointer, manifest/file checksum, per-row provenance, count, CRS,
  uniqueness, bounds, and member-count validation;
- PostGIS reference/cache/geometry/attempt/current-selection persistence;
- byte-identical input/version replay with no new attempt, cache, or geometry;
- selective source-field reprocessing and append-only history;
- preservation of a later higher-precision selection and of all state after a
  fail-closed upstream snapshot mismatch; and
- distinct API/UI presentation of every `LocationPrecision` value.

Reproducible commands:

```bash
./gradlew test \
  --tests 'rs.sud.eaukcija.coarselocation.*' \
  --tests 'rs.sud.eaukcija.controller.LocationControllerTest' \
  --tests 'rs.sud.eaukcija.controller.AuctionControllerLocationPresentationTest' \
  --tests 'rs.sud.eaukcija.spatial.LocationPrecisionPresentationTest' \
  --no-daemon

COARSE_LOCATION_FULL_CORPUS="$PWD/spike/issue-32/out/corpus.json" \
COARSE_LOCATION_FULL_DICTIONARY="$PWD/data/address-registry-ko-dictionary" \
COARSE_LOCATION_FULL_CENTROIDS="$PWD/data/address-registry-centroids" \
./gradlew test \
  --tests 'rs.sud.eaukcija.coarselocation.CoarseLocationCurrentPopulationTest' \
  --no-daemon
```

The focused suite and retained-population proof both completed successfully.
CI keeps the full clean suite and its test reports as the terminal publication
gate.
