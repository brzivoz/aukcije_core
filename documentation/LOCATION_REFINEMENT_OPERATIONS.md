# Location refinement (#55 / #23)

Detailed descriptions are not coordinates. The pipeline now separates lexical
extraction, official KO reconciliation, external parcel validation, and local
Address Registry fallback. A successful enrichment run can still select a
centroid; it does not assert that the advertised property was precisely located.

## Behavior

- `property-reference-v3` retains v2's full-description handling: it stops KO names at property prose, retains UTF-16 evidence
  spans, recognizes spaced house suffixes, and binds explicit postfix KO/parcel
  groups and isolated property clauses. A repeated parcel in the short description
  can reuse one explicit association from the full description. No nearest-KO
  guess is made across ambiguous groups.
- V3 additionally recognizes land-use/parcel-title context for unlabelled parcel
  fractions. A bare number + exact structured place title is retained only as
  `NEEDS_REVIEW`; it cannot trigger RGZ or registry resolution. Proper ownership
  fractions, areas, cadastral-part/unit numbers and bare person/street names are
  not promoted. Newlines are actual clause boundaries, not Latin `n`/`r` letters.
  See the [59-auction audit](2026-09-09-no-reference-audit.md).
- Lexically extracted names remain raw. The existing #33 matcher compares
  official codes, so reviewed aliases can agree with structured metadata even
  when their strings differ. True identity conflicts remain `AMBIGUOUS`; unknown
  names stay unresolved. `STRUCTURED_ONLY` is not promoted into a parcel identity.
- The versioned alias source now supports `ORTHOGRAPHIC` reviews. Five explicit
  reviews cover the reported Velika Plana numbering/typo and numeric spellings of
  Novi Sad I, Kragujevac III and Zrenjanin I. Other typos/numerals are not guessed.
- After RGZ, the local fallback tries an exact registry KO/parcel identifier
  (`ADDRESS`, not a boundary), an exact KO/municipality/settlement/street/house
  point, then one official street identity (`STREET`). Competing exact-address
  points remain ambiguous. Missing municipality/settlement context is not
  discarded to broaden a match. A matched current KO is required.
- The registry snapshot is pinned for a run and protected against retirement
  during each transaction. Activation changes trigger normal work discovery.
  Precise registry selections share the current-#33 fingerprint and in-flight
  guards with RGZ. A standalone KO invalidation revokes the pointer, not history.
- Coarse markers are suppressed when a publishable parcel, address or street
  selection exists. Failed later lookups do not downgrade a retained valid result.

## Activate on an existing checkout

Code changes do not silently replace locally published dictionary artifacts or
import a 1 GB external dataset. Use the same database/profile/environment as the
managed application. In particular, retain its selected `AUKCIJE_DB_PORT` (the
local launcher may have selected 5433 rather than 5432).

1. Publish the reviewed aliases over the already approved centroid artifact:

   ```bash
   export ADDRESS_REGISTRY_KO_DICTIONARY_CENTROID_DIRECTORY="$PWD/data/address-registry-centroids"
   export ADDRESS_REGISTRY_KO_DICTIONARY_PUBLISH_DIRECTORY="$PWD/data/address-registry-ko-dictionary"
   export ADDRESS_REGISTRY_KO_DICTIONARY_ALIAS_OVERRIDES="$PWD/config/address-registry/ko-alias-overrides.json"
   ./gradlew buildKoDictionary
   ```

   The publisher validates target KO codes and preserves official/alias collisions.
   See [dictionary operations](KO_DICTIONARY_OPERATIONS.md) for source hash pins,
   magnitude gates and rollback. Do not lower validation gates just to activate.

