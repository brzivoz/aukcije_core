# Issue #14 verification — canonical official KO dictionary

**Date:** 2026-08-22

**Issue:** [#14](https://github.com/brzivoz/aukcije_core/issues/14)

**Runtime:** Java 17; database-free filesystem publisher consuming the retained
official #36 artifact

**Outcome:** clean full build and byte-identical unchanged replay passed

## Reviewed official input

The retained #36 proof artifact was used directly; no network request, source
mutation, database dependency, or repository data artifact was introduced.

| Property | Value |
|---|---|
| Dataset date | `2026-08-22` |
| Source ZIP SHA-256 | `3e601009bc1c540c83e2396af703a51713edb508d3fb220d5e6e36a52e4e0f15` |
| Source GPKG SHA-256 | `ce983232d50cf445f0c71d45381e1d1d537450135b0b4be237c11c045229d3b3` |
| Source schema SHA-256 | `f5e76adbfcca49f4e2146f47e71ed7177e891915905d7a7874aba18c57d163be` |
| Source rows / active / rejected | 2,488,562 / 2,488,562 / 0 |
| #36 centroid file SHA-256 | `162112e9fb2cb6ae22ff0a9b922cabcf454c393243524a28598e541203d26c5b` |
| Alias dataset | `2026-08-22.1`; zero entries; no unreviewed overrides |
| Alias canonical SHA-256 | `cfa87e561054de6535f211db7b5546b0a4f13a196fbe5231a5cf5ac1a728c1ed` |

## Full build and replay

The production CLI was run twice with the active #36 directory, committed
alias source, and independently pinned GPKG SHA-256. The clean build returned
`SUCCEEDED`; the replay rebuilt all outputs and returned `UNCHANGED`.

| Metric | Clean build | Replay |
|---|---:|---:|
| KO dictionary entries | 4,497 | 4,497 |
| Normalized index keys | 3,870 | 3,870 |
| Cross-municipality duplicate-name groups | 402 | 402 |
| Alias overrides applied | 0 | 0 |
| Rejected source rows | 0 | 0 |
| Published immutable bytes | 4,618,386 | byte-identical existing version |
| Validation / publication / total | 168 / 8 / 235 ms | 163 / 7 / 222 ms |
| Outcome | `SUCCEEDED` | `UNCHANGED` |

The immutable version is
`2026-08-22-ce983232d50cf445f0c71d45381e1d1d537450135b0b4be237c11c045229d3b3-aliases-cfa87e561054de6535f211db7b5546b0a4f13a196fbe5231a5cf5ac1a728c1ed`.

## Deterministic output hashes

| File | SHA-256 |
|---|---|
| `ko-dictionary.ndjson` | `87e29577382e834ad049a896ebc8370f11cb8d8f0b61dd744e3328da28627852` |
| `normalized-index.ndjson` | `217045e3a1a5ac1b805102e551ffe4d3b9b0724b494ea5c84a149b1368e123f6` |
| `report.json` | `77b423935cf45b17462635bb6e86a20a417a1579b6f8d66a2b44b7455fe4fad0` |
| `alias-overrides.json` | `cfa87e561054de6535f211db7b5546b0a4f13a196fbe5231a5cf5ac1a728c1ed` |
| `ATTRIBUTION.md` | `5bac11f046ab16b71be1edf761b056c5e7ab43539fd2a13633927a30454a37ea` |
| `manifest.json` | `c6654affd364ad314ad6057622cce34991494ac5fd9c2dad3676fde0e5ff30b3` |

The `STATUS` action independently returned the same active version, source
date/hash, 4,497 entries, 402 duplicate groups, and zero aliases.

## Automated acceptance evidence

`KoDictionaryPublisherTest` and the normalizer regression prove:

- exact official codes and Cyrillic/Latin names plus KO→settlement→municipality
  relationships survive into the dictionary;
- dictionary and query index share public contract `serbian-name-v1`;
- fixed source and aliases generate byte-identical immutable files;
- duplicate official KO names across municipalities remain explicit candidates
  and report groups rather than guessed matches;
- every reviewed alias retains its target, provenance, source reference,
  reviewer, review date, and normalized index link;
- missing review evidence and unknown alias targets fail closed;
- implausible KO counts and broken official relationships cannot publish; and
- a truncated/checksum-invalid source leaves the prior `ACTIVE` dictionary
  untouched;
- an older source date fails with `SOURCE_DATE_DOWNGRADE` before version
  publication or pointer mutation, including across separate centroid trees;
  and
- reverting alias data on the same source date remains supported and reactivates
  the byte-identical retained version.

The final clean `./gradlew clean test --no-daemon` suite passed 83/85 tests,
including all existing PostGIS/Testcontainers integration and negative-control
tests. The two opt-in full-source tests remained skipped unless their
separately reviewed environment variables are supplied. The official input and
generated 4.62 MB runtime version stayed under `/private/tmp` and were not
committed.
