# Map GeoJSON API

`GET /api/map/auctions` returns only the selected, verified auction locations
needed to render the current map viewport. It is unavailable in the legacy
`local-h2` profile because that profile has no PostGIS contract.

## Request

```text
GET /api/map/auctions?bbox=18,41,24,47&status=Verified&kind=Парцела&precision=PARCEL&from=2026-08-23&to=2026-08-30&limit=1000
```

Only these query parameters are accepted; repeated or unknown parameters return
the structured `400` contract below.

| Parameter | Contract |
|---|---|
| `bbox` | Required WGS84 `minLon,minLat,maxLon,maxLat`. Longitude comes first. Values must be finite and in `[-180,180]` / `[-90,90]`; both axes must increase, so antimeridian-wrapping boxes are rejected. Boundary intersections are included. The spherical rectangle may not exceed 1,000,000 km². |
| `status` | Optional, case-insensitive allowlist: `InPrediction`, `Published`, `Verification`, `Verified`. The response retains the canonical source spelling. This is a source workflow status, not an ended/active flag. |
| `kind` | Optional exact eAukcija category allowlist: `Гаража`, `Грађевинско земљиште`, `Земљиште`, `Кућа`, `Локал`, `Непокретности`, `Објекат`, `Остали пословни објекат`, `Парцела`, `Пољопривредно земљиште`, `Стамбена зграда са више станова`, `Стамбени објекат`, `Шумско земљиште`. The always-generic source `PropertyType=ImmovableProperties` is deliberately not used. |
| `precision` | Optional, case-insensitive: `PARCEL`, `ADDRESS`, `STREET`, `CADASTRAL_MUNICIPALITY`, `SETTLEMENT`, or `MUNICIPALITY`. `NONE` has no geometry and is not a map filter value. This filters the winning tier after canonical-property deduplication: `precision=ADDRESS` does not return a property whose winner is `PARCEL`. |
| `from` / `to` | Optional ISO calendar dates (`YYYY-MM-DD`) in `Europe/Belgrade`. `from` is inclusive at local start of day; `to` includes the complete local day by using the next local start of day as an exclusive UTC boundary. DST days therefore remain honest 23/24/25-hour local days. |
| `limit` | Optional integer `1..5000`; default `1000`. The repository fetches at most one private sentinel row beyond it to determine whether the public response is truncated. |

When `from` is absent, its lower bound is the current instant. That is the
default “currently relevant” behavior: auctions whose `end_date` has passed are
not returned. eAukcija has no reliable closed status, so `status` is never used
as a substitute for that time rule. Supplying `from` is the explicit way to
request historical dates. The map form states this default beside the empty
`from` field; an empty control therefore never hides the active time rule.

The MapLibre client derives its minimum zoom from the rendered canvas size and
the same 1,000,000 km² ceiling, with a safety margin. It re-evaluates that
minimum after resize and verifies the actual spherical bbox before fetching.
This keeps a national zoom useful on both narrow and wide layouts without
sending a request the API must reject. The server ceiling remains authoritative.

PostgreSQL stores `end_date` as `TIMESTAMP WITH TIME ZONE`; the JSON value is an
ISO-8601 UTC instant. Map UI consumers display it in `Europe/Belgrade`.

## Response

The default content type is `application/geo+json`; a client sending strict
`Accept: application/json` receives `application/json` instead of `406`. The
body is identical for both representations and caches vary by `Accept`. Point,
Polygon, and MultiPolygon coordinates are WGS84 longitude/latitude.

```json
{
  "type": "FeatureCollection",
  "features": [
    {
      "type": "Feature",
      "id": "12345:735977df20b23da84356ab104b49f2cb",
      "geometry": {"type": "Point", "coordinates": [20.457273, 44.787197]},
      "properties": {
        "auctionId": 12345,
        "title": "Н12345",
        "amount": 125000.50,
        "currency": "RSD",
        "endTime": "2026-08-24T10:00:00Z",
        "sourceStatus": "Verified",
        "propertyKind": "Парцела",
        "precision": "CADASTRAL_MUNICIPALITY",
        "detailUrl": "https://eaukcija.sud.rs/#/aukcije/12345"
      }
    }
  ],
  "numberReturned": 1,
  "limit": 1000,
  "truncated": false
}
```

`amount` is the source starting price and `currency` is always `RSD`. A feature
never contains `short_description`, `description`, raw reference evidence,
resolver candidate evidence, or a source response payload. Display strings are
plain JSON text with control/Unicode-format characters removed, whitespace
normalized, and length bounded. They are not HTML entity encoded. Consumers
must render them through a text API such as DOM `textContent` or React text
interpolation, never through `innerHTML`. `detailUrl` is derived only from the numeric auction id beneath the fixed
`https://eaukcija.sud.rs/#/aukcije/` prefix; no source URL is accepted.

The response repeats result-size state in headers for clients and operators:

