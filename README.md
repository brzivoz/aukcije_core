# aukcije_core

GIS enrichment for Serbian judicial real-estate auctions from
[eaukcija.sud.rs](https://eaukcija.sud.rs).

The official portal lists immovable property but offers no filtering by location,
price or property attributes, and no map. This project ingests the listings,
resolves each advertisement to a cadastral parcel or an address, and renders the
result on a locally hosted map of Serbia.

## Status

The ingest layer is a working proof of concept ported from an earlier prototype.
The GIS layers are planned — see the [epics](../../issues?q=is%3Aissue+label%3Aepic).

| Layer | State |
|---|---|
| eAukcija ingest (listings + details) | working |
| Local filtering / list UI | working |
| Property reference extraction | planned (EPIC-02) |
| Parcel + address resolution | planned (EPIC-03, EPIC-04) |
| PostGIS store | planned (EPIC-05) |
| Basemap + map UI | planned (EPIC-06, EPIC-07) |

## Running

```bash
./gradlew bootRun
```

Serves on <http://localhost:8081>.

1. **Преузми листинге** — fetches all listing pages.
2. **Преузми детаље** — fetches per-auction details (location, category, description).
3. Filter and sort locally.

Data persists to `./data/aukcije` (H2 file DB), so syncing is a one-time step.

### Sync API

```
POST /api/sync/listings   start listings sync
POST /api/sync/details    start details sync
GET  /api/sync/status     sync progress
```

H2 console: <http://localhost:8081/h2-console> — JDBC URL `jdbc:h2:file:./data/aukcije`, user `sa`, empty password.

## Tests

Everything runs from one command:

```bash
./gradlew clean test
```

**Prerequisite: a running Docker daemon.** The integration tests start a real
`postgis/postgis:18-3.6` container through Testcontainers — the same image
EPIC-05 targets — rather than substituting H2. Without Docker the integration
tests fail rather than silently skipping.

No test touches a live network. eaukcija.sud.rs responses are served from
`src/test/resources/fixtures/eaukcija/` through `MockRestServiceServer`.

| Suite | Covers |
|---|---|
| `EAukcijaClientTest` | request shape, listing/detail parsing, empty page, API error envelope, transport failure, malformed body |
| `PostgisSchemaIntegrationTest` | Flyway migrating an empty database, Hibernate starting with `ddl-auto=validate`, entity round-trip |
| `SchemaNegativeControlTest` | context must fail on an invalid migration, on a database without PostGIS, and on a schema that drifted from the entity |
| `CrsTransformIntegrationTest` | EPSG:4326 → 25834/32634 through PostGIS, cross-checked against the pyproj values proven in issue #13 |
| `SpatialQueryIntegrationTest` | bbox filtering incl. boundary inclusion, metre-based distance ordering |

`SpatialQueryIntegrationTest` deliberately asserts query *results* only. Its
fixture builds its own scratch table, so asserting that table's SRID or index
would just be reading back its own DDL. Schema and index assertions belong to
#20, against the real migrated schema.

Reports land in `build/reports/tests/test/index.html`. CI publishes them as the
`test-reports` artifact when a run fails.

### Migrations

`src/main/resources/db/migration/` is PostgreSQL/PostGIS flavoured and is
applied by the `postgis` test profile. The application itself still runs on H2
with `spring.flyway.enabled=false` until EPIC-05 (#15) moves it across.

### If Testcontainers cannot find Docker

Testcontainers falls back to Docker API version 1.32, which Docker Engine 29+
rejects outright (its minimum is 1.40). The build pins a working version; to
override it for a different engine:

```bash
./gradlew clean test -Dapi.version=1.47
```

## Upstream data source

eaukcija.sud.rs is a React SPA backed by a public JSON API. There is no HTML
scraping:

```
POST /WebApi.Proxy/api/EAukcija/GetAuctionsByCategoryId     { CategoryId, ItemCount, PageCount }
POST /WebApi.Proxy/api/EAukcija/GetImmovablePropertyDetails { AuctionId }
```

Category ids: `7` Непокретности (parent), `47` Парцела, `48` Објекат,
`49` Посебан део објекта, `8` заједничка продаја.

A per-auction canonical URL is not returned by the API. It is derived as
`https://eaukcija.sud.rs/#/aukcije/{Id}`.

### What the API does and does not give you

Measured over a 1,221-record snapshot:

| Field | Coverage | Note |
|---|---|---|
| `Place.Cadastral` (KO name) | 100% | 210 distinct values; use this, do not parse KO from text |
| `Place.Municipality` | 100% | 102 distinct |
| `Place.Code` | 100% | 6-digit **settlement** code, not the KO code |
| `Place.ParcelNumber` | 0% | always null — parcel number must come from free text |
| parcel number in `Description` | 87.7% | |
| parcel number in either description | 90.8% | ~9.2% have none |
| `PropertyType` | 100% | always `ImmovableProperties` — useless as a filter |

`Status` observed values are `InPrediction`, `Verified`, `Verification`. There is
no "closed" status; a finished auction must be derived from `EndDate`.

Roughly 8.7% of advertisements contain neither a parcel number nor a street
address (e.g. `"Парцела"`, `"Њива 6. класе"`) and can only ever be placed at
KO or municipality granularity.

## Layout

```
src/main/java/rs/sud/eaukcija/
├── SudAukcijeApplication.java   main class
├── client/                      REST client + DTOs for eaukcija.sud.rs
├── model/Auction.java           JPA entity
├── repository/                  Spring Data repo + dynamic filter specs
├── service/SyncService.java     sync orchestration
└── controller/                  Thymeleaf UI + sync REST API

src/test/java/rs/sud/eaukcija/
├── client/                      client tests against recorded fixtures
├── spatial/                     CRS transform + spatial query integration tests
├── testsupport/                 shared PostGIS container, fixture loader
├── PostgisSchemaIntegrationTest.java
└── SchemaNegativeControlTest.java

src/main/resources/db/migration/  Flyway migrations (PostgreSQL/PostGIS)

src/test/resources/fixtures/
├── auctions-sample.json         86-record sample, thumbnails stripped
├── eaukcija/                    API response fixtures for the client tests
└── spatial/crs-samples.json     CRS proof points shared with spike/issue-13

tools/
├── eaukcija-scraper.js          standalone Node scraper (reference impl)
└── make-fixture.py              regenerates the fixture from raw scraper output
```

## Fixtures

`tools/eaukcija-scraper.js` writes a ~28 MB `aukcije.json` that is mostly base64
thumbnails, so it is git-ignored. To refresh the committed sample:

```bash
node tools/eaukcija-scraper.js
python3 tools/make-fixture.py tools/aukcije.json src/test/resources/fixtures/auctions-sample.json
```

## Stack

Java 17 · Spring Boot 3.4.3 (Web, Data JPA, Thymeleaf) · H2 (migrating to
PostgreSQL/PostGIS per EPIC-05) · Flyway · Gradle wrapper.

Tests: JUnit 5 · Testcontainers (`postgis/postgis:18-3.6`) · GitHub Actions.