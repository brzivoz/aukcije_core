# Issue #19 verification — 2026-09-02

## Outcome

Versioned property-reference extraction is implemented as the production
`PARSE` stage. It consumes the immutable source-backed
`enrichment-location-input-v2` projection, reads structured `Place` before both
description fields, emits every supported reference in source order, and
retains raw plus normalized evidence without performing a network or
geospatial lookup.

Flyway V17 adds immutable extraction-run/membership/observation evidence and a
single atomic current-set pointer. Same-input replay reuses the exact run,
rows, and hash. A changed snapshot under the same parser or a parser-version
bump creates a new immutable run and advances only the pointer, retaining prior
evidence and carrying user-reviewed rows without updating them.

## Acceptance evidence

| Contract | Evidence |
|---|---|
| Full normalized contract | `property_references` records reference type, raw/normalized KO, optional matched KO code, raw/canonical parcel text, land-register and address fields, source field/UTF-16 offsets/raw evidence, parser version, extraction status, canonical key, and exact source/input hashes. The database enforces unique `(auction_id, parser_version, canonical_key)`; each extraction membership stores that run's unique ordinal. |
| Structured-first, both text fields, multiple references | `PropertyReferenceParser` always emits the structured row first, then stable source-order matches from `detail.Description` and `detail.ShortDescription`. It emits multiple and enumerated parcels and deduplicates repeated canonical keys. |
| Normalization without evidence loss | Serbian Cyrillic/Latin KO variants, slash variants, punctuation, whitespace, and parcel suffix/part forms normalize into canonical values while raw strings, evidence spans, and source offsets remain available. |
| Honest extraction state | Missing `Place` data yields `NO_STRUCTURED_REFERENCE`; valid text references still yield `EXTRACTED`. Text/structured KO disagreement or multiple distinct text KOs yields `NEEDS_REVIEW`, with no guessed KO code. Resolution status remains in the separate location-attempt model. |
| Deterministic transactional reprocessing | V17 keys immutable runs by auction/input/parser; deterministic UUIDs and result hashes make same-input replay byte stable. Immutable memberships retain each selected set and its authoritative order, while `current_property_reference_extractions` advances atomically in the per-auction transaction. V17 removes the obsolete per-auction/parser ordinal uniqueness from shared reference rows. A same-parser changed-snapshot regression covers both a shifted existing key and a new key occupying an old ordinal. Result JSON snapshots parser output and exact selected rows. |
| Reviewed corrections | Automatic upsert is guarded by `NOT user_reviewed`; matching reviewed canonical keys are selected instead of overwritten, and all other reviewed rows are carried into the new current set. The integration test verifies the reviewed value in both the live row and immutable new-run JSON. |
| Isolated failures and per-run metrics | Every auction still executes inside `EnrichmentItemProcessor`'s transaction. Parser bounds/control failures become fixed permanent codes for that auction and do not abort the run. The run view has explicit extraction-success and parse-stage-failure counters; once-only observations also add selected/text/missing-structure/conflict counts plus the frozen corpus and metrics identities. |
| Precision traps and hostile input | Unit regressions cover folio, area, object-part and subparcel false contexts; Unicode slash/script variants; whitespace/punctuation; multiple and duplicate references; inert markup; unsafe controls; oversized fields/totals; and the exact 256-row reference boundary. |
| Downstream compatibility | The coarse resolver reuses the current issue-#19 structured row and may attach only a uniquely matched KO code. Same-KO re-extraction preserves that resolved code, while an identity/status change clears it for resolution. Extraction status/evidence and reviewed data remain parser/user concerns, and a retained verified-parcel resolution is not downgraded. |

## Frozen quality result

Parser `property-reference-v1` was developed against only the issue-#18
development split. After it was frozen, the held-out split was evaluated once
through the aggregate gate. The committed report is
`property-reference-parser-v1-metrics.json` and its SHA-256 is
`8468d6efe54cc3623c3eb3d161d583737e653a68ac854a924e82d5f4b90d3473`.

| Split | Expected | Predicted | TP | FP | FN | Precision | Recall | Negative-auction FP |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| Development | 81 | 81 | 81 | 0 | 0 | 1.00000 | 1.00000 | 0 |
| Held-out | 37 | 37 | 36 | 1 | 1 | 0.97297 | 0.97297 | 0 |
| Overall | 118 | 118 | 117 | 1 | 1 | 0.99153 | 0.99153 | 0 |

The held-out gate requires precision ≥0.95, recall ≥0.88, and zero false
positives on the five annotated negative auctions. Category recall floors are
0.88 for agricultural land, 0.75 for generic parcels, and 0.80 otherwise when
the held-out category contains at least five expected references. The report's
type/category breakdown surfaces the two non-perfect aggregate cells for
review without feeding held-out examples back into parser development.

The held-out precision margin is intentionally thin: one additional false
positive at the current 36 true positives would produce 36/38 = 0.94737 and
fail the gate. Expanding and governing that frozen corpus remains issue #18;
the issue-#19 implementation does not tune against held-out labels.

The quality CLI writes canonical LF-terminated JSON on every platform and loads
its frozen production profile from the runtime classpath, so verification does
not depend on the process working directory.

## Focused verification

No command contacts eAukcija or RGZ. PostgreSQL checks use the pinned local
`postgis/postgis:18-3.6` Testcontainers image.

```text
./gradlew propertyReferenceParserCheck --no-daemon --rerun-tasks
Property-reference parser property-reference-v1:
development precision=1.00000 recall=1.00000;
held-out precision=0.97297 recall=0.97297 negative-fp=0
BUILD SUCCESSFUL

./gradlew test \
  --tests 'rs.sud.eaukcija.propertyreference.PropertyReferenceParserTest' \
  --tests 'rs.sud.eaukcija.propertyreference.PropertyReferenceExtractionIntegrationTest' \
  --tests 'rs.sud.eaukcija.PostgisSchemaIntegrationTest' \
  --tests 'rs.sud.eaukcija.enrichment.EnrichmentReprocessingIntegrationTest' \
  --tests 'rs.sud.eaukcija.coarselocation.EnrichmentPipelinePostgisIntegrationTest'
```

```text
./gradlew clean check --no-daemon
BUILD SUCCESSFUL in 1m 45s

./gradlew check --no-daemon
BUILD SUCCESSFUL in 2s after the clean pass (all tasks up-to-date)

431 Java tests: 427 passed, 4 existing opt-in complete-dataset tests skipped
14/14 offline basemap contracts passed
```

The four skipped tests are the existing opt-in complete official-dataset
population suites. No issue-#19 parser, corpus, migration, persistence,
reprocessing, production-pipeline, or failure-isolation test was skipped.

## Operations

The v2 publication contract, extraction statuses, current-set query, parser
quality commands, reprocessing behavior, retained metrics, and reviewed-row
policy are documented in
[deterministic enrichment operations](ENRICHMENT_OPERATIONS.md).