```text
X-Map-Feature-Count: 1
X-Map-Feature-Limit: 1000
X-Map-Truncated: false
Cache-Control: max-age=60, public
Vary: Accept
```

`numberReturned`/`X-Map-Feature-Count` make feature-count response size
observable. `truncated=true` means another matching feature exists and the
client should narrow the viewport or filters; the sentinel is never returned.
Normal HTTP access logs or client tooling can additionally record transferred
byte size without changing the public JSON contract.

The data is anonymous, read-only, and sourced from the public auction portal, so
responses permit browser/proxy reuse for 60 seconds. That bounds staleness for
an auction crossing its end time while avoiding another database query when a
map client revisits the same viewport. Query parameters remain part of the HTTP
cache key.

## Map-data version and freshness

`GET /api/map/status` is a separate anonymous, `Cache-Control: no-store`
metadata endpoint used by the map shell. It reads the newest transactionally
completed `coarse_location_resolution_runs` row; an in-progress or rolled-back
population run can never become the visible version.

```json
{
  "available": true,
  "state": "AVAILABLE",
  "dataVersion": "coarse-location-v1/centroids-2026-08/4a7d2c981de0",
  "lastSuccessfulSync": "2026-08-23T10:00:00Z",
  "stale": false,
  "populationCount": 589,
  "mappedAuctionCount": 587,
  "precisionSummary": {"ADDRESS": 240, "MUNICIPALITY": 347, "NONE": 2},
  "warning": null
}
```

The version combines the retained resolver version, centroid-extract version,
and a twelve-character prefix of the exact source checksum. The timestamp is
the successful run's durable `finished_at`, not page-load time or process-local
state. `mappedAuctionCount` is the population less explicit `NONE` results.
`map.data.stale-after` (default `PT24H`, environment override
`MAP_DATA_STALE_AFTER`) controls the stale boundary.
Internal coarse-resolution and refresh-workflow UUIDs are used by the
server-side readiness check but are not fields of this anonymous response.

If no completed run exists, the endpoint deliberately returns
`available=false`, `stale=true`, null version/timestamp, and
`warning=NO_SUCCESSFUL_MAP_SYNC`. The UI renders that warning and never turns
missing freshness into an apparently current dataset. A stale successful run
uses `warning=MAP_DATA_STALE` while retaining its exact version and timestamp.

## Property and resolution semantics

The database owns one current selected attempt per property reference. Only
current `RESOLVED` attempts with geometry whose extraction status is
`EXTRACTED` or `USER_CONFIRMED` can enter the public map; all other extraction
states fail closed. References
that share a canonical parcel identity, or the same canonical non-parcel key,
collapse to one feature; the highest precision among those selected results is
used. Parser-version duplicates do not create another feature and are not
reported as a property count. Distinct canonical properties remain distinct
features with stable feature ids even when
they belong to the same auction or share a coarse representative point. The
numeric `auctionId` is stable across all of that auction's features.

The map and `/api/locations/{id}` selectors share one enum-generated order:
strongest declared `LocationPrecision`, then lowest source reference order,
then latest completed attempt, then attempt UUID. This prevents the two public
contracts from choosing different results when one canonical property has tied
references. An unrecognized precision sorts last in both winner selectors;
the coarse resolver's non-downgrade comparison retains SQL `NULL` semantics and
therefore cannot promote an unrecognized tier. Adding or reordering a precision
in the enum changes both SQL selectors and that comparison together.

The detail endpoint is intentionally an evidence view, not a publication gate.
It keeps current `NEEDS_REVIEW`, `INVALID`, and explicit `NONE` selections
visible and returns both `extractionStatus` and `publishable`. The map alone
enforces `publishable=true`, so review workflows retain the candidate evidence
without exposing it as a public map feature.

The repository performs this join, deduplication, filtering, ordering, and field
projection in one SQL statement. It always requires a bounded bbox, applies
both the PostGIS `&&` operator and `ST_Intersects`, orders by stable auction and
MD5 property keys (rather than environment-specific text collation), and applies
`LIMIT`. No entity hydration or per-feature lookup is
performed.

## Errors

Invalid requests return `application/problem+json`:

```json
{
  "type": "about:blank",
  "title": "Invalid map request",
  "status": 400,
  "detail": "minLongitude must be less than maxLongitude",
  "instance": "/api/map/auctions",
  "code": "INVALID_MAP_REQUEST",
  "field": "bbox"
}
```

The browser client preserves and displays `field` plus `detail` for a `4xx`
response and asks the user to change the view or filter. It reserves the retry
instruction for network/`5xx` failures, so a deterministic invalid request is
not presented as a transient outage.

The `field` property identifies the missing, repeated, unknown, or invalid
parameter and is normalized and capped at 64 Unicode code points before being
echoed. A valid viewport outside Serbia simply returns an empty
`FeatureCollection`.
