# Deterministic enrichment operations

Issue #29 replaces a leased multi-worker queue with one deterministic,
single-threaded reprocessor. Work is derived from immutable local input and
active parser/resolver/dataset versions; it never refetches eAukcija and never
contacts RGZ or an online geocoder. PostgreSQL retains current per-auction state
and append-only run/item evidence.

The `local-h2` compatibility profile deliberately disables this subsystem. All
commands below require the normal PostgreSQL/PostGIS runtime.

## Publication and work identity

Only the atomic promotion transaction of a `SUCCEEDED` synchronization run may
publish an enrichment input. It writes:

- an immutable minimized JSON snapshot in
  `auction_enrichment_input_snapshots`, keyed by auction ID and SHA-256; source
  values are canonicalized while sync-only clocks such as `detailsFetchedAt`
  are excluded so a no-change refresh does not invent work;
- an immutable success-gated observation linking that snapshot to its source
  sync run; and
- `auctions.current_enrichment_snapshot_sha256`, which is protected by a
  composite foreign key.

A database trigger independently rejects snapshot observations whose source
sync run is not `SUCCEEDED`. `PARTIAL`, `FAILED`, and still-running sync runs
therefore cannot create enrichment work even through accidental direct SQL.
Pre-V13 auctions are bootstrapped only when they already carry a successful
sync observation.

The canonical work key is a length-prefixed SHA-256 of:

```text
auction ID
current immutable input snapshot SHA-256
active parser version
active resolver-set version
active dataset-set version
current persisted parcel-evidence dependency SHA-256
```

`enrichment_state` keeps one current row per auction: that exact identity,
status, pending/last-attempt/completion times, attempt count, last stage,
deterministic output hash, and bounded redacted error codes. A normal run
selects only a missing or changed work key, `PENDING` interrupted work, or a
`RETRYABLE_FAILURE` below its attempt cap. An unchanged terminal row is not
selected.

## Fixed stage order

Every selected auction runs in its own transaction, in this exact order:

1. `PARSE` — persist the deterministic structured `Place` reference available
   in the accepted snapshot;
2. `KO_MATCHING` — use the checksum-validated active local KO dictionary;
3. `PARCEL_PATH` — consume only already validated, private local parcel
   evidence;
4. `ADDRESS_FALLBACK` — run the available local resolution ladder without an
   online geocoder;
5. `SELECTED_RESOLUTION` — retain the best lawful result and classify
   resolved, not-found, or ambiguous.

The current fallback includes the shipped #38 KO/settlement/municipality/`NONE`
resolver. The higher-precision #19/#21/#23 implementations plug into the same
persisted boundaries when those dependency issues land; their parser,
resolver, dataset, or parcel-evidence changes alter the work key and select
only affected auctions. A coarse result can never replace a retained address
or verified parcel result.

All stages read local snapshots or artifacts. The private parcel stage does not
perform the user-initiated RGZ import and the application has no RGZ network
client in this path.

## Enable and schedule

The subsystem is enabled for PostgreSQL profiles, but its schedule is disabled
by default. Enable a cadence only after the sync cadence and local artifact
publication workflow are understood:

```text
EAUKCIJA_ENRICHMENT_ENABLED=true
EAUKCIJA_ENRICHMENT_SCHEDULE_CRON=0 15 * * * *
EAUKCIJA_ENRICHMENT_SCHEDULE_ZONE=Europe/Belgrade
EAUKCIJA_ENRICHMENT_MAX_ATTEMPTS=3
EAUKCIJA_ENRICHMENT_MAX_ITEMS_PER_RUN=1000
EAUKCIJA_ENRICHMENT_MAX_REPLAY_ITEMS=1000
```

Bounds are fail-fast:

| Setting | Allowed | Default |
|---|---:|---:|
| `max-attempts` | 1–20 | 3 |
| `max-items-per-run` | 1–1,000 | 1,000 |
| `max-replay-items` | 1–1,000 | 1,000 |
| schedule | `-` or valid Spring cron | `-` |
| zone | valid IANA zone | `UTC` |

