# Issue #11 verification — 2026-09-09

## Delivered

V29 and `history/` extend the existing success-gated publication transaction and
V16 source evidence. Ordered lineage/run references, exact observed-content
classification, versioned safe review comparisons, lifecycle projections and
append-only transitions, bounded history/revision reads, captured display frames,
and reconciled pipeline metrics are implemented. The detailed operational and
consumer contract is [SOURCE_CHANGE_HISTORY_OPERATIONS.md](SOURCE_CHANGE_HISTORY_OPERATIONS.md).

No second raw snapshot ledger, leased queue, network-dependent history read,
source refetch on closure, user review mutation, or #56 UI/filter implementation
was added. #44's live date predicate is unchanged.

## Commands and results

Latest targeted command:

```sh
./gradlew test \
  --tests '*SyncPersistenceIntegrationTest' \
  --tests '*SourceComparisonPolicyTest' \
  --tests '*DatabaseLifecycleIntegrationTest' \
  --tests '*PipelineStatus*Test' \
  --tests '*OperatorStatusControllerTest' \
  --tests '*MapAuction*Test' \
  --tests '*AuctionControllerLocationPresentationTest'
```

**102 passed, zero failures/skips**, 54 seconds. Real PostgreSQL 18.6 / PostGIS
3.6 Testcontainers ran on Docker Desktop (amd64 PostGIS under arm64 emulation).

Also passed:

```sh
./gradlew basemapTest propertyReferenceCorpusCheck propertyReferenceParserCheck
```

The basemap task was up-to-date. The offline corpus and frozen parser checks
executed successfully (held-out precision/recall both 0.97297, negative FP zero).
`git diff --check` passed.

The full `./gradlew test` run completed **636 tests: 630 passed, 4 skipped,
2 failed** in 3m09s. Both failures are unrelated missing-input failures in the
existing `NoReferenceAuditCorpusTest`: this checkout and HEAD lack
`corpus/property-references/no-reference-audit-v1/evidence.json` and
`manifest.json`. These fixtures were not fabricated, the tests were not disabled,
and that unrelated corpus issue was not hidden. All other executed regressions,
including deterministic enrichment, sync/client, snapshots, worker locks,
refresh, clock, map/filter, and RGZ-local-fixture tests, passed. Subsequent final
history/query/metric changes are covered by the 102-test targeted run above.

## Verification matrix

The existing `SyncPersistenceIntegrationTest` is extended rather than replaced:

- NEW, legacy BASELINE, exact UPDATED, UNCHANGED scheduled detail refresh;
  detail-description-only change, previous accepted observation across an omitted
  run, immutable hash reuse, first/last observation versus last-change references;
- A → B → A with two source activities but no net differences, followed by an
  unchanged sync; per-auction and global exclusive/inclusive bounded pages;
- monetary scale/string equivalence, substantive starting price, live-price-only
  change; safe text/status/place/date field codes; retained comparison-policy
  upgrade as baseline maintenance with unsupported cross-policy comparison;
- inclusive known end boundaries, unknown end, UNCHANGED + elapsed end without
  writes, UPDATED + CLOSED, retained effective versus recorded times, end extension;
- first/second absences, zero/nonzero grace, just-before/equal grace threshold,
  failed/partial runs between absences, retained-root scope, quarantine freeze,
  repeat close/reopen cycles, combined reasons and still-past-ended reappearance;
- equal publication timestamps, backwards application clock, ordered last-success
  metrics, malformed/foreign/restored-branch/unknown/reversed references,
  unavailable older date coverage, bounds and keyset continuation;
- same-run replay, duplicate IDs, concurrent promotion winner, atomic failure
  after the success gate while publishing downstream work, immutable evidence,
  and repeatable-read displayed data/reference under concurrent publication;
- real pre-V16 migration retains reliable first observation, leaves unsupported
  publication/edit/absence times unknown, never derives source delta from legacy
  enrichment hashes; existing backup/restore and restart regressions pass.

## 600-auction fixture and representative reads

600 accepted fixture auctions: 20 near their known end and 580 with later ends.
Two complete absence observations close all administratively, while the first
absence separately records the 20 elapsed end dates. There are 1,220 bounded
activity results across the source/lifecycle union. Pages are capped at 200;
each query reads at most a 201-row prefix from each indexed event branch before
union/deduplication, not an unbounded historical union before LIMIT. Batch review
metadata reads are capped at 200 identities with indexed lateral lookups, not
one SQL round trip per auction.

Representative final EXPLAIN ANALYZE output (publication sequence varies with
other tests; it is not an asserted permanent sequence):

```text
-- last accepted source observation for one auction, LIMIT 1
Limit (actual time=0.040..0.041 rows=1 loops=1)
  -> Index Only Scan using idx_source_observations_revision
       Index Cond: ((auction_id = 2001) AND (publication_id <= 53))
       Heap Fetches: 1
       Buffers: shared hit=3
Planning Time: 0.215 ms
Execution Time: 0.119 ms

-- bounded lifecycle history for one auction, LIMIT 200
Limit (actual time=0.033..0.034 rows=3 loops=1)
  -> Sort (quicksort, 25kB, publication_id DESC)
       -> Bitmap Heap Scan on auction_lifecycle_transitions
            -> Bitmap Index Scan on auction_lifecycle_transitions_pkey
                 Index Cond: ((auction_id = 2001) AND (publication_id <= 53))
Planning Time: 0.106 ms
Execution Time: 0.047 ms
```

The complete 200-identity revision service call measured **7.09 ms** in that run,
including validation, all selected source/change/review/lifecycle metadata, JDBC
transfer and DTO mapping. These are representative local observations, not
production latency guarantees or a permanent source-population invariant.
