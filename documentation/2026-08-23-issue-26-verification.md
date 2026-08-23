# Issue #26 bounded GeoJSON API verification

Date: 2026-08-23

Scope: public WGS84 viewport request validation, one-query selected-location
projection, multi-property semantics, safe GeoJSON fields, filters, and bounded
response observability.

## Delivered contract

`MapAuctionController` exposes `GET /api/map/auctions` as default
`application/geo+json` and also honors strict `Accept: application/json`.
Responses are public-cacheable for 60 seconds and vary by `Accept`.
`MapAuctionRequestParser` is the single request-policy
boundary: it rejects missing/repeated/unknown parameters, malformed or wrapping
WGS84 boxes, boxes over 1,000,000 km², unsupported filter values, invalid local
dates, reversed date ranges, and limits outside `1..5000`. Every such failure is
a stable `400 application/problem+json` response with
`code=INVALID_MAP_REQUEST` and the normalized, 64-code-point-bounded failing
`field`. The parser is absent from the legacy `local-h2` profile together with
the rest of the PostGIS-only endpoint.

Dates in storage and the response are UTC instants. `from` and `to` are
`Europe/Belgrade` calendar dates; `to` advances to the next local start and is
exclusive so a requested day remains correct across DST. Without `from`, the
current instant is the lower bound because eAukcija status does not encode an
auction's ended lifecycle.

`MapAuctionRepository` performs one query that:

- starts with `geometry.canonical_geometry && viewport.bounds` and
  `ST_Intersects`;
- joins current successful resolution, property reference, and auction data;
- admits only `EXTRACTED` or `USER_CONFIRMED` references, excluding every other
  extraction state fail-closed;
- selects only the response fields, never descriptions or raw/candidate
  evidence;
- collapses a canonical property's duplicate references at its highest selected
  precision while retaining distinct properties as distinct stable features;
- applies date/status/kind filters, selects the winner, then applies precision,
  cross-environment stable hash ordering, and a hard SQL limit.

Map and single-auction location selection now share one SQL policy generated
from `LocationPrecision` declaration order. Ties use reference order, completed
time, and attempt UUID in that order, so the endpoints cannot drift through two
hand-written precision ladders or incompatible tie breakers. The coarse
resolver's non-downgrade comparison consumes the same generated rank. Winner
ordering uses `DESC NULLS LAST`, so a future database tier not yet known to the
running enum cannot outrank a known precision; the coarse comparison retains
its fail-closed SQL `NULL` behavior.

Publication and review visibility are separate contracts. The map applies the
extraction-state gate, while `/api/locations/{id}` keeps the best current
selection visible—including `NEEDS_REVIEW`, `INVALID`, and explicit `NONE`—and
returns `extractionStatus` plus a derived `publishable` flag. One explicit
allowlist drives both the SQL predicate and the Java flag.

The requested limit is sent to the database as `limit + 1`. That one private
sentinel is the complete truncation probe. The public response never exceeds
the requested `1..5000`; `numberReturned`, `limit`, and `truncated`, plus the
equivalent `X-Map-*` headers, make response count and partial-result state
observable.

`MapAuctionService` serializes only schema-allowed Point, Polygon, and
MultiPolygon shapes. Display fields are normalized, length-bounded, and
kept as plain JSON text; render-layer escaping remains the consumer's job. The
source link is derived from the numeric id under one fixed
eAukcija HTTPS origin. The response contains the stable numeric auction id,
safe title, source starting amount with `RSD`, UTC end time, source status,
source category kind, explicit precision, and link. Parser-version duplicate
counts are deliberately not exposed as a misleading number of properties.

## Acceptance coverage

| Test | Evidence |
|---|---|
| `MapAuctionRequestParserTest` | coordinate order/ranges/finite values, exact outer edges, non-wrapping order, maximum area, required bbox, allowlists, bounded unknown-field echo, repeated fields, `1..5000`, and DST-aware Belgrade date bounds |
| `MapAuctionControllerTest` | default GeoJSON plus strict JSON negotiation, 60-second public cache and `Vary`, complete fields, absence of description/payload fields, count/limit/truncation headers, and structured invalid-request JSON |
| `MapAuctionServiceTest` | plain-text injection preservation with control/format-character removal, fixed source link, point/polygon/multipolygon coordinates, and sentinel removal |
| `MapAuctionRepositoryIntegrationTest` | real HTTP/PostGIS behavior, bbox/date/status/kind/post-winner-precision filters, duplicate/multi-property handling, shared tie-breaks, map-only publication gates with review evidence retained by detail, stable replay, amount and source fields |
| `MapAuctionRepositoryUnitTest` | exactly one JDBC query for all feature fields, with bbox operators and SQL limit; no per-feature/N+1 lookup is possible |
| `LocationSelectionSqlTest` | every precision is generated from enum order, unknown tiers sort last, selectors share the same tie-break, and the map publication allowlist remains explicit |
| `SpatialResolutionSchemaIntegrationTest` | exact production map query under the default PostgreSQL planner over 20,000 geometries, 100,000 attempts, and 20,000 selections uses both the GiST geometry index and attempt-geometry index and has no sequential attempts scan |

## Reproducible verification

```bash
./gradlew clean test --no-daemon
git diff --check
```

The suite uses the digest-pinned PostgreSQL 18/PostGIS 3.6 Testcontainers image.
No test reaches eaukcija.sud.rs or any other live network data source.

## Latest local evidence

On 2026-08-23, `./gradlew clean test --no-daemon` completed successfully in 48
seconds: 166 tests discovered, 162 passed, zero failed/errors, and four explicit
opt-in full-data tests skipped because their local official artifacts were not
configured. The run included a real HTTP server backed by an isolated PostGIS
database for this endpoint. `git diff --check` also passed.
