# Issue #55 implementation evidence — 2026-09-09

This is an implementation and regression baseline, **not a claim that all of
#55's acceptance criteria have been completed**. Activation/recovery commands
are in [location refinement operations](LOCATION_REFINEMENT_OPERATIONS.md).

## Same-input population comparison

The read-only local export contains 570 current enrichment snapshots, ordered by
auction ID, filtered with `end_date > 2026-09-09T11:11:42Z`. This is a fixed end-date
filter over the export's current source pointers, not a database time-travel
query. Both parsers used the exact same exported file and updated dictionary.
No production references, cache entries, coordinates or current selections were
edited to obtain these numbers.

- Input frame SHA-256:
  `c3b587bcb576292c039db4cf4b5642fa6934f6509a6b901c349ea4464699cffc`
- Official source GPKG SHA-256:
  `ce983232d50cf445f0c71d45381e1d1d537450135b0b4be237c11c045229d3b3`
- Published canonical alias payload SHA-256:
  `5720026c2414d8b8d6539f86906c5495e913806b5dfc8e1133c7952ca9825730`
- Full dictionary version, extraction/eligibility counts and the 21 unresolved
  parcel/address reference entries are retained in
  [`2026-09-09-issue-55-coverage.json`](2026-09-09-issue-55-coverage.json).
  The input descriptions remain private in ignored `tmp/`, except the explicitly
  minimized known regression fixture below.

| Measure | Frozen v1 | Full-description v2 |
|---|---:|---:|
| Auctions with extracted parcels | 508 | 508 |
| Auctions with at least one eligible exact parcel lookup | 474 | 495 |
| Extracted parcel references | 593 | 597 |
| Eligible parcel references | 555 | 580 |
| Extracted address references | 240 | 240 |
| Eligible address references | 228 | 236 |

These are **eligibility**, not accuracy or selected-geometry counts. New v2
extraction runs no longer classify mere name-string disagreement as a lexical
review failure. Official matching still withholds 17 parcel references: eight
identity conflicts, one ambiguous unresolved match and eight unknown-name
matches. Two conflicting and two unknown-name address references also remain
blocked. Neither fuzzy selection nor structured-only parcel promotion was used.
The spelling/prose patterns examined during this audit are now known development
regressions; this population cannot subsequently be presented as fresh held-out
evidence for those changes.

## End-to-end regression coverage

- The complete minimized current input for 181104, including both full text
  fields and structured context, is in
  `src/test/resources/fixtures/propertyreference/issue55/181104-current.json`.
  Retained source hash:
  `65463727f7120e55a069b0832f866952d8141efbe5628579676f38e1d325e531`.
  Parser tests verify raw spans, both parcel identifiers, the uncorrupted Roman
  suffix, address 109 and explicit KO association. The PostGIS refresh fixture
  consumes those descriptions verbatim and performs both exact KO/parcel calls.
- `FullDescriptionParserTest` also checks isolated conflicting property groups,
  enumerations, ownership/area/subparcel negatives, date-named streets, spaced
  house suffixes and property prose boundaries.
- `LocationResolutionCoverageIntegrationTest` exercises the actual parser,
  dictionary matcher, refresh/enrichment pipeline, selected PostGIS geometry,
  and map projection. Missing-registry failure upgrades to an address after a
  snapshot becomes active without re-fetching cached parcels. It also checks
  ambiguous addresses, street-only precision, deterministic replay, standalone
  KO invalidation and parse-failure explanations for retained geometry.
- `RgzParcelClientTest` covers consistent empty responses without CRS and
  inconsistent count envelopes; nonempty geometry still has strict validation.
  `RgzParcelResolutionIntegrationTest` covers opt-in invalid-cache epochs,
  immutable rejected/successful evidence, failed rechecks, successful-cache reuse,
  duplicate logical claims and eventual cache attachment without another fetch.
