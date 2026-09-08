# Issue #21 — automatic RGZ parcel-resolution contract

## Outcome

During #29 enrichment, automatically resolve a current #33 `MATCHED` KO plus
canonical parcel number through the selected RGZ WFS parcel layer. The path is
private/local, cache-first, bounded, unauthenticated, and fail-closed.
The capability is disabled until an operator explicitly supplies current
source pins; once activated, no per-parcel user action is required.

## Fixed contract

- Feature type: `dkp:dkp_parcels_weekly_only_utm`, WFS 2.0.0.
- Identity: numeric `cadmun_code` plus canonical `parcel_num`.
- Request CRS: `EPSG:4326`; response count ceiling: `2`.
- Dataset key: operator-supplied current dataset identifier plus current
  capabilities and schema SHA-256 values. The retained 2026-08-21 update
  sequence `6441` and hashes are implementation evidence, not defaults.
- Eligibility: current extraction reference, parcel present, extraction status
  `EXTRACTED` or `USER_CONFIRMED`, current #33 status `MATCHED`, reconciliation
  not `STRUCTURED_ONLY`, and no conflicting user-reviewed KO.
- Automatic results are bound to the exact current #33 input fingerprint.

## Network guardrails

- `0.2` requests/second and concurrency `1` across physical attempts.
- `100` logical cache misses per enrichment run.
- Maximum three physical attempts; retry only transport failures and HTTP
  `429/502/503/504`; wait `5s`, then `15s`, honoring `Retry-After` up to `60s`.
- Connect/read/call timeouts are `5s`/`20s`/`25s`; maximum response is
  `5,000,000` bytes.
- Check `rgz.enabled` and `data/control/rgz.disabled` before every physical
  request. Never send credentials, cookies, browser state, or personal fields.
- Default `rgz.enabled=false`; enabling requires `RGZ_DATASET_VERSION`,
  `RGZ_CAPABILITIES_SHA256`, and `RGZ_SCHEMA_SHA256`, with startup rejection
  for missing or malformed values.

## Cache, validation, and invalidation

- Cache first by feature type + publisher dataset version + KO code + parcel.
  V21's `rgz_parcel_cache_keys` binds the logical identity independently of
  capabilities/schema hashes or resolver version; historical duplicates and
  original fetch provenance remain intact. Cache reuse remains enabled when
  outbound fetching is disabled and a dataset identity is configured.
  A durable per-run identity claim and ceiling reservation commit before HTTP;
  result persistence runs in a second short transaction.
- Cache `RESOLVED`, `NOT_FOUND`, `AMBIGUOUS`, and validated identity/geometry
  `INVALID` results. Do not cache transport, server, content-type, body-size,
  JSON/envelope, feature-shape, or CRS errors. A dataset-version change produces
  a new cache key; non-terminal failures retry only in a later run. Ordinary
  discovery includes unhandled ERROR evidence for current eligible references
  independently of a completed fallback status. Disabled/killed networking
  suspends retry discovery; removing the kill switch resumes it. Quota-deferred
  identities precede recently attempted failures to prevent starvation.
- Validate content type, response size, FeatureCollection shape, exactly one
  matching feature for success, declared CRS, exact identity, positive area,
  Polygon/MultiPolygon nesting, finite coordinates, Serbia bounds, closed rings,
  and geometry validity.
- Persist only allowlisted non-personal properties, the response hash, source
  feature id, decision/access mode, feature/dataset versions, schema/capability
  hashes, geometry, attempt, and exact #33 fingerprint.
- A standalone #33 change atomically deletes a stale current pointer but retains
  immutable cache, attempt, and geometry evidence. Every map/read selection
  repeats the current-#33 eligibility predicate.

## Fail-closed behavior

No result, ambiguity, invalid data, remote failure, request ceiling, kill switch,
or stale #33 premise may create a new current `PARCEL` result. Enrichment
continues to #23. A transient error does not erase a previously valid selection.

## Verification

- Isolated HTTP client tests cover exact requests, headers, redaction, Polygon and
  MultiPolygon, not-found, ambiguity, identity/CRS/geometry/content/size failures,
  retries, `Retry-After`, kill-switch interruption, and zero-call invalid input.
- Isolated PostGIS tests cover automatic persistence/selection, current #33
  provenance, cache replay with zero outbound calls, dataset-version refetch,
  standalone #33 conflict invalidation, request ceiling, negative cache, and
  parcel geometry reaching the map viewport.
- Test and local-H2 profiles keep real RGZ networking disabled; the dedicated
  isolated fixture opts in explicitly.

## Deferred scope

Publisher billing/service-agreement work and operator monitoring/alerting are
deferred by the owner. Building footprints remain outside this parcel contract.
#42 stays open: current schema join candidates are retained, but actual geometry,
join, whitelist, dataset, and building-access scope still need verification.
