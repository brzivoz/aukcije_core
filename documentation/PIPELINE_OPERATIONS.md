# Pipeline status, alerts, and recovery

Issue #30 provides one local operator surface over the persisted evidence owned
by synchronization, enrichment, Address Registry import, spatial resolution,
and basemap activation. It does not scrape logs for counters and it does not
call the external eAukcija source while answering a status request.

## Access and response contract

Both operator surfaces are restricted to the direct loopback peer. Forwarded
headers are ignored deliberately.

```bash
curl --silent --show-error http://127.0.0.1:8081/api/operator/status | jq
open http://127.0.0.1:8081/operator/status
curl --fail-with-body http://127.0.0.1:8082/actuator/health/readiness | jq
```

The API and page use `Cache-Control: no-store`. The API returns retained IDs,
fixed error classes, hashes/versions, timestamps, durations, counts, and
distributions. It never returns source payloads, descriptions, thumbnails,
credentials, import exception messages, or filesystem paths.

Actuator has a separate listener bound explicitly to `127.0.0.1:8082`; the
application listener remains on port 8081. Readiness evaluations are cached for
`OPERATIONS_READINESS_CACHE_TTL` (default `PT5S`) so repeated probe traffic
cannot continuously execute the status aggregate queries.

The top-level states are:

| State | Meaning |
|---|---|
| `FRESH_AND_HEALTHY` | Readiness gates pass and no current state-demoting warning is active. |
| `SERVING_LAST_GOOD_DATA` | The last complete local publication remains available, but a later failed/partial attempt, retryable backlog, artifact failure, or freshness condition needs attention. `ready` says whether traffic should still be admitted. |
| `UNAVAILABLE` | No safe last-good serving combination exists, or a mandatory gate is unavailable. |

`lastAttempt` and `lastSuccessful` are intentionally separate. A partial or
failed refresh never makes an older successful publication look fresh.
`notices` contains retained terminal error evidence and normal activity such as
an active import; notices remain visible but do not turn fresh data into
`SERVING_LAST_GOOD_DATA`.

## Evidence traceability

| Displayed evidence | Durable authority |
|---|---|
| Source counts, delta, retries, error classes, status, stage, duration | immutable terminal `sync_runs`, `sync_run_errors`, and `pipeline_sync_run_metrics` |
| New/changed/unchanged raw local input snapshots | immutable `auction_enrichment_snapshot_observations` and `auction_enrichment_input_snapshots` |
| Parser/resolver/dataset versions and run outcomes | immutable terminal `enrichment_runs` / `enrichment_run_items` |
| Queue depth, oldest age, retry/error distribution | current durable `enrichment_state` work authority |
| Parser quality results | persisted `property_references` grouped by parser version and extraction status |
| Precision and resolver/source distributions | selected persisted `current_location_resolutions` joined to append-only attempts |
| Active/last Address Registry import and duration | terminal `address_registry_import_runs`; successful retention duration is joined from `address_registry_retention_jobs` |
| Post-commit retention outcome/duration | append-only `address_registry_retention_jobs` |
| Active Address Registry artifact | database active pointer plus immutable snapshot metadata |
| Active resolver dataset | latest completed immutable coarse-resolution run |
| Active basemap | checksum-validated immutable bundle selected by the durable `ACTIVE` pointer |
| Database/migration readiness | live database probe plus injected Flyway `info().pending()` and resolved/current versions |

All database-backed values can be reread after application restart. Basemap selection and
validation survive restart through the immutable bundle and atomic `ACTIVE`
pointer. V14 prevents updates/deletes of terminal import, structured KO,
coarse-resolution, and retention job evidence; sync/enrichment and raw snapshot
evidence already had equivalent guards.

