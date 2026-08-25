# Issue #30 verification — pipeline observability and operator status

## Delivered contract

- V14 adds indexed observability queries, immutable terminal import/resolver
  evidence, and a separate append-only post-commit retention-job ledger.
- `GET /api/operator/status` and `/operator/status` are loopback-only,
  `no-store`, restart-stable views of sync, snapshot, parser, enrichment,
  precision, import, artifact, backlog, failure, version, and duration evidence.
- `/actuator/health` and `/actuator/health/readiness` include the fail-closed
  pipeline indicator on a dedicated `127.0.0.1:8082` listener; database,
  Flyway pending migrations, active basemap, and successful-sync freshness are
  mandatory. A five-second cache bounds aggregate-query frequency.
- External source outages retain readiness only while last-good data is within
  the configured local/private freshness window.
- Bounded correlation IDs are returned to HTTP clients and propagated to the
  single managed worker. Logs use fixed key/value codes and never include
  source payloads or exception messages on status/import failure paths.

## Review remediation

- Migration currentness comes from the injected Flyway bean's pending set; no
  schema-version constant can drift when a migration is added.
- Transaction-acquisition, JDBC, and Flyway failures produce the explicit
  payload-safe `DATABASE_UNAVAILABLE` readiness code.
- Terminal enrichment errors and a normal active import are `notices`, while
  retryable work and real failed/partial last-good conditions remain
  state-demoting `warnings`.
- The importer owns a session-scoped advisory lease from before `RUNNING`
  through staging, validation, and terminal update. Startup recovery finalizes
  only an unlocked abandoned row as `IMPORT_PROCESS_RESTARTED`; a concurrent
  JVM is retained as `IMPORT_ALREADY_RUNNING` without touching the live row.
- A lease-release failure after the terminal update is redacted to
  `IMPORT_LOCK_RELEASE_FAILED`; persisted terminal evidence controls the CLI
  result, and a committed successful import still executes retention.
- Successful import duration joins the terminal promotion phase to its
  append-only retention job; rollback actions cannot replace the last
  successful import shown by status.
- A successful latest sync reuses one metric instance, avoiding a second raw
  snapshot-change query.
- `tmp/` is ignored so locally retained source PDFs cannot enter a commit.
- The append-only structured-KO/coarse-run growth trade-off and lack of a safe
  pruning path are documented in the operations runbook.

## Acceptance coverage

The focused issue suite covers fresh success, partial last attempts, stale last
success, queue depth/age, failed imports, source outages, fail-closed database /
migration / basemap gates, loopback/forwarded-header policy, redaction,
correlation propagation, durable restart reads, raw snapshot delta derivation,
terminal evidence mutation rejection, automatic pending-migration detection,
database transaction failure mapping, fresh-state classification with terminal
errors/active imports, abandoned-import reconciliation, successful-import
duration joining, rollback filtering, cached readiness, and separate Actuator
listener behavior. A blocking-stager concurrency test proves recovery cannot
terminalize a live pre-transaction import and a second JVM-style invocation is
rejected before staging. A release-failure regression proves a committed import
returns `SUCCEEDED`, records successful retention, writes the fixed redacted
signal without a throwable, and still emits its normal completion log.

## Final clean evidence

```text
./gradlew clean check --no-daemon
BUILD SUCCESSFUL in 1m 37s
Java/PostgreSQL tests: 369, failures: 0, skipped: 4
Basemap contract tests: 14/14
```

The four Java skips are the existing opt-in full Address Registry extract,
full Address Registry import, current structured-KO population, and current
coarse-location population proofs. Their reviewed local artifacts are not CI
inputs.

```text
./gradlew browserTest --no-daemon
BUILD SUCCESSFUL in 39s
Playwright tests: 19/19
```

The browser run includes the real `/operator/status` page, its local status API
fetch, fail-closed no-success state, rendered evidence/signals, and the shared
localhost-only HTTP/WebSocket guard. No external host was contacted by the new
status-page test.
