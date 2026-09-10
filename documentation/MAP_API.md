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
`sortBy`, `sortDir`, `page`, `auction`, `since`, `sinceAt`, `publication`,
`changeKind`, `liveBidding`. Native civil-time input adapters are `sinceLocal`
and `sinceOffset`. Sort/page never limit map membership.
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

## Changes since and reviewed revisions (#56)

All three GET routes (`/`, `/api/auctions/view`, `/api/map/auctions`) use the
same server predicate: **current membership intersected with activity**. This is
not historical price/category/search membership or geometry replay. End-date
scope remains independent; recently changed does not mean not-ended or bidding-open.

| Parameter | Contract |
|---|---|
| `since` | Blank/absent = Any time; `24h`, `7d`, `date`, `publication`. Personal names `previous`/`checkpoint` are rejected server-side. |
| `sinceAt` | Required ISO instant for `date`/`publication`, years 0001–9998; canonical UTC. For publication comparisons this is the displayed **server evaluation time**, not acknowledgement time. |
| `sinceLocal` | Native `datetime-local` civil input in Europe/Belgrade for `since=date`. Overrides the hidden prior `sinceAt` draft. Successful native GET redirects to the canonical UTC URL. |
| `sinceOffset` | Explicit `+02:00` / `+01:00` selection when a civil time overlaps (encode `+` as `%2B`). A gap or a wrong offset is a field-specific 400, never guessed. |
| `publication` | Required with `since=publication`: `lineageUUID:sequence:runUUID`; `lineageUUID:0:origin` explicitly means the start of ordered history, not pre-history coverage. |
| `changeKind` | Blank/absent = New and updated; `new` / `updated`. Buckets are disjoint. |
| `liveBidding` | Blank/false = excluded; `true` includes LIVE_BIDDING_ONLY separately labelled price ticks. Starting price is substantive by default. |

Relative presets subtract exactly 86,400 / 604,800 seconds from the **one** server
evaluation instant for the shared refresh, not Belgrade calendar days. They remain
relative in bookmarks; fixed comparisons carry a UTC instant and, for personal
baselines resolved before submission, a non-personal publication coordinate.
No local-storage key or reviewed-ID collection is accepted in search URLs.
Publication order and evaluation are independent coordinates: a committed
publication can be captured just after `asOf` was selected. It is not rejected
merely because its recorded time is later than that evaluation; future validation
checks committed reference availability/order and lower evaluation ≤ upper evaluation.

