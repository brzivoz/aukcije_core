# RGZ parcel availability recheck — 2026-09-08

**Result: available from the current project connection.** The owner reported
being back in Serbia. Three sequential, unauthenticated HTTPS requests between
06:23 and 06:25 UTC succeeded, including an actual parcel-polygon lookup.
No retries were needed. This does not establish why the
[2026-09-03 checks](2026-09-03-decision-41-rgz-automatic-geometry-access.md)
timed out, or prove geographic access restrictions.

Endpoint: `https://ogc-tmp.geosrbija.rs/regdkp/ows`

| WFS 2.0.0 operation | HTTP | Total time | Response bytes |
|---|---:|---:|---:|
| `GetCapabilities` | 200 | 0.193 s | 96,493 |
| `DescribeFeatureType` — `dkp:dkp_parcels_weekly_only_utm` | 200 | 0.303 s | 3,309 |
| `GetFeature` — exact KO code + parcel | 200 | 0.502 s | 1,214 |

Requests used verified TLS, a contact-bearing `aukcije-core/0.0.1` User-Agent,
5-second connect / 25-second total timeouts, and a 5,000,000-byte response limit.
No credentials, cookies, or browser session were used.

## Polygon result

The query matched the application's numeric identity contract:

```text
typeNames=dkp:dkp_parcels_weekly_only_utm
cql_filter=cadmun_code=743968 AND parcel_num='4577/337'
outputFormat=application/json
srsName=EPSG:4326
count=2
```

- KO **ČAJETINA (743968)**, parcel **4577/337**: exactly one `MultiPolygon`,
  reported area **410 m²**, response CRS `urn:ogc:def:crs:EPSG::4326`.
- Server response timestamp: `2026-09-08T06:24:07.632Z`.
- Passed the existing `spike/issue-13/fetch_parcel.py` response sanitizer,
  explicit numeric KO and matched/returned-count checks, and Shapely topology
  validation. Geometry equals the private 2026-08-21 sample.
- Property-whitelisted private output (Git-ignored):
  `spike/issue-13/out/2026-09-08/cajetina-4577-337-5540556af3738788.geojson`.
- Raw response SHA-256:
  `ecc3a37d3eadac9b95254414e4e752c1780ca81f3d976f99eacf7465025881c8`.

## Fresh service fingerprints

- Capabilities SHA-256:
  `63d2e107b0073502c5363d53dd898beab9992246e10b48bf414a0007b2d575a5`
  — unchanged from 2026-08-21; WFS update sequence remains `6441`.
- Parcel schema SHA-256:
  `4de609f7f017295f2891d3e0219729e24f440dd7f89c4e8baa872bb3c06a4b48`
  — differs from the retained 2026-08-21 schema. The current schema still
  declares `cadmun_code` as `xsd:int`, `parcel_num` as `xsd:string`, and
  `geom` as `gml:GeometryPropertyType`.

No publisher dataset edition was established; the capabilities update sequence
alone is not evidence of a parcel-data version. This was a bounded manual
availability check, not an automatic enrichment run. Application settings,
activation pins, database state, and historical decision fixtures were left
unchanged. Raw XML/GeoJSON and polygon coordinates are not added to Git.