An Address Registry importer acquires a dedicated PostgreSQL session advisory
lock before it creates the `RUNNING` row and holds it across download, staging,
GeoPackage validation, and the terminal promotion update. Startup recovery can
finalize a row as `IMPORT_PROCESS_RESTARTED` only when that session lock is
free. A server restart or second CLI process therefore cannot terminalize a
live import; a competing import action is retained as
`IMPORT_ALREADY_RUNNING` without entering `RUNNING`.
If releasing the dedicated session reports an error after a terminal update,
the terminal row remains authoritative and the importer emits only the fixed
`IMPORT_LOCK_RELEASE_FAILED` signal. A committed successful import still runs
its post-commit retention phase and returns success to the operator.

## Readiness and source-outage policy

Readiness fails closed when any of these is true:

- the database probe fails;
- Flyway reports any resolved migration as pending;
- no checksum-validated basemap is active;
- no successful source synchronization exists;
- the last successful synchronization is older than
  `OPERATIONS_SYNC_STALE_AFTER` (default `PT26H`).

The installation is a local/private, last-good-data service. A temporary
external eAukcija timeout, I/O failure, rate limit, or retryable HTTP failure
therefore creates `EXTERNAL_SOURCE_OUTAGE_SERVING_LAST_GOOD` but does not by
itself fail readiness while the successful local publication is still fresh.
It fails readiness when that publication crosses the freshness threshold. Do
not switch to browser scraping, bypass TLS, increase concurrency, or silently
raise source rates during an outage.

A rejected new basemap pointer also keeps serving the previously validated
bundle and raises a warning. Readiness fails only if no validated bundle remains.

## Alert thresholds

The defaults are intentionally conservative and can be set with ISO-8601
durations or positive counts:

| Signal | Default | Action |
|---|---:|---|
| Successful sync age | warning on later failed/partial attempt; readiness down after `PT26H` | Page immediately on readiness down; investigate warning within one operating hour. |
| Enrichment backlog depth | `100` (`OPERATIONS_BACKLOG_MAX_DEPTH`) | Investigate when exceeded for two consecutive checks. |
| Oldest enrichment backlog age | `PT2H` (`OPERATIONS_BACKLOG_MAX_AGE`) | Investigate on first breach; page if still growing after one hour. |
| Database/migration/basemap unavailable | immediate | Page immediately. Do not route around the gate. |
| Address Registry import failed | immediate warning | Inspect the fixed error code before the next planned refresh; the previous active snapshot remains authoritative. |
| Retryable enrichment failures | immediate warning | Inspect distribution and bounded run-item evidence before replay. Terminal permanent/attempt-limit evidence is an informational notice. |

Monitor readiness at most once every 30 seconds and alert only on transitions or
the sustained intervals above. The five-second indicator cache bounds database
evaluation even if a probe is accidentally more aggressive. The operator page
already refreshes every 30 seconds; it does not create source traffic.

## Diagnostic sequence

1. Capture the status and correlation ID without modifying state.

   ```bash
   export STATUS_EVIDENCE="$(mktemp)"
   curl --silent --show-error --dump-header - \
     http://127.0.0.1:8081/api/operator/status \
     | tee "$STATUS_EVIDENCE" | jq
   ```

2. Check `readinessFailures` before `warnings`. Confirm `sync.lastAttempt` and
   `sync.lastSuccessful` are different when a refresh failed.
3. Follow the retained `runId` in the existing payload-safe endpoints:

   ```bash
   curl --fail-with-body \
     "http://127.0.0.1:8081/api/sync/runs/<run-id>" | jq
   curl --fail-with-body \
     "http://127.0.0.1:8081/api/enrichment/runs/<run-id>" | jq
   ```

4. Check the active artifact warning/version before changing any pointer. Never
   edit a manifest, retained run, or Flyway history row manually.

## Recovery by signal

- `DATABASE_UNAVAILABLE`: stop write actions, verify the configured profile,
  network, credentials, PostgreSQL/PostGIS health, and disk capacity. Restore a
  reviewed backup into a separate target if required. Do not use `flyway repair`
  or `ddl-auto=update`.
- `MIGRATIONS_NOT_CURRENT`: compare the deployed JAR/commit with Flyway history.
  Deploy the intended application so normal Flyway validation/migration runs;
  do not edit checksums or history.