There is one Spring enrichment schedule and no lease, owner, expiry, per-item
timer, jitter, or backoff queue. It submits to the same capacity-zero,
single-threaded `syncRunExecutor` used by #17 and acquires the same PostgreSQL
session advisory worker lock. A unique partial index also prevents two retained
`RUNNING` enrichment claims. A concurrent sync or enrichment attempt therefore
serializes or fails safely instead of creating a second concurrency model.
The coordinator rechecks the active version set after every item transaction.
If an artifact pointer changes during a run, that run fails safely, the
in-flight state returns to `PENDING`, and the next bounded run uses the new work
key; results can never be acknowledged under a stale version set.

## Start and inspect a run

Mutation endpoints trust the servlet peer address, not forwarding headers, and
accept only loopback clients. Keep the API on the local/private runtime.

```bash
export ENRICHMENT_KEY="$(uuidgen | tr '[:upper:]' '[:lower:]')"
curl --fail-with-body --include \
  --request POST \
  --header "Idempotency-Key: $ENRICHMENT_KEY" \
  http://localhost:8081/api/enrichment/runs
```

A new claim returns `202 Accepted`, `Cache-Control: no-store`, and a `Location`
header. Retrying the same UUID returns the same run; a terminal replay returns
`200 OK`. Only the idempotency-key SHA-256 is stored.

```bash
export ENRICHMENT_RUN_ID="replace-with-returned-run-id"
curl --fail-with-body \
  "http://localhost:8081/api/enrichment/runs/$ENRICHMENT_RUN_ID"
```

The retained response contains versions, selector, bounded counters, item
auction IDs, work hashes, attempts, stages, outcomes, and fixed error codes. It
does not contain canonical input JSON or source payloads.

Run states are:

| State | Meaning |
|---|---|
| `RUNNING` | Retained claim is executing or awaiting the shared worker lock. |
| `SUCCEEDED` | Every selected attempt ended as resolved, not-found, or ambiguous, with no retry/permanent/capped failures. A zero-candidate replay is also successful. |
| `PARTIAL` | The run continued after one or more isolated retryable, permanent, or capped failures. |
| `PAUSED` | An operator pause was observed between auction transactions; untouched work remains discoverable. |
| `FAILED` | Submission/lock/run-level infrastructure failed; any in-flight item was reset to `PENDING`. |
| `INTERRUPTED` | Startup recovery terminalized a run left `RUNNING` by a process exit. |

Per-auction current states distinguish `PENDING`, `RUNNING`, `SUCCEEDED`,
`RETRYABLE_FAILURE`, `TERMINAL_NOT_FOUND`, `AMBIGUOUS`,
`PERMANENT_FAILURE`, and `ATTEMPT_LIMIT_REACHED`. Retryable failures are picked
up by the next normal run. The attempt that reaches the configured cap becomes
terminal; there is no background backoff timer.

## Backlog and safe status

The public read-only operational summary exposes no snapshot JSON:

```bash
curl --fail-with-body http://localhost:8081/api/enrichment/status
```

It returns the active version set, durable pause flag, active run ID, backlog
size, `oldestPendingSince`, and the full current-state distribution. For local
database diagnosis, equivalent payload-free queries are:

```sql
SELECT status, count(*)
FROM enrichment_state
GROUP BY status
ORDER BY status;

SELECT count(*) AS unfinished,
       min(pending_since) AS oldest_unfinished
FROM enrichment_state
WHERE status IN ('PENDING', 'RUNNING', 'RETRYABLE_FAILURE');

SELECT id, trigger_kind, status, started_at, finished_at,
       candidate_count, attempted_count, succeeded_count,
       retryable_failure_count, permanent_failure_count,
       attempt_limit_count
FROM enrichment_runs
ORDER BY started_at DESC
LIMIT 20;
```

