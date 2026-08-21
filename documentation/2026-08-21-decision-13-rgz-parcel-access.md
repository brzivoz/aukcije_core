# Issue #13 — RGZ parcel-geometry access decision

**Issue:** [#13](https://github.com/brzivoz/aukcije_core/issues/13)

**Decision date:** 2026-08-21

**Reopened scope:** occasional private, non-commercial use

**Status:** **option B verified; live issue left open for owner review**

## Decision

Select **B: public OGC WFS to a private local GeoJSON artifact**, but only for
the owner-declared scope: a manually initiated, occasional, non-commercial
lookup of one known cadastral municipality (KO) and parcel number.

This is not approval for a commercial product, unattended enrichment,
scheduled access, bulk collection, a shared cache, or redistribution. It is
also not a general legal opinion. The earlier outcome C correctly failed closed
for a production/commercial integration; the owner subsequently narrowed the
actual use case and explicitly requested option A or B.

The repository now contains a one-shot command that makes one unauthenticated
WFS request, requests at most two features, validates exact identity/CRS/
geometry, drops unrecognized properties, and writes the geometry only to a
gitignored private directory. The application itself does not call RGZ.

## Why option B is technically available

The public GeoSrbija host publishes a standards-shaped WFS 2.0.0 contract at
[`regdkp/ows`](https://ogc-tmp.geosrbija.rs/regdkp/ows?service=WFS&version=2.0.0&request=GetCapabilities):

- `GetCapabilities` advertises `GetFeature`, JSON output, and the feature type
  `dkp:dkp_parcels_weekly_only_utm` without authentication.
- `DescribeFeatureType` exposes KO identity (`cadmun_code`,
  `cadmun_name_lat`), `parcel_num`, polygon geometry, status, area, projection,
  and scale fields.
- The feature type's default CRS is `EPSG:25834`. The command explicitly asks
  for `EPSG:4326` and rejects a response that does not declare that CRS.
- The capabilities fields `Fees` and `AccessConstraints` are empty. That is
  evidence that the public endpoint does not advertise a technical access
  restriction; it is **not** treated as a broad license or redistribution
  grant.

This selects the OGC half of option B. It does not use the portal's browser API,
WMS proxy, cookies, sessions, credentials, or personal records.

## Fixed private-use contract

| Concern | Contract |
|---|---|
| Invocation | Manual command only |
| Identity | Exact `cadmun_name_lat` + exact `parcel_num`; diacritics are significant |
| Network | One unauthenticated HTTPS request per invocation; no automatic retry |
| Result cap | `count=2`, so ambiguity is detectable without bulk retrieval |
| CRS | Request `EPSG:4326`; require the response CRS declaration |
| Geometry | Accept only non-empty `Polygon` or `MultiPolygon` inside broad Serbia bounds |
| Size/timeout | At most 5 MB; timeout is configurable from 0–60 seconds (20 default) |
| Properties | Fixed non-personal cadastral whitelist; future unknown fields are dropped |
| Retention | User-requested GeoJSON under `spike/issue-13/out/`, ignored by Git |
| Product behavior | No background/scheduled request and no shared/product cache |
| Redistribution | Outside this decision and prohibited by the repository contract |
| Fallback | Not found, ambiguity, network/schema/CRS error: write nothing and continue through #23 |

The machine-readable version is
[`private-wfs-contract.json`](../spike/issue-13/fixtures/private-wfs-contract.json).

## Live observations

All observations were made on 2026-08-21 without authentication. Raw XML and
raw server GeoJSON were transient. The committed
[`wfs-observations.json`](../spike/issue-13/fixtures/wfs-observations.json)
contains only whitelisted metadata and SHA-256 fingerprints.

| Case | Observed behavior |
|---|---|
| Success: DIMITROVGRAD / 1572 | One `Polygon`; KO code `713848`; area `406 m²` |
| Success: ČAJETINA / 4577/337 | One `MultiPolygon`; KO code `743968`; area `410 m²` |
| Success: VOŽDOVAC / 7300/1 | One `Polygon`; KO code `703621`; area `20,177 m²` |
| Not found | ASCII-folded `CAJETINA / 4577/337` returned zero; the exact WFS name is `ČAJETINA` |
| Ambiguous | A deliberately under-specified parcel-only query for `7300/1` matched 37 nationally and returned only the requested first two; the command never performs this query |
| Input error | Invalid KO/parcel syntax is rejected locally before network access with exit 2 |
| Remote/schema error | HTTP, timeout, media type, JSON, identity, CRS, area, or geometry failure exits 5 and writes nothing |

The three precise GeoJSON results are private working artifacts and are not
committed:

- `spike/issue-13/out/dimitrovgrad-1572.geojson`
- `spike/issue-13/out/cajetina-4577-337.geojson`
- `spike/issue-13/out/vozdovac-7300-1.geojson`

## CRS and identity proof

The three #32 Address Registry samples remain in
[`ko-parcel-samples.json`](../spike/issue-13/fixtures/ko-parcel-samples.json).
The WFS returned an exact KO + parcel identity for each sample. The local
command preserves Serbian Latin diacritics because ASCII folding changes WFS
identity semantics.

The service feature type advertises ETRS89 / UTM zone 34N (`EPSG:25834`), while
the portal UI had displayed/requested WGS 84 / UTM zone 34N (`EPSG:32634`).
`verify.py` recomputes both transforms from the samples with `pyproj==3.6.1`,
checks the committed coordinates within 1 cm, and proves round trips within
`1e-9` degrees. The lookup asks the server for WGS 84 longitude/latitude and
validates the returned CRS instead of assuming either projected CRS.

Only a feature that passes exact identity and geometry validation may be
labelled `PARCEL`. Address Registry points remain `ADDRESS`; KO, settlement,
and municipality centroids retain their coarse precision.

## Sources inspected

| Source | Reproducible finding |
|---|---|
| [Public WFS capabilities](https://ogc-tmp.geosrbija.rs/regdkp/ows?service=WFS&version=2.0.0&request=GetCapabilities) | WFS 2.0.0, public `GetFeature`, JSON output, parcel feature type, empty fees/access-constraints fields |
| [Public WFS schema](https://ogc-tmp.geosrbija.rs/regdkp/ows?service=WFS&version=2.0.0&request=DescribeFeatureType&typeNames=dkp%3Adkp_parcels_weekly_only_utm) | Exact KO/parcel fields and polygon geometry contract |
| [RGZ public map](https://portal.rgz.gov.rs/rgz-portal/map) | Guest map and parcel cartography; no authentication used |
| [RGZ GeoSrbija overview](https://www.rgz.gov.rs/%D0%B3%D0%B5%D0%BE-%D1%81%D1%80%D0%B1%D0%B8%D1%98%D0%B0) | Public access/insight into spatial and parcel data |
| [RGZ electronic-service terms](https://www.rgz.gov.rs/%D1%83%D1%81%D0%BB%D0%BE%D0%B2%D0%B8-%D0%BA%D0%BE%D1%80%D0%B8%D1%88%D1%9B%D0%B5%D1%9A%D0%B0-%D0%B5%D0%BB%D0%B5%D0%BA%D1%82%D1%80%D0%BE%D0%BD%D1%81%D0%BA%D0%B8%D1%85-%D1%81%D0%B5%D1%80%D0%B2%D0%B8%D1%81%D0%B0) | Requires lawful, non-disruptive use and constrains unauthorized collection/transfer |
| [RGZ eKatastar](https://www.rgz.gov.rs/%D0%B5-%D0%BA%D0%B0%D1%82%D0%B0%D1%81%D1%82%D0%B0%D1%80) | Public parcel-number/address lookup; registered extended access is contract-based |

## Reproduction

Install the pinned CRS dependency and verify all committed evidence offline:

```bash
python3 -m venv /tmp/aukcije-issue13-venv
/tmp/aukcije-issue13-venv/bin/pip install -r spike/issue-13/requirements.txt
/tmp/aukcije-issue13-venv/bin/python spike/issue-13/verify.py
```

Perform one owner-initiated private lookup:

```bash
python3 spike/issue-13/fetch_parcel.py --ko 'ČAJETINA' --parcel '4577/337'
```

The verifier is offline. The fetch command is the only networked component.

## Acceptance checklist

| Issue criterion | Result |
|---|---|
| Reproducible decision with evidence | **Met:** option B, public WFS capabilities/schema fingerprints, and fixed private-use contract |
| Redacted fixtures without credentials/personal data | **Met:** property-whitelisted metadata only; private GeoJSON is ignored |
| Sample KO + parcel cases and CRS proof | **Met:** three exact live matches and six offline transform checks |
| Success/not-found/ambiguous/error behavior | **Met:** observed and enforced fail-closed client behavior |
| Operational/security/legal constraints | **Met:** one-shot private-use boundary; no product automation or redistribution |
| Exact #21 acceptance changes | **Met:** replacement issue body committed and applied live |
| Two-working-day time box | **Met:** revised scope evaluated and implemented on 2026-08-21 |
