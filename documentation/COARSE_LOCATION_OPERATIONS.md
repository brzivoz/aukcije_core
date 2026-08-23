# Coarse location resolution operations

Issue [#38](https://github.com/brzivoz/aukcije_core/issues/38) places every
auction at the best location its structured `Place` fields honestly support:

1. `CADASTRAL_MUNICIPALITY` from a `MATCHED` #37 KO code;
2. `SETTLEMENT` from an unambiguous normalized `Place.Name`;
3. `MUNICIPALITY` from reviewed #37 municipality context or an unambiguous
   normalized `Place.Municipality`; and
4. explicit `NONE` when no identity is safe to select.

The resolver never writes `PARCEL`, `ADDRESS`, or `STREET`. A centroid is an
administrative-area representative point, not evidence for an address or
parcel. The list UI repeats that qualification and the selected-location API
returns both the enum and a Serbian precision label.

## Prerequisites and source contract

Build/review the active #36 artifact and run #37 after the active #14
dictionary is published:

- [Centroid extract operations](CENTROID_EXTRACT_OPERATIONS.md)
- [KO dictionary operations](KO_DICTIONARY_OPERATIONS.md)
- [Structured KO matching operations](STRUCTURED_KO_MATCH_OPERATIONS.md)

The resolver accepts coordinates only from the active immutable #36 artifact.
It validates the `ACTIVE` pointer, manifest format/version, complete file list,
file sizes and SHA-256 hashes, per-row provenance, official-code uniqueness,
per-level counts, positive member-point counts, WGS84 CRS, and Serbia bounds
before database mutation.

Every retained #37 row must trace to the active centroid extract's GPKG hash.
A mismatch fails with `STRUCTURED_KO_SNAPSHOT_MISMATCH`; republish #14 from the
new #36 version and rerun #37. This prevents a KO selected from one official
snapshot from being joined to coordinates from another.

`AMBIGUOUS`, `NOT_FOUND`, `INVALID`, and missing #37 results are never promoted
to a KO. They fall through to settlement/municipality. Reviewed municipality
identity is consumed only from #37 candidate evidence. There is no local
`-град`/`Град` stripping, suffix table, synonym list, fuzzy auto-selection, or
network geocoder.

## Run the resolver

```bash
export SPRING_PROFILES_ACTIVE=dev
export AUKCIJE_DB_PASSWORD="$(tr -d '\r\n' < .secrets/postgres-password)"
export COARSE_LOCATION_CENTROID_DIRECTORY="$PWD/data/address-registry-centroids"
./gradlew resolveCoarseLocations
```

The task starts the real application without a web server, so Flyway,
PostGIS preflight, and Hibernate validation remain active. One PostgreSQL
advisory lock serializes the complete transaction.

The JSON result includes resolver/extract version and hash, duration,
population/processed/unchanged counts, the complete tier distribution, #37
status distribution, rationale distribution, and the number of KO selections
that used reviewed municipality-alias evidence.

## Persistence and idempotency

For every auction the service maintains one `STRUCTURED_LOCATION`
`property_references` row containing the current structured fields and selected
KO code, then writes through the canonical #20 model:

- `spatial_resolution_geometries`: exact WGS84 centroid point;
- `location_resolution_cache_records`: reusable result for identical inputs
  and versions;
- `location_resolution_attempts`: append-only tier, rationale, source feature,
  candidate evidence, source/version/hash, member-point count, and timing; and
- `current_location_resolutions`: current selection pointer.

`V8__coarse_location_resolution_runs.sql` retains every population report.
Candidate evidence includes all raw structured fields, #37 status/method/
rationale/candidates and dictionary provenance, every attempted tier, the
selected official code/name/coordinate, extract version/hash, resolver version,
and selected member-point count.

The fingerprint uses the exact three structured source fields, the effective
#37 result, the resolver version, and the extract version. Identical inputs and
versions create no new reference, cache, geometry, or attempt. A changed source
field or version appends a new attempt; it never updates or deletes history.

A later `PARCEL`, `ADDRESS`, or `STREET` selection can supersede a coarse
selection. A later coarse replay will not downgrade that higher tier. Artifact,
upstream-version, or database failure rolls back the complete run, leaving the
last valid selection intact.

Useful evidence queries:

```sql
SELECT location_precision, count(*)
FROM location_resolution_attempts attempt
JOIN current_location_resolutions current_resolution
  ON current_resolution.resolution_attempt_id = attempt.id
GROUP BY location_precision
ORDER BY location_precision;

SELECT reference.auction_id, attempt.location_precision,
       attempt.source_feature_id, attempt.member_point_count,
       attempt.confidence_reason, attempt.candidate_evidence
FROM property_references reference
JOIN current_location_resolutions current_resolution
  ON current_resolution.property_reference_id = reference.id
JOIN location_resolution_attempts attempt
  ON attempt.id = current_resolution.resolution_attempt_id
ORDER BY reference.auction_id;

SELECT finished_at, extract_version, population_count,
       processed_count, unchanged_count,
       cadastral_municipality_count, settlement_count,
       municipality_count, none_count,
       municipality_alias_ko_count,
       structured_ko_status_counts, rationale_counts
FROM coarse_location_resolution_runs
ORDER BY finished_at DESC;
```

## Consumer contract

`GET /api/locations/{auctionId}` returns the best selected result, including an
explicit `NONE` with null coordinates. The response carries `precision`,
`precisionLabelSr`, `coarse`, member-point count, rationale, resolver/version,
source version, and resolution time. It returns `404` only when no current
selection exists, not for explicit `NONE`.

The list UI shows the same precision label. Coarse values use a distinct badge
and a visible notice that an area centroid is not an address, street, or parcel.

## Reproduce the retained population proof

```bash
export COARSE_LOCATION_FULL_CORPUS="$PWD/spike/issue-32/out/corpus.json"
export COARSE_LOCATION_FULL_DICTIONARY="$PWD/data/address-registry-ko-dictionary"
export COARSE_LOCATION_FULL_CENTROIDS="$PWD/data/address-registry-centroids"
./gradlew test \
  --tests 'rs.sud.eaukcija.coarselocation.CoarseLocationCurrentPopulationTest' \
  --no-daemon
```

The opt-in test creates a disposable PostGIS database, loads the retained
589-auction corpus, freshly runs #37 against the post-#39 dictionary, runs #38
twice, and prints the first distribution, unchanged replay, and all non-KO
fallbacks. It never changes a developer database or contacts a live service.
