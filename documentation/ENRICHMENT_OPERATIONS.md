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
  `auction_enrichment_input_snapshots`, keyed by auction ID and SHA-256; input
  is `enrichment-location-input-v2` and contains the exact source-snapshot
  SHA-256, auction identity, structured `Place` fields, `Description`, and
  `ShortDescription` consumed by the shipped stages. Price, status, and
  sync-bookkeeping changes do not invent work;
- an immutable success-gated observation linking that snapshot to its source
  sync run; and
- `auctions.current_enrichment_snapshot_sha256`, which is protected by a
  composite foreign key.

A database trigger independently rejects snapshot observations whose source
sync run is not `SUCCEEDED`. `PARTIAL`, `FAILED`, and still-running sync runs
therefore cannot create enrichment work even through accidental direct SQL.
Source-backed pre-v2 auctions are upgraded from their retained issue-#10
snapshot. Historical pre-issue-#10 inputs remain discoverable for compatible
replay and are not silently dropped; the versioned parser fails those auctions
in isolation until a source-backed v2 input exists.

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
status, pending/last-attempt/completion times, total start count, independent
retryable-failure and interruption counts, last stage, deterministic output
hash, and bounded redacted error codes. A normal run
selects only a missing or changed work key, `PENDING` interrupted work, or a
`RETRYABLE_FAILURE` below its attempt cap. An unchanged terminal row is not
selected.

## Fixed stage order

Every selected auction runs in its own transaction, in this exact order:

1. `PARSE` — persist the structured `Place` reference first, then every
   normalized reference found in `Description` and `ShortDescription`;
2. `KO_MATCHING` — refresh structured #37 matching, then match and explicitly
   reconcile every current extracted #19 KO against the same checksum-validated
   local dictionary;
3. `PARCEL_PATH` — consume only already validated, private local parcel
   evidence;
4. `ADDRESS_FALLBACK` — run the available local resolution ladder without an
   online geocoder;
5. `SELECTED_RESOLUTION` — retain the best lawful result and classify
   resolved, not-found, or ambiguous.

The current fallback includes the shipped #38 KO/settlement/municipality/`NONE`
resolver. Issue #19 now supplies the versioned extracted-reference parser;
#21/#23 plug private parcel and higher-precision resolution into the same
persisted boundaries. Parser, resolver, dataset, or parcel-evidence changes
alter the work key and select only affected auctions. A coarse result can never
replace a retained address or verified parcel result.

All stages read local snapshots or artifacts. The private parcel stage does not
perform the user-initiated RGZ import and the application has no RGZ network
client in this path.

## Property-reference extraction

The production parser version is `property-reference-v1`. It is bounded to
32,768 characters per field, 65,536 characters across the accepted input, and
256 references per auction. It treats markup as inert text and rejects unsafe
control characters. Extraction order is stable: structured `Place`, then
`detail.Description`, then `detail.ShortDescription`, with source-field names,
UTF-16 offsets, and raw evidence retained for every text match.

Each row keeps the original and normalized KO names, original and canonical
parcel number as text, land-register or address components, parser version,
extraction status, source/input hashes, and a canonical key. Cyrillic/Latin,
slash, whitespace, and punctuation variants normalize without replacing the
raw evidence. Multiple references are emitted and identical canonical keys are
deduplicated. Folio, area, object-part, and subparcel contexts are not promoted
to parcel identities.

`Place.Cadastral` is the default KO context. A disagreeing free-text KO or
multiple distinct text KOs set `NEEDS_REVIEW`; no KO code is guessed.
`NO_STRUCTURED_REFERENCE` means only that the `Place` structure was absent.
It is independent of geospatial not-found/ambiguity and does not prevent valid
free-text references from being `EXTRACTED`. The #38 resolver reuses the
current structured reference and may attach a uniquely matched KO code, but it
does not rewrite extraction evidence/status or a reviewed row.

V17 retains one immutable `property_reference_extraction_runs` row per auction,
input hash, and parser version; immutable memberships snapshot the selected
set, and `current_property_reference_extractions` is the only replaceable
pointer. `property_reference_extraction_memberships.reference_order` is the
authoritative order for that run. The older `property_references.reference_order`
column is only a non-authoritative first-seen value retained for compatibility;
V17 removes its per-auction/parser uniqueness constraint. Running the same
input/version reuses the same run, rows, and result hash. A changed input with
the same parser, or a new parser version, creates a new run and atomically
advances the current pointer while retaining earlier run JSON/memberships.
Existing `user_reviewed` references are carried forward and never updated by
the parser.

