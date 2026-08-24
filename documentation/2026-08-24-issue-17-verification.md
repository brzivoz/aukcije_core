# Issues #12 and #17 verification — 2026-08-24

## Outcome

The eAukcija ingestion path now runs as one bounded, durable synchronization
run. Issue #12's canonical category scope is included because it explicitly
blocks issue #17: roots define discovery, every captured direct child provides
subset-checked classification evidence, and each stable auction ID receives at
most one required detail request. Current auction state, absence counters,
observations, memberships, and enrichment work publish only in the single
successful PostgreSQL transaction. Bounded positive-ID invalid listing rows and
auction-specific invalid details are now retained as resolved quarantines: an
invalid ID is held back while valid records publish, so one deterministic
source defect cannot force every later run to refetch the whole population.

No verification command contacted `eaukcija.sud.rs`. Contract responses came
from committed fixtures and local HTTP stubs. The distinct immovable/common
detail routes were separately checked against the portal's dated SPA bundle and
are documented in the source acceptable-use note.

## Acceptance evidence

| Contract | Retained implementation evidence |
|---|---|
| Source scope and taxonomy | Strict live `ResultCode = "0"`; bounded `GetCategories`; deterministic canonical JSON/SHA-256; roots `7` and `8`; reviewed children `47`/`48`/`49` and `121`/`124`/`135`; configured-root and reviewed-child disappearance/type checks. |
| Root/child completeness | Stable totals, bounded page count, short/empty/no-unique-progress rejection, cross-page duplicate handling, positive-ID listing-row quarantine with raw/unique accounting, root union, child-subset checks, root-only/new-child `UNKNOWN`, immutable per-root and per-child reports. |
| One detail per auction | Root union is complete before child classification and detail work; children cannot add discovery IDs; root `7` uses immovable details and root `8` common details; mixed scopes fail before a detail call; a changed sale scope forces a refresh through the newly applicable endpoint. |
| Source-safe client | Explicit connect/read/call timeouts, decompressed response limit, content-type check, global rate/concurrency gate, full-jitter retry, both `Retry-After` forms, bounded attempts, immediate refusal when a retained shared pause exceeds the next call's wait budget, persistence-domain text/money validation, contact-bearing User-Agent, shutdown cancellation. |
| Durable orchestration | One named queue-free Spring worker, optional managed schedule, PostgreSQL claim/advisory lock, idempotent UUID trigger, stale-run recovery, retained progress/retry/error/listing/detail-quarantine coordinates, bounded error-row retention with aggregate counts, checkpointed detail heartbeats, and late recovered-task refusal. |
| Atomic publication | V10–V12 exact root/direct-child success gates; candidate-plus-listing/detail-quarantine coverage and membership-scope checks; one transaction using bounded multi-row auction upserts and evidence inserts, one taxonomy-scoped membership delete, scoped absences, held-back quarantine evidence, `SUCCEEDED`, and enrichment queue. Missing, partial, non-subset, threshold-exceeding, or unresolved evidence rolls back without current-state mutation. |
| API and UI | Loopback-only `POST /api/sync/runs`; no-store `202`/`200` replay semantics and structured `400`/`403`/`404`/`409`/`503`; retained root/child/error/listing/detail-quarantine status; one UI trigger with bounded polling and terminal reset. |
| Error-data minimization | Typed fixed error codes and coordinates only; response bodies, exception messages, credentials, personal data, and base64 fields never enter logs, retained sync-error evidence, problem responses, or error-status UI. Auction fields intentionally accepted by the validated domain contract remain application data. Sentinel tests cover client, service, scheduler, controller, and browser failures. |

## Fresh verification

Environment: Java 17, Gradle 8.5, Docker-backed
`postgis/postgis:18-3.6`, Flyway V1 through V12, and the pinned Playwright
Chromium runtime.

Commands and terminal results:

```text
./gradlew clean check --no-daemon
BUILD SUCCESSFUL in 1m 8s
51 suites; 295 tests; 291 passed; 4 skipped; 0 failures; 0 errors
plus 14/14 offline basemap contracts

./gradlew browserTest --no-daemon
BUILD SUCCESSFUL in 38s
5 suites; 17/17 passed; 0 skipped; 0 failures; 0 errors
```

Issue-specific suite counts from the final `clean check` XML:

| Suite | Result |
|---|---:|
| `EAukcijaClientTest` | 28/28 |
| `EAukcijaClientPropertiesTest` | 2/2 |
| `SyncServiceTest` | 35/35 |
| `SyncControllerTest` | 17/17 |
| `AuctionControllerLocationPresentationTest` | 2/2 |
| `SyncSchedulerTest` | 5/5 |
| `SyncExecutionConfigurationTest` | 2/2 |
| `SyncPropertiesTest` | 2/2 |
| `SyncPersistenceIntegrationTest` | 20/20 |
| `PostgisSchemaIntegrationTest` | 10/10 |
| `DatabaseLifecycleIntegrationTest` | 7/7 |
| `ExistingPageBrowserTest` | 7/7 |

The four skipped Java tests are the repository's existing opt-in full official
dataset population tests. Their explicit skip does not substitute H2 for
PostgreSQL and does not skip any issue #12/#17 contract: schema, migration,
locking, recovery, atomicity, API, redaction, and browser tests all ran.

## Operational boundary

Scheduling remains disabled by default. The version-controlled operating
contract, defaults, safe bounds, request estimate, recovery procedure, and
status interpretation are in
[eAukcija synchronization operations](EAUKCIJA_SYNC_OPERATIONS.md). The dated
[source acceptable-use note](EAUKCIJA_SOURCE_ACCEPTABLE_USE.md) records the
undocumented-backend status, missing `robots.txt`, conservative two-request per
second/concurrency-one posture, contact User-Agent, and the fact that no
external contact was authorized or sent.
