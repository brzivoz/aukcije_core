# aukcije_core

GIS enrichment for Serbian judicial real-estate auctions from
[eaukcija.sud.rs](https://eaukcija.sud.rs).

The official portal lists immovable property but offers no filtering by location,
price or property attributes, and no map. This project ingests the listings,
resolves each advertisement to the best official location its evidence supports,
and is building toward a locally hosted map of Serbia.

## Status

The ingest layer is a working proof of concept ported from an earlier prototype.
The GIS layers are being delivered incrementally — see the
[epics](../../issues?q=is%3Aissue+label%3Aepic).

| Layer | State |
|---|---|
| eAukcija ingest (listings + details) | working |
| Local filtering / list UI | working |
| Property reference extraction | planned (EPIC-02) |
| Official Address Registry centroid extract | working (small immutable artifact, #36) |
| Canonical KO dictionary + normalized index | working (immutable artifact, #14) |
| Structured auction KO matching | working (auditable PostgreSQL results, #37) |
| Coarse auction locations | working (KO/settlement/municipality/`NONE`, #38) |
| Official Address Registry full snapshots | working (points + centroids, #22) |
| Parcel + address resolution | planned (EPIC-03, EPIC-04) |
| PostgreSQL/PostGIS + Flyway foundation | working |
| Spatial auction schema | working (canonical references, provenance, WGS84 geometry, #20) |
| Bounded GeoJSON viewport API | working (indexed and precision-aware, #26) |
| Browser-test harness | working (Playwright, seeded PostGIS, localhost-only guard, #34) |
| Offline Serbia PMTiles basemap | working (reproducible local bundle, #24) |
| Local basemap serving | working (Range/ETag, atomic activation, offline proof, #25) |
| Auction map UI | working (offline MapLibre, clustered and precision-aware, #27) |

## Running

### Prerequisites

- Java 17
- Docker with Docker Compose

The explicit `dev` profile requires PostgreSQL/PostGIS. On a new checkout,
create the ignored database-password file once:

```bash
mkdir -p .secrets
openssl rand -hex 32 > .secrets/postgres-password
chmod 600 .secrets/postgres-password
```

Do not regenerate that file while reusing an existing `postgres-data` volume:
PostgreSQL applies the password only when it initializes a new database. To
customize the database name, user, or host port, copy `.env.example` to `.env`
and edit the non-secret values there.

### Launch

From the repository root, run:

```bash
./start.sh
```

The script loads non-secret overrides from `.env`, builds the executable jar,
starts the digest-pinned database, starts the application in the background,
and waits for <http://localhost:8081> to respond. If the default PostgreSQL
host port `5432` is occupied and no port was explicitly configured, the script
selects the first free port from `5433` through `5499` and passes the same value
to Compose and Spring. Set `AUKCIJE_DB_PORT` in `.env` when a stable alternate
port is required. Runtime files are ignored:

- `.run/aukcije-core.pid` identifies the exact managed Java process.
- `.run/aukcije-core.log` contains application output; follow it with
  `tail -f .run/aukcije-core.log`.

### Stop

Stop the managed application and local database with:

```bash
./stop.sh
```

The stop script validates the recorded PID before sending `SIGTERM`, waits for
the application to exit, and then runs `docker compose down`. Normal shutdown
preserves the named `postgres-data` volume, so the next launch retains the
database. To stop only the application and leave PostgreSQL running, use
`./stop.sh --keep-db`. Do not run `docker compose down --volumes` unless you
intentionally want to delete all local PostgreSQL data.

Compose and `start.sh` read `.env`; Spring Boot itself does not. If `.env`
changes the database name, user, or port and you launch manually with
`./gradlew bootRun`, export the matching `AUKCIJE_DB_*` variables first. Set
`AUKCIJE_DB_HOST` directly in the application environment when needed.
The Gradle `bootRun` task supplies `dev` for this local workflow only. The
packaged application has no default: starting it without exactly one of `dev`,
`test`, `prod`, or `local-h2` is rejected before the datasource or web server
starts.

1. **Преузми листинге** — fetches all listing pages.
2. **Преузми детаље** — fetches per-auction details (location, category, description).
3. Filter and sort locally.

PostgreSQL data persists in the named Compose volume mounted at the PostgreSQL
18 path `/var/lib/postgresql`. Flyway owns the schema and Hibernate validates it
at startup.

### Application API

```
POST /api/sync/listings   start listings sync
POST /api/sync/details    start details sync
GET  /api/sync/status     sync progress
GET  /api/locations/{id}  best selected location with explicit precision
GET  /api/map/auctions     bounded GeoJSON features for one WGS84 viewport
GET  /api/map/status       retained map-data version and freshness state
GET  /api/basemap/status   active immutable basemap version and health
GET  /basemap/*            same-origin PMTiles, style, sprites, and glyphs
```

The map endpoint requires `bbox=minLon,minLat,maxLon,maxLat`; optional
allowlisted filters are `status`, `kind`, `precision`, `from`, `to`, and
`limit`. See [Map API](documentation/MAP_API.md) for the complete request,
timezone, safety, deduplication, and truncation contract.

The old H2 console and automatic DDL are disabled. An explicitly activated
`local-h2` profile remains only for legacy compatibility after an archive has
been taken; it is never the default runtime.

See [Database operations](documentation/DATABASE_OPERATIONS.md) for profile,
backup/restore, legacy-H2 archive, clean re-sync, and failure-recovery commands.
See [Centroid extract operations](documentation/CENTROID_EXTRACT_OPERATIONS.md)
for the database-free coarse-location artifact, reviewed checksums,
reproducible publication, status, and failure recovery.
See [KO dictionary operations](documentation/KO_DICTIONARY_OPERATIONS.md) for
the shared Serbian-name normalizer, reviewed alias records, deterministic
dictionary/index publication, duplicate-name evidence, and status commands.
See [structured KO matching operations](documentation/STRUCTURED_KO_MATCH_OPERATIONS.md)
for the transactional population matcher, ambiguity/review semantics,
idempotent reprocessing, retained provenance, and match-rate reports.
See [coarse location operations](documentation/COARSE_LOCATION_OPERATIONS.md)
for the #37→#36 resolution ladder, transactional spatial persistence, retained
tier reports, idempotent refreshes, precision-aware consumers, and recovery.
See [Address Registry snapshot operations](documentation/ADDRESS_REGISTRY_OPERATIONS.md)
for reviewed checksums, full GPKG import, status, evidence, retention, and atomic
rollback.
See [issue #20 spatial verification](documentation/2026-08-23-issue-20-verification.md)
for the V7 reference/resolution model, geometry gates, bounded repository
contract, and reproducible PostGIS evidence.
See [issue #38 verification](documentation/2026-08-23-issue-38-verification.md)
for the complete 589-auction tier distribution and exact replay evidence.
See [issue #26 verification](documentation/2026-08-23-issue-26-verification.md)
for GeoJSON contract, validation, real PostGIS filtering, query-plan, and
single-query evidence.
See [issue #27 verification](documentation/2026-08-23-issue-27-verification.md)
for the usable precision-aware map, clustered shared-centroid behavior,
safe popup/URL state, responsive screenshots, cancellation/state matrix, and
localhost-only browser proof.
See [issue #24 verification](documentation/2026-08-23-issue-24-verification.md)
for the dated Serbia source, pinned build toolchain, byte-identical PMTiles,
local asset gates, manifest, three tile reads, and delivery-time localhost-only
map render.
See [issue #25 verification](documentation/2026-08-23-issue-25-verification.md)
for HTTP Range/ETag/concurrency evidence, atomic activation failure safety, the
full-bundle activation result, and retained localhost-only multi-zoom render.
See [browser and frontend decisions](documentation/BROWSER_AND_FRONTEND.md) for
the Playwright harness, localhost-only network fixture, failure evidence,
same-origin vendoring policy, and the decision to extend the Thymeleaf UI.
See [Serbia basemap operations](basemap/README.md) for the one-command dated
Geofabrik build, pinned Java 21 Planetiler toolchain, local style assets,
validation gates, ODbL attribution, manifests, resources, and cleanup.
See [local basemap serving operations](documentation/BASEMAP_SERVING_OPERATIONS.md)
for checksum-gated activation, HTTP Range/ETag behavior, live health, rollback,
the operator smoke page, and safe immutable-build retirement.

## Unit and integration tests

The JUnit and PostGIS integration suites run from one command:

```bash
./gradlew clean test
```

Run the complete repository verification, including the Python basemap
contracts, with `./gradlew clean check`. Keeping `basemapTest` on `check` means
ordinary Java `test` does not acquire a host-Python dependency.

**Prerequisite: a running Docker daemon.** The integration tests start a real
`postgis/postgis:18-3.6` container through Testcontainers — the same image
EPIC-05 targets — rather than substituting H2. Without Docker the integration
tests fail rather than silently skipping.

No test touches a live network. eaukcija.sud.rs responses are served from
`src/test/resources/fixtures/eaukcija/` through `MockRestServiceServer`.

| Suite | Covers |
|---|---|
| `EAukcijaClientTest` | request shape, listing/detail parsing, empty page, API error envelope, transport failure, malformed body |
| `PostgisSchemaIntegrationTest` | Flyway migrating an empty database through V9, Hibernate `validate` of the mapped JPA schema, entity round-trip, and direct PostGIS/catalog checks for filter, KO-match, spatial/coarse-run provenance/indexes, and the canonical-derivation trigger |
| `AuctionRepositoryPostgisIntegrationTest` | fixture parity, exact facet ordering, controller-equivalent paged filters/search, concurrent upserts |
| `SchemaNegativeControlTest` | migration/PostGIS/schema/checksum/credential/connectivity failures, including proof that missing PostGIS fails before the connector opens |
| `CrsTransformIntegrationTest` | EPSG:4326 → 25834/32634 through PostGIS, cross-checked against the pyproj values proven in issue #13 |
| `SpatialQueryIntegrationTest` | bbox filtering incl. boundary inclusion, metre-based distance ordering |
| `SpatialResolutionSchemaIntegrationTest` | isolated PostGIS database; source CRS transform; point/polygon/multipolygon fidelity; invalid geometry/bounds/SRID rejection; recorded repair; write-free identity replay; immutable provenance; supersession; `STREET` representative-point semantics; and a default-planner exact-query proof over 20k geometries/100k attempts |
| `MapAuctionRequestParserTest` / `MapAuctionControllerTest` | WGS84 order/ranges/edges/area, allowlisted filters, Belgrade date boundaries, structured errors, GeoJSON fields, safe links, and observable truncation |
| `MapAuctionRepositoryIntegrationTest` / `MapAuctionRepositoryUnitTest` | stable multi-property deduplication, highest selected precision, bbox/date/status/kind/precision filters, amounts, inclusive edges, one bounded JDBC query, and no N+1 hydration |
| `MapDataStatusServiceTest` / `MapDataStatusControllerTest` | retained successful resolution version/timestamp, mapped counts, configurable stale boundary, never-synchronized disclosure, and no-store HTTP metadata |
| `LocationSelectionSqlTest` | enum-generated precision ranking, unknown-tier fail-closed ordering, shared tie-breaks, and publication policy |
| `AddressRegistryCentroidExtractorTest` | deterministic immutable centroid artifact, exact ids/names/relationships, reports, validation, atomic activation |
| `AddressRegistryCentroidCrsIntegrationTest` | production 25834→4326 transform cross-checked against PostGIS |
| `KoDictionaryPublisherTest` | official relationship preservation, shared normalization, manifest-v2 compatibility, distinct reviewed KO/municipality aliases, orphan-target rejection, alias/official collision retention, byte-identical replay, immutable publication, and failure-safe activation |
| `StructuredKoMatcherTest` | scripts/diacritics, exact-code precedence, duplicate names, collision-safe municipality alias disambiguation (including official-name conflicts and alias-free official collisions), retained review evidence, precomputed municipality context, malformed inputs, fuzzy-review-only behavior, and shared query/index normalization |
| `KoDictionaryPublisherCompatibilityTest` | real #36 extractor -> #14 publisher -> #37 loader compatibility for duplicate names, reviewed KO/municipality aliases, and multi-parent relationships |
| `StructuredKoMatchIntegrationTest` | V6 persistence, immutable snapshot/KO/municipality-alias provenance, candidate evidence, population report, and unchanged replay against real PostgreSQL |
| `CoarseLocationResolverTest` | all honest coarse tiers, shared Serbian normalization, ambiguity fallthrough, structural reviewed-alias evidence, pre/post-filter settlement evidence, and upstream-versioned fingerprints |
| `CoarseLocationResolutionIntegrationTest` | V7/V8/V9 persistence, complete #37/#39 run provenance, real-#37 alias evidence, missing-upstream refusal, republish refresh, unchanged replay, failure safety, and higher-tier non-downgrade against PostGIS |
| `LocationControllerTest` / `AuctionControllerLocationPresentationTest` | explicit machine/Serbian precision, extraction/publication state in JSON, review visibility, and rendered UI honesty notice |
| `AddressRegistryImporterIntegrationTest` | offline GPKG/ZIP import, exact names/ids, Đ normalization, 25834→4326, checksum/schema/CRS/source+active-row/geometry gates, parcel-loss metrics, unchanged replay, atomic promotion, post-commit retention, rollback |
| `ExistingPageBrowserTest` | three real Playwright tests: HTTP/Thymeleaf rendering over seeded PostGIS, non-empty visible UI, exact contacted-host evidence, reserved-character external-asset blocking, and loopback/external WebSocket controls |
| `LocalBasemapBrowserTest` | actual compact PMTiles v3 through the production endpoint; same-origin MapLibre protocol/style/sprite/glyph/worker requests; zoom 5/9/14 plus pan; visible linked OSM attribution; exact localhost-only host and `206`/ETag evidence |
| `AuctionMapBrowserTest` | real local basemap plus PostGIS GeoJSON; all six precision styles; shared-centroid cluster/list; keyboard selection; escaped popup and allowlisted source link; allowlisted URL restoration; debounced pan/zoom abort; retained loading/empty/error/limit state; desktop/narrow evidence; exact localhost-only traffic |
| `PostgisBrowserFixtureCleanupTest` | browser-free proof that fixture reset handles a selected location graph and append-only resolution evidence |
| `LocalhostOnlyNetworkTest` | browser-free proof that only browser-local `blob:`/`data:` schemes bypass the JDK protocol-handler registry while HTTP(S) and WebSockets remain guarded |
| `basemapTest` | dated-source checksum failures, immutable tool/asset pins, canonical metadata drift, PMTiles v3/layer/smoke validation, complete manifest inventory, host-neutral command/manifest equality and drift rejection, orphaned/concurrent lock recovery, active sprite references, six glyph ranges, and external-style-asset rejection without a full map rebuild |
| `BasemapAssetHttpIntegrationTest` | full/prefix/open/suffix/invalid/conditional/concurrent PMTiles reads over real HTTP, RFC-correct unsupported-range fallback, public notices/licenses, correct content types, strong ETags, `304`, `416`, cache/version headers, and health |
| `BasemapArtifactActivationTest` / `FrontendAssetLockTest` | durable atomic pointer publication, failed-update last-good retention, steady-state poll timestamps, non-blocking unavailable requests, explicit republish-to-retry behavior, rollback, and exact transformed vendored dependency license/hash/inventory pins |

`SpatialQueryIntegrationTest` deliberately asserts scratch-query semantics only. Its
fixture builds its own scratch table, so asserting that table's SRID or index
would just be reading back its own DDL. `SpatialResolutionSchemaIntegrationTest`
asserts V7 and the production repository instead.

Reports land in `build/reports/tests/test/index.html`; the basemap unittest
transcript lands in `build/basemap-test/result.txt`. Every CI run — passing or
failing — retains them as the `test-reports` artifact for 14 days, so a run
stays citable as evidence after its log expires.

## Browser tests

The Playwright suite is opt-in and separate from `test`. From a clean checkout,
with Docker running, one command installs the pinned Chromium build, starts a
Testcontainers PostGIS database, boots the application with deterministic data,
and runs a non-zero browser suite:

```bash
./gradlew browserTest
```

The first run downloads Chromium into ignored `.gradle/playwright-browsers`;
unchanged later runs reuse that verified task output without executing the
installer. On Linux CI, `-PplaywrightWithDeps` also installs required operating-
system libraries. To watch the suite locally:

```bash
./gradlew browserTest -Dbrowser.headless=false
```

Reports land in `build/reports/tests/browserTest/index.html`. Failed tests retain
`failure.png` and `trace.zip` under `build/browser-test-results/artifacts/`; CI
publishes those files as `playwright-failure-evidence`. Successful map proofs
retain the #25 basemap screenshot and #27 desktop/narrow product screenshots
plus JSON manifests under `build/browser-test-results/evidence/`; CI publishes
that directory in `browser-test-report`. The shared network guard
aborts every non-loopback HTTP(S) request, closes every non-loopback WebSocket,
and tests assert the exact contacted host set. See
[browser and frontend decisions](documentation/BROWSER_AND_FRONTEND.md) for the
fixture and asset-upgrade contract.

### Migrations

`src/main/resources/db/migration/` is the only schema authority. Through V9 it
owns the auction baseline plus immutable Address Registry snapshots, the atomic
active/previous pointer, lookup/geometry indexes, centroids, and retained import
evidence, plus current structured-KO results, reviewed municipality-alias
provenance, structured-KO and coarse-location population-run reports, canonical property/parcel identities,
source plus WGS84 resolution geometry, append-only attempt evidence, separate
cache records, mutable selected-resolution pointers, and the viewport GiST
plus reverse-FK indexes. Canonical WGS84 is derived by a normal-write trigger so
backup restore does not re-run PROJ-dependent transforms. The dev, test,
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
├── coarselocation/              active-artifact loader + #38 resolver/CLI
├── model/Auction.java           JPA entity
├── repository/                  Spring Data repo + dynamic filter specs
├── service/SyncService.java     sync orchestration
├── spatial/                     location queries + precision presentation
└── controller/                  Thymeleaf UI + REST APIs

src/test/java/rs/sud/eaukcija/
├── client/                      client tests against recorded fixtures
├── spatial/                     CRS transform + spatial query integration tests
├── testsupport/                 shared PostGIS container, fixture loader
├── PostgisSchemaIntegrationTest.java
└── SchemaNegativeControlTest.java

src/main/resources/db/migration/  Flyway migrations (PostgreSQL/PostGIS)

data/address-registry-centroids/  ignored runtime #36 versions, ACTIVE pointer, and run evidence
data/address-registry-ko-dictionary/  ignored runtime #14 versions, ACTIVE pointer, and run evidence

config/address-registry/ko-alias-overrides.json  reviewed, versioned KO alias source

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

Tests: JUnit 5 · Testcontainers (`postgis/postgis:18-3.6`) · Playwright 1.61.0
(Chromium) · GitHub Actions.
