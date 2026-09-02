# Issue #29 verification — 2026-08-25

## Outcome

Deterministic enrichment reprocessing is implemented over immutable successful
sync input and active local versions. PostgreSQL V13 retains one current state
per auction plus immutable run/item/snapshot evidence. A single Spring schedule
uses #17's existing capacity-zero worker and PostgreSQL session advisory lock;
there is no lease queue or second concurrency model.

The production pipeline runs parse → KO matching → private parcel evidence →
local address/coarse fallback → selected resolution. Every auction has its own
transaction. Retrying the same work is safe, a process exit is recovered from
accepted state, and no stage calls eAukcija, RGZ, or an online geocoder.

## Acceptance evidence

| Acceptance contract | Evidence |
|---|---|
| Persist canonical state | V13 `enrichment_state` stores source run, immutable snapshot SHA-256, parser/resolver/dataset versions, parcel dependency hash, canonical work-key SHA-256, state, total starts, separate retryable-failure/interruption counts, times, last stage, output hash, and bounded error codes. Schema constraints and catalog tests cover all columns/indexes. |
| Pure, identical replay with zero source calls | `EnrichmentPipelinePostgisIntegrationTest` runs all five production stages twice over real PostGIS, compares derived rows and output hashes, observes no new attempts, and verifies zero interactions with the mocked `EAukcijaClient`. `EnrichmentReprocessingIntegrationTest` separately proves an unchanged normal run has zero candidates/attempts and leaves the entire state serialization identical. The current v2 canonical input contains the source-snapshot hash, auction ID, cadastral/place/municipality, `Description`, and `ShortDescription`; price, status, and sync-bookkeeping ticks do not invent work. |
| One schedule and reused lock | `EnrichmentScheduler` is the sole enrichment `@Scheduled` method and ships hourly at minute 15 in `Europe/Belgrade`. It submits to `syncRunExecutor`; `EnrichmentService` acquires `SyncRunRepository.tryAcquireWorkerLock()`. Concurrent PostgreSQL claims have one winner, and a held #17 worker lock excludes enrichment. Sync contention produces retained `SKIPPED` evidence and an INFO diagnostic, not a failed run/error log. |
| Changed/retryable-only discovery | Candidate discovery compares immutable snapshot, parser/resolver/dataset set, and per-auction parcel dependency against the retained work key. Tests independently bump each version, exclude an already-current auction, retry only retryable state, stop exactly at the configured cap, and preserve the original retryable backlog age across a later unchanged sync. Immutable dictionary/centroid artifacts are pinned once per run; a pre-execution pointer change refuses the stale claim, while a mid-run publication is selected by the next work-key comparison without mixing artifact versions. |
| Successful-sync gate | Successful #17 promotion publishes snapshot, observation, current pointer, and legacy queue in one transaction. A V13 trigger rejects an enrichment observation for a still-running sync. Snapshot/observation immutability, 1,005-row chunking, and rollback remain covered by the sync persistence suite. |
| Exact stage order and failure isolation | Pipeline construction requires exactly the five enum stages and sorts them to the contract order. A parameterized real-PostgreSQL test injects the single failing auction at each of the five stages in turn; the other 600 complete every time. The production cold pass separately records one genuine `ADDRESS_FALLBACK` incompatibility while 600 complete. |
| Outcome and retry classes | Current/item states distinguish retryable, not-found, ambiguous, permanent, and attempt-cap outcomes. Retryable failures and process interruptions have independent bounded counters: interruptions do not consume the genuine retry cap, and repeated crashes terminate with `INTERRUPTION_LIMIT_REACHED`. The final selection keeps ambiguous KO as `AMBIGUOUS`, `NONE` as terminal not-found, and a validated parcel above all fallback tiers. Cause text is replaced with allowlisted safe codes. |
| Kill and restart | The crash test completes one item, launches a separate JVM for the second, durably calls `startItem`, then terminates that JVM with `Runtime.halt(29)` before completion; a third item remains unstarted. Parent recovery retains `INTERRUPTED` evidence, resets only in-flight current state to `PENDING`, starts one idempotent `RECOVERY` run, and converges all three output hashes. A separate regression proves a stale heartbeat self-heals on a later start without restarting the application, and an escaping `Error` terminalizes its ledger. |
| Pause/resume and bounded replay | Pause is serialized with claims under a PostgreSQL transaction advisory lock and checked between item transactions. Loopback-only no-store endpoints provide durable pause/resume and require exactly one sync-run, enrichment-run, auction, or version selector plus a 1–1,000 item cap. There is no reset or bulk-retry endpoint. |
| Idempotent retry while running | `EnrichmentService` performs a hash-only retained-key lookup before stale/overlap recovery. A real-service PostgreSQL test holds the executor task, retries the same key while its run is `RUNNING`, receives the same run with `replayed=true`, proves only one task submission, then executes it to success. The controller test separately represents same-key replay and uses a different key for the overlap `409`. |
| Payload-free operations | `/api/enrichment/status` is write-free and returns active versions, durable pause, active run, backlog size, oldest pending time, population lineage gap count, and status distribution. A PostgreSQL regression proves it does not bootstrap or mutate snapshots/observations/current pointers. Run status returns IDs, hashes, counters, stages, and safe codes only. Controller tests assert canonical/raw payload fields are absent. |

