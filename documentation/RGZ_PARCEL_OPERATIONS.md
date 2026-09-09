# Automatic parcel geometry (#21)

## #55 validator/recovery update

`rgz-parcel-v5` classifies a consistent empty feature collection before checking
coordinate CRS; nonempty geometries retain the strict CRS/topology/identity
gates. `RGZ_INVALID_RESULT_RECHECK_VERSION` is an opt-in, bounded re-evaluation
epoch for cached `INVALID` outcomes only, with immutable history and V28 guarded
pointer replacement. Successful and authoritative not-found cache entries are
unchanged. See [location refinement operations](LOCATION_REFINEMENT_OPERATIONS.md)
for the exact activation/recovery procedure, registry fallback and diagnostics.

## Access and activation

The application implements the owner-directed [#41 private POC amendment](2026-09-08-decision-41-private-poc-auto-activation.md),
not an inferred permission from empty WFS `Fees`/`AccessConstraints`. Its scope
is a single private, local, non-commercial installation, without credentials,
cookies, browser sessions, redistribution, shared caches, or building fetching.
Publisher billing/service-agreement work remains deferred under that decision.

### Local POC (`dev`): automatic by default

Run `./start.sh` (or the existing `dev` run configuration). No manually supplied
RGZ version/hash values are needed. The POC binds to loopback by default and:

1. Fetches and verifies bounded GetCapabilities and parcel DescribeFeatureType
   metadata if no matching observed contract is already retained in PostGIS.
2. Stores only whitelisted hashes/contract fields in V23's immutable
   `rgz_observed_source_contracts`; restarts reuse that evidence without HTTP.
3. Automatically enriches retained auctions in batches of 100, prioritizing
   unended auctions. It shares the normal enrichment worker, yields to active
   refresh/sync, respects pause, and backs off 15 minutes on a no-progress outage.
   A normal refresh submitted during refinement waits for the current bounded
   batch to release the worker instead of failing executor submission; its active
   workflow prevents another background batch from taking the next slot.
4. Refreshes the visible, idle **local** map every 15 seconds as polygons arrive.
   The POC opens a Serbia-wide view; selecting a parcel zooms to its boundary.

The default cache version is **`private-local-first-observation-v1`**, labelled
`PRIVATE_FIRST_OBSERVATION`, not a publisher dataset edition. RGZ has not exposed
a verified edition in the retained review. This explicitly approved local epoch
never rotates automatically on dates, metadata changes, or restarts. Cached
geometry can become stale; the POC does not promise weekly refresh of boundaries.
Any future epoch change must be a deliberate operator data-refresh decision,
not a way to retry authoritative negatives.

A fresh empty installation still needs normal source ingestion (the existing
refresh action/schedule). Retained auctions need no new source download or
parcel-specific action. Only current #33 `MATCHED` references with numeric KO
and canonical parcel are candidates; `STRUCTURED_ONLY` never borrows #37's KO.

### Strict/production configuration: off by default

Common/production profiles retain opt-in activation and manual pins. Set
`RGZ_AUTO_CONFIGURE=false` and supply all four values to use that path:

```bash
export RGZ_ENABLED=true
export RGZ_AUTO_CONFIGURE=false
export RGZ_DATASET_VERSION='<operator-reviewed version>'
export RGZ_CAPABILITIES_SHA256='<reviewed GetCapabilities SHA-256>'
export RGZ_SCHEMA_SHA256='<reviewed parcel DescribeFeatureType SHA-256>'
```

Missing/malformed strict pins prevent enabled startup; incomplete manual pin
pairs are also rejected in automatic mode. Automatic metadata failure leaves
parcel networking unready and preserves fallback/cache serving instead of
crashing startup. `./start.sh` reads `.env`; direct `bootRun` requires exported
variables. An explicit `RGZ_ENABLED=false` disables automatic access in every
profile. Do not leave a copied old `.env` override set to false when expecting
POC auto-activation.

## Traffic contract

All settings bind as `rgz.*` properties, with `RGZ_*` environment overrides as
listed in `application.properties`. The #41 defaults are:

| Property | Default |
|---|---|
| `enabled` / `auto-configure` / `warmup-enabled` | `true` in `dev`; `false` otherwise |
| `warmup-batch-size` | `100` auctions (the separate parcel-miss ceiling still applies) |
| `auto-start-delay` / `auto-poll-interval` / `auto-retry-delay` | `PT2S` / `PT30S` / `PT15M` |
| `base-url` | `https://ogc-tmp.geosrbija.rs/regdkp/ows` |
| `feature-type` | `dkp:dkp_parcels_weekly_only_utm` (only authorized layer) |
| `requests-per-second` / `max-concurrency` | `0.2` / `1` |
| `max-logical-lookups-per-run` | `100` |
| `max-attempts` / `retry-delays` | `3` including the first / `PT5S,PT15S` |
| `max-retry-after` | `PT60S` |
| `connect-timeout` / `read-timeout` / `call-timeout` | `PT5S` / `PT20S` / `PT25S` per HTTP call |
| `max-response-bytes` | `5000000`, including streamed/decompressed bodies |
| `user-agent` | `aukcije-core/0.0.1` |
| `contact` | `https://github.com/brzivoz/aukcije_core/issues/41` |
| `kill-switch-path` | `data/control/rgz.disabled` |

The wire User-Agent is
`aukcije-core/0.0.1 (+https://github.com/brzivoz/aukcije_core/issues/41)`.
Retries apply only to transport failures and HTTP `429/502/503/504`; all wire
requests, including retries, pass the rate/concurrency/kill gates. Redirects
and HTTP-library automatic follow-ups are prohibited. The logical ceiling
allows at most 300 physical attempts per run at the defaults. Cache hits and
disabled/killed misses consume no network quota. Claims and run counters commit
before HTTP; no database transaction is held during rate waits or network I/O.
First-contract bootstrap has a separate maximum of two logical metadata lookups
(up to six physical attempts), sharing the same rate/concurrency/kill gate.
Background refinement spans bounded runs without increasing the rate.

Tests alone use `rgz.allow-http-loopback-test=true` with a local fixture server.
It cannot authorize HTTP to a non-loopback host. Production keeps HTTPS/TLS
validation and never reuses a browser session.

## Stop/resume without a restart

```bash
mkdir -p data/control
touch data/control/rgz.disabled  # stop new physical requests
# After investigating the reason for the stop:
rm data/control/rgz.disabled    # allow ordinary enrichment discovery again
```

Use the configured path if overridden. Existence, a dangling symlink, or an
indeterminate/inaccessible filesystem state fails closed. The check runs
before every physical request, including after waiting for a rate slot. An
already-started request can finish within its timeout; valid cached geometry
remains usable. Restarting the application does not remove the switch.

`/operator/status` displays **RGZ parcel access**. Its loopback-only,
`Cache-Control: no-store` API at `/api/operator/status` includes `rgz.state`,
`enabled`, `killSwitchEngaged`, `networkAllowed`, `datasetConfigured`, the
decision version, and traffic limits. It also includes `autoConfigure`,
`sourceContractReady`, `datasetVersion`, `datasetVersionPolicy`,
`sourceContractObservedAt`, `warmupEnabled`, and `warmupState`.
`AWAITING_SOURCE_CONTRACT` / `SOURCE_CONTRACT_UNAVAILABLE` explain a pending or
failed bootstrap; `ENABLED` means parcel networking is ready. It refreshes every 30 seconds (or via
**Refresh now**) without calling RGZ. The control state remains visible during
a database outage. Paths, endpoint/header values, credentials, and raw payloads
are not exposed. An intentional stop is informational, not a readiness failure:
cache and fallback serving remain available. Changing `RGZ_ENABLED` itself is
a startup configuration change, not the live control.

## Cache, evidence, and selection

- The durable key is feature type + explicit edition/local cache epoch + numeric KO + canonical
  parcel. `rgz_parcel_cache_keys` binds it to its first terminal cache record.
  Capability/schema/resolver-version changes do **not** cause refetches.
- Per the current #41 contract, `RESOLVED`, authoritative `NOT_FOUND`,
  `AMBIGUOUS`, and identity/geometry-level `INVALID` are terminal cache results.
  Unchanged terminal identities never refetch. HTTP/transport/envelope/CRS/size
  failures are retained as `ERROR` **attempts**, not authoritative negative
  cache results, and can retry in a later ordinary enrichment run. This is the
  explicit #41 exception to the issue's shorthand “fetch at most once”.
- Protocol errors, ceiling deferrals, and killed lookups continue into the
  existing #23/#38 fallback ladder, not a failed auction/run. Discovery resumes
  after the switch is removed and prioritizes quota deferrals to avoid starvation.
- Exact success requires one identity-matching feature, declared `EPSG:4326`,
  a nonempty valid positive-area `Polygon`/`MultiPolygon`, positive declared
  area, finite closed coordinate rings, and broad Serbia bounds (18–24°E,
  41–47°N). Declared multiple matches remain ambiguous even if truncated to one
  returned feature. No selecting the first feature, merging, or geometry repair.
- Only whitelisted identity, geometry, area, projection/scale metadata,
  retrieval time, WFS/version/decision/source hashes, and complete bounded-body
  SHA-256 are retained. Unrecognized feature, collection, and geometry members
  are discarded. HTTP headers, raw bodies, cookies, credentials, sessions, and
  personal fields are never stored or exported.
- Every attempt retains its reference id and exact contributing #33 fingerprint.
  V19 invalidates stale selections on standalone #33 updates. V22 additionally
  rejects late/stale selection writes and invalidates selections when a current
  KO pointer is deleted. Cache, geometry, and attempts are immutable evidence;
  only the current selection pointer is removed. All read paths recheck #33.
- A failed new dataset lookup does not downgrade or delete the last valid
  parcel. A revoked KO premise *does* make it ineligible immediately. Restored
  eligible identities can reuse their retained cache without another request.

The map API exports validated geometry with `precision=PARCEL`; registry points
remain `ADDRESS`, and centroid tiers remain explicitly coarse. A verified
parcel hides the generic auction-level structured centroid on the map, not
locations for other distinct properties. When #33 revokes that parcel, the
retained fallback is visible again without another enrichment run. Map responses
use private browser caching, never shared-proxy cache permission. #26/#27 can
distinguish every tier from the existing precision field.

## Verification

See the [#21 acceptance evidence](2026-09-08-issue-21-verification.md) and
[automatic POC activation verification](2026-09-08-rgz-auto-activation-verification.md). All source
and WFS traffic in Java/browser tests goes to loopback fixtures; no live RGZ
request or private captured boundary is required.

```bash
./gradlew check browserTest --no-daemon
python3 spike/issue-41/verify.py
```
