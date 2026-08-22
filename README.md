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
| PostgreSQL/PostGIS + Flyway foundation | working |
| Spatial auction schema | planned (EPIC-05/#20) |
| Basemap + map UI | planned (EPIC-06, EPIC-07) |

## Running

The explicit `dev` profile requires PostgreSQL/PostGIS. Create an ignored local
secret, start the digest-pinned database, and pass the same password only to the
application process:

```bash
mkdir -p .secrets
# Put a generated local password in .secrets/postgres-password (one line).
docker compose up -d --wait db
AUKCIJE_DB_PASSWORD="$(tr -d '\r\n' < .secrets/postgres-password)" \
  ./gradlew bootRun
```

Compose reads `.env`; Spring Boot does not. If `.env` changes the database name,
user, or port, export the matching `AUKCIJE_DB_*` variables before `bootRun`;
set `AUKCIJE_DB_HOST` directly in the application environment when needed.
The Gradle `bootRun` task supplies `dev` for this local workflow only. The
packaged application has no default: starting it without exactly one of `dev`,
`test`, `prod`, or `local-h2` is rejected before the datasource or web server
starts.

Serves on <http://localhost:8081>.

1. **Преузми листинге** — fetches all listing pages.
2. **Преузми детаље** — fetches per-auction details (location, category, description).
3. Filter and sort locally.

PostgreSQL data persists in the named Compose volume mounted at the PostgreSQL
18 path `/var/lib/postgresql`. Flyway owns the schema and Hibernate validates it
at startup.

### Sync API

```
POST /api/sync/listings   start listings sync
POST /api/sync/details    start details sync
GET  /api/sync/status     sync progress
```

The old H2 console and automatic DDL are disabled. An explicitly activated
`local-h2` profile remains only for legacy compatibility after an archive has
been taken; it is never the default runtime.

See [Database operations](documentation/DATABASE_OPERATIONS.md) for profile,
backup/restore, legacy-H2 archive, clean re-sync, and failure-recovery commands.

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
| `PostgisSchemaIntegrationTest` | Flyway migrating an empty database through V3, Hibernate `validate`, entity round-trip, PostGIS and filter indexes |
| `AuctionRepositoryPostgisIntegrationTest` | fixture parity, exact facet ordering, controller-equivalent paged filters/search, concurrent upserts |
| `SchemaNegativeControlTest` | migration/PostGIS/schema/checksum/credential/connectivity failures, including proof that missing PostGIS fails before the connector opens |
| `CrsTransformIntegrationTest` | EPSG:4326 → 25834/32634 through PostGIS, cross-checked against the pyproj values proven in issue #13 |
| `SpatialQueryIntegrationTest` | bbox filtering incl. boundary inclusion, metre-based distance ordering |

`SpatialQueryIntegrationTest` deliberately asserts query *results* only. Its
fixture builds its own scratch table, so asserting that table's SRID or index
would just be reading back its own DDL. Schema and index assertions belong to
#20, against the real migrated schema.

Reports land in `build/reports/tests/test/index.html`. Every CI run — passing or
failing — retains them as the `test-reports` artifact for 14 days, so a run
stays citable as evidence after its log expires.

### Migrations

`src/main/resources/db/migration/` is the only schema authority. The dev, test,
and prod profiles enable Flyway and set `spring.jpa.hibernate.ddl-auto=validate`.
Migration validation, Hibernate validation, and an explicit PostGIS startup
probe make checksum drift, schema drift, and a missing extension fatal.

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

Java 17 · Spring Boot 3.4.3 (Web, Data JPA, Thymeleaf) · PostgreSQL 18/PostGIS
3.6 · Hibernate Spatial/JTS · Flyway · Gradle wrapper.

Tests: JUnit 5 · Testcontainers (`postgis/postgis:18-3.6`) · GitHub Actions.
