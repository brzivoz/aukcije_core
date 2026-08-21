# Issue #13 — lawful RGZ parcel-geometry access decision

**Issue:** [#13](https://github.com/brzivoz/aukcije_core/issues/13)
**Decision date:** 2026-08-21
**Time box:** completed inside one working day
**Status:** **COMPLETE — outcome C**

## Decision

Select **C: view-only / unsupported / unconfirmed**.

The product must not call, scrape, proxy, cache, or redistribute RGZ/GeoSrbija
parcel geometry. Issue #21 must implement the explicit
`PARCEL_GEOMETRY_UNAVAILABLE` capability state and hand resolution to #23.
Address Registry points remain `ADDRESS`; KO, settlement, and municipality
centroids retain their own coarse precision. None may be labelled `PARCEL`.

This is a product integration decision, not a general legal opinion. It is the
mandatory fail-closed result of #13's time box: no official source inspected on
2026-08-21 grants this project automated parcel-geometry reuse, caching, or
redistribution rights.

## Why A and B were rejected

| Candidate | Evidence | Result |
|---|---|---|
| A — supported public parcel API | The guest portal exposes an undocumented application API and a WMS map proxy. RGZ documents public eKatastar as an interactive lookup and says extended registered access requires an RGZ contract. No supported parcel API contract, versioning policy, quota, or reuse license was found. | Rejected |
| B — licensed download/OGC source | The guest map renders a weekly cadastral parcel layer through WMS. Its capabilities advertise `Fees=none` and `AccessConstraints=none`, but provide no title, contact, license, reuse/redistribution terms, retention terms, or SLA. The official fee schedule separately lists paid WMS, WFS, REST, and vector-download access; its free-download exceptions name the Address Registry and other registries, not cadastral parcel geometry. | Rejected |
| C — unavailable with fallback | Public viewing is demonstrable; automated production reuse is not authorized by a reproducible official contract. | **Selected** |

`Fees=none` and `AccessConstraints=none` are service-capabilities fields, not a
license grant. The service host also identifies itself as `ogc-tmp`; its
availability and compatibility cannot be treated as a production contract.

## Sources inspected

All sources were inspected on 2026-08-21.

| Source | Reproducible finding |
|---|---|
| [RGZ public map](https://portal.rgz.gov.rs/rgz-portal/map) | Guest mode provides search, visible parcel cartography, an EPSG selector, and a login control. It describes the portal as a place to view spatial data and perform basic in-application GIS operations. It does not publish a reuse license in the inspected UI. |
| [RGZ GeoSrbija overview](https://www.rgz.gov.rs/%D0%B3%D0%B5%D0%BE-%D1%81%D1%80%D0%B1%D0%B8%D1%98%D0%B0) | Describes public access/insight into spatial data and parcel information, but does not grant automated extraction, caching, or redistribution. |
| [RGZ electronic-service terms](https://www.rgz.gov.rs/%D1%83%D1%81%D0%BB%D0%BE%D0%B2%D0%B8-%D0%BA%D0%BE%D1%80%D0%B8%D1%88%D1%9B%D0%B5%D1%9A%D0%B0-%D0%B5%D0%BB%D0%B5%D0%BA%D1%82%D1%80%D0%BE%D0%BD%D1%81%D0%BA%D0%B8%D1%85-%D1%81%D0%B5%D1%80%D0%B2%D0%B8%D1%81%D0%B0) | Requires lawful, non-disruptive use; prohibits unauthorized collection and protected-content transfer; records access identity/IP/time and documents cookie/analytics use. Published October 2023. |
| [RGZ eKatastar](https://www.rgz.gov.rs/%D0%B5-%D0%BA%D0%B0%D1%82%D0%B0%D1%81%D1%82%D0%B0%D1%80) | Public access supports parcel-number/address lookup. Extended registered access is only for users with an RGZ data-use contract. This is a web-application contract, not a supported API contract. |
| [Official administrative-fee schedule](https://www.rgz.gov.rs/content/Datoteke/Dokumenta/01%20Zakoni/%D0%97%D0%B0%D0%BA%D0%BE%D0%BD%20%D0%BE%20%D1%80%D0%B5%D0%BF%D1%83%D0%B1%D0%BB%D0%B8%D1%87%D0%BA%D0%B8%D0%BC%20%D0%B0%D0%B4%D0%BC%D0%B8%D0%BD%D0%B8%D1%81%D1%82%D1%80%D0%B0%D1%82%D0%B8%D0%B2%D0%BD%D0%B8%D0%BC%20%D1%82%D0%B0%D0%BA%D1%81%D0%B0%D0%BC%D0%B0%2043_2003...138_2022-%D0%A3%D0%A1%D0%9A%D0%9B%D0%90%D0%82%D0%95%D0%9D%D0%98%20%D0%94%D0%98%D0%9D%D0%90%D0%A0%D0%A1%D0%9A%D0%98%20%D0%98%D0%97%D0%9D%D0%9E%D0%A1%D0%98%20%D0%9E%D0%94%2001.07.2023.%20%28%D0%BA%D0%BE%D0%BD%D0%B0%D1%87%D0%BD%D0%BE%29.pdf) | Tariff 215i lists annual WMS/WFS/REST/download-service fees. Public cadastral viewing is fee-free, while the named free vector downloads include the Address Registry and do not include cadastral parcels. |
| [GKIS rulebook](https://www.rgz.gov.rs/content/docs/000/000/005/Pravilnik%20o%20geodetsko-katastarskom%20informacionom%20sistemu.pdf) | Defines WMS as georeferenced raster-image delivery and WFS as geographic features plus attributes. The guest parcel observation was WMS, not a documented WFS/download contract. |
| [RGZ open-data organization](https://data.gov.rs/sr/organizations/republichki-geodetski-zavod/) | Lists five RGZ datasets. The official Address Registry is present; a nationwide cadastral parcel-geometry dataset is not. |

## Redacted guest-mode observation

No authentication was performed. No cookies, browser storage, authorization
headers, session identifiers, tokens, personal data, raw feature responses, or
precise parcel requests were inspected or retained.

The committed fixture
[`guest-map-observation.json`](../spike/issue-13/fixtures/guest-map-observation.json)
records only these whitelisted facts:

- The guest map loaded with `Prijava` visible and displayed `EPSG:32634`.
- A guest search for `Dimitrovgrad` returned a cadastral-municipality result.
- At parcel-visible scale, the application requested WMS 1.3.0 `GetMap` for
  `dkp:dkp_parcels_weekly_only_utm` through the RGZ portal proxy.
- The response was observed only as a rendered `image/png` asset. The viewport
  bounding box is redacted and no raster body is committed.
- The nested service path was
  `https://ogc-tmp.geosrbija.rs/regdkp/ows`; no query credential was present.

The redacted capabilities fixture records HTTP 200, `text/xml`, response hash
`dd69b6564ccc04a2843098af2799504d3e29a4da3e28d840a31a14ac08c47722`,
WMS 1.3.0 update sequence `6441`, and the selected weekly parcel layer's
advertised CRS values (`EPSG:25834`, `CRS:84`). The 22,038-byte raw response was
used transiently and was not committed.

## CRS and identity proof

The portal UI's displayed/requested `EPSG:32634` is an observation, not the
weekly layer's source-CRS guarantee. The layer capabilities advertise
`EPSG:25834` and `CRS:84`; therefore any future licensed vector contract must
validate CRS from its own authoritative metadata for every source version.

Three KO + parcel samples from the completed #32 measurement are committed in
[`ko-parcel-samples.json`](../spike/issue-13/fixtures/ko-parcel-samples.json).
They prove the transformation path only; they do **not** claim that an RGZ
polygon was retrieved or matched.

| KO / parcel | WGS 84 (lon, lat) | EPSG:25834 (E, N) | EPSG:32634 (E, N) |
|---|---|---|---|
| DIMITROVGRAD / 1572 | `22.780484, 43.013322` | `645094.618, 4763832.342` | `645094.618, 4763832.342` |
| ČAJETINA / 4577/337 | `19.693799, 43.734697` | `394810.562, 4843235.894` | `394810.562, 4843235.894` |
| VOŽDOVAC / 7300/1 | `20.495631, 44.770078` | `460089.341, 4957533.212` | `460089.341, 4957533.213` |

`verify.py` recomputes both transforms with `pyproj==3.6.1`, checks the
committed coordinates within 1 cm, and proves round trips within `1e-9`
degrees. The near equality of ETRS89 / UTM 34N and WGS 84 / UTM 34N at these
samples is measured behavior, not permission to conflate their CRS identities.

## Operational, security, and legal boundary

- Production makes **zero** RGZ/GeoSrbija parcel-geometry requests.
- No RGZ browser endpoint, WMS URL, session, or token is a production
  dependency. There is no assumed rate limit, availability target, cache TTL,
  or retry contract.
- No observed RGZ imagery, feature attributes, or parcel geometry is persisted
  or redistributed.
- The capability state is versioned and auditable:
  `PARCEL_GEOMETRY_UNAVAILABLE / UNCONFIRMED_REUSE_AUTHORITY / 2026-08-21`.
- Existing #22/#23 Address Registry and coarse fallbacks remain deterministic,
  local, and precision-labelled.

Outcome C may be revisited only after written RGZ authority names the dataset
and license/contract and specifies permitted purposes, automated access method,
identity fields, geometry types, CRS, source versioning, quotas, availability,
caching/retention, redistribution, security, and support. A merely reachable
endpoint or changed capabilities document is insufficient.

## Exact downstream contract

The complete replacement body for #21 is committed at
[`downstream-issue-21.md`](../spike/issue-13/downstream-issue-21.md). It narrows
#21 to the selected unavailable-state wiring and replaces A/B-only fixtures
with outcome-C acceptance cases.

## Reproduction

```bash
python3 -m venv /tmp/aukcije-issue13-venv
/tmp/aukcije-issue13-venv/bin/pip install -r spike/issue-13/requirements.txt
/tmp/aukcije-issue13-venv/bin/python spike/issue-13/verify.py
```

Verification is offline. It performs no RGZ/GeoSrbija requests.

## Acceptance checklist

| Issue criterion | Result |
|---|---|
| Reproducible, reviewed decision; every assumption evidenced or C | **Met:** C selected; official sources and redacted fixture provenance recorded |
| Guest fixtures without auth, tokens, or personal data | **Met:** whitelisted observation/capabilities fixtures; verifier scans for sensitive fields |
| Sample KO + parcel cases and CRS transform proof | **Met:** three samples, two CRS transforms, round-trip checks |
| Operational/security/legal constraints | **Met:** zero-request boundary and reopening conditions are explicit |
| Exact #21 acceptance changes | **Met:** complete replacement issue body committed |
| Two-working-day time box | **Met:** decision completed on 2026-08-21; unconfirmed reuse authority automatically selected C |
