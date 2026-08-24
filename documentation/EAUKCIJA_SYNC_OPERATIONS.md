# eAukcija synchronization operations

The synchronization endpoint starts one durable ingestion run that discovers
configured root categories, validates every required page, deduplicates stable
auction IDs, verifies each direct-child membership endpoint, refreshes required
details, and promotes the complete result in one database transaction. The old
split listing/detail workflow is not part of this contract.

This workflow requires PostgreSQL. The transitional `local-h2` profile has no
durable run ledger or PostgreSQL advisory lock, so synchronization is
unavailable under that profile.

Before enabling scheduled use, read the
[source acceptable-use note](EAUKCIJA_SOURCE_ACCEPTABLE_USE.md).

## Start a run

Create a fresh idempotency key for one intended run and retain both that key and
the returned run ID until the request is resolved:

```bash
export EAUKCIJA_SYNC_KEY="$(uuidgen | tr '[:upper:]' '[:lower:]')"
curl --fail-with-body --include \
  --request POST \
  --header "Idempotency-Key: $EAUKCIJA_SYNC_KEY" \
  http://localhost:8081/api/sync/runs
```

A newly accepted run returns HTTP `202 Accepted`, a `Location` header pointing
to `/api/sync/runs/{runId}`, and a JSON body containing the run ID and current
status. The run is persisted before it is handed to the Spring-managed worker,
so the response does not mean ingestion has succeeded.

Retry the request with the **same** idempotency key when the first HTTP response
is lost or uncertain. It resolves to the same run ID instead of starting
duplicate work. An active replay returns `202`; a terminal replay returns `200`;
both carry `replayed: true`. A different key while another durable run claim is
active is rejected with HTTP `409 Conflict`; the structured response identifies
the active run. Raw idempotency keys are not retained in PostgreSQL: only their
SHA-256 values are stored.

Trigger failures are fixed `application/problem+json` contracts with
`Cache-Control: no-store`: `400 INVALID_IDEMPOTENCY_KEY`, `403 SYNC_LOCAL_ONLY`,
`409 SYNC_ALREADY_RUNNING`, and `503` for a disabled runtime, executor rejection,
or ledger failure. An executor/ledger `503` that contains `runId` means the claim
was already retained; poll its `statusUrl` or retry the same idempotency key.
Never replace that key merely because the HTTP response was uncertain.

The mutation endpoint is intended for the local operator. Do not expose it
through a public bind, proxy, or tunnel. A future externally reachable runtime
requires the separate authentication, authorization, and threat-model work in
issue #31.

## Read status

Poll the URL returned in `Location`, or use the run ID from the response:

```bash
export EAUKCIJA_RUN_ID="replace-with-returned-run-id"
curl --fail-with-body \
  "http://localhost:8081/api/sync/runs/$EAUKCIJA_RUN_ID"
```

Status responses are `no-store` and expose retained evidence rather than
process-local progress. The response includes timestamps, configured roots,
page size, taxonomy observation and hash, expected/completed pages, listing and
unique/duplicate/unknown counts, required/attempted/succeeded/failed detail
counts, retry counts, bounded errors, and per-root results.
The `childResults` array separately reports every captured direct child with its
parent root, totals, pagination, duplicates, subset proof, and completion state.

Status errors use the same no-store problem format: `400 INVALID_SYNC_RUN_ID`,
`404 SYNC_RUN_NOT_FOUND`, or `503 SYNC_LEDGER_UNAVAILABLE`. A `404` is terminal
for that stored ID (for example after an intentional database rebuild); clear
the local ID rather than polling forever. Retry a transient `503` with bounded
backoff.

The terminal states are:

| Status | Meaning | Auction state |
|---|---|---|
| `RUNNING` | The durable claim is awaiting or executing on the single worker; while executing it owns the session advisory lock. | The previously successful auction state remains visible. |
| `SUCCEEDED` | Taxonomy, every required root and direct-child page, required details, and the final promotion all completed consistently. | The complete candidate set was promoted atomically. |
| `PARTIAL` | Some source work completed, but a required later step failed or restart recovery found recorded progress. | The candidate set was not promoted; prior state remains unchanged. |
| `FAILED` | The run failed before usable source progress, or failed in a way that could not yield a complete candidate. | Prior state remains unchanged. |

Progress moves through `CLAIMED`, `CATEGORIES`, `LISTINGS`, `DETAILS`,
`PROMOTING`, and `COMPLETED`. A `COMPLETED` stage does not weaken the terminal
status: only `SUCCEEDED` is eligible to advance source-delta, absence, or
downstream-enrichment state.

## Completeness and atomic promotion

