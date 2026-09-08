# #41 amendment v4 — automatically enabled private POC

**Date:** 2026-09-08  
**Decision id:** `2026-09-08-issue-41-private-poc-v4`  
**Selected local mode:** `OWNER_AUTHORIZED_AUTOMATIC_PRIVATE_LOCAL_POC`

The owner explicitly requested: “Do everything needed for the rgz to be auto
enabled. Implement now and find best defaults … get visible results inside POC
App as soon as possible.” This supersedes only the manual activation/publisher-
edition prerequisite in the [previous #41 record](2026-09-03-decision-41-rgz-automatic-geometry-access.md)
for the single private local `dev` POC. It does not assert publisher consent,
waive fees, authorize redistribution, or extend access to building layers.
Published-source findings and deferred billing/service-agreement work remain.

## Fresh metadata observation

Unauthenticated, bounded requests with the documented User-Agent, five seconds
apart, returned:

| Source / exact URL | Read time (UTC) | Outcome |
|---|---|---|
| [GetCapabilities](https://ogc-tmp.geosrbija.rs/regdkp/ows?service=WFS&version=2.0.0&request=GetCapabilities) | 2026-09-08 10:07:58 | HTTP 200; 96,493 bytes; SHA-256 `63d2e107b0073502c5363d53dd898beab9992246e10b48bf414a0007b2d575a5` |
| [DescribeFeatureType](https://ogc-tmp.geosrbija.rs/regdkp/ows?service=WFS&version=2.0.0&request=DescribeFeatureType&typeNames=dkp%3Adkp_parcels_weekly_only_utm) | 2026-09-08 10:08:04 | HTTP 200; 3,309 bytes; SHA-256 `4de609f7f017295f2891d3e0219729e24f440dd7f89c4e8baa872bb3c06a4b48` |

Capabilities still expose update sequence `6441` and no parcel metadata URL or
publisher edition. The selected layer remains `dkp:dkp_parcels_weekly_only_utm`,
source CRS `EPSG:25834`; required schema fields remain `cadmun_code` (`xsd:int`),
`parcel_num` (`xsd:string`), `area` (`xsd:decimal`), and `geom`
(`gml:GeometryPropertyType`). These hashes document the observation, not embedded
runtime defaults. No authentication, cookies, sessions, or raw personal data
were used. The application discovers its own hashes before its first lookup.

## Honest cache-version policy

The POC defaults to **`private-local-first-observation-v1`**, explicitly labelled
`PRIVATE_FIRST_OBSERVATION`. This is an operator cache epoch, **not** a publisher
dataset edition, a weekly release, the current date, or a capabilities hash.
It never rotates automatically, including across application restarts or
metadata/resolver changes. Each terminal identity remains fetch-once in that
epoch. It can therefore become stale; this POC policy does not promise weekly
freshness. Any deliberate future epoch change is an operator data-refresh
decision, not a retry mechanism for cached negatives.

On a missing observed contract, the application makes at most two logical
metadata lookups (capabilities, then the selected parcel schema), with at most
three physical attempts each. It validates the advertised layer/CRS/JSON format
and required schema types with external XML access/DTDs/entities prohibited.
Only source-key, feature/epoch/policy, WFS/CRS, hashes, observation time, and
decision version enter V23's immutable `rgz_observed_source_contracts` table.
Raw XML, provider/contact records, response headers and future fields do not.

The source key includes endpoint, feature type, cache epoch and verifier
contract version. Restarts reuse the retained contract and terminal parcel
cache without metadata or parcel requests. A failure leaves parcel networking
unready, retains coarse/last-valid data and waits 15 minutes before automatic
retry. The kill switch applies to metadata and every physical parcel attempt.
Strict/manual pins remain supported; common/production defaults remain off.

## POC defaults and execution

Only `dev` defaults `rgz.enabled`, `rgz.auto-configure`, and
`rgz.warmup-enabled` to `true`; explicit `RGZ_ENABLED=false` always wins.
No prefilled hashes, credential, or per-parcel command is required. The shared
worker refines already-retained auctions in batches of 100, beginning about two
seconds after startup and checking work every 30 seconds. Unended auctions are
ordered first so useful map results appear early. Normal source-to-map refresh
and background enrichment share the existing worker/advisory lock and ledger;
background work yields to active refresh/sync and respects enrichment pause.
A foreground refresh waits for an already-running bounded background batch to
release the worker, with a maintained heartbeat and bounded wait, rather than
failing submission. Its active workflow prevents the next background batch.
An empty/complete population does not create endless empty runs. An outage
with no resolved parcel progress backs off for 15 minutes rather than spinning.

Unchanged #41 traffic limits: **0.2 requests/second**, **one concurrent call**,
**100 logical parcel misses/run**, **three attempts**, **5s/15s backoff**,
**60s Retry-After cap**, **5s/20s/25s timeouts**, **5,000,000-byte body limit**,
fixed User-Agent/contact, no redirects/hidden follow-ups. Metadata shares the
same physical gate and has its separate six-attempt bootstrap maximum. The
background population may span several bounded runs; it never raises the rate.

The POC binds to `127.0.0.1` by default, opens a Serbia-wide overview, and fits
the actual boundary when a parcel result is selected. The visible/idle POC map
re-reads its **local** viewport every 15 seconds, so committed polygons become
visible without reload or another refresh click. No
browser requests go to RGZ. Operator status exposes contract readiness, honest
cache-version policy/observation date, kill switch, and background state.

Use `RGZ_ENABLED=false` to opt out at startup, or create
`data/control/rgz.disabled` for an immediate no-restart stop of new requests.
No building footprint access is authorized. All exact identity, current-#33,
geometry, whitelist, immutable evidence and fallback guards remain in force.