`NEW` requires #11's first reliable successful local observation in `(lower,
upper]`, not the source's publication date. Known legacy baseline establishment
is not NEW. An identity discovered in the interval remains NEW even after updates.
`UPDATED` requires a known baseline and intervening SUBSTANTIVE activity (or
explicit live-price opt-in). Monday's update survives Tuesday's unchanged fetch;
A → B → A remains activity and is explained as reverted when comparable final
values equal the baseline. Representation-only, baseline/policy maintenance,
parser/resolver/dataset changes and improved map locations are not substantive
source-update badges. Global filtering does not treat an elapsed end as a source
edit; the explicit reviewed view explains effective-time/lifecycle activity.

Each map response adds `comparison` (null for Any time): mode, kind, live opt-in,
resolved lower reference/time, `problem`, localized `notice`, and disjoint
`newCount`/`updatedCount` **within applied current criteria and bucket selection**.
An unavailable comparison returns **normal current results**, a stable problem
code and null change counts, never a misleading zero-change result. Invalid
syntax is a 400. Unknown run/sequence, foreign lineage, future/evaluation-reversed
boundaries and dates before the earliest supported publication are not moved to
latest; the notice instructs choosing a supported date or removing the criterion.
`PARTIAL_PRE_HISTORY` remains visible; explicit origin never turns legacy identities
into NEW. Full restores retain compatible references; a restored branch reusing a
sequence but not its run UUID is unavailable. `incompleteEvidence` separately
warns about baseline/policy-maintenance gaps within an otherwise valid interval;
qualifying counts are not presented as proof of zero source activity in those gaps.

`evidence` is keyed by the bounded returned auction IDs and contains exact
`review` references (auction ID, revision coordinate, displayed evaluation time,
comparison policy), textual badge, safe field codes/reasons, reverted activity,
separate substantive/live/lifecycle counts, end-time evidence and coverage.
Values are **not** source snapshot exports: descriptions/executor text are never
returned here. The UI honestly gives field names/unknown evidence rather than
inventing before/after values. Already-public lifecycle end instants can be shown
as the **last recorded** before → after deadline, not an inferred net description
change. HTML remains the same escaped table projection.
History probes are indexed and batched at at most 200 identities; no per-card
history calls or browser catalogue download. V30 adds sparse meaningful/NEW and comparison-gap indexes.

### Initializing comparison history on an existing catalogue

Applying V29/V30 does not create ordered publications for old syncs. Until the
first fully successful source refresh, an existing catalogue can legitimately
have `sourceFrame.publication.sequence=0` with no earliest publication. The UI
instructs running **Освежи све податке**; reloading/panning the map only reads data
and cannot initialize source history. No source fetch is triggered by selecting
a comparison filter.

After that successful refresh, capture **Моја тачка** to compare subsequent
changes immediately. The full Last 7 days preset still requires a supported
publication at or before its seven-day lower boundary (Last 24 hours similarly
requires a day-old boundary). Refreshing again cannot reconstruct earlier
history, and the application never silently shortens the selected period. A
retained legacy lifecycle without an observed source revision is unavailable for
Mark reviewed; it must not create an acknowledgement with a missing policy.

### Read-only review batch

```text
POST /api/auctions/reviews
Content-Type: application/json
{"frame": <displayed sourceFrame>, "reviews": [<stored exact Review>, ...], "liveBidding": false}
```

This is a read, **not a mutation**, with `Cache-Control: no-store, private`.
Maximum 200 distinct positive identities and a 96 KiB body, bounded *before* JSON
parsing. Invalid shapes/duplicates/limits return 400. The response echoes the
validated displayed frame and returns `evidence`, including `reviewState`:
`UNCHANGED`, `CHANGED` or `UNAVAILABLE` for each submitted identity. Unreviewed
identities are not requested/included. Unknown auction/revision, unsupported
policy, foreign lineage, future lower bounds or missing retained evidence yield
per-identity UNAVAILABLE; an invalid upper frame rejects the batch. A historical
upper reference is supported for **evidence only**. Current catalogue hydration
is never combined with that older reference.

The batch compares each identity's own baseline, including ended and no-longer-
matching auctions. The browser explicitly labels this relaxed scope and renders
retained identity/reasons without inventing availability or geometry. Elapsed
end time uses the displayed evaluation instant even with no new publication;
a later audit of an already acknowledged effective end is not another unseen
transition. Absence/reopening evidence remains distinct from legal source status.

The atomic view captures table/map/count/evidence and `sourceFrame` in one
REPEATABLE_READ transaction. The browser verifies the table and map frames before
accepting them; failures retain the previous coherent view and do not advance
checkpoints or infer that the user is caught up. A submitted checkpoint/action
uses the displayed reference, never an in-flight refresh or status poll.
See [browser storage/reset semantics](BROWSER_AND_FRONTEND.md#comparisons-and-local-review-state-56).

## GeoJSON response

The response also carries `sourceFrame`: the lineage/sequence/run reference for
committed source data, its recorded publication time, earliest supported boundary,
coverage, and the same `evaluatedAt` as `asOf`. It is captured with the displayed
rows inside one repeatable-read transaction, not by racing a separate latest-status
request. It is not `mapDataVersion` and does not promise historical geometry replay.
See the [read-only history contract](SOURCE_CHANGE_HISTORY_OPERATIONS.md).

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
