# One-click auction-to-map refresh operations

Issue #40 makes the page action **Освежи све податке** the normal manual and
scheduled operating path. It is the only control that means the map has been
refreshed successfully. The stage-only sync and enrichment endpoints remain
available under **Напредне операторске контроле**, but finishing either one is
not map-readiness evidence.

## Normal manual use

Open <http://localhost:8081>, then activate **Освежи све податке**. The action
returns a durable workflow ID immediately and follows these persisted stages:

1. `Преузимање огласа`
2. `Преузимање детаља`
3. `Обрада локација`
4. `Припрема карте`

The page shows counts where the underlying stage has a meaningful total, start
and elapsed times, the last successful complete refresh, and the current daily
schedule. Reloading the page or opening another local tab follows the same
database-backed workflow. Repeated clicks attach to the active workflow; the
partial unique database index prevents duplicate manual/scheduled work.

The page announces success only after all of the following are correlated:

- the referenced #17 source run is `SUCCEEDED` and complete;
- the referenced #29 run is `SUCCEEDED`, uses the versions pinned by the
  workflow, and every eligible source snapshot is complete for those versions;
- a coarse-location population run references the workflow; and
- the server-side map readiness snapshot contains that exact resolution run and
  workflow with a non-null timestamp. The anonymous `/api/map/status` response
  deliberately omits those internal identifiers.

On success, the page shows mapped/total and selected-precision counts, refreshes
the current server-rendered list, map metadata, and visible GeoJSON without a
full-page reload, and retains focus predictably.

## Daily schedule and safe disable

The normal schedule defaults to 03:00 once per day in `Europe/Belgrade`:

```text
EAUKCIJA_REFRESH_ENABLED=true
EAUKCIJA_REFRESH_SCHEDULE_CRON=0 0 3 * * *
EAUKCIJA_REFRESH_SCHEDULE_ZONE=Europe/Belgrade
EAUKCIJA_REFRESH_POLL_INTERVAL=PT1S
EAUKCIJA_REFRESH_RUNNING_STALE_AFTER=PT15M
```

Set `EAUKCIJA_REFRESH_SCHEDULE_CRON=-` to pause automatic refreshes while
leaving the one-click action available. Set `EAUKCIJA_REFRESH_ENABLED=false`
to disable both claims and recovery. The old stage-only schedules default to
`-`; do not enable them for normal operation because they cannot assert map
readiness. Any scheduled source use must still follow
`EAUKCIJA_SOURCE_ACCEPTABLE_USE.md`.

The page calculates and displays the next scheduled occurrence from the same
cron/zone configuration used by Spring. Manual and scheduled triggers call the
same `RefreshCoordinator`, persistence aggregate, source/enrichment services,
map preparation, and readiness checks.

## Failure, retry, and last-good behavior

A failed stage is shown in Serbian with an assertive live announcement and a
**Покушај поново** action. The database retains only a fixed safe failure code;
stack traces, exception text, credentials, source payloads, descriptions, and
personal data are not stored or returned. A retry creates a new durable
workflow and uses deterministic child idempotency keys. Valid unchanged
enrichment state is reused only when its source snapshot and active parser,
resolver, and dataset versions still match.

Progress/success and failure announcements use separate static polite and
assertive live regions. When a state change swaps the visible action, keyboard
focus moves from retry to the busy primary action and from a failed primary
action to retry, rather than falling back to the page body. A terminal state
also clears the tab's retained idempotency key before another activation.

Failed source or enrichment work never publishes a new complete-refresh time.
A failed map-status confirmation never produces a success banner. Existing map
resolution evidence is append-only, so a failed/partial attempt does not clear
the previous usable map; the UI continues to show the actual last successful
complete-refresh time.

## Diagnostics and recovery

Use the page's **Дијагностика система** link or:

```text
GET /api/operator/refresh
GET /api/operator/refresh/{workflowId}
GET /api/operator/status
GET http://127.0.0.1:8082/actuator/health/readiness
```

Responses are `no-store`. Refresh reads are loopback-only. The mutation also
requires a loopback peer, same-origin browser metadata, a canonical
`Idempotency-Key`, and `X-Operator-Request: refresh-v1`, which prevents a remote
or cross-site page from starting local work.

On application startup, a retained active workflow is resubmitted to the
Spring-managed coordinator. Deterministic child keys reconnect a claim made
before a crash. If aggregate map preparation committed before the process
stopped, recovery recognizes its workflow-correlated resolution row and
completes without publishing a duplicate population run. Terminal evidence is
immutable. An interrupted child that is reconciled as failed leaves the
workflow failed and retryable; it is never silently relabeled successful.

`EAUKCIJA_REFRESH_RUNNING_STALE_AFTER` defaults to `PT15M` and accepts values
from `PT5M` through `PT12H`. Claim, startup, and retained status reads
atomically terminalize an expired `RUNNING` refresh as
`REFRESH_STALE_RECLAIMED`; the next fresh claim can proceed without an
application restart. The coordinator also compares linked source and
enrichment heartbeats with this lease so a child left `RUNNING` cannot keep the
four-stage workflow polling forever. Executor rejection during startup recovery
is terminalized as retryable evidence instead of leaving an unowned active row.

For stage-specific evidence and recovery details, also see
`EAUKCIJA_SYNC_OPERATIONS.md`, `ENRICHMENT_OPERATIONS.md`, and
`PIPELINE_OPERATIONS.md`.
