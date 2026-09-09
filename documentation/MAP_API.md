# Shared auction / map API

`GET /api/map/auctions` returns eligible winning property locations in a bounded
viewport of the **same filtered auction population as the table**. `GET
/api/auctions/view` is the browser's atomic table/map/count refresh endpoint.
Both require PostgreSQL/PostGIS, not the legacy `local-h2` profile.

The authoritative [shared-filter contract and user guide](SHARED_FILTERS.md)
documents every criterion, validation rule, URL field, compatibility alias,
search normalization, time boundary, and #28 extension point. There is no
independent map filter state or hidden `from=now` default.

## Request

```text
GET /api/map/auctions?bbox=18,41,24,47&category=Викендица&timeScope=ended&from=2026-08-28&to=2026-08-28&precision=CADASTRAL_MUNICIPALITY&limit=1000
```

Shared fields: `municipality`, `placeName`, `category`, `status`, `search`,
`minPrice`, `maxPrice`, `firstSale`, `precision`, `parcelSize`, `timeScope`, `from`, `to`,
`sortBy`, `sortDir`, `page`, `auction`. Sort/page never limit map membership.
`municipality` alone can repeat: `municipality=Ада&municipality=Чачак` matches
any selected municipality (case-insensitive), intersected with other criteria.
Names come from the bundled 168-name RGZ extract plus safe retained labels,
including municipalities with no auctions; blank/absent means no restriction.
All routes reject unknown/conflicting/invalid parameters and repetitions of
other fields with a field-specific problem. Raw category/status values come from supported legacy
seeds plus safe retained values, including `Викендица` and `Closed`.

Map-only transport fields:

| Parameter | Contract |
|---|---|
| `bbox` | Required WGS84 `minLon,minLat,maxLon,maxLat`, longitude first. Finite values in `[-180,180]` / `[-90,90]`; both axes increase, no antimeridian wrapping. Inclusive intersections. Maximum spherical rectangle area: 1,000,000 km². |
| `limit` | Integer 1..5000, default 1000. At most one extra private sentinel feature determines truncation. |

The browser calculates its responsive minimum zoom from the same area ceiling
with a safety margin, rechecks after resize and verifies bounds before fetch.
A valid viewport outside Serbia returns an empty collection.

### Time, legacy links and unknown dates

The visibly selected default `timeScope=not-ended` requires known `end_date >
asOf`; `ended` requires known `end_date <= asOf`; `all` adds no current-time
restriction and permits null end times. Neither `ended` nor `all` inherits a
hidden current-time lower bound. Raw source workflow status is independent.
Dates filter **end time**, intersect scope, and use inclusive Belgrade
start-of-day `from` / exclusive start-of-next-day `to`, with DST. Null end dates
fail explicit dates and the two known-time scopes; under `all` they may have
features with `endTime: null`.

`kind`/`mapKind`, `mapStatus`, `mapPrecision`, `mapFrom`, `mapTo` are compatibility
aliases only. Date-bearing legacy links without an explicit scope normalize to
`all`; links without dates/scope now default **both** views to `not-ended`.
Conflicting canonical/aliased values are errors. `asOf` is server response
metadata, not a bookmark parameter; every refresh evaluates relative time anew.

### Parcel size (#57)

`parcelSize=under-8|8-15|over-15` filters **individual whole cadastral parcel area
reported by RGZ**. Absent/blank means All sizes. Values are case-sensitive and
trimmed; unsupported/repeated values return `INVALID_MAP_REQUEST` with
`field: "parcelSize"` on all three shared routes, including `/`.

| Preset | Exact comparison (1 ar = 100 m²) |
|---|---|
| `under-8` | `area < 800 m²` |
| `8-15` | `800 m² <= area <= 1500 m²` |
| `over-15` | `area > 1500 m²` |

Fractional square metres are compared without rounding; both boundary values
belong to the middle band. Area is **not** floor area, building footprint,
ownership-share-adjusted area or the total of multiple parcels. Category remains
independent, including for house/apartment auctions. Text search remains literal,
so `search=< 8ar` is not a size expression.

An auction qualifies once if **any current canonical-property winner** matches.
Only matching properties are returned on the map and counted as viewport features;
duplicate references do not inflate counts, genuine parcels remain separate.
Combined size/precision predicates must match the **same winning property**.
Missing, invalid, non-positive or stale area is unknown, not zero. All sizes keeps
otherwise eligible unknown-area auctions/properties; bands exclude them. Coverage
is incomplete. `precision=NONE` plus a size band yields no auctions or features.
Selection explanations and limits use these same predicates; map panning never
restricts the global table.

Example (houses with any parcel above 15 ar, not houses above 1,500 m² floor area):

```text
GET /api/auctions/view?bbox=20.2,44.6,20.8,44.9&category=Кућа&parcelSize=over-15&timeScope=not-ended
```

## GeoJSON response

Default content type: `application/geo+json`; strict `Accept: application/json`
receives JSON with the same body. Coordinates are WGS84 longitude/latitude.

