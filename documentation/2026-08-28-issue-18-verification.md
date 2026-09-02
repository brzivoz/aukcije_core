# Issue #18 verification — reviewed property-reference corpus

Date: 2026-09-02

## Delivered contract

| Requirement | Retained evidence |
|---|---|
| Redistribution-minimized coverage sample | Corpus v`2026-09-02.2` contains 60 purposively selected root-7 auctions, including one fully Latin-script case and five mixed-script cases with a Latin `KO` label and Cyrillic KO name. A Latin tag requires a token of at least two letters and excludes Roman numerals, so incidental `I` in `КО Велика Плана I` does not count. The sample also covers parcel suffixes, multiple parcels, land-register references, addresses, missing fields, malformed prose, and false-positive traps. It is not a probability sample. Twenty cases are explicit `NO_DESCRIPTION_REFERENCE` negatives. |
| Honest annotation surface | 118 annotations retain exact raw evidence from `detail.Description`. Metrics cover only those minimized phrases. All 589 source records have structured `detail.Place.Cadastral`; that field is contextual metadata and structured-first issue-19 behavior is outside this corpus's scored surface. |
| Provenance and authority | The manifest records the 589-record sampling frame, purposive method and limitations, capture timestamp/hash, #10 schema/policy, SQL retrieval, review limitation, split policy, licensing note, and SHA-256 for all seven contract artifacts. The KO authority extract pins the official dictionary lineage and validates all 36 used code/name pairs. |
| Two-pass review | Every auction is `ADJUDICATED`. Five disagreements remain linked. Both distinct passes were performed by the same automated OpenAI Codex agent, not independent human reviewers; the limitation is recorded in the manifest. |
| Deterministic split | Development is 45 auctions / 81 references / 15 negatives. Held-out is 15 / 37 / 5, frozen before baseline evaluation. Parser tests and tuning may not use held-out labels. |
| Schema and CI gate | `propertyReferenceCorpusCheck`, included in `check`, validates schema, typed invariants, IDs, hashes/inventory, exact script tags, at least five auctions with a non-Roman Latin token of two or more letters, at least 15 distinct negative templates, KO authority, corpus minimums, evidence/PII limits, adjudications, and committed metrics. Mutation regressions cover artifact, schema, personal-data, KO-authority, and metric-drift failures. |
| Reproducible baseline metrics | `byExpectedPattern` reports annotation-pattern recall and `byDetector` reports detector precision; zero-denominator values are `null`. Overall minimized-phrase baseline is 92.24% precision and 90.68% recall, with nine false positives, two on description-negative evidence. |

## Commands and results

```text
./gradlew --no-daemon propertyReferenceCorpusCheck
Property-reference corpus 2026-09-02.2 validated: 60 auctions, 118 references,
20 negatives; held-out=15 auctions. Baseline precision=0.9224, recall=0.9068,
false positives=9.
BUILD SUCCESSFUL
```

With the ignored exact #32 capture present:

```text
./gradlew --no-daemon propertyReferenceCorpusSourceCheck
Private source verification passed for 60 corpus auctions.
Property-reference corpus 2026-09-02.2 validated: 60 auctions, 118 references,
20 negatives; held-out=15 auctions. Baseline precision=0.9224, recall=0.9068,
false positives=9.
BUILD SUCCESSFUL
```

The private command runs the production `AuctionSourceSnapshotFactory` and proves every
committed snapshot hash, field hash, and exact evidence phrase. The source
capture remains ignored and is never a CI artifact.

The CI-equivalent repository verification was then run from a clean build:

```text
./gradlew --no-daemon clean check
415 Java tests, 0 failures, 0 errors, 4 skipped
14 basemap tests passed
Property-reference corpus gate passed
BUILD SUCCESSFUL in 1m 43s
```

The JSON Schema dependency is isolated to test and the two corpus JavaExec
tasks; it is absent from the production runtime classpath.