Issue #33 then writes immutable per-reference match results and a replaceable
current pointer. Exact structured/text disagreements are `AMBIGUOUS` with
`STRUCTURED_CONFLICT`; neither code reaches `property_references.ko_code`.
Identical results are reused and each production enrichment run observes the
exact immutable result fingerprint it consumed. See
[extracted KO matching operations](EXTRACTED_KO_MATCH_OPERATIONS.md) for the
decision ladder, reconciliation matrix, provenance, population command, and
operator queries.

The immutable run JSON includes both raw parser output and the exact selected
row values, including reviewed corrections.

Inspect a current set without exposing descriptions:

```sql
SELECT run.auction_id, run.parser_version, run.result_sha256,
       run.generated_reference_count, run.selected_reference_count,
       run.text_reference_count, run.no_structured_count, run.ko_conflict_count,
       member.reference_order, reference.reference_type,
       reference.normalized_ko, reference.ko_code,
       reference.canonical_parcel_number, reference.land_register_number,
       reference.extraction_status, reference.canonical_key,
       reference.user_reviewed
FROM current_property_reference_extractions current_set
JOIN property_reference_extraction_runs run ON run.id = current_set.extraction_run_id
JOIN property_reference_extraction_memberships member
  ON member.extraction_run_id = run.id
JOIN property_references reference ON reference.id = member.reference_id
ORDER BY run.auction_id, member.reference_order;
```

The issue-#18 corpus gate is included in `check` and can be run directly:

```bash
./gradlew propertyReferenceParserDevelopmentCheck
./gradlew propertyReferenceParserCheck
```

The first command prints development-only errors and leaves held-out labels
sealed. The second evaluates the frozen parser, requires at least 95% held-out
precision, 88% held-out recall, zero false positives on annotated negatives,
and category recall floors when a category has at least five references. It
also byte-compares the committed metrics report and verifies the SHA-256 and
aggregate values in the production quality profile. Every extraction and
enrichment run records that corpus/profile identity.

## Enable and schedule

The subsystem is enabled for PostgreSQL profiles, but its stage-only schedule
defaults to `-`. Issue #40 owns the normal once-daily cadence through the
source-to-map refresh coordinator. Enable this advanced schedule only for an
explicit maintenance reason; it does not prove that the map is ready:

```text
EAUKCIJA_ENRICHMENT_ENABLED=true
EAUKCIJA_ENRICHMENT_SCHEDULE_CRON=-
EAUKCIJA_ENRICHMENT_SCHEDULE_ZONE=Europe/Belgrade
EAUKCIJA_ENRICHMENT_MAX_ATTEMPTS=3
EAUKCIJA_ENRICHMENT_MAX_INTERRUPTIONS=3
EAUKCIJA_ENRICHMENT_RUNNING_STALE_AFTER=PT15M
EAUKCIJA_ENRICHMENT_MAX_ITEMS_PER_RUN=1000
EAUKCIJA_ENRICHMENT_MAX_REPLAY_ITEMS=1000
```

Bounds are fail-fast:

| Setting | Allowed | Default |
|---|---:|---:|
| `max-attempts` | 1–20 | 3 |
| `max-interruptions` | 1–20 | 3 |
| `running-stale-after` | 5 minutes–12 hours | 15 minutes |
| `max-items-per-run` | 1–1,000 | 1,000 |
| `max-replay-items` | 1–1,000 | 1,000 |
| schedule | `-` or valid Spring cron | `-` |
| zone | valid IANA zone | `Europe/Belgrade` |

The optional advanced Spring enrichment schedule has no lease, owner, expiry, per-item
timer, jitter, or backoff queue. It submits to the same capacity-zero,
single-threaded `syncRunExecutor` used by #17 and acquires the same PostgreSQL
session advisory worker lock. A unique partial index also prevents two retained
`RUNNING` enrichment claims. A tick that loses contention to sync is retained
as `SKIPPED` and logged at `INFO`; it is not an operational failure.
The coordinator pins the immutable KO dictionary and centroid snapshot once on
the worker thread, verifies that pinned set against the claimed work version,
and holds it for the whole run. It therefore avoids per-item ACTIVE-file reads
and cannot mix old and new artifacts. If a pointer is published mid-run, the
current run finishes consistently on its pinned work key and the next bounded
run sees the new active version and selects the affected auctions.

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
`200 OK`. A retry while that same run is still healthy returns its own retained
run with `replayed=true` before overlap/staleness checks; only a different key
receives the overlap `409`. Only the idempotency-key SHA-256 is stored.