## Cold-pass measurement

The final clean verification measured the actual production five-stage pipeline
over a cold 601-auction deterministic fixture on one thread:

```text
ISSUE_29_COLD_REPROCESS auctions=601 succeeded=600 failed=1 duration_ms=3506
JUnit method duration: 3.770 s
```

The deliberately incompatible auction failed at `ADDRESS_FALLBACK`; its
transaction rolled back and the next 600/601 work continued. The unchanged
follow-up selected zero items. This is a real PostgreSQL/PostGIS and production
stage measurement with a local fixture, not a live-source benchmark. It is far
below the deferred leased-queue threshold of 30 minutes and the measured
population is far below the roughly 5,000-item sustained-backlog trigger.

## Fresh verification

Environment: Java 17, Gradle 8.5, Docker-backed pinned
`postgis/postgis:18-3.6`, Flyway V1 through V13, and pinned Playwright Chromium.
No verification contacted eaukcija.sud.rs or RGZ.

V13 is still an unmerged branch migration, so the contract was corrected in
place. Any disposable development database that applied an earlier working-tree
copy of V13 must be recreated before verification; after V13 is shared or
deployed, every further schema change must use V14 or later instead.

```text
./gradlew clean check --no-daemon
BUILD SUCCESSFUL in 1m 24s
58 suites; 341 tests; 337 passed; 4 skipped; 0 failures; 0 errors
plus 14/14 offline basemap contracts

./gradlew browserTest --no-daemon
BUILD SUCCESSFUL in 38s
5 suites; 17/17 passed; 0 skipped; 0 failures; 0 errors
```

Issue-relevant suite counts from the terminal clean run:

| Suite | Result |
|---|---:|
| `EnrichmentInputSnapshotTest` | 2/2 |
| `EnrichmentPipelineTest` | 6/6 |
| `EnrichmentPropertiesTest` | 2/2 |
| `EnrichmentSchedulerTest` | 2/2 |
| `EnrichmentControllerTest` | 6/6 |
| `EnrichmentReprocessingIntegrationTest` | 23/23 |
| `EnrichmentPipelinePostgisIntegrationTest` | 4/4 |
| `PostgisSchemaIntegrationTest` | 11/11 |
| `SyncPersistenceIntegrationTest` | 20/20 |
| `DatabaseLifecycleIntegrationTest` | 7/7 |

The four skipped Java tests are the existing opt-in complete official-dataset
population suites. They do not skip any #29 schema, schedule, API, state,
locking, recovery, failure-isolation, production-pipeline, or cold-pass
contract.

## Dependency boundary

Issue #19 now owns the production extracted-reference parser and reuses these
stage/version boundaries. Issues #21 and #23 still own the user-initiated
private parcel importer and higher-precision address/parcel resolver. The
pipeline coordinates #19/#37/#38 and consumes already validated parcel
evidence; later dependency versions plug into the same stage interfaces and
automatically invalidate exactly the relevant work keys. The complete
operating procedure and deferred-queue trigger are in
[deterministic enrichment operations](ENRICHMENT_OPERATIONS.md).
