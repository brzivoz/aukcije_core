# aukcije_core

GIS enrichment for Serbian judicial real-estate auctions from
[eaukcija.sud.rs](https://eaukcija.sud.rs).

The official portal lists immovable property but offers no filtering by location,
price or property attributes, and no map. This project ingests the listings,
resolves each advertisement to the best official location its evidence supports,
and is building toward a locally hosted map of Serbia.

## Status

The ingest layer uses durable, source-safe synchronization runs with retained
status and atomic promotion. The GIS layers are being delivered incrementally —
see the [epics](../../issues?q=is%3Aissue+label%3Aepic).

| Layer | State |
|---|---|
| eAukcija ingest (complete durable runs) | working (#17) |
| Deterministic enrichment reprocessing | working (single-threaded, restart-safe, #29) |
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

1. Start one synchronization run; it fetches and validates the configured
   category roots, direct-child membership pages, and required auction details.
2. Follow the returned run ID until it reaches `SUCCEEDED`. Partial and failed
   runs leave the previously successful auction state untouched.
3. Filter, map, and sort the locally promoted data.

PostgreSQL data persists in the named Compose volume mounted at the PostgreSQL
18 path `/var/lib/postgresql`. Flyway owns the schema and Hibernate validates it
at startup.

### Application API

```
POST /api/sync/runs          start one complete run; requires Idempotency-Key
GET  /api/sync/runs/{runId} retained run status and completeness evidence
POST /api/enrichment/runs    start deterministic local enrichment; requires Idempotency-Key
POST /api/enrichment/replays bounded replay by sync/enrichment run, auction, or version
POST /api/enrichment/pause   durably pause between auction transactions
POST /api/enrichment/resume  resume normal work discovery
POST /api/operator/refresh   start or attach to the durable source-to-map workflow
GET  /api/operator/refresh   latest workflow, schedule, and complete success
GET  /api/operator/refresh/{workflowId} persisted workflow state and correlations
GET  /api/enrichment/status  active versions, backlog/age/gaps/distribution, active run
GET  /api/enrichment/runs/{runId} retained redacted run and item evidence
GET  /api/locations/{id}    best selected location with explicit precision
GET  /api/map/auctions      bounded GeoJSON features for one WGS84 viewport
GET  /api/map/status        retained map-data version and freshness state
GET  /api/basemap/status    active immutable basemap version and health
GET  /api/operator/status   loopback-only persisted pipeline/readiness evidence
GET  /operator/status       loopback-only operator status page
GET  http://127.0.0.1:8082/actuator/health/readiness  loopback-only fail-closed readiness
GET  /basemap/*             same-origin PMTiles, style, sprites, and glyphs
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
See [eAukcija synchronization operations](documentation/EAUKCIJA_SYNC_OPERATIONS.md)
for the idempotent trigger, durable status, atomic promotion, retry limits,
configuration bounds, and stale-run recovery. The related
[source acceptable-use note](documentation/EAUKCIJA_SOURCE_ACCEPTABLE_USE.md)
records the reviewed source posture and conservative traffic defaults.
See [deterministic enrichment operations](documentation/ENRICHMENT_OPERATIONS.md)
for immutable work identity, the fixed five-stage local pipeline, shared #17
locking, retry/failure states, safe pause/resume, bounded replay, payload-free
backlog status, restart recovery, and the measured cold-pass threshold.
See [issues #12/#17 verification](documentation/2026-08-24-issue-17-verification.md)
for taxonomy scope, child-subset and atomic-promotion evidence, exact focused
suite counts, clean PostgreSQL/check results, and fresh browser proof.
See [issue #29 verification](documentation/2026-08-25-issue-29-verification.md)
for the immutable work-key/state contract, actual five-stage PostGIS replay,
kill/startup convergence, every-stage 600-neighbor isolation, exact clean-suite
counts, and the measured 601-auction cold pass.
See [pipeline status and recovery operations](documentation/PIPELINE_OPERATIONS.md)
for evidence traceability, alert thresholds, fail-closed readiness, the
last-good external-source outage policy, diagnostics, recovery, and structured
log redaction.
See [one-click refresh operations](documentation/REFRESH_OPERATIONS.md) for the
single manual action, shared daily schedule, persisted cross-tab progress,
last-good behavior, retry, same-origin mutation gate, and restart reconciliation.
See [issue #40 verification](documentation/2026-08-25-issue-40-verification.md)
for the durable correlation contract, acceptance matrix, and fresh clean /
Playwright/PostGIS evidence.
See [issue #30 verification](documentation/2026-08-25-issue-30-verification.md)
for the persisted status/readiness contract and executable acceptance evidence.
See [Centroid extract operations](documentation/CENTROID_EXTRACT_OPERATIONS.md)
for the database-free coarse-location artifact, reviewed checksums,
reproducible publication, status, and failure recovery.
See [KO dictionary operations](documentation/KO_DICTIONARY_OPERATIONS.md) for
the shared Serbian-name normalizer, reviewed alias records, deterministic
dictionary/index publication, duplicate-name evidence, and status commands.
See [structured KO matching operations](documentation/STRUCTURED_KO_MATCH_OPERATIONS.md)
for the transactional population matcher, ambiguity/review semantics,
idempotent reprocessing, retained provenance, and match-rate reports.
See [extracted KO matching operations](documentation/EXTRACTED_KO_MATCH_OPERATIONS.md)
for per-reference matching, explicit structured/text conflict handling,
immutable versioned evidence, idempotent reprocessing, and held-out quality.
See [issue #33 verification](documentation/2026-09-02-issue-33-verification.md)
for the acceptance matrix and fresh Java, PostGIS, migration, and browser
evidence.
See [coarse location operations](documentation/COARSE_LOCATION_OPERATIONS.md)
for the #37→#36 resolution ladder, transactional spatial persistence, retained
tier reports, idempotent refreshes, precision-aware consumers, and recovery.
See [Address Registry snapshot operations](documentation/ADDRESS_REGISTRY_OPERATIONS.md)
for reviewed checksums, full GPKG import, status, evidence, retention, and atomic
rollback.
See [eAukcija source snapshot operations](documentation/SOURCE_SNAPSHOT_OPERATIONS.md)
for the pre-DTO listing+detail contract, versioned minimization policy,
append-only replay lineage, retention/export/redaction policy, and storage
evidence.
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

The reviewed property-reference corpus and frozen baseline also have a focused,
database-free gate (included in `check`):

```bash
./gradlew propertyReferenceCorpusCheck
```

Its schema, evidence-minimization policy, two-pass review, held-out governance,
and metric definitions are documented in
`corpus/property-references/v1/README.md`.

The frozen issue-#19 parser has a separate gate, also included in `check`:

```bash
./gradlew propertyReferenceParserCheck
```

It verifies the committed development/held-out metrics and the production
quality-profile hash. Use `propertyReferenceParserDevelopmentCheck` while
iterating without reading held-out labels.

**Prerequisite: a running Docker daemon.** The integration tests start a real
`postgis/postgis:18-3.6` container through Testcontainers — the same image
EPIC-05 targets — rather than substituting H2. Without Docker the integration
tests fail rather than silently skipping.

No test touches a live network. eaukcija.sud.rs responses are served from
`src/test/resources/fixtures/eaukcija/` through local HTTP stubs.

| Suite | Covers |
|---|---|
| `PropertyReferenceCorpusCliTest` / `propertyReferenceCorpusCheck` | 60-auction/118-reference/20-description-negative purposive corpus; deterministic development/held-out split; exact artifact, snapshot, and source-field hashes; exact script tags and negative-template diversity; official KO authority; schema, typed-value, evidence-budget, personal-data, adjudication, and baseline-metric gates; focused artifact-hash, schema, personal-data, KO-authority, and committed-metric drift mutations |
| `PropertyReferenceParserTest` / `propertyReferenceParserCheck` | structured-first multi-reference extraction from both description fields; raw/canonical Cyrillic/Latin and parcel evidence; offsets; KO conflicts; missing-structure status; folio/object-part/subparcel precision traps; duplicate suppression; hostile/control/oversized/reference-flood bounds; deterministic row/hash replay; frozen ≥95% precision, ≥88% recall, category floors, and zero negative-auction false positives |
| `PropertyReferenceExtractionIntegrationTest` | real-PostgreSQL same-input idempotence, immutable extraction runs/memberships/observations, atomic current-set replacement on parser bumps, exact selected-row evidence, reviewed-correction preservation, and once-only per-run counts plus frozen corpus/metric identity |
| `ExtractedKoMatcherTest` / `ExtractedKoHeldOutQualityTest` / `ExtractedKoMatchIntegrationTest` | shared-normalizer Cyrillic/Latin/diacritic/name-code matching; duplicate-name municipality context; reviewed aliases; distinct malformed/missing/normalization-drift assertions; explicit structured/text conflicts; literal exact-method enforcement for 37/37 frozen held-out identities with zero false positives; immutable V18 result/run evidence, text/structured-fallback/unresolved provenance and split metrics, reviewed-row preservation, and unchanged replay |
| `EAukcijaClientTest` / `EAukcijaClientPropertiesTest` | exact listing/immovable/common request identity, strict envelopes and taxonomy hash, arbitrary-precision pre-DTO source `Data` with no `double` round-trip, bounded JSON/content types, invalid/null and persistence-incompatible text/money data, timeout/disconnect/status retry policy, full-jitter and both `Retry-After` forms, immediate over-budget shared-pause refusal, global rate/concurrency gates, shutdown cancellation, redaction, and fail-fast safe configuration bounds |
| `AuctionSourceSnapshotFactoryTest` | golden exact source replay including decimal scale, key-order/configuration-stable canonical SHA-256, versioned binary/image/token/unreviewed-field exclusion, allowed-detail changes, scalar schema-drift and malformed/null/ID/size/depth rejection, and a parser with no network client |
| `SyncServiceTest` / `SyncSchedulerTest` / `ListingFingerprintTest` | complete root/direct-child pagination, one-pass per-page source indexing and immediate minimized staging, child-subset evidence, root-only/new-child `UNKNOWN`, reviewed-child drift, root-7 immovable/root-8 common detail routing, stable-ID union and one detail call, bounded listing/detail record quarantine including an unminimizable 64-KiB rejected row with continued promotion, aggregate/capped error evidence, duplicate/conflict/short-page/changed-total refusal, client/failure coordinates including snapshot-read classification, new/changed/stale refresh and legacy-source-snapshot bootstrap policy, checkpointed heartbeats, deterministic summary fingerprints, startup/scheduler log redaction, deterministic scheduled idempotency, late recovered-task refusal, and no promotion on unresolved partial work |
| `SyncControllerTest` | loopback-only idempotent `202`/`200` trigger semantics, `400`/`403`/`404`/`409`/`503` no-store problems, retained status/root/child/error/listing/detail-quarantine evidence, executor/ledger recovery coordinates, and fixed-code log redaction |
| `SyncPersistenceIntegrationTest` / `WorkerLockLeaseTest` | real-PostGIS idempotent/concurrent claims, advisory locks and physical-session abort, stale recovery, immutable/crash-consistent root/child/quarantine evidence, exact captured-taxonomy completeness/subset gates with PostgreSQL timestamp-precision normalization, scoped absences and held-back IDs, set-based membership/observation/current-snapshot publication, exact fixed-point and exponent-normalized JSONB read-back plus reused-detail re-hash, append-only source-snapshot dedup/corrections/offline replay/storage evidence, atomic promotion/rollback, and success-only observations/enrichment |
| `SyncPropertiesTest` / `SyncExecutionConfigurationTest` | orchestration defaults/bounds, single named queue-free worker, correlation propagation, immediate interruption, and bounded Spring context shutdown |
| `PostgisSchemaIntegrationTest` | Flyway migrating an empty database through V18, Hibernate `validate` of the mapped JPA schema, entity round-trip, and direct PostGIS/catalog checks for filter, structured/extracted KO-match, spatial/coarse-run provenance/indexes, durable sync/source-snapshot/enrichment/property-reference/refresh/observability evidence and success gates, and canonical/immutability triggers |
| `PipelineStatusServiceTest` / `OperatorStatusControllerTest` | success/partial/stale/backlog/import/source-outage policy, fail-closed readiness, loopback access, no-store responses, correlation IDs, and payload/log redaction |
| `PipelineStatusRepositoryIntegrationTest` | restart-stable persisted attempts/successes, source and raw-snapshot deltas, parser/import evidence, and terminal run/job immutability |
| `EnrichmentPipelineTest` / `EnrichmentPropertiesTest` / `EnrichmentSchedulerTest` | exact five-stage order, deterministic hashes/version sets, complete stage wiring, redacted failure classification, bounded queue-free settings, deterministic schedule idempotency, overlap handling, and fixed safe logs |
| `RefreshCoordinatorTest` / `RefreshControllerTest` / `RefreshPropertiesTest` / `RefreshSchedulerTest` / `RefreshExecutionConfigurationTest` | strict source→enrichment→map order, detail/partial/enrichment failure isolation, active-version and readiness correlation, child/aggregate stale-heartbeat recovery, startup/error/executor recovery, no false success, duplicate attachment, race-free one-slot handoff, deterministic daily Belgrade scheduling through the shared coordinator, localized failures, and loopback same-origin mutation protection |
| `RefreshRepositoryIntegrationTest` | real-PostgreSQL concurrent manual/scheduled claim winner, atomic stale-active reclaim, fail-closed missing-snapshot lineage, durable retry, and append-only terminal workflow evidence |
| `EnrichmentControllerTest` | loopback-only idempotent trigger, typed bounded replay, durable pause/resume, payload-free backlog/status distribution, retained redacted item evidence, and no-store `400`/`403`/`409`/`503` problems |
| `EnrichmentReprocessingIntegrationTest` | real-PostgreSQL state/work-key discovery, unchanged zero-work replay, exact parser/resolver/dataset bumps, every-stage failure isolation across 601 auctions, retry cap, durable pause, bounded replay, database overlap, shared #17 worker lock, and kill/startup recovery including never-started accepted work |
| `EnrichmentPipelinePostgisIntegrationTest` | all five production stages over real PostGIS; byte-stable local replay with zero source-client interactions; verified-parcel non-downgrade; ambiguity preservation; and a measured single-thread 601-auction cold pass with one isolated real stage failure |
| `AuctionRepositoryPostgisIntegrationTest` | fixture parity, exact facet ordering, controller-equivalent paged filters/search, concurrent upserts |
| `SchemaNegativeControlTest` | migration/PostGIS/schema/checksum/credential/connectivity failures, including proof that missing PostGIS fails before the connector opens |
| `CrsTransformIntegrationTest` | EPSG:4326 → 25834/32634 through PostGIS, cross-checked against the pyproj values proven in issue #13 |
| `SpatialQueryIntegrationTest` | bbox filtering incl. boundary inclusion, metre-based distance ordering |
| `SpatialResolutionSchemaIntegrationTest` | isolated PostGIS database; source CRS transform; point/polygon/multipolygon fidelity; invalid geometry/bounds/SRID rejection; recorded repair; write-free identity replay; immutable provenance; supersession; `STREET` representative-point semantics; and a default-planner exact-query proof over 20k geometries/100k attempts |
| `MapAuctionRequestParserTest` / `MapAuctionControllerTest` | WGS84 order/ranges/edges/area, allowlisted filters, Belgrade date boundaries, structured errors, GeoJSON fields, safe links, and observable truncation |
| `MapAuctionRepositoryIntegrationTest` / `MapAuctionRepositoryUnitTest` | stable multi-property deduplication, highest selected precision, bbox/date/status/kind/precision filters, amounts, inclusive edges, one bounded JDBC query, and no N+1 hydration |
| `MapDataStatusServiceTest` / `MapDataStatusControllerTest` | retained successful resolution version/timestamp and internal workflow correlation, anonymous DTO exclusion of both internal run IDs, mapped/precision counts, configurable stale boundary, never-synchronized disclosure, and no-store HTTP metadata |
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
| `AddressRegistryImporterIntegrationTest` | offline GPKG/ZIP import, exact names/ids, Đ normalization, 25834→4326, checksum/schema/CRS/source+active-row/geometry gates, parcel-loss metrics, unchanged replay, session-locked staging/promotion and recovery isolation, post-commit retention, rollback |
| `ExistingPageBrowserTest` | seven real Playwright tests: HTTP/Thymeleaf rendering over seeded PostGIS, stale-run recovery, transient-to-terminal polling, secret-bearing JSON/non-JSON error redaction, non-empty visible UI, exact contacted-host evidence, reserved-character external-asset blocking, and loopback/external WebSocket controls |
| `LocalBasemapBrowserTest` | actual compact PMTiles v3 through the production endpoint; same-origin MapLibre protocol/style/sprite/glyph/worker requests; zoom 5/9/14 plus pan; visible linked OSM attribution; exact localhost-only host and `206`/ETag evidence |
| `AuctionMapBrowserTest` | real local basemap plus PostGIS GeoJSON; all six precision styles; shared-centroid cluster/list; keyboard selection; escaped popup and allowlisted source link; allowlisted URL restoration; coalesced pan/zoom refresh with an idle-or-250-ms fallback; retained loading/empty/error/limit state; desktop/narrow evidence; exact localhost-only traffic |
| `PostgisBrowserFixtureCleanupTest` | browser-free proof that fixture reset handles a selected location graph and append-only resolution evidence |
| `LocalhostOnlyNetworkTest` | browser-free proof that only browser-local `blob:`/`data:` schemes bypass the JDK protocol-handler registry while HTTP(S) and WebSockets remain guarded |
| `RefreshWorkflowBrowserTest` / `RefreshEndToEndBrowserTest` | Enter-key activation, rapid-click coalescing, every-stage reload, two-tab restoration, terminal idempotency reset, retry→busy→retry focus transfer, retained last-good time, static polite/assertive announcements, measured ≥3:1 default/hover focus contrast, no pre-readiness success, in-place list/map refresh, and one real fixture source→production enrichment→PostGIS map-ready browser flow |
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

`src/main/resources/db/migration/` is the only schema authority. Through V18 it
owns the auction baseline plus immutable Address Registry snapshots, the atomic
active/previous pointer, lookup/geometry indexes, centroids, and retained import
evidence, plus current structured-KO results, reviewed municipality-alias
provenance, structured-KO and coarse-location population-run reports, canonical property/parcel identities,
source plus WGS84 resolution geometry, append-only attempt evidence, separate
cache records, mutable selected-resolution pointers, the viewport GiST plus
reverse-FK indexes, and durable eAukcija sync runs, bounded
error/root/child/quarantine evidence, category membership, success-only
observations/enrichment work, freshness, and absence counters; deterministic
enrichment reprocessing; pipeline/refresh evidence; and immutable minimized
listing+detail source snapshots with current-state and run-observation lineage;
plus versioned property-reference runs, memberships, current selection,
source/input lineage, quality metrics, and reviewed-correction-safe replay.
V18 adds immutable per-reference extracted-KO results, structured/text
reconciliation, current pointers, population-run membership, and enrichment
observations.
Canonical WGS84
is derived by a normal-write trigger so
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

eaukcija.sud.rs is a React SPA backed by an undocumented JSON backend. There is
no HTML scraping, but these routes are not a published or supported public API:

```
POST /WebApi.Proxy/api/EAukcija/GetCategories                 {}
POST /WebApi.Proxy/api/EAukcija/GetAuctionsByCategoryId     { CategoryId, ItemCount, PageCount }
POST /WebApi.Proxy/api/EAukcija/GetImmovablePropertyDetails { AuctionId }
POST /WebApi.Proxy/api/EAukcija/GetCommonPropertyDetails    { AuctionId }
```

Configured roots are `7` Непокретности and `8` заједничка продаја. Reviewed
direct children are `47` Парцела, `48` Објекат, `49` Посебан део објекта and
common-scope `121`, `124`, `135`. Roots define discovery; child requests only
prove membership and normalized kind, so they never duplicate detail work.

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