```json
{
  "type": "FeatureCollection",
  "features": [{
    "type": "Feature",
    "id": "179415:735977df20b23da84356ab104b49f2cb",
    "geometry": {"type": "Point", "coordinates": [20.457273, 44.787197]},
    "marker": {"type": "Point", "coordinates": [20.457273, 44.787197]},
    "properties": {
      "auctionId": 179415,
      "title": "Н179415",
      "amount": 125000.50,
      "currency": "RSD",
      "endTime": "2026-08-28T11:00:00Z",
      "sourceStatus": "InPrediction",
      "category": "Викендица",
      "propertyKind": "Викендица",
      "precision": "CADASTRAL_MUNICIPALITY",
      "detailUrl": "https://eaukcija.sud.rs/#/aukcije/179415"
    }
  }],
  "numberReturned": 1,
  "limit": 1000,
  "truncated": false,
  "asOf": "2026-08-30T12:00:00Z",
  "timeScope": "ended",
  "counts": {
    "filteredAuctionCount": 3,
    "unmappedAuctionCount": 1,
    "mappedAuctionCountInViewport": 1,
    "featureCountInViewport": 1
  },
  "returnedAuctionCount": 1,
  "selection": null
}
```

`category` is raw category, nullable when unknown (never inferred from prose or
generic property type). `propertyKind` is a deprecated JSON alias for it,
**not normalized property kind or sale scope**. `amount` is starting price in
RSD. End times display in Europe/Belgrade, with an explicit unknown fallback.

`geometry` is the unchanged canonical Point/Polygon/MultiPolygon. The additive
GeoJSON foreign member `marker` is a presentation-only Point on that geometry,
**not another feature/property**. For points it is identical. For polygons the
existing JTS library derives an interior point (never the envelope centre); if
that point is outside `bbox`, it derives one on the geometry's visible
intersection instead. A boundary vertex is the numerical fallback for extremely
narrow shapes. This also preserves inclusive edge touches and disjoint parts
without multiple pins per property. Marker coordinates can change with the
viewport; the canonical geometry and feature ID do not. Derivation runs only on
the already eligible, bounded returned rows, after winner/size/bbox/precision/limit
selection. No new query, geometry buffer, source acquisition or pin for NONE is
introduced. Markers, popup anchors and location links use the same on-geometry
coordinate; a parcel boundary is not necessarily an auctioned building footprint.

Counts precede map limits. Distinct mapped-auction count can differ from feature
count for genuine multi-property auctions; neither equals paginated table row
count. Outside-viewport auctions = filtered − unmapped − mapped-in-viewport.
`truncated` and `numberReturned` explain feature limiting; `returnedAuctionCount`
is the distinct auction count actually included. `precision=NONE` means no
publishable location, matches the table and produces an explained empty map.

