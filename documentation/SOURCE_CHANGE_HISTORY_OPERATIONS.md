# Source change history and lifecycle (#11)

## Publication, clocks, and coverage

V29 extends V16 snapshots and successful observations. It does **not** create a
second payload ledger or replace deterministic enrichment discovery.

`source_publications` allocates an identity sequence while holding the singleton
`source_history_lineage` row lock, inside the existing serialized promotion.
The reference is **(database lineage UUID, publication sequence, sync run UUID)**.
The application records `published_at` using the **same SyncRunRepository clock**
as claim/terminalization (#43), clamped to at least run start and the previous
publication time. It is a recorded publication time, not a PostgreSQL commit
clock, source edit timestamp, or start/claim timestamp. Equal timestamps are
ordered by sequence. Sequence gaps are normal after rollback.

A reference becomes usable only on transaction commit. A deferred database
constraint requires SUCCEEDED; immediate guards reject insertion against a
terminal run, and terminal publication/observation/transition evidence is
immutable. Auctions, counters, history, success status, immutable source and
enrichment inputs, and downstream work all roll back together. Same-run replay
of promotion is rejected before writing; claim replay still returns the original
run identity. The run lock prevents two promotions from double-counting absence.

* A new/rebuilt database gets a new lineage UUID. Never copy a reference to a
  different lineage or silently replace an unknown reference with latest.
* A full restore retaining the referenced run remains comparable. A restore to
  before that run reports UNKNOWN_PUBLICATION. If a restored branch later reuses
  a sequence number, the different **run UUID** still rejects the old reference.
* References are not `mapDataVersion`, enrichment/parser/resolver versions, client
  wall clocks, snapshot hashes, or sortable UUIDs.
* V29 does **not** invent publication order or times for old successful runs.
  Their committed retained evidence remains auditable, but outside this ordered
  namespace. `earliestPublication` / `earliestPublishedAt` identify the first
  supported publication. A date lookup before it returns
  HISTORICAL_COVERAGE_UNAVAILABLE, never latest or an invented older boundary.
* Sequence zero + null run UUID is the explicit origin of this lineage's ordered
  history, not a claim to cover earlier source history. `PARTIAL_PRE_HISTORY`
  means auctions existed at migration; `COMPLETE_SINCE_EMPTY` means the lineage
  started empty. Neither is evidence of when the source legally published a sale.
* Migration backfills only the earliest retained successful run observation time
  (`category_tree_observed_at`, the same run observation clock used by
  `last_seen_at`) and its run identity. If several runs share the earliest time,
  the first run identity remains unknown. No live refetch or enrichment hash is
  used, and no source-change timestamp, closure time, or NEW event is backfilled.

## Source content versus review meaning

Every newly accepted observation has one `content_delta`:

| Delta | Evidence |
|---|---|
| NEW | No previously known local auction identity; first successful accepted observation. |
| UPDATED | Exact sanitized source hash differs from the previous accepted hash of this auction. |
| UNCHANGED | Exact hashes match, including scheduled detail refreshes. |
| BASELINE | Known identity has no comparable V16 source evidence. Never counted as NEW. |

An omitted or quarantined global run does not replace the prior accepted source
baseline. Snapshot A can be reused after B: observations still record **A → B →
A**, with two UPDATED events. Snapshot creation time is never last changed.

`source-review-v1` is a separate comparison policy over allowed retained source
JSON. Hashes and old snapshot rows are untouched. Its bounded field codes are:

```
STARTING_PRICE ESTIMATED_PRICE START_DATE END_DATE SOURCE_STATUS PROPERTY_TYPE
CATEGORY STRUCTURED_PLACE DESCRIPTION SHORT_DESCRIPTION AUCTION_NUMBER
FIRST_SALE BID_STEP SOURCE_PUBLICATION_DATE EXECUTOR CURRENT_PRICE MAX_OFFERED_PRICE
```

Both listing and detail values are compared, so a detail-only change is visible.
Money uses exact decimal numeric equality (including equivalent numeric strings).
No whitespace, case, Unicode, text, location, or date strings are normalized away.
Descriptions and executor text are **never returned** by this contract, only codes.
No before/after value payload is exported; projections are identity, codes, times,
coverage, and lifecycle evidence only.

`comparison_kind` is SUBSTANTIVE, LIVE_BIDDING_ONLY (only CurrentPrice /
MaxOfferedPrice), REPRESENTATION_ONLY (exact audit hash changed, reviewed fields
are equivalent), UNCHANGED, BASELINE, or UNSUPPORTED. Starting/estimated prices
and bid step are never put in the live-bidding noise group. NEW has baseline
comparison, not fabricated differences against empty text.

Known V16 identities can have comparable content but no ordered review reference
yet: an unchanged first ordered observation remains **UNCHANGED**, with comparison
kind **BASELINE** to establish that reference. It is maintenance, not new content.
Unknown snapshot schema/minimization versions or review-policy versions report
UNSUPPORTED; exact source activity remains separately available, never guessed
substantive. Upgrading a parser, resolver, dictionary, map selection, or processing
attempt does not create a source observation, publication, or review revision.

`last_observation_publication` advances on accepted observations.
`last_source_change_publication` advances only on NEW/UPDATED.
`review_publication` advances on source activity, explicit baseline establishment,
or lifecycle evidence, **not an unchanged detail refresh or visit**. Review
acknowledgements are not stored server-side by this issue.

## Lifecycle and grace

Lifecycle is independent of source content and raw workflow status. Known
`auctions.end_date <= evaluation instant` is the primary effective ended signal,
inclusive at equality. Unknown end dates stay unknown in #44's date membership.
Source presence is never proof of bidding availability. Absence is not a legal
sale outcome and never rewrites `status`, precision, or enrichment state.

Absence closure requires **two eligible complete successful root-union
observations** and the grace threshold. `eaukcija.sync.absence-grace` /
`EAUKCIJA_ABSENCE_GRACE` defaults to **PT24H**, valid PT0S through P30D with
millisecond precision. Grace starts at the **first qualifying absence publication**,
not last seen or run start; the threshold is inclusive (`published_at >= first +
grace`). Even zero grace needs a second distinct publication. Retained pre-V29
absence counts remain visible, but cannot close until two fresh timed absence
observations establish the missing first-absence evidence. Grace configuration
changes affect not-yet-closed streaks; they do not retroactively reopen a closure.

Scope uses the existing retained ROOT membership intersection with the run's
configured roots (including the existing legacy root-7 fallback), not hard-coded
sale types or child disappearance. PARTIAL, FAILED, and out-of-scope runs supply
no absence evidence. Both listing and detail quarantine freeze **the whole held
auction row**, content baseline, first-absence time, count, and lifecycle. They
neither increment nor reset a streak, and cannot reopen an auction. The grace
clock may elapse during a holdback, but another eligible absence is still required
to advance its evidence. Accepted reappearance resets absence and its timed
streak; a later absence starts again at one.

Durable states are UNKNOWN, NOT_ENDED (known future end, not a bidding claim),
and ENDED. Reasons are UNKNOWN, FUTURE_END, END_DATE, ABSENCE, or
END_DATE_AND_ABSENCE. **End date wins effective-time precedence** when both ended
reasons apply; absence's independent effective threshold remains in the evidence.
An unknown end does not become date-ended merely through absence; absence can
independently establish the administrative missing-source closure.

The append-only transition projection retains previous/current end times,
from/to states and reasons, publication, recorded time (via publication), and
effective time. Absence's effective time is the inferred first-absence + grace
threshold, recorded only when a successful promotion also proves the second
eligible absence. It is not an asserted source/legal event time. An end-date
closure uses the actual retained EndDate even when recorded later. End extensions
are audited even if both ends are future or both past. Reopening to a known future
end is recorded at publication: the source's actual edit time is unknown.

Reappearance clears ABSENCE, but does not reopen a still-past-ended auction.
Removing an end time produces UNKNOWN, not an invented reopening for bidding.
Repeated CLOSED/REOPENED cycles remain reconstructible from transitions. Current
last closure/reopening timestamps and reasons remain in the auction projection.

Only successful promotion writes these projections, and quarantine/out-of-scope
rows remain frozen. Between runs, #44's read-only predicate still recognizes an
elapsed end. The comparison contract pairs publication with evaluation instant,
so #56 can explain an auction ending with **no new source snapshot or publication**.

## Read-only consumer contract

`SourceHistoryService` is the backend prerequisite; #56 owns HTTP/shared-filter
integration, presets, browser-local checkpoints, acknowledgements, and UI. There
is no review mutation, new user state, or automatic ingestion endpoint here.

* `capture(serverEvaluationInstant)` returns a Frame: successful publication,
  recorded time, evaluation instant, earliest boundary, and lineage coverage.
* `atOrBefore(instant)` selects the last publication at or before that timestamp,
  including **all** equal-timestamp publications. It is a date-to-lower-boundary
  lookup, not a reconstruction of data before the supported namespace.
* `changes(lower, upper, evaluatedAt, cursor, limit)` returns activity in
  **(lower, upper]**. `auctionChanges` restricts the same indexed contract to one
  identity. Order is `(publication sequence, auction ID)` ascending, limit 1–200,
  with one sentinel row and a keyset cursor. Retain the returned **upper Frame**
  unchanged across pages. Later unchanged syncs cannot erase earlier activity.
* `revisions(ids, upper, evaluatedAt)` batch-loads up to 200 displayed identities:
  reliable first observation, last observed/changed references, exact Review
  reference (including its originally retained comparison policy), end-date
  predicate, latest durable lifecycle evidence, and coverage.
  Uncovered legacy rows have UNKNOWN_BASELINE and may have no review reference.
* `compare(review, upper, evaluatedAt)` validates that the auction's reviewed
  reference was an actual source/baseline/lifecycle revision. It returns the new
  exact Review, net field comparison, independent intervening source/lifecycle
  activity counts, lifecycle evidence, and `elapsedEnd`. A → B → A can have
  activity count two and empty net differences. Persist the exact displayed
  Review including its comparison policy and evaluation instant, not latest status.

Public methods run read-only REPEATABLE_READ. Display consumers must call capture
and read their rows **inside the same repeatable-read transaction**. The existing
map response now includes `sourceFrame`; list model and combined `/api/auctions/view`
use that same transaction and #44 evaluation instant. Thus the reference describes
the data actually displayed, even if another promotion commits concurrently.
Historical metadata lookup is supported; historical map geometry, list membership,
or historical search-result replay is **not** promised.

Invalid/missing/foreign/unknown/reversed boundaries fail with stable
BoundaryException codes, never silent fallback. Cursor and batch sizes are
validated. Read operations have no source client, RGZ client, or enrichment
scheduler dependencies. The SQL uses bounded keyset pages and per-auction indexed
lateral lookups in one batch, not a query per auction or full browser catalogue.

## Metrics and verification

Pipeline `rawSnapshotChanges` uses the stored classification, not enrichment hash
fallback or run-start ordering. NEW + changed(UPDATED) + unchanged + baseline
reconciles exactly with accepted observations. Old unclassified observations are
counted in baseline coverage. Quarantines remain separate source-union holdbacks.
`sourcePublication` carries the publication reference/time and separate absent,
closed, and reopened counts, durably retained per run even after later syncs.
Legacy runs have null publication/lifecycle metrics rather than invented zeros.

Run `./gradlew test`. `SyncPersistenceIntegrationTest` extends the existing real
PostGIS suite with the lifecycle/history matrix and a 600-auction fixture. The
fixture includes 20 near-ended and 580 future-ended auctions, not a permanent
assumption about the live source. Tests print representative EXPLAIN ANALYZE and
bounded 200-identity revision timing. No live network or speculative large-scale
infrastructure is required. See [verification](2026-09-09-issue-11-verification.md).
