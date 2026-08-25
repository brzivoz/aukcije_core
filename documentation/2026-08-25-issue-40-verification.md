# Issue #40 verification — single-click auction-to-map refresh

## Delivered outcome

The page now has one primary **Освежи све податке** action. A Spring-managed
coordinator persists and orders source listings/details, deterministic location
enrichment, coarse map preparation, and exact map-status confirmation. Manual
and scheduled claims use the same coordinator and durable workflow; the old
stage-only controls are explicitly advanced maintenance actions and their
independent schedules default to disabled.

Flyway V15 owns `refresh_runs`, one-active-run uniqueness, immutable terminal
evidence, redacted failure codes, child run/version/count correlation, and the
workflow reference on coarse-location population runs. The mutation is
loopback and same-origin guarded; status reads are loopback-only and `no-store`.

## Acceptance evidence

| Contract | Executable evidence |
|---|---|
| One persisted four-stage experience | `RefreshWorkflowBrowserTest` drives the Serbian UI through listings, details, locations, and map preparation, reloads at every stage, opens a second tab, and proves neither tab announces success before correlated map readiness. Progress, start/elapsed/last-success times, next daily run, mapped totals, and precision summary are rendered without internal enum names. |
| Strict #17 → #29 → #30 order | `RefreshCoordinatorTest` proves only a complete `SUCCEEDED` source advances, enrichment uses the workflow-pinned source and versions, every eligible source observation must have a matching snapshot/current terminal state, and completion requires the exact workflow and coarse-resolution IDs in the server-side readiness snapshot. Missing snapshot lineage and concurrent claims are checked against real PostgreSQL by `RefreshRepositoryIntegrationTest`; `SyncPersistenceIntegrationTest` also proves a Linux nanosecond observation remains the same evidence after PostgreSQL's microsecond timestamp normalization. |
| Fail closed without losing last-good data | Partial source, failed detail, failed enrichment, active-version drift, missing enrichment lineage, empty population, and map-status mismatch all terminalize with fixed safe codes before a false success. Existing spatial evidence is append-only. The browser failure path keeps and labels the retained successful refresh time, exposes Serbian retry, and never renders a code, exception, payload, description, or personal data. |
| Duplicate/retry/restart semantics | Rapid duplicate activation and two tabs attach to one workflow. Real PostgreSQL gives eight manual contenders and a scheduled collision one durable winner. A terminal restore clears the browser idempotency key, while a terminal retry gets a new workflow and the old row remains immutable. Claim and status reconciliation atomically reclaim an expired refresh heartbeat; source/enrichment child heartbeats are bounded by the same lease. Startup recovery reconnects deterministic child keys, recognizes an already committed correlated map run, and terminalizes executor rejection instead of leaving an unowned active row. A one-slot executor handoff closes the terminal-commit/worker-return race while the database remains the one-active authority. |
| Daily local operation | The shared schedule defaults to `03:00` once per day in `Europe/Belgrade`; cron, zone, polling, and the complete disable switch are validated and documented. The stage-only source/enrichment schedules default to `-`. |
| Private mutation boundary | Controller tests reject non-loopback, forwarded-loopback, headerless browser mutation, and cross-site metadata. The accepted browser request carries a canonical idempotency UUID and `X-Operator-Request: refresh-v1`. The full browser suite proves only loopback HTTP(S) and WebSocket traffic; this feature introduces no WebSocket. |
| Accessibility | Playwright activates primary and retry actions with Enter, proves focus transfers retry→busy primary→retry across a real running/failure transition, and keeps success focus predictable. Separate static polite and assertive live regions provide progress/success and failure delivery. Browser-computed WCAG relative luminance for both the inner outline and outer focus ring remains at least 3:1 on the primary/retry default and hover backgrounds. |
| Anonymous status boundary | The map service retains workflow/resolution UUIDs for server-side correlation, while `MapDataStatusControllerTest` proves `/api/map/status` publishes version, freshness, counts, and precision only—neither internal run identifier is serialized. |
| Real browser/PostGIS outcome | `RefreshEndToEndBrowserTest` starts with no successful map run, activates the production page once, uses the real source client against a local fixture server, runs the production enrichment and coarse-location pipeline over PostGIS, and proves the workflow/source/enrichment/map IDs correlate, `/api/map/status` is available with a non-null timestamp, and the MapLibre feature is visible. No Gradle/CLI population task is part of that workflow. |

## Operator contract

The operating procedure is in [REFRESH_OPERATIONS.md](REFRESH_OPERATIONS.md).
It covers one-click use, schedule override/pause, safe disable, retry,
last-good behavior, diagnostics, same-origin requirements, and restart
reconciliation. `.env.example` exposes the unified schedule and stale-heartbeat
lease by default.

## Fresh verification

Environment: Java 17, Gradle 8.5, Docker-backed pinned
`postgis/postgis:18-3.6`, Flyway V1 through V15, and pinned Playwright Chromium.
The source boundary in browser evidence was a loopback fixture; no live
eaukcija.sud.rs, RGZ, geocoder, CDN, or other external host was contacted.

```text
./gradlew clean check --no-daemon
BUILD SUCCESSFUL in 1m 43s
Java/PostgreSQL tests: 397; passed: 393; skipped: 4; failures: 0
Basemap contract tests: 14/14

./gradlew browserTest --no-daemon
BUILD SUCCESSFUL in 54s
Playwright tests: 24/24; skipped: 0; failures: 0
```

The four JVM skips are the existing opt-in full Address Registry extract,
full Address Registry import, current structured-KO population, and current
coarse-location population proofs. None skips the #40 schema, coordinator,
source/enrichment/map correlation, endpoint, browser, accessibility, recovery,
or real PostGIS end-to-end contract.
