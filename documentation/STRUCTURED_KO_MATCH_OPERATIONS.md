# Structured KO matching operations

Issue [#37](https://github.com/brzivoz/aukcije_core/issues/37) matches the
structured `auctions.cadastral` value against the active immutable #14 KO
dictionary. It also consumes `place_name` and `municipality`: all three raw
fields are retained and fingerprinted, municipality is the only automatic
duplicate-name disambiguator, and place/settlement agreement is retained in
candidate evidence for review. Description text is deliberately out of scope;
#33 owns extracted-text disagreement and must not silently replace this result.

## Match contract

The matcher applies this order:

1. exact official KO code;
2. one exact normalized official-name or reviewed-alias candidate;
3. one candidate after exact normalized-name candidates are constrained by the
   structured municipality's official name/code or one non-colliding reviewed
   municipality equivalence; and
4. edit-distance-ranked candidates for human review only.

Step 4 always persists `NOT_FOUND`; it never auto-selects the top result.
Multiple exact candidates persist as `AMBIGUOUS` unless municipality context
leaves exactly one. Missing or characterless `Place.Cadastral` values persist
as `INVALID`. The only statuses are `MATCHED`, `AMBIGUOUS`, `NOT_FOUND`, and
`INVALID`.

Municipality equivalences come only from the reviewed #14 artifact. The matcher
contains no `-град`/`Град` stripping, suffix table, or named municipality
exception. A normalized municipality name that denotes more than one official
municipality makes no candidate eligible, so the result remains `AMBIGUOUS`.
Candidates carry `municipalityIdentityCollision` and the colliding official
codes. When a reviewed alias is part of the collision the rationale is
`AMBIGUOUS_MUNICIPALITY_ALIAS_COLLISION` and the review evidence is retained;
when the official names alone collide the rationale is
`AMBIGUOUS_MUNICIPALITY_IDENTITY_COLLISION`, which never blames reviewed data
for a source-side ambiguity. Candidate JSON retains the exact municipality alias id,
target code, normalized name, provenance, source reference, reviewer, and date
used by an automatic decision. The loader precomputes normalized official
municipality-name/code lookups once, so context resolution does not scan the
complete KO dictionary for every input.

Both query values and dictionary/alias validation call
`SerbianNameNormalizer.normalize` directly. The active manifest must name the
same `serbian-name-v1` contract or the run fails before database mutation.
Unicode compatibility, Cyrillic/Latin folding, case, diacritics, punctuation,
and spacing therefore cannot drift between the two sides.

## Run a population match

Build and review the active dictionary first as described in
[KO_DICTIONARY_OPERATIONS.md](KO_DICTIONARY_OPERATIONS.md). Then run the
matcher against the explicit PostgreSQL profile:

```bash
export SPRING_PROFILES_ACTIVE=dev
export AUKCIJE_DB_PASSWORD="$(tr -d '\r\n' < .secrets/postgres-password)"
export KO_STRUCTURED_MATCH_DICTIONARY_DIRECTORY="$PWD/data/address-registry-ko-dictionary"
./gradlew matchStructuredKo
```

The task starts the real application with no web connector. Flyway and
Hibernate validation still run, PostGIS is still required, and the complete
match is one transaction protected by a PostgreSQL advisory lock. Invalid
configuration, a missing/symlinked artifact, manifest/provenance drift,
checksum mismatch, index/alias inconsistency, or database error rolls back the
run.

The final JSON reports the dictionary/source/normalizer/alias versions, total
population, processed and unchanged counts, all four statuses, method counts,
duration, and the measured match rate. A `NOT_FOUND` row retains up to five
deterministically ranked review candidates whose normalized-name similarity is
at least 70%. Candidates below that review floor are omitted; if none remain,
the row uses method `NONE`. The fixed floor and bound are not hidden runtime
inputs to idempotency.

## Persistence and audit evidence

Flyway migration `V5__structured_ko_matches.sql` owns:

- `auction_structured_ko_matches`: one current result per auction, including
  all raw source fields, an input fingerprint, status, method, rationale,
  selected official KO code, candidate JSON, immutable dictionary/source
  hash, normalizer, alias dataset/hash, and `resolved_at`; and
- `structured_ko_match_runs`: one retained population report per invocation,
  including unchanged invocations.

Migration `V6__municipality_alias_match_evidence.sql` adds the independently
hashed municipality-alias dataset/version to current results and run reports.
Historical V5 rows stay nullable because missing evidence must not be invented;
every new #39 run writes both fields.

Candidate JSON retains official names, municipality codes/names,
municipality/place context flags, match kind, and fuzzy distance/rank. A
reviewed KO-alias candidate additionally embeds the review id, kind, provenance,
source reference, reviewer, and review date. Municipality-context candidates
likewise embed reviewed municipality equivalence evidence. Dedicated
`municipality_alias_dataset_version` and `municipality_alias_sha256` columns
trace the exact subset contract alongside the full alias and official GPKG
hashes.

Useful operator queries:

```sql
SELECT status, count(*)
FROM auction_structured_ko_matches
GROUP BY status
ORDER BY status;

SELECT auction_id, source_cadastral, source_place_name, source_municipality,
       status, method, rationale, candidates
FROM auction_structured_ko_matches
WHERE status <> 'MATCHED'
ORDER BY auction_id;

SELECT finished_at, dictionary_version, population_count, processed_count,
       unchanged_count, matched_count, ambiguous_count, not_found_count,
       invalid_count, municipality_alias_dataset_version,
       municipality_alias_sha256, method_counts
FROM structured_ko_match_runs
ORDER BY finished_at DESC;
```

## Idempotency and refreshes

The SHA-256 input fingerprint length-prefixes the auction id and exact raw
`cadastral`, `place_name`, and `municipality` values together with the
normalizer contract, full alias dataset/hash, municipality-alias hash,
dictionary version, and official snapshot hash. An identical current
fingerprint is counted from the retained
row without invoking the matcher or changing `resolved_at`. A changed source
field or version input recomputes and conditionally upserts only that auction.

To reproduce the retained full-population measurement without touching a
developer database:

```bash
export KO_STRUCTURED_MATCH_FULL_CORPUS="$PWD/spike/issue-32/out/corpus.json"
export KO_STRUCTURED_MATCH_FULL_DICTIONARY="$PWD/data/address-registry-ko-dictionary"
./gradlew test \
  --tests 'rs.sud.eaukcija.komatching.StructuredKoCurrentPopulationTest'
```

The opt-in test creates a disposable PostGIS database, loads only the three
structured fields from the retained corpus, runs the production service twice,
and prints the first report, unchanged replay, and every unresolved auction.
