# Issue #41 — automatic RGZ parcel access

**Issue:** [#41](https://github.com/brzivoz/aukcije_core/issues/41)

**Activation policy superseded for the private `dev` POC:**
[2026-09-08 owner-directed automatic activation / stable first-observation policy](2026-09-08-decision-41-private-poc-auto-activation.md).
The historical explicit-pin policy below remains available for strict/production
configuration. The source, billing, privacy, rate and building caveats remain.

**Decision date:** 2026-09-03

**Amended:** 2026-09-08 — fresh WFS evidence, building-contract review reopened,
and parcel retry/cache corrections. The private parcel access scope is unchanged.

**Selected outcome:** `OWNER_AUTHORIZED_AUTOMATIC_PRIVATE_LOCAL_EXPLICIT_ACTIVATION`

Issue #41 records the access decision; the automatic runtime capability is the
implementation of #21. The owner explicitly directed the project to implement
fetching now and defer publisher billing and operator monitoring. Once an
operator explicitly activates RGZ with current source fingerprints, every
normal manual or scheduled refresh automatically attempts exact parcel geometry
resolution during enrichment. This is an operational project decision, not a
claim that RGZ granted machine-access, caching, or redistribution rights, and
not legal advice.

The automatic path is intentionally narrow: one private, local, non-commercial
installation; a current #33 `MATCHED` KO plus canonical parcel number; one named
parcel feature type; a private durable cache; no credentials, cookies, browser
session, personal fields, shared cache, redistribution, or building fetch.

## Published-source status

The source review still matters even though it is no longer an implementation
blocker:

1. The [RGZ electronic-service terms](https://www.rgz.gov.rs/uslovi-kori%C5%A1%C4%87enja-elektronskih-servisa)
   require lawful, non-disruptive use and allow access monitoring/blocking, but
   do not state an automatic WFS rate, cache right, or redistribution grant.
2. The [RGZ laws index](https://www.rgz.gov.rs/dokumenta-zakoni) links the
   consolidated administrative-fee law. Tariff 215i, with amounts marked from
   Official Gazette 55/2025, lists standard NIGP WFS at **228,010 RSD for 12
   months or 200,000 requests**. Billing/service agreement work is deferred.
3. The [GeoSrbija overview](https://www.rgz.gov.rs/geo-srbija) describes public
   access and viewing, not a machine-use licence.
4. The 2026-09-08 capabilities re-read confirms that `Fees` and
   `AccessConstraints` remain empty; that technical metadata supports neither
   automated access nor redistribution rights.
5. The 2026-09-03 timeouts are retained as historical evidence, not current
   availability or evidence that a building layer is absent. On 2026-09-08,
   capabilities, parcel schema, an exact parcel query, and the object schema
   returned HTTP 200. See the [parcel availability recheck](2026-09-08-rgz-availability-recheck.md)
   and the building disposition below. Runtime failures still fall through
   without damaging prior valid geometry.

Exact URLs, read dates, and outcomes are retained in
[`reviewed-sources.json`](../spike/issue-41/fixtures/reviewed-sources.json).

## Selected implementation contract and current-source caveat

The 2026-09-08 capabilities response reproduces the nine-feature-type inventory
and capabilities hash captured on 2026-08-21. Availability and schema metadata
alone do not establish a complete dataset/access contract:

| Feature type | Default CRS | Disposition |
|---|---|---|
| `dkp:katastarska_parcela` | `EPSG:32634` | Not selected; schema/currentness not validated |
| `dkp:objekat` | `EPSG:32634` | Schema available; KO/parcel join candidates identified; building contract review pending |
| `dkp:parcelparts_only_utm` | `EPSG:25834` | Not selected |
| `dkp:parcelparts_utm` | `EPSG:25834` | Not selected |
| `dkp:dkp_parcelparts_weekly_only_utm` | `EPSG:25834` | Not selected |
| `dkp:parcels_only_utm` | `EPSG:25834` | Not selected |
| `dkp:parcels_utm` | `EPSG:25834` | Not selected |
| `dkp:dkp_parcels_weekly_only_utm` | `EPSG:25834` | Implemented parcel-source candidate; activation requires current pins |
| `dkp:scales_utm` | `EPSG:25834` | Cartographic metadata, not geometry |

The implementation was built against retained WFS 2.0.0 update sequence `6441`, capabilities
SHA-256
`63d2e107b0073502c5363d53dd898beab9992246e10b48bf414a0007b2d575a5`,
and schema SHA-256
`8274f9672d6b0e244ce51113e693741cb55d558adbd2e4e40218ad5b0ba40cd6`.
The current parcel schema, re-read on 2026-09-08, is 3,309 bytes with SHA-256
`4de609f7f017295f2891d3e0219729e24f440dd7f89c4e8baa872bb3c06a4b48`.
Its identity/geometry fields remain `cadmun_code`, `parcel_num`, and `geom`.
The exact ČAJETINA (743968) / 4577/337 query returned one valid `MultiPolygon`.
Requests filter exact numeric `cadmun_code` and canonical `parcel_num`, request
`EPSG:4326`, and cap the response at two features so ambiguity is observable.
Only `Polygon` and `MultiPolygon` are accepted. These dated hashes are evidence,
not runtime defaults; update sequence `6441` is not a verified parcel-data edition.

## Building/object disposition — amended 2026-09-08

**Keep #42 open pending a complete building contract review.** The previous
not-feasible recommendation based on the failed schema re-read is withdrawn.
A timeout never established that the layer or a usable join did not exist.

At 06:30 UTC on 2026-09-08, the unauthenticated
[`dkp:objekat` DescribeFeatureType request](https://ogc-tmp.geosrbija.rs/regdkp/ows?service=WFS&version=2.0.0&request=DescribeFeatureType&typeNames=dkp%3Aobjekat)
returned HTTP 200, `application/gml+xml; version=3.2`, 3,024 bytes, SHA-256
`06d95eb06d4d4a5408e8156df22a43082101affaf3b2b5ca4a6d6e6df87f8658`.

| Field | Declared type | Current interpretation |
|---|---|---|
| `objectid` | `xsd:int` | Object identity candidate |
| `maticnibrojko` + `brparcele` | `xsd:int` + `xsd:string` | KO + parcel join candidates, not a verified join |
| `brdelaparc`, `deoparcele_id` | `xsd:int`, `xsd:string` | Parcel-part/disambiguation candidates |
| `wkb_geometry` | `gml:GeometryPropertyType` | Geometry field; actual footprint geometry type not yet observed |

Current capabilities declare default `EPSG:32634`. No building records were
fetched. The actual join, footprint geometry type, non-personal whitelist,
dataset identity, and building-specific access scope still need verification.
The parcel decision does not authorize building fetching, and schema availability
does not establish publisher permission. No building resolver is implemented;
object auctions retain an exact parcel boundary or an honest fallback meanwhile.

## Runtime and cache guardrails

The guarded defaults are fixed in `application.properties`:

| Setting | Value |
|---|---|
| `rgz.enabled` | `false`; the automatic capability requires explicit activation |
| Dataset/capabilities/schema pins | empty; all three are required when enabled |
| `rgz.requests-per-second` | `0.2` |
| `rgz.max-concurrency` | `1` |
| `rgz.max-logical-lookups-per-run` | `100` |
| Attempts | `3` maximum, including the first request |
| Retry conditions | transport failures and HTTP `429/502/503/504` |
| Backoff | `5s`, then `15s`; `Retry-After` capped at `60s` |
| Timeouts | connect `5s`, read `20s`, whole call `25s` |
| Body ceiling | `5,000,000` bytes |
| Kill switch | `data/control/rgz.disabled`, checked before every physical request |

Activation remains explicit: current service hashes are available, but a
publisher dataset edition has not been established. After obtaining a current
dataset identifier and checking the current GetCapabilities and parcel
DescribeFeatureType SHA-256 values, set:

```bash
export RGZ_ENABLED=true
export RGZ_DATASET_VERSION='<current publisher dataset identifier>'
export RGZ_CAPABILITIES_SHA256='<current GetCapabilities SHA-256>'
export RGZ_SCHEMA_SHA256='<current parcel DescribeFeatureType SHA-256>'
```

An enabled runtime with a missing or malformed pin fails startup instead of
silently treating the 2026-08-21 capture as current. This guard does not make
fetching manual: after startup, parcel fetching is automatic in `PARCEL_PATH`.

Resolution is cache-first. The logical identity is the feature type, publisher
dataset version, numeric KO code, and canonical parcel number. Before a miss can
make an HTTP request, a short transaction durably claims that identity for the
enrichment run and increments the run ceiling. That transaction commits before
network I/O; a separate short transaction persists the result. Consequently a
post-fetch persistence rollback cannot erase the lookup count or permit the
same identity to be fetched again in that run.

`RESOLVED`, `NOT_FOUND`, `AMBIGUOUS`, and validated identity/geometry-level
`INVALID` results are cached. Transport/server failures and protocol/envelope
failures—including wrong content type, oversized or malformed JSON, invalid
FeatureCollection structure, and missing or unexpected CRS—are `ERROR` and are
not cached. Ordinary enrichment discovery and backlog measurement include their
unhandled `ERROR` attempts independently of the final fallback work status.
Only errors for a current eligible #33 reference, configured feature type, and
dataset participate. A terminal cache-backed attempt retires that pending work;
a stale/non-matched KO does not keep retrying. Discovery resumes when the kill
switch is removed without needing a version change or explicit replay. Deferred
identities sort ahead of recently attempted failures so a per-run ceiling does
not permanently starve the remainder. Physical retries and run ceilings remain
bounded; finer-tier failures do not consume the generic stage-failure budget.

Migration V21 binds each logical identity to its first retained terminal cache
record in `rgz_parcel_cache_keys`. Historical duplicate metadata-version records
and their immutable evidence are preserved. Capability/schema hashes and
resolver versions are provenance, not additional cache identities. Reuse retains
the original fetch's source hashes and resolver version, even after metadata
pins change. Changing `rgz.dataset-version` still forces a new lookup.

Successful geometry is stored with provenance tying it to the exact current
#33 input fingerprint. If #33 later changes, a database trigger removes only
the stale current-selection pointer. Immutable request, cache, geometry, and
attempt evidence remains. Read paths repeat the current-#33 predicate as
defence in depth.

The client validates content type, bounded size, GeoJSON collection shape,
CRS, exact identity, finite Serbia bounds, positive area, coordinate nesting,
ring closure, and JTS validity. It persists only the fixed non-personal
allowlist and raw-response hash; unrecognized fields are discarded.

Create the kill-switch file to stop new network calls immediately:

```bash
mkdir -p data/control
touch data/control/rgz.disabled
```

Set `RGZ_ENABLED=false` for a configuration-level stop. Re-enable only after
removing the one kill-switch file and supplying the required current pins.
Cached records remain usable while fetching is disabled, including attachment
to another eligible reference to the same configured dataset/parcel. Dataset
identity remains required for cache lookup; current capability/schema pins are
required only for enabled networking. Disabled or killed misses consume no
network quota; existing cache and last-valid selections are not deleted.

The exact machine-readable contract is
[`automated-access-contract.json`](../spike/issue-41/fixtures/automated-access-contract.json).

## Fail-closed outcomes and downstream scope

- A missing/ambiguous/invalid parcel, remote error, exhausted ceiling, stale KO
  match, `STRUCTURED_ONLY` reconciliation, or engaged kill switch creates no new
  current parcel selection. Enrichment continues into #23 fallback tiers.
- A previous valid selection is preserved on a transient remote failure. It is
  made ineligible only when its exact #33 premise becomes stale.
- #21 owns the automatic parcel-resolution runtime; it is delivered alongside
  this gate because the owner explicitly required automatic fetching now.
- Monitoring/alerting remains for #30, as explicitly deferred by the owner.
- Publisher billing/service-agreement work is explicitly deferred.
- #42 remains open for building-contract review, not closed as not feasible.
  The fresh object schema establishes technical candidates only; no building
  feature type is authorized by this parcel decision. Object auctions can use
  an exact parcel boundary or #23 fallback.

## #21 implementation follow-up

The [#21 verification](2026-09-08-issue-21-verification.md) now covers the full
local WFS → ordinary refresh → rendered-polygon workflow and live kill-switch
visibility in #30's operator surface. V22 also guards selection writes against
KO changes during HTTP and current-KO deletion. This completes those runtime
items without changing this access decision, activating a live dataset, or
extending permission to buildings. See [parcel operations](RGZ_PARCEL_OPERATIONS.md).

## Reproduction

The decision verifier is offline:

```bash
python3 spike/issue-41/verify.py
python3 -O spike/issue-41/verify.py
```

The Java verification uses an isolated HTTP fixture and isolated PostGIS. It
does not depend on the live RGZ endpoint:

```bash
./gradlew test --tests 'rs.sud.eaukcija.rgz.*' --no-daemon
```