- `AutomaticParcelBrowserTest` verifies a real coarse → registry-address map/rail
  upgrade, Serbian explanations, cache reuse and dismissal safety.
  `OperatorStatusBrowserTest` exercises the new separate metrics pane. The browser
  harness now stops the browser before database teardown, avoiding live polling
  deadlocking with test-only `TRUNCATE CASCADE`.
- Flyway lifecycle checks include V26, V27 and V28. The existing #22 importer,
  PostGIS topology/CRS, #41 access limits/kill switch and map/browser gates remain
  part of verification.

All parcel geometries and registry coordinates in these tests are **synthetic**.
They establish pipeline behavior and precision semantics, not actual availability
of the two advertised parcel boundaries or house point upstream.

## Read-only real registry availability check

A separate available local full GeoPackage was inspected without importing or
modifying it. Its SHA-256 is
`b78cdb490df67acd1507a6484b39cca477c04da40ee6b824f36742315d39c84e`
(this is **not** the centroid/dictionary source hash above). An exact lookup for
KO `708585` and parcels `4411/2`, `4411/20`, or street `БУЛЕВАР ОСЛОБОЂЕЊА` /
house `109` returned one row:

- official registry primary key `3927177`;
- municipality and settlement `ВЕЛИКА ПЛАНА`;
- street `БУЛЕВАР ОСЛОБОЂЕЊА`, house `109`, parcel `4411/2`;
- status `АКТИВАН`, no retirement date, source CRS `EPSG:25834`.

This demonstrates that a relevant real address point is present in the available
file, not that its transformed geometry has been imported, selected or published
by the running application. No coordinates were inserted manually, and no
publisher edition/date was inferred from a local file timestamp.

## Quality and remaining acceptance work

The frozen v1 snippet gate still reproduces development precision/recall 1.00000,
held-out precision/recall 0.97297 and zero negative false positives. It explicitly
uses `legacyV1()` and is **not** v2 quality evidence. V26 allows all-null quality
metadata for an unevaluated parser rather than copying those old scores to v2.
The current parse-stage dataset advertises evaluation pending.

Before closing #55:

1. Independently review the full-description supported-pattern gold data and a
   fresh, auction-disjoint held-out sample; demonstrate the requested 100%
   supported regression coverage and ≥99% precision / ≥95% recall targets. The
   current annotations and five aliases are explicitly coding-agent evidence
   reviews, not independent human review. Auction 181104 is already known from
   the frozen v1 corpus (different snapshot) and is not a fresh evaluation case.
2. Diagnose representative actual `INVALID_GEOMETRY` / `INVALID_CRS` evidence.
   The new empty-response handling and safe recheck mechanism do not prove that
   all previously rejected official responses should pass. Do not disable the
   CRS/topology gates or rotate epochs to manufacture coverage.
3. Activate the rebuilt dictionary and an approved full registry import in the
   intended runtime, then perform bounded replay and compare selected precision
   and fallback reasons on the same retained input frame. No live import,
   application restart or production population reprocessing was performed here.
   The running old instance therefore does not acquire this behavior just from
   the source edits.
4. Record that real before/after precision distribution separately from pipeline
   success and the offline eligibility comparison above. Building footprints,
   online geocoders and guessed coordinates remain out of scope.

Final `./gradlew check` passed: **567 JUnit cases, 563 passed, 4 opt-in cases
skipped, no failures/errors**. The skipped cases are the full official import,
full centroid extraction and two mutable current-population probes; they are not
being counted as verification of a live rollout. The frozen corpus/parser gates
and `git diff --check` also passed. The final check log is
`tmp/issue55-final-check.log`.

The complete **58-test Chromium/browser suite passed**. It exposed a teardown race
that was fixed by closing the browser before database cleanup. Fast RGZ cache
fixture runs also exposed host/container clock skew: fixture start times now use
the same database clock as fixture completion, without weakening time constraints.

Verification commands: `./gradlew check`, `./gradlew browserTest`, and the pinned
`auditLocationCoverage` command documented in the operations guide. Detailed local
logs are retained in ignored `tmp/issue55-*.log`; Gradle generates JUnit/XML,
HTML and browser failure artifacts in `build/`.
