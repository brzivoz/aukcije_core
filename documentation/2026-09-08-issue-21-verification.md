# Issue #21 — automatic parcel geometry verification

**Later same-day follow-up:** the owner-directed
[automatic POC activation](2026-09-08-rgz-auto-activation-verification.md)
supersedes the activation defaults below. This record describes the initial
#21 delivery, before live activation and the stable local-epoch amendment.

Implemented under the existing [#41 parcel access decision](2026-09-03-decision-41-rgz-automatic-geometry-access.md).
Runtime operations are in [RGZ_PARCEL_OPERATIONS.md](RGZ_PARCEL_OPERATIONS.md).
This completes the runtime/workflow gaps identified by the earlier #41 review;
it does not resolve publisher billing, establish a current publisher dataset
edition, authorize redistribution, or implement #42 building footprints.

## Delivered

The existing cache-first `PARCEL_PATH` now has a complete local-fixture proof
through normal refresh, real #19 extraction, #33 matching, PostGIS persistence,
#23/#38 fallback, #26 export, and #27 MapLibre rendering. No parcel-specific
user action or preseeded geometry/match is involved.

Additional safeguards:

- `rgz-parcel-v3` and Flyway V22 reject stale selection writes when #33 changes
  during HTTP, and revoke selection on current-KO deletion. Late valid geometry
  remains immutable cache/attempt evidence but does not count as an auction
  resolution. Current-match row locking serializes selection with standalone
  matcher updates; read paths retain their independent eligibility predicate.
- The map hides its generic structured centroid when a verified parcel exists,
  without collapsing distinct properties. KO revocation immediately exposes the
  retained fallback again. HTTP responses permit private, not shared, caching.
- GeoJSON foreign members are stripped at the geometry boundary as well as the
  property boundary. Persistence has an explicit evidence whitelist, including
  exact requested identity on negative/error attempts and WFS/retrieval provenance.
- Redirects and hidden HTTP-library retries cannot bypass rate/attempt/kill
  gates. Duplicate/trailing JSON, invalid counts, and truncated multi-match
  responses fail closed. The authorized feature type/access mode are validated.
- The file kill switch fails closed on indeterminate state/dangling symlinks.
  #30's loopback status API/page display its live state and traffic limits,
  including when database evidence is unavailable, without exposing paths or
  request headers. No restart is needed to stop or resume new requests.

## Acceptance coverage

| Requirement | Evidence |
|---|---|
| Automatic enrichment from source identities; no preseeded extraction/KO/geometry | `RgzAutomaticEnrichmentIntegrationTest` starts a normal scheduled refresh against a loopback source/WFS server and real production stages/PostGIS |
| DIMITROVGRAD (713848)/1572 Polygon, ČAJETINA (743968)/4577/337 MultiPolygon, VOŽDOVAC (703621)/7300/1 Polygon | Shared `RgzWorkflowFixture`; fixture geometry is synthetic, including a two-part MultiPolygon, not redistributed private RGZ captures |
| Durable identity cache; duplicate identities; zero-request second enrichment; changed edition refetches exactly affected identities | Full workflow test includes four auctions/three identities, checks total outbound request count, source-independent cache replay, and exactly one new call per identity for a new dataset; existing migration and older-resolver-cache tests remain |
| Not found, ambiguity, invalid input, HTTP/schema/JSON errors, identity mismatch, CRS, invalid geometry, streamed size ceiling | Parameterized full-workflow cases assert precise reason codes and real coarse fallback; client tests also cover numeric/syntax rejection, invalid area/rings, missing CRS, counts, content type, and timeout |
| Standalone `CONFLICT`, every non-matched #33 status, in-flight changes, deletion, and retained evidence | `RgzParcelResolutionIntegrationTest` verifies pointer-only invalidation, stale-write rejection, no obsolete KO reattachment, and cache-only restoration; full-workflow test verifies immediate public map fallback without another enrichment run |
| `STRUCTURED_ONLY` never reaches WFS or receives an auction-level KO | Full-workflow and parcel-stage integration tests assert no request and no copied KO code |
| Rate/concurrency/backoff/ceiling/kill switch | Gate/client tests cover configured spacing, shared-client concurrency during body reads, 5s/15s backoff, Retry-After cap, maximum attempts, hidden-503-retry prevention, and kill checks before retries; full-workflow tests prove quota/kill deferral does not fail the run |
| Failures never downgrade last-valid geometry | Real WFS new-edition failure preserves selected attempt/cache; existing retry/discovery/fairness/rollback tests remain |
| No cookies, credentials, sessions, personal/future fields in persistence/export | Sentinel properties/headers at collection, feature, and geometry levels; assertions over database cache/attempt evidence and exported GeoJSON; shared HTTP client never replays response cookies |
| Map really draws polygons; every fallback remains distinguishable | `AutomaticParcelBrowserTest` drives normal refresh from an empty population, checks rendered MapLibre parcel layers and precision labels, and asserts no generic centroid result |
| Live kill-switch operator visibility; localhost-only test traffic | Browser test toggles the actual temporary switch file and refreshes #30's status surface without restarting; full browser localhost-only network guard remains active |

The #41 cache contract deliberately distinguishes terminal cache results from
non-authoritative protocol/transport `ERROR` attempts. Terminal identities never
refetch at an unchanged edition. Unhandled errors may retry in a later ordinary
run, not again as a new logical lookup in the same run. No narrower manual
contract or negative-control prohibition on all application RGZ calls is retained.

## Verification run

```bash
./gradlew check browserTest --no-daemon
python3 spike/issue-41/verify.py
python3 -O spike/issue-41/verify.py
git diff --check
```

- Java: **507 passed, 4 optional full-dataset/current-population tests skipped**.
- Browser: **26 passed**, including both new #21 tests and the localhost-only guard.
- `check` also includes offline basemap and property-reference corpus/parser gates.
- Both offline #41 decision verifiers and whitespace checks passed.

No live RGZ call, credential/session use, private polygon export, production
migration, runtime activation, or GitHub issue mutation was performed. Defaults
remain off; an operator must provide reviewed current dataset/capability/schema
pins before the automatic network path is enabled.