- `BASEMAP_UNAVAILABLE`: follow
  [local basemap serving operations](BASEMAP_SERVING_OPERATIONS.md). Validate and
  activate a known immutable build; never point directly at an unvalidated file.
- `NO_SUCCESSFUL_SYNC` / `SUCCESSFUL_SYNC_STALE`: inspect the last attempted run
  and source state, then follow
  [eAukcija synchronization operations](EAUKCIJA_SYNC_OPERATIONS.md). Use a new
  idempotency UUID for an intentional new run. Poll until terminal.
- `SYNC_LAST_ATTEMPT_PARTIAL` / `FAILED`: inspect retained stage/error classes.
  The prior current auction state is unchanged. Correct the local/source issue
  and trigger a new run; never promote partial observations manually.
- `EXTERNAL_SOURCE_OUTAGE_SERVING_LAST_GOOD`: retain the last good dataset,
  respect the documented rate policy, and retry only at the scheduled/operator
  cadence. Escalate when the freshness budget approaches its threshold.
- `ENRICHMENT_BACKLOG_THRESHOLD_EXCEEDED`: check pause state, active run, oldest
  age, versions, and error distribution. Resume or issue one bounded replay by
  the documented selector only after the cause is corrected; see
  [enrichment operations](ENRICHMENT_OPERATIONS.md).
- `ADDRESS_REGISTRY_LAST_IMPORT_FAILED`: use the fixed error code and
  [Address Registry operations](ADDRESS_REGISTRY_OPERATIONS.md). The active
  pointer remains on the previous validated snapshot.
- `IMPORT_PROCESS_RESTARTED`: the previous process exited with a `RUNNING`
  import row. Startup reconciliation finalized it as failed; confirm the active
  pointer, then start a new import with a new job ID.
- `IMPORT_ALREADY_RUNNING`: another import, rollback, or retention action owns
  the shared lock. Do not start parallel work; inspect the active run and retry
  only after the owning action is terminal.
- `IMPORT_LOCK_FAILED`: the importer could not query PostgreSQL for the shared
  lock. Verify database connectivity, session capacity, and pool/database logs;
  retry only after the database is stable. The attempt may have no run row when
  the database failure also prevents evidence persistence.
- `IMPORT_LOCK_RELEASE_FAILED`: a terminal import or rollback result was
  already persisted, and that terminal result remains authoritative. The
  dedicated connection was aborted/closed defensively; inspect database and
  pool logs without rewriting the terminal row. A successful import continues
  into the separately recorded retention phase and returns success.
- `ADDRESS_REGISTRY_RETENTION_FAILED`: the new snapshot is already active and
  must not be rolled back implicitly. Inspect retained snapshot count and run
  the documented operator cleanup only after verifying active/previous IDs.
- `BASEMAP_LAST_ACTIVATION_REJECTED`: keep serving the prior active artifact,
  validate the candidate offline, and activate again atomically.

## Structured logging and redaction

HTTP responses carry `X-Correlation-ID`. A caller-supplied ID is accepted only
when it matches `[A-Za-z0-9._-]{1,64}`; otherwise the application creates a
UUID. The managed sync/enrichment worker propagates that correlation context.
Terminal job logs also include the persisted `runId` or `jobId` and a fixed
error code.

Log messages use key/value fields. Do not add request bodies, query strings,
source JSON, descriptions, thumbnails, exception messages, credentials,
filesystem paths, or unrelated personal data. Status failure paths deliberately
log only fixed codes even when the underlying exception contains sensitive
content.

## Evidence growth policy

The immutability triggers intentionally make structured-KO and coarse-location
run evidence append-only. There is currently no pruning path, so those run
tables grow with every execution. Monitor table and index size during normal
database capacity reviews. A future retention policy must preserve externally
referenced run IDs and audit requirements; manual `DELETE`, trigger disabling,
and ad-hoc history rewrites are not supported recovery actions.