With `auction` selected, `selection` has `auctionId` and `state`: `VISIBLE`,
`OUTSIDE_FILTERS`, `UNMAPPED`, `OUTSIDE_VIEWPORT`, `LIMIT`, or `NOT_FOUND`. The
browser keeps that selection and explains exclusions instead of clearing filters
or fabricating a pin. This is selection metadata, **not popup visibility**.
The browser retains the current property by the existing feature ID within the
page. `auction` is still the only shareable selection parameter; reload/history
restore it with details closed. #54 presents details in the results rail (or a
Map-only popup), with a compact reopen/exclusion control; this does not change
selection metadata. No open/dismissed or DOM state is sent to the API.
See the [details/focus contract](SHARED_FILTERS.md#selection-and-map-details-46).

Headers preserve the existing contract:

```text
X-Map-Feature-Count: 1
X-Map-Feature-Limit: 1000
X-Map-Truncated: false
Cache-Control: max-age=60, private
Vary: Accept
```

This permits private reuse only, never shared proxy caching of #41 geometry.
The interactive client uses no-store fetching and the combined endpoint below.

### Atomic browser refresh

`GET /api/auctions/view` accepts exactly the same query and returns:

```text
{ "map": <GeoJSON response>, "resultsHtml": <escaped Thymeleaf table fragment>,
  "query": <canonical query without bbox/limit>,
  "options": { "category": [...], "status": [...], "municipality": [...], "placeName": [...] },
  "catalogue": { "total": 123, "details": 120 } }
```

One repeatable-read transaction and one cutoff cover both projections, counts,
selection and options. The browser replaces results together, never the draft
form, and rejects stale/cancelled responses. The table has at most 25 hydrated
entities, fetched in bulk. Corresponding result/count/time-scope state refreshes
on panning, automatic local updates and source-refresh completion. This endpoint
and the page use `Cache-Control: no-store, private`.

## Publication, deduplication, spatial bounds and privacy

`AuctionFilterSql` supplies identical auction-level predicates. The shared
`PublishableLocationSql` relation includes only current `RESOLVED` attempts
with geometry on `EXTRACTED`/`USER_CONFIRMED` references, satisfying current #33
RGZ eligibility. A legacy fallback does not need source snapshots to publish.
Canonical parcel identities/non-parcel keys collapse duplicates; genuine
properties retain distinct stable IDs. Winners use enum precision rank, source
reference order, latest completion, then attempt UUID.

**Winners are chosen before size, bbox and precision filtering**, including when a
winning parcel is outside the viewport or fails the size band and a lower-tier
duplicate would match. A replacement with unknown area cannot borrow a losing
attempt's area.
Generic `STRUCTURED_LOCATION` centroids remain auction-level fallbacks, hidden
while an eligible parcel exists anywhere. Revoking the #33 premise makes a
retained fallback available again; filtering never fetches external geometry or
changes selection/history. A table precision predicate uses any eligible
canonical-property winner, not the evidence/detail endpoint's best attempt.
`AuctionFilterSql.propertyPredicate` supplies the same size/precision intersection
for membership, map features, counts, selection explanations and table tier labels.

Area comes only from the selected `RGZ_WFS_PARCEL` attempt's
`candidate_evidence.areaSquareMetres`: a JSON-number type guard followed by a
positive-value check projects exact PostgreSQL `numeric`, otherwise null. No
floating-point conversion or rounding is used. JSON strings, arrays, objects and
missing/null/non-positive values are unknown. Existing current extraction/KO
eligibility applies before publication; cache/history is not joined to find a
convenient matching area. Filtering makes no source requests or evidence writes.

Spatial reads keep `&&`, `ST_Intersects`, canonical SRID protections, stable
auction/property ordering and sentinel `LIMIT`. The winner CTE is not a global
materialized geometry load: GiST-driven bounded spatial reads and auction-local
competitor lookups remain possible. Aggregate counts do not hydrate the
catalogue or export it to the browser. V24 adds immutable Serbian search
functions, a `pg_trgm` GIN index and a lowercase-status index. V25 adds a
lowercase-municipality B-tree index for multi-selection. All values are bound
parameters and sort expressions are allowlisted with an ID tie-break.

#57 reuses JSON evidence without a generated/typed column or new index. The real
PostGIS plan fixture has 20,000 background geometries, 100,000 historical attempts
and 60 current sized RGZ properties. All three bands retain geometry GiST and
attempt-geometry index access for the map, auction-local winner lookups and a
`limit + 1` sentinel; table reads hydrate at most 25 auctions. Global aggregates
necessarily inspect current eligibility, not a bounded viewport, but export only
counts. `EXPLAIN (ANALYZE, BUFFERS)` evidence for map/table/global-count reads is
written to `build/reports/parcel-size-plans/`. This fixture's dominant global cost
is winner/eligibility lookup, not numeric JSON conversion; an area column/index is
not justified for v1. Re-evaluate with larger current populations before adding
one, without weakening winner-before-filter semantics. See
[#57 verification](2026-09-09-issue-57-verification.md).

GeoJSON never contains descriptions, raw reference/candidate evidence or source
payloads. Display strings have control/format characters removed, whitespace
normalized and length bounded. Render them with DOM `textContent`, not HTML.
`detailUrl` is derived only from numeric ID and the fixed eAukcija origin.
The combined endpoint's table fragment is the same escaped, same-origin
Thymeleaf markup as the initial table, not descriptions exported through GeoJSON.
`/api/locations/{id}` remains an evidence view: review/invalid/NONE attempts can
be inspected there without becoming publishable table/map locations.

## Map-data freshness

`GET /api/map/status` remains separate anonymous no-store metadata. It reads
the latest transactionally completed `coarse_location_resolution_runs` row,
never an in-progress/rolled-back population run. Fields include `available`,
`state`, `dataVersion`, `lastSuccessfulSync`, `stale`, `populationCount`,
`mappedAuctionCount`, `precisionSummary`, and `warning`. These are **pipeline
population statistics**, not shared-filter result counts.

The version combines resolver version, extract version and twelve checksum
characters; the timestamp is durable `finished_at`, not page-load time.
`map.data.stale-after` defaults to PT24H (`MAP_DATA_STALE_AFTER`). No successful
run means unavailable/stale, null version/time and `NO_SUCCESSFUL_MAP_SYNC`;
a stale success retains its timestamp and reports `MAP_DATA_STALE`. Internal
run/workflow UUIDs are not exposed by this metadata endpoint.

## Errors

```json
{
  "type": "about:blank",
  "title": "Invalid map request",
  "status": 400,
  "detail": "maxPrice must be at least minPrice (RSD)",
  "instance": "/api/auctions/view",
  "code": "INVALID_MAP_REQUEST",
  "field": "maxPrice"
}
```

The historical title/code are retained on all three routes for compatibility.
Content type is `application/problem+json`; field is normalized and capped at
64 Unicode code points. Browser 4xx states name the field and request correction;
network/5xx states offer retry. Both retain the last usable view and drafts.
