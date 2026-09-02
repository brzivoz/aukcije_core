# Property-reference ground-truth corpus v1

This directory is the reviewed, frozen issue-18 corpus used to measure
property-reference extraction from `detail.Description`. It contains **60
auctions**, **118 expected references**, and **20 explicit description-negative
auctions** selected from the 589-record root-7 capture fetched on 2026-08-21.
This is a purposive coverage sample, not a random or prevalence-weighted sample;
its metrics are regression evidence and not a population-performance estimate.

Every record in the captured sampling frame has a nonempty structured
`detail.Place.Cadastral` value. Consequently, `NO_DESCRIPTION_REFERENCE` means
only that the reviewed description text contains no extractable reference. It
does not mean the source record lacks structured KO metadata, and this corpus
does not measure the structured-first parsing contract required by issue #19.

The fixtures are deliberately evidence-minimized. They are not source snapshot
exports and cannot replay an auction. Each record contains only:

- the auction ID and SHA-256 of its exact #10 sanitized snapshot;
- hashes of the full reviewed `Description` and `ShortDescription` fields;
- short exact source phrases needed to support an annotation or negative; and
- typed expected values for parcel, cadastral municipality, land-register, or
address references.

`ko-authority.json` is a minimized extract of the active official Address
Registry-derived KO dictionary. Its manifest pins the dictionary version,
source GPKG hash, and full dictionary hash. The gate validates every annotation's
six-digit KO code and normalized KO name against that extract.

Images, thumbnails, files, bidder state, executor/debtor/creditor names, JMBG
values, contact details, prices, and unrelated prose are excluded. The Java
gate also rejects JMBG-like identifiers and email addresses if they are added
to evidence. See `manifest.json` for the complete licensing/provenance note and
`selection.sql` for the frozen selection query. The query joins on both auction
ID and reviewed snapshot SHA-256, so later snapshots of the same auction cannot
silently replace review input.

## Review contract

The initial annotation and a separate evidence-by-evidence adjudication pass
were performed by OpenAI Codex under distinct review IDs. Both passes used the
same automated agent; they are not independent human review or inter-annotator
agreement evidence. This limitation is explicit in `manifest.json`.
Five disagreements are retained in `adjudications.json` and linked from the
affected auctions:

- a misspelled parcel label that is still unambiguous;
- a structured/text KO conflict;
- a parcel-area number that is not another parcel;
- four parcel numbers governed by one enumeration label; and
- a subparcel value that is not an independent parcel identity.

Every fixture is `ADJUDICATED`. The gate rejects missing, duplicate, unlinked,
or incorrectly linked adjudications.

## Development and held-out split

`development.json` contains 45 auctions and 81 references. `held-out.json`
contains 15 auctions and 37 references, including five negatives. The held-out
file was frozen before the baseline was evaluated.

Parser examples, parser assertions, regex design, and error-driven tuning may
use the development fixture only. The held-out labels may be opened by an
aggregate evaluation command after a parser version is frozen; they must not be
copied into parser tests or used to choose a pattern. Any fixture change
requires a new corpus version, two review passes, new artifact hashes, and a
new baseline report.

## One-command quality gate

Run the same validation and metric reproduction used by CI:

```bash
./gradlew propertyReferenceCorpusCheck
```

`./gradlew check` includes this task. It enforces the published JSON model,
exact artifact inventory and hashes, split isolation, unique auction and
annotation IDs, all required selection strata, all supported patterns, the
60/100/20 minimums, at least five auctions with a Latin token of two or more
letters excluding Roman numerals, at least 15 distinct negative templates,
evidence-size and personal-data limits, KO
authority, two-pass adjudication, and exact reproduction of
`baseline-metrics.json`.

When the ignored #32 capture is present, also verify that all 60 snapshot
hashes, source-field hashes, and evidence phrases still address the exact
private source records:

```bash
./gradlew propertyReferenceCorpusSourceCheck
```

The private check reads `spike/issue-32/out/corpus.json`, whose expected SHA-256
is recorded in the manifest. It rematerializes every selected record through
the production `AuctionSourceSnapshotFactory`; no reconstructed entity fields
are trusted. The private capture and full descriptions remain uncommitted.
The review helper can also reproduce either minimized fixture byte-for-byte.
It reads snapshot hashes from the frozen SQL selection; only the Java private
source check computes canonical snapshot hashes through production code:

```bash
python3 tools/property-reference-corpus/curate_from_capture.py \
  --split DEVELOPMENT | cmp - corpus/property-references/v1/development.json
```

## Baseline and metric semantics

The frozen `issue-32-regex-baseline-v1` is intentionally a pre-parser baseline,
not the issue-19 implementation. Reference identity is exact per auction and
type:

- parcel numbers use `ParcelIdentityNormalizer` and remain text;
- KO names use the shared `SerbianNameNormalizer`;
- land-register numbers are whitespace-insensitive text; and
- address tokens use the shared Serbian name normalizer.

Duplicate occurrences of the same canonical reference count once. These
precision and recall figures evaluate only the retained, minimized exact
evidence phrases—not full descriptions, structured fields, or end-to-end
auction parsing. Precision is `TP / (TP + FP)`; recall is `TP / (TP + FN)`.
An undefined zero-denominator ratio is serialized as `null`, never as perfect
performance. False positives on description-negative auctions are reported
separately. `byExpectedPattern` reports annotation-pattern recall, while
`byDetector` reports detector precision; the two namespaces are deliberately
not mixed.

| Split | Auctions | Expected refs | Negatives | Precision | Recall | FP | FP on negatives |
|---|---:|---:|---:|---:|---:|---:|---:|
| Development | 45 | 81 | 15 | 91.76% | 96.30% | 7 | 2 |
| Held-out | 15 | 37 | 5 | 93.55% | 78.38% | 2 | 0 |
| Overall | 60 | 118 | 20 | 92.24% | 90.68% | 9 | 2 |

These numbers are the corrected Unicode-aware phrase-baseline starting point.
Issue #19 must separately prove structured-first behavior and meet its own
held-out thresholds with a frozen parser version.
