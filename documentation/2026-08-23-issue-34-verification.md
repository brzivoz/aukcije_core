# Issue #34 browser-harness verification

Date: 2026-08-23
Scope: dedicated Playwright harness, seeded PostGIS runtime, localhost-only
network proof, CI evidence wiring, and frontend asset/UI decisions

## Acceptance evidence

| Contract | Evidence |
|---|---|
| One clean-checkout command with a non-zero browser count | `./gradlew browserTest`; `browserTest` depends on the pinned Chromium installer and discovers 3 actual Playwright tests, plus focused fixture/policy checks |
| Existing page loads and exposes a visible element | `existingListPageLoadsFromSeededPostgisWithOnlyLocalhostTraffic` rendered the real Thymeleaf `h1` and deterministic auction row |
| Real PostGIS-backed application fixture | `PostgisBrowserFixture` uses the digest-pinned `postgis/postgis:18-3.6` Testcontainer, Flyway/Hibernate `test` profile, random HTTP port, and a seeded auction |
| Reusable offline-network fixture | `LocalhostOnlyNetwork` records all HTTP(S) and WebSocket hosts, connects loopback only, aborts or closes every other host, tolerates Chromium's raw path brackets, and fail-closed records parser failures without full URLs |
| Browser-local scheme boundary | Browser-free `LocalhostOnlyNetworkTest` proves only `blob:` and `data:` bypass the JDK protocol-handler registry; `ws:`/`wss:` remain guarded without depending on unavailable JDK handlers |
| Non-vacuous negative control | `externalAssetOnTheRealPageProvesTheGuardWouldFailTheSuite` loaded the Thymeleaf page, injected `cdn.example.invalid/a[1]^x\|y.png`, observed the external host in contacted and blocked sets, and asserted the final guard throws |
| WebSocket boundary | `loopbackWebSocketConnectsWhileExternalWebSocketIsRecordedAndClosed` completes a real in-process loopback handshake, then proves external `wss:` is recorded, closed before connection, and fails the final guard |
| Location-safe fixture cleanup | Browser-free `PostgisBrowserFixtureCleanupTest` creates a selected geometry/attempt graph, then proves cleanup truncates current rows and append-only evidence before reseeding auctions |
| Failure traces and screenshots in CI | `BrowserHarnessExtension` writes `failure.png` and `trace.zip`; the `browser-test` workflow job publishes reports always and failure evidence on failure |
| Browser/build/vendoring/UI decisions | `BROWSER_AND_FRONTEND.md` records Playwright 1.61.0, plain vendored ES modules, same-origin-only assets with checksum/license locking, and extension of the existing Thymeleaf shell |

## Fresh local run

```text
./gradlew clean test browserTest --no-daemon

test:        166 discovered, 162 passed, 4 explicit full-artifact/population skips,
             0 failures, 0 errors
browserTest:   5 discovered,   5 passed, 0 skipped,
               (3 Playwright browser tests + 1 PostGIS fixture test
                + 1 pure network-policy test)
             0 failures, 0 errors
BUILD SUCCESSFUL in 1m
```

The browser smoke contacted exactly `localhost`. On that same real application
path, the negative control contacted `localhost` plus `cdn.example.invalid`;
Playwright aborted the external request before any public response could be
consumed. The reserved-character URL completed without a route-handler crash.
The WebSocket control connected to `127.0.0.1`, while the external `wss:`
attempt recorded and closed `socket.example.invalid` without relying on DNS
failure.

Additional checks:

```text
./gradlew browserTestClasses --no-daemon  -> BUILD SUCCESSFUL
second unchanged ./gradlew browserTest --no-daemon -> 7 tasks UP-TO-DATE
Ruby YAML parser on .github/workflows/ci.yml -> OK
git diff --check -> clean
```

Local HTML reports are under `build/reports/tests/test/` and
`build/reports/tests/browserTest/`. Failure-only Playwright evidence is written
under `build/browser-test-results/artifacts/` and is intentionally absent after
this green run.
