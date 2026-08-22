# Issue #37 verification — structured KO matching

**Date:** 2026-08-22

**Issue:** [#37](https://github.com/brzivoz/aukcije_core/issues/37)

**Runtime:** Java 17; PostgreSQL 18/PostGIS 3.6; Flyway V5; retained official
#14 dictionary and #32 589-auction population

**Outcome:** 566/589 matched (96.10%); ambiguity and missing dictionary names
remained unresolved; byte-identical inputs replayed without re-matching

## Reviewed inputs

| Property | Value |
|---|---|
| Population | 589 auctions from the complete retained #32 corpus |
| Nonblank structured `Place.Cadastral` | 589/589 |
| Dictionary date | `2026-08-22` |
| Dictionary KO entries / normalized keys | 4,497 / 3,870 |
| Official GPKG SHA-256 | `ce983232d50cf445f0c71d45381e1d1d537450135b0b4be237c11c045229d3b3` |
| Normalizer | `serbian-name-v1` |
| Alias dataset | `2026-08-22.1`; zero aliases |
| Alias SHA-256 | `cfa87e561054de6535f211db7b5546b0a4f13a196fbe5231a5cf5ac1a728c1ed` |

The active dictionary version was
`2026-08-22-ce983232d50cf445f0c71d45381e1d1d537450135b0b4be237c11c045229d3b3-aliases-cfa87e561054de6535f211db7b5546b0a4f13a196fbe5231a5cf5ac1a728c1ed`.
The official/generated data remained under ignored runtime directories.

## Current-population result

| Status | Count | Percent |
|---|---:|---:|
| `MATCHED` | 566 | 96.10% |
| `AMBIGUOUS` | 21 | 3.57% |
| `NOT_FOUND` | 2 | 0.34% |
| `INVALID` | 0 | 0.00% |
| Total | 589 | 100.00% |

The 566 automatic matches consist of 453 globally unique exact normalized-name
matches and 113 exact normalized names reduced to one candidate by structured
municipality context. The population used no KO codes and the reviewed alias
dataset is empty, so those two production paths correctly reported zero here.

All 21 ambiguous rows are duplicate official KO names for which the portal's
municipality string did not equal any official municipality form. They remain
unresolved with every exact official candidate retained. Examples include
`БРЕСНИЦА / Врање-град`, `ЗАГРАЂЕ / Зајечар-град`,
`КРАЉЕВО / Краљево-град`, `СТАРИ ГРАД / Суботица-град`, and
`ЛЕСКОВАЦ / Лесковац-град`. No popularity, row-order, settlement, or fuzzy
guess was used to resolve them.

The two `NOT_FOUND` rows are auctions `180244` and `180245`, both carrying
`БУНУШЕВЦЕ / Врање-град`. Their top five edit-distance candidates were
persisted for review, but `matched_ko_code` remained null. Adding a mapping
would require a reviewed #14 alias record with provenance; the matcher contains
no one-off exception.

The final disposable-PostGIS run processed all 589 rows in 568 ms. An immediate
identical replay processed zero, counted 589 unchanged, preserved the same
status totals, and completed in 80 ms. The integration regression additionally
proves that an exact change to `place_name` changes the fingerprint and
reprocesses only that auction while unchanged rows preserve `resolved_at`.

## Automated acceptance evidence

The reviewed fixture suite covers:

- Cyrillic/Latin equivalence, case, punctuation, spacing, and `Č` diacritics;
- exact official-code precedence and exact normalized official names;
- duplicate KO names across municipalities, including missing/mismatching
  municipality inputs that remain `AMBIGUOUS`;
- unique municipality-context disambiguation with all original candidates
  retained;
- a historical `Caribrod` alias carrying its full review id, provenance,
  source reference, reviewer, and date;
- null and punctuation-only structured names as `INVALID`; and
- a one-edit typo producing ranked `FUZZY_REVIEW` candidates while remaining
  `NOT_FOUND` with no selected KO.

`StructuredKoMatcherTest` directly proves that query normalization calls the
same `SerbianNameNormalizer` implementation and contract as the dictionary
index. The loader independently verifies `ACTIVE`, the manifest, every file
size/hash, per-row source provenance, shared-normalizer output, embedded alias
reviews, and a fully reconstructed official-name/alias index before matching.
A checksum-negative control fails before a match runs.

`StructuredKoMatchIntegrationTest` migrates real PostgreSQL through V5 and
proves all four statuses, method/rationale fields, selected-code nullability,
JSONB candidates, official snapshot and alias provenance, retained run reports,
unchanged replay, and selective source-field reprocessing. In the reviewed
fixture matrix, every exact selection equals the expected official code and all
duplicate-name cases without one unique municipality remain unresolved: zero
exact-match false positives.

Description fields are never read. The known structured/text conflict on
auction `179324` therefore remains #33's reconciliation responsibility, as the
issue contract requires.

The final clean `./gradlew clean test --no-daemon` run discovered 98 tests:
95 passed, the three explicitly opt-in full-artifact/population tests skipped,
and there were zero failures or errors. The separately enabled current-population
test then passed against the retained official dictionary and 589-row corpus.
`git diff --check` also passed.
