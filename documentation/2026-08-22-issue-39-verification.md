# Issue #39 verification — reviewed municipality aliases

**Date:** 2026-08-22

**Issue:** [#39](https://github.com/brzivoz/aukcije_core/issues/39)

**Runtime:** Java 17; PostgreSQL 18/PostGIS 3.6; Flyway V6; retained official
#14 dictionary source and complete 589-auction #37 population

**Outcome:** all 21 formerly ambiguous auctions resolved through eight reviewed
municipality equivalences; 587/589 matched (99.66%), zero remain ambiguous, and
the two independent KO-name typos remain review-only `NOT_FOUND` results

## Reviewed dataset and immutable artifact

Format 2 of `config/address-registry/ko-alias-overrides.json` separates
`koAliases` from `municipalityAliases`. Every municipality record stores
`recordKind=MUNICIPALITY_ALIAS`, the official municipality code, portal alias,
the checked `serbian-name-v1` normalized form, provenance, source reference,
reviewer, and review date.

| Portal form | Normalized | Official municipality | Rows resolved |
|---|---|---|---:|
| `Врање-град` | `VRANJE GRAD` | `70432` ВРАЊЕ | 1 ambiguous; also context for 2 typo rows |
| `Зајечар-град` | `ZAJECAR GRAD` | `70556` ЗАЈЕЧАР | 5 |
| `Краљево-град` | `KRALJEVO GRAD` | `70653` КРАЉЕВО | 5 |
| `Лесковац-град` | `LESKOVAC GRAD` | `70726` ЛЕСКОВАЦ | 6 |
| `Шабац-град` | `SABAC GRAD` | `71269` ШАБАЦ | 1 |
| `Палилула` | `PALILULA` | `70203` ПАЛИЛУЛА (БЕОГРАД) | 1 |
| `Панчево-град` | `PANCEVO GRAD` | `80314` ПАНЧЕВО | 1 |
| `Суботица-град` | `SUBOTICA GRAD` | `80438` СУБОТИЦА | 1 |

The city-qualified forms were reviewed from the retained eAukcija population
against the official Address Registry municipality relationships. `Палилула`
was reviewed separately: auction 179985 carries municipality and KO name
`Палилула`; official KO 703907 belongs to municipality 70203
`ПАЛИЛУЛА (БЕОГРАД)`, while the other exact KO-name candidate 739090 belongs to
`СВРЉИГ`. It is therefore an explicit reviewed identity, not suffix handling.
Both official Palilula municipality names in this snapshot are qualified. If a
future source introduces an unqualified official municipality name, the
publisher and matcher preserve the alias-versus-official collision and refuse
automatic selection.

The republished dictionary evidence is:

| Property | Value |
|---|---|
| Manifest format | `2` |
| Dictionary version | `2026-08-22-ce983232d50cf445f0c71d45381e1d1d537450135b0b4be237c11c045229d3b3-aliases-72455511d935ccf10cdb4a5e829bb498460b40abc4adf1ae5e9a2e496fda9c04` |
| Official GPKG SHA-256 | `ce983232d50cf445f0c71d45381e1d1d537450135b0b4be237c11c045229d3b3` |
| Review dataset | `2026-08-22.3` |
| Complete alias SHA-256 | `72455511d935ccf10cdb4a5e829bb498460b40abc4adf1ae5e9a2e496fda9c04` |
| Municipality-alias SHA-256 | `7d13955eb71af897549712bb11cdf85ca3559a2d364473955eadc499e6bed580` |
| KO / municipality aliases | 0 / 8 |
| KO entries / duplicate groups | 4,497 / 402 |
| Artifact bytes | 4,701,522 |

The first publication returned `SUCCEEDED`. An immediate rebuild returned
`UNCHANGED` with the same version, hashes, counts, and artifact byte size,
proving deterministic publisher replay for the new record kind. Generated bulk
artifacts remain under the ignored `data/` tree.

## Before and after full population

| Status | Before #39 | After #39 | Delta |
|---|---:|---:|---:|
| `MATCHED` | 566 | 587 | +21 |
| `AMBIGUOUS` | 21 | 0 | -21 |
| `NOT_FOUND` | 2 | 2 | 0 |
| `INVALID` | 0 | 0 | 0 |
| Total | 589 | 589 | 0 |
| Match rate | 96.10% | 99.66% | +3.56 pp |

Exactly 21 persisted rows use rationale
`MUNICIPALITY_CONTEXT_REVIEWED_ALIAS`, across all eight distinct review ids.
Auction 179985 resolves to official KO 703907 through the separately reviewed
Palilula identity. No rows remain `AMBIGUOUS`.

The only unresolved auctions are 180244 and 180245 (`БУНУШЕВЦЕ /
Врање-град`). Their municipality now has reviewed context, but the KO name is
absent from the exact index. Both correctly remain `NOT_FOUND` with fuzzy-review
candidate 711209 (`БУНУШЕВАЦ`); #39 does not convert a KO typo into an automatic
match.

The immediate population replay processed zero rows and counted all 589 as
unchanged while preserving 587/0/2/0. The municipality-alias dataset version
and hash participate in the input fingerprint, so any reviewed-equivalence
change forces the appropriate fresh evidence.

## Fail-closed and compatibility evidence

- Publisher tests reject missing municipality review metadata, stored
  normalization drift, an unknown official municipality target, wrong record
  kinds, future dates, and duplicate ids.
- Publisher tests also reject an otherwise official municipality target that
  has no KO relationship in the emitted dictionary, preventing publication of
  an artifact that the loader would later reject.
- Publisher evidence records normalized alias collisions across reviewed
  aliases and official municipality names with every official target; matcher
  tests prove either collision contributes review evidence, makes no candidate
  eligible, and remains `AMBIGUOUS`.
- A municipality name that denotes several official municipalities without any
  reviewed alias also makes no candidate eligible, but reports
  `AMBIGUOUS_MUNICIPALITY_IDENTITY_COLLISION` with the colliding official codes
  rather than attributing a source-side ambiguity to reviewed data.
- Cyrillic `Општина Б-град` and Latin `Opština B-grad` fixtures both resolve
  through the same `serbian-name-v1` review record.
- The real #36 extractor → #14 publisher → #37 loader compatibility test uses a
  municipality alias to disambiguate a duplicate KO name and retains the full
  review record on the selected candidate.
- The loader verifies the complete alias-file checksum, independently rebuilds
  and checks the municipality-alias semantic hash, recomputes every stored
  normalized name, validates every target/link, and reconstructs the KO-name
  index before matching.
- Dictionary manifest format 2 makes the split alias counts and independent
  municipality-alias object part of the explicit schema. Publisher status and
  the matcher reject legacy manifest format 1 as a version mismatch before
  interpreting format-2-only fields.
- Production matching has no city suffix stripping, suffix list, municipality
  table, or named exception. Only reviewed artifact records can create an
  equivalence.
- Flyway V6 adds nullable historical/evidence-safe municipality-alias columns;
  every new result and run stores the dataset version and SHA-256. PostgreSQL
  integration tests verify those columns and candidate JSON provenance.

## Verification commands

```bash
./gradlew test \
  --tests 'rs.sud.eaukcija.addressregistry.KoDictionaryPublisherTest' \
  --tests 'rs.sud.eaukcija.komatching.StructuredKoMatcherTest' \
  --tests 'rs.sud.eaukcija.komatching.KoDictionaryPublisherCompatibilityTest'

./gradlew test \
  --tests 'rs.sud.eaukcija.komatching.StructuredKoMatchIntegrationTest' \
  --tests 'rs.sud.eaukcija.PostgisSchemaIntegrationTest'

export ADDRESS_REGISTRY_KO_DICTIONARY_CENTROID_DIRECTORY="$PWD/data/address-registry-centroids"
export ADDRESS_REGISTRY_KO_DICTIONARY_PUBLISH_DIRECTORY="$PWD/data/address-registry-ko-dictionary"
export ADDRESS_REGISTRY_KO_DICTIONARY_ALIAS_OVERRIDES="$PWD/config/address-registry/ko-alias-overrides.json"
export ADDRESS_REGISTRY_KO_DICTIONARY_EXPECTED_GPKG_SHA256='ce983232d50cf445f0c71d45381e1d1d537450135b0b4be237c11c045229d3b3'
./gradlew buildKoDictionary
./gradlew buildKoDictionary

export KO_STRUCTURED_MATCH_FULL_CORPUS="$PWD/spike/issue-32/out/corpus.json"
export KO_STRUCTURED_MATCH_FULL_DICTIONARY="$PWD/data/address-registry-ko-dictionary"
./gradlew test \
  --tests 'rs.sud.eaukcija.komatching.StructuredKoCurrentPopulationTest'
```

The final `./gradlew clean test --no-daemon` run discovered 111 tests: 108
passed, the three explicitly opt-in full-artifact/population tests skipped, and
there were zero failures or errors. The separately enabled complete-population
test passed against the republished official artifact. `git diff --check` also
passed.