For each run the client fetches and hashes the current category tree, verifies
that every configured root still exists, and pages each root until its reported
unique total is satisfied. Totals must remain consistent across pages. Duplicate
stable IDs within or across pages are counted and deduplicated; a page that adds
no new ID fails instead of creating an unbounded loop. Stable auction IDs are
also unioned across roots before detail work, so an auction appearing in more
than one source category is fetched at most once.

Root endpoints alone define discovery. After all roots are complete, the run
pages every direct child captured beneath each configured root. A child ID must
already belong to its parent root; otherwise the run fails without adding that
auction. Child rows therefore cannot expand discovery or trigger another detail
request. They provide retained `CHILD` memberships and the only input to the
normalized `PARCEL`, `BUILDING`, or `UNIT` kind. Root-only records and records
seen only in a newly observed, unreviewed child remain `UNKNOWN`; detail prose or
raw detail category IDs never infer a kind.

The reviewed children are `47`/`48`/`49` beneath root `7` and
`121`/`124`/`135` beneath root `8`. Their disappearance, re-parenting, or scope
type drift fails at the taxonomy stage. A new direct child with the same scope
type is retained, fetched, reported, and classified conservatively as
`UNKNOWN`; the changed taxonomy hash and child result make that drift visible.
Promotion independently requires one complete subset result for the exact set
of direct children in the captured taxonomy.

With the 2026-08-21 measured shape and the default page size, a no-retry run
uses one taxonomy request, two root-page requests, six direct-child page
requests, and one detail request for each auction that is new, changed, or
stale. A from-empty 622-auction run is therefore about `631` physical requests;
at the reviewed two-request-per-second ceiling, the rate gate alone imposes a
lower bound of roughly 5.3 minutes before network and database time. A fresh,
unchanged run needs only the nine taxonomy/listing requests. Retries add to
these totals and are retained in the run evidence.

Details are required for new auctions, auctions whose summary changed, auctions
whose observed sale scope changed, and existing details older than the
configured staleness interval. The validated taxonomy routes root `7`
(`ImmovableProperties`) to
`GetImmovablePropertyDetails` and root `8` (`CommonProperties`) to
`GetCommonPropertyDetails`; an incompatible mixed scope fails closed before a
detail request. A null response,
invalid envelope or JSON, mismatched auction ID, timeout after the retry budget,
missing page, or inconsistent total prevents success. `detailsFetched` and its
timestamp advance only with validated detail data in a successful promotion.

Listing and detail candidates are not applied page by page. Promotion is one
transaction after all completeness checks pass. `PARTIAL` and `FAILED` runs
therefore preserve the complete previous auction state and cannot enqueue
enrichment.

## Restart and stale-run recovery

The worker owns a PostgreSQL session advisory lock through a dedicated
connection. A process exit releases that lock even if a retained run row still
says `RUNNING`.

On a normal application shutdown, the source client cancels its active HTTP
call and the managed executor interrupts rate/backoff waits. The worker then has
a five-second managed-lifecycle phase followed, if necessary, by a bounded
20-second termination window inside the launcher's 30-second stop grace. That
window lets it retain a terminal failure and release the session lock. A hard
crash still relies on stale-run recovery below.

During startup and before a replacement claim, recovery attempts to acquire the
same lock. It terminalizes an orphan only when the global advisory lock is free
**and** the run's heartbeat is at or before the stale cutoff (at least
`eaukcija.sync.running-stale-after` old). Requiring both conditions prevents
recovery from stealing the short claim-to-worker handoff. A crash can therefore
block a replacement run until the stale interval expires.

Recovery records the redacted `STALE_RUN_RECOVERED` code: `PARTIAL` when the
retained counters show source progress, otherwise `FAILED`. It does not resume
from a guessed page, alter auctions, or make the old run successful. A new
trigger with a new idempotency key can then claim a replacement run. Terminal
run evidence is immutable; do not repair it with manual SQL.

If a run appears stuck while its process is still alive, inspect status and the
application log first. Do not kill a database session merely to clear the lock.
Stop the managed application normally, restart it, confirm recovery of the old
run, and trigger a replacement.

## Errors and data minimization

Stored and returned errors use bounded stage codes and safe coordinates such as
root ID, direct-child ID, page number, auction ID, attempt, and retryability.
They never contain source response bodies, request or response headers,
credentials, complete URLs, personal data, exception dumps, or base64
thumbnails. Logs follow the same rule. Consult counters and fixed error codes
first; reproduce only against the committed synthetic/recorded test fixtures.