The API's backlog additionally includes auctions whose immutable input,
dependency hash, or active version no longer matches their stored state, so it
is the authoritative readiness view. For an unchanged retryable work key it
uses the durable `pending_since`, not the time of a newer no-change sync
observation, so unresolved work cannot appear artificially younger.

## Pause, resume, and bounded replay

Pause is a durable database control. It prevents new claims immediately and an
active run stops between auction transactions:

```bash
curl --fail-with-body --request POST http://localhost:8081/api/enrichment/pause
curl --fail-with-body --request POST http://localhost:8081/api/enrichment/resume
```

Resume does not mutate terminal evidence or bulk-reset state. The next schedule
or manual run deterministically discovers the remaining work.

Replay requires a new UUID, an explicit selector, and a hard item bound. Exactly
one selector is accepted. Examples:

```bash
export REPLAY_KEY="$(uuidgen | tr '[:upper:]' '[:lower:]')"
curl --fail-with-body --request POST \
  --header "Idempotency-Key: $REPLAY_KEY" \
  --header 'Content-Type: application/json' \
  --data '{"auctionId":180466,"maxItems":1}' \
  http://localhost:8081/api/enrichment/replays
```

Selector bodies are:

```json
{"sourceSyncRunId":"00000000-0000-4000-8000-000000000000","maxItems":500}
{"enrichmentRunId":"00000000-0000-4000-8000-000000000000","maxItems":100}
{"auctionId":180466,"maxItems":1}
{"version":"parser-v2","maxItems":500}
```

Replay is intentionally non-destructive: it creates a retained run and new
attempt evidence but never deletes or mass-resets current rows. There is no
bulk-retry endpoint.

## Restart recovery and failure isolation

`startItem` commits a durable `RUNNING` state/item before its auction
transaction starts. If the process exits:

1. PostgreSQL releases the session advisory lock;
2. application startup acquires that same #17 worker lock;
3. retained `RUNNING` runs become `INTERRUPTED`, and their in-flight state
   becomes `PENDING` while append-only interruption evidence is retained;
4. one idempotent `RECOVERY` run discovers all accepted unfinished work,
   including items the old run had not started; and
5. deterministic stage upserts make a committed-but-not-yet-acknowledged item
   safe to execute again.

Every auction has its own transaction. A stage failure rolls back only that
auction's derived writes, records a bounded safe code, updates the run counter,
and allows the next auction to continue. Exception messages, SQL text, source
JSON, headers, credentials, cookies, and personal fields are not copied into
the ledger, API, or scheduler logs.

Do not repair `enrichment_runs` or `enrichment_run_items` with SQL. Terminal
evidence is trigger-protected. Inspect status, pause if necessary, correct the
local artifact/configuration, then use a bounded replay or normal version bump.

## Measured cold pass and deferred queue trigger

On 2026-08-25, the production five-stage pipeline processed a cold 601-auction
fixture through real PostgreSQL/PostGIS on one thread in **3,667 ms**. Exactly
600 auctions completed and one intentionally incompatible KO artifact failed
at `ADDRESS_FALLBACK`; the run continued, and its unchanged follow-up selected
zero items. The source client was mocked and verified to have zero interactions.
The containing JUnit method took 4.256 seconds.

Reproduce the measurement with:

```bash
./gradlew test \
  --tests 'rs.sud.eaukcija.coarselocation.EnrichmentPipelinePostgisIntegrationTest' \
  --no-daemon
rg 'ISSUE_29_COLD_REPROCESS' \
  build/test-results/test/TEST-rs.sud.eaukcija.coarselocation.EnrichmentPipelinePostgisIntegrationTest.xml
```

This is a deterministic fixture benchmark, not a promise for future
higher-precision stages. It is far below the 30-minute threshold. Open a
separate leased-queue issue only if sustained backlog exceeds roughly 5,000,
a measured full cold pass exceeds roughly 30 minutes, or a slow external
dependency makes per-item parallelism necessary. Do not expand this coordinator
in place merely to anticipate those conditions.