```bash
export ENRICHMENT_RUN_ID="replace-with-returned-run-id"
curl --fail-with-body \
  "http://localhost:8081/api/enrichment/runs/$ENRICHMENT_RUN_ID"
```

The retained response contains versions, selector, bounded counters, item
auction IDs, work hashes, attempts, stages, outcomes, fixed error codes,
property-extraction success/failure counts, property/text-reference counts,
missing-structure/conflict counts, and the frozen corpus/metrics identity. It
does not contain canonical input JSON, source payloads, descriptions, or raw
reference evidence.

Run states are:

| State | Meaning |
|---|---|
| `RUNNING` | Retained claim is executing or awaiting the shared worker lock. |
| `SUCCEEDED` | Every selected attempt ended as resolved, not-found, or ambiguous, with no retry/permanent/capped failures. A zero-candidate replay is also successful. |
| `PARTIAL` | The run continued after one or more isolated retryable, permanent, or capped failures. |
| `PAUSED` | An operator pause was observed between auction transactions; untouched work remains discoverable. |
| `SKIPPED` | The shared worker was occupied by sync; no enrichment item started and the next tick may claim the work. |
| `FAILED` | Submission/lock/run-level infrastructure failed; any in-flight item was reset to `PENDING`. |
| `INTERRUPTED` | Startup recovery terminalized a run left `RUNNING` by a process exit. |

Per-auction current states distinguish `PENDING`, `RUNNING`, `SUCCEEDED`,
`RETRYABLE_FAILURE`, `TERMINAL_NOT_FOUND`, `AMBIGUOUS`,
`PERMANENT_FAILURE`, and `ATTEMPT_LIMIT_REACHED`. Retryable failures are picked
up by the next normal run. The attempt that reaches the configured cap becomes
terminal; there is no background backoff timer. Process interruption uses its
own counter: it does not consume the retryable-failure budget, and the third
interruption of one work key becomes `ATTEMPT_LIMIT_REACHED` with the fixed
`INTERRUPTION_LIMIT_REACHED` code. A later deterministic success, terminal
not-found, or ambiguous outcome resets both failure budgets for a future
explicit replay of the same work key.

## Backlog and safe status

The public read-only operational summary exposes no snapshot JSON:

```bash
curl --fail-with-body http://localhost:8081/api/enrichment/status
```

It performs no bootstrap or other writes. It returns the active version set,
durable pause flag, active run ID, backlog size, `oldestPendingSince`,
`populationGapCount`, and the full current-state distribution. The population
gap count exposes successful-sync auctions missing a current snapshot or
matching success-gated observation instead of silently omitting them. For local
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
       attempt_limit_count, property_reference_extraction_success_count,
       property_reference_parse_failure_count,
       property_reference_count, text_reference_count,
       no_structured_reference_count, ko_conflict_count,
       property_reference_quality_corpus_version,
       property_reference_quality_metrics_sha256
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
   becomes `PENDING` while append-only interruption evidence is retained
   (or reaches the separate bounded interruption limit);
4. one idempotent `RECOVERY` run discovers all accepted unfinished work,
   including items the old run had not started; and
5. deterministic stage upserts make a committed-but-not-yet-acknowledged item
   safe to execute again.

Startup recovery is opportunistic rather than one-shot: if the shared lock is
busy or recovery temporarily fails, every later manual/scheduled start checks
the retained heartbeat. A run older than `running-stale-after` is recovered
under the shared worker lock before the new claim, so a dead executor thread
cannot wedge the unique `RUNNING` slot until another process restart.

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
fixture through real PostgreSQL/PostGIS on one thread in **3,506 ms**. Exactly
600 auctions completed and one intentionally incompatible KO artifact failed
at `ADDRESS_FALLBACK`; the run continued, and its unchanged follow-up selected
zero items. The source client was mocked and verified to have zero interactions.
The containing JUnit method took 3.770 seconds.

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