The client never retries malformed JSON, invalid application envelopes, null or
invalid detail data, or ordinary non-retryable `4xx` responses. It retries only
bounded timeout/I/O failures and HTTP `408`, `429`, `500`, `502`, `503`, and
`504`. A usable `Retry-After` is a minimum delay. If it is longer than the
configured maximum, the request fails visibly instead of retrying early.

## Optional scheduling

Scheduling is disabled by default with `eaukcija.sync.schedule-cron=-`. Keep it
disabled until the source acceptable-use note has been reviewed for the intended
deployment and cadence. A configured schedule uses the same durable claim,
database lock, rate gate, completeness checks, and atomic promotion as a manual
trigger; it cannot overlap an existing run. `eaukcija.sync.schedule-zone`
defaults to `UTC`, so an operator who enables a civil-time schedule must choose
and document the intended zone explicitly.

## Configuration defaults and safe bounds

The committed `application.properties` exposes explicit `EAUKCIJA_*`
environment overrides for the operational knobs; use the exact names there,
for example `EAUKCIJA_REQUESTS_PER_SECOND`. The dotted property names below can
also be supplied through normal Spring property sources. Invalid values fail
application startup before any source request is made.

| Property | Default | Validated bounds or rule |
|---|---:|---|
| `eaukcija.api.base-url` | `https://eaukcija.sud.rs/WebApi.Proxy/api/EAukcija` | Absolute HTTPS URI; no credentials, query, or fragment. |
| `eaukcija.api.root-category-ids` | `7,8` | `1`–`16` distinct positive integers. Root `2` is intentionally excluded. |
| `eaukcija.api.page-size` | `3000` | `1`–`3000`. Paging and total checks still apply when the source grows. |
| `eaukcija.api.connect-timeout` | `PT5S` | Greater than zero and at most `PT30S`. |
| `eaukcija.api.read-timeout` | `PT20S` | Greater than zero and at most `PT2M`. |
| `eaukcija.api.call-timeout` | `PT30S` | At least both transport timeouts and at most `PT5M`. |
| `eaukcija.api.max-attempts` | `3` | `1`–`5`, including the first attempt. |
| `eaukcija.api.retry-base-delay` | `PT0.5S` | Greater than zero and at most `PT1M`. |
| `eaukcija.api.retry-max-delay` | `PT10S` | At least the base delay and at most `PT1M`. |
| `eaukcija.api.max-retry-after` | `PT2M` | Greater than zero and at most `PT15M`; a longer source delay aborts the request. |
| `eaukcija.api.requests-per-second` | `2.0` | `0.1`–`10.0` across all calls. Do not raise the default without source review. |
| `eaukcija.api.max-concurrency` | `1` | `1`–`4`. Keep `1` unless explicitly reviewed and authorized. |
| `eaukcija.api.max-response-bytes` | `16777216` | `1024`–`268435456` bytes; oversized responses fail rather than exhaust memory. |
| `eaukcija.api.user-agent` | `aukcije-core/0.0.1` | Nonblank, single line, at most 200 characters. |
| `eaukcija.api.contact` | repository issues URL | Nonblank, single line, at most 200 characters; appended to the User-Agent. |
| `eaukcija.sync.enabled` | `true` | Boolean master switch; disabled means no run may be claimed. |
| `eaukcija.sync.detail-stale-after` | `P1D` | `PT1H`–`P30D`. |
| `eaukcija.sync.running-stale-after` | `PT15M` | `PT5M`–`PT12H`; recovery also requires the advisory lock to be free. |
| `eaukcija.sync.max-pages-per-root` | `10000` | `1`–`100000`; protects against corrupt or runaway source totals. |
| `eaukcija.sync.max-errors` | `100` | `1`–`1000`; additional failures remain counted but are not retained verbatim. |
| `eaukcija.sync.schedule-cron` | `-` | `-` disables scheduling; otherwise a valid Spring cron expression. |
| `eaukcija.sync.schedule-zone` | `UTC` | Valid Java `ZoneId`; applied only when a schedule is enabled. |

The validated maxima are emergency guardrails, not recommended operating
targets. The reviewed source posture is `2` requests per second and concurrency
`1`. Raising rate, concurrency, response size, or retry windows increases load
on an undocumented service and requires an explicit review of source policy and
observed capacity.

## Verifying a completed run

Do not treat HTTP `202` or a nonzero row count as success. Confirm the run itself
is terminal `SUCCEEDED`, has no unresolved errors, completed every expected
root/child page and required detail, and reports the expected configured roots,
direct children, subset proofs, and taxonomy hash. Then compare representative
Serbian text, prices, timestamps, municipality/category facets, and detail
timestamps. The retained run ID is the operator evidence for that promotion.
