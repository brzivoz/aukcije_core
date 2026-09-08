# Private POC automatic RGZ activation — 2026-09-08

Owner-requested follow-up to #21: enable RGZ automatically and make real parcel
shapes visible in the POC now, rather than requiring manually supplied pins.
The [#41 v4 decision](2026-09-08-decision-41-private-poc-auto-activation.md)
records the explicit change to activation/cache-version policy. Published
permission/billing and building-layer caveats are unchanged.

## Implementation

- Private `dev` defaults RGZ on, discovers/validates capabilities and the parcel
  schema, and durably stores only sanitized source-contract evidence in V23.
  No historical hashes are embedded as defaults. Strict/common/production
  defaults remain off; explicit disable and live kill-switch controls remain.
- `private-local-first-observation-v1` is an explicitly labelled stable local
  cache epoch, not a publisher edition or an automatic weekly timestamp.
  Contract and terminal geometry caches survive restarts without HTTP refetches.
- The background worker processes retained auctions in bounded batches, with
  unended auctions first, normal worker/ledger arbitration, pause support,
  no empty-run loop, and 15-minute no-progress outage retry delay. No new source
  download, manual pin entry, parcel action, or Gradle enrichment task is needed.
- Traffic limits remain 0.2 requests/second, concurrency one, 100 logical parcel
  misses/run, three attempts, 5s/15s backoff, and the existing timeout/body gates.
  First-contract metadata has a separate maximum of two logical/six physical
  attempts sharing the same physical gate. XML external access/entities/DTDs
  are prohibited. Metadata failures leave geometry networking unready and retain
  fallback/cache serving rather than failing application startup.
- The POC binds to loopback, starts with a Serbia-wide overview, automatically
  re-reads its local viewport every 15 seconds while visible/idle, and zooms to
  a selected parcel's boundary. Existing precision/KO/geometry/privacy gates
  are not weakened to produce results.

## Automated verification

```bash
./gradlew check browserTest --no-daemon
python3 spike/issue-41/verify.py
python3 -O spike/issue-41/verify.py
git diff --check
```

Results: **516 Java tests passed, 4 optional full-dataset/current-population
tests skipped; 27 browser tests passed**. Offline basemap and corpus/parser
gates also passed.

New coverage:

- `RgzSourceContractVerifierTest`: honest epoch/readiness, schema/layer/CRS
  failures, external XML prohibition, and source/dataset binding.
- `RgzAutomaticBootstrapIntegrationTest`: production source-to-enrichment chain
  without supplied pins, persisted contract/cache reuse after fresh runtime
  state, stop/resume, disabled mode, metadata-failure backoff, pause, and no
  repeated empty runs after completion. Foreground refresh also proves it waits
  for an occupied background worker and resumes successfully instead of failing
  executor submission.
- `AutoEnabledRgzBrowserTest`: real automatic scheduler and loopback metadata/WFS
  server; the page changes from an empty viewport to real persisted fixture
  polygons without click/reload/pan or a test-triggered enrichment call. Parcel
  selection then fits the boundary and renders the parcel layer.
- Gradle test JVMs default all automatic RGZ network/bootstrap/warmup options
  off even for tests that boot `dev`; dedicated DynamicPropertySource fixtures
  opt in locally. The browser localhost-only guard remains active. No automated
  test depends on the live RGZ service.

## Actual local POC activation (separate from offline tests)

The owner explicitly authorized these local runtime operations:

1. Took a private, mode-0600 PostGIS backup before migration/activation:
   `data/backups/pre-rgz-auto-20260908T101458Z.dump` (about 4.3 MiB).
   This ignored private backup is not committed.
2. Restarted the managed POC with the new `dev` defaults. Flyway applied V23.
   The app itself discovered the real source contract at
   `2026-09-08T10:36:42.927843Z`, enabled geometry networking and automatically
   started scheduled background enrichment of the retained population.
3. Verified actual validated `PARCEL` MultiPolygon results through the local map
   API. Geometry appeared within the first startup check; refinement continued
   in the background. Invalid CRS/geometry responses continued to coarse tiers,
   not false parcel labels or deletion of last-valid geometry.
4. Restarted again with the final national-view/parcel-fit defaults. The same
   source-contract observation time remained, with cache/geometry intact and
   automatic refinement resumed.
5. Ran a separate owner-authorized **live UI probe**, not a CI test, against
   `http://127.0.0.1:8081/?mapPrecision=PARCEL`. The final recheck observed **430
   parcel map features**, selected auction `180320`, and confirmed the real
   polygon visibly rendered after fitting its boundary. Browser traffic was
   restricted to `127.0.0.1`; the application owns the bounded RGZ calls.

The private screenshot is `tmp/rgz-live-map.png`; it is ignored and not committed.
Counts are point-in-time observations, not a claim that all auctions have parcel
geometry or that every background batch has completed. Some auctions remain
honest fallbacks. No cache/attempt/source history was deleted, no credentials or
browser sessions were supplied to RGZ, and no private geometry was committed.

Open the POC at **http://127.0.0.1:8081/**. For verified parcels only, use
**http://127.0.0.1:8081/?mapPrecision=PARCEL** and select a result to inspect its
boundary. Live state is at **http://127.0.0.1:8081/operator/status**.

Emergency no-restart stop:

```bash
mkdir -p data/control
touch data/control/rgz.disabled
```