2. Import a reviewed full Address Registry snapshot using
   [the existing #22 importer](ADDRESS_REGISTRY_OPERATIONS.md#import-a-reviewed-snapshot).
   A centroid artifact alone is not an address-point dataset. The import can use
   an already downloaded GPKG; no new download is necessary if its date/hash have
   already been reviewed. Keep RGZ and scheduled work disabled for this non-web
   import invocation:

   ```bash
   export SPRING_PROFILES_ACTIVE=dev
   export AUKCIJE_DB_PASSWORD="$(tr -d '\r\n' < .secrets/postgres-password)"
   export ADDRESS_REGISTRY_IMPORT_SOURCE_URI='file:///absolute/path/to/reviewed.gpkg'
   export ADDRESS_REGISTRY_IMPORT_SOURCE_DATE='<reviewed date>'
   export ADDRESS_REGISTRY_IMPORT_EXPECTED_SHA256='<reviewed GPKG sha256>'
   RGZ_ENABLED=false RGZ_AUTO_CONFIGURE=false RGZ_WARMUP_ENABLED=false \
     EAUKCIJA_REFRESH_SCHEDULE_CRON=- EAUKCIJA_ENRICHMENT_SCHEDULE_CRON=- \
     ./gradlew importAddressRegistry
   ```

3. Rebuild/restart the managed application with `./start.sh` after stopping its
   previous process as documented in the main README. Flyway applies V26–V28.
   Normal enrichment discovers the parser/resolver/dataset changes and reuses
   retained source snapshots and successful RGZ cache entries. You can trigger
   `/api/enrichment/runs` using the existing idempotent API; a source re-crawl or
   manually edited coordinate is not required. The usual pause, quota and kill
   switch controls still apply.

Until the full snapshot is imported, the API/UI explicitly say that address
resolution was unavailable, rather than claiming that the street did not exist.

## RGZ errors and bounded cache recovery

V6 adds a bounded native-CRS recovery path for geographic rounding failures;
see [the 181158 diagnosis](2026-09-09-native-crs-recovery.md). The native geometry
and its local WGS84 transform must both validate. The following v5 envelope fix
and cache-recheck safeguards remain in force.

`rgz-parcel-v5` validates the collection/count envelope before requiring CRS.
An internally consistent empty collection has no coordinates and may legitimately
omit/null CRS: it is `AUTHORITATIVE_NOT_FOUND`, not an indefinitely retrying
`INVALID_CRS`. Nonempty geometry still needs the reviewed explicit EPSG:4326
contract, exact KO/parcel identity, valid topology, positive area and Serbia bounds.
There is **no** automatic geometry repair or coordinate relabeling.

Cached `INVALID` results can be re-evaluated only with an explicit operator epoch:

```bash
export RGZ_INVALID_RESULT_RECHECK_VERSION='<new-reviewed-validator-fix-token>'
```

Use a new token after diagnosing/fixing the relevant validator behavior; do not
rotate it periodically to defeat caching. The default is empty/off. Each terminal
invalid result is evaluated once per requested epoch; transient network errors
retain bounded normal retry discovery. A successful/not-found/ambiguous cache
entry is never re-fetched merely because this token or the resolver changes.
Old records remain intact. V28 allows additional evaluation provenance and guards
cache-pointer replacement so a successful result cannot be overwritten by a
recheck. The shared per-run claim still prevents duplicate physical lookups;
other references defer and consume the eventual cached result in a later run.

## Diagnostics

- `GET /api/locations/{auctionId}/refinement`: no-store, current reference-level
  extraction/KO status, selected precision, last current-premise parcel/address
  reasons and timestamps, registry availability, processing status, and concise
  Serbian explanations. No descriptions, raw candidate payloads or transport
  secrets are exposed.
- `GET /api/operator/location-refinement`: loopback-only no-store snapshot of
  not-ended auction precision counts, separate processing status counts, declined
  reference-tier counts, and explicit current-parser evaluation status.
- Map/rail details fetch explanations without reopening dismissed details or
  moving focus. Selection/precision changes invalidate stale responses. The
  operator page shows the aggregate refinement evidence separately from workflow
  success.

## Reproducible offline coverage comparison

Export a private, fixed-as-of current input frame (this query is read-only):

```bash
mkdir -p tmp
# Use your actual Compose database/user, not credentials embedded in a URL.
docker compose exec -T db psql -qAt -U aukcije -d aukcije \
  -v ON_ERROR_STOP=1 -v as_of='2026-09-09T11:11:42Z' <<'SQL' > tmp/location-coverage-input.ndjson
BEGIN READ ONLY;
SELECT s.canonical_input::text
  FROM auctions a JOIN auction_enrichment_input_snapshots s
    ON s.auction_id=a.id AND s.snapshot_sha256=a.current_enrichment_snapshot_sha256
 WHERE a.end_date > :'as_of'::timestamptz
 ORDER BY a.id;
COMMIT;
SQL
./gradlew auditLocationCoverage \
  -PauditInput=tmp/location-coverage-input.ndjson \
  -PauditDictionary=data/address-registry-ko-dictionary \
  -PauditOutput=build/reports/location-coverage.json
```

The audit has no Spring context, database writes, or network client. It compares
frozen v1/v2 and current v3 against the **same** input frame and dictionary.
The v2 report schema uses `currentParserVersion` and `unresolvedCurrent` rather
than the old parser-specific `unresolvedV2` field. It pins
the normalized-LF frame SHA-256 and dictionary version and emits aggregate counts
plus an unresolved ledger of auction/snapshot/reference hashes and fixed status
codes, never descriptions. Keep the input file private/uncommitted.

These are pipeline-eligibility metrics, **not** independent precision/recall or a
claim that every eligible identity has available geometry. See
[#55 verification](2026-09-09-issue-55-verification.md) for measured results and the
remaining acceptance work.
