# eAukcija source snapshot operations

## Purpose and boundary

`auction_source_snapshots` is the replay and audit boundary for eAukcija. One
row contains the exact reviewed public fields from one listing record and one
valid detail `Data` object, captured before either object is normalized into an
`Auction`. The canonical JSONB has this stable shape:

```json
{
  "detail": { "Id": 180466 },
  "listing": { "Id": 180466 }
}
```

The abbreviated example shows the envelope only; accepted rows retain every
field in the policy below. The SHA-256 addresses the compact canonical JSON of
`detail` plus `listing`. It does not hash reconstructed entity fields,
timestamps added by the application, endpoints, or database serialization.

This ledger is intentionally separate from
`auction_enrichment_input_snapshots`. The latter is a small normalized parser
input introduced by V13; it is not raw-source evidence.

## Versioned minimization policy

Snapshot schema: `eaukcija-listing-detail-v1`
Policy: `public-auction-fields-v1`

Allowed listing fields are:

```text
AuctionNumber CurrentPrice EndDate Id IsFirstSale MaxOfferedPrice
PropertyType ShortDescription StartDate StartingPrice Status
```

Allowed detail fields are:

```text
AuctionNumber BidStep Category CurrentPrice Description EndDate EstimatedPrice
ExecutorName Id IsFirstSale MaxOfferedPrice Place PropertyType PublicationDate
ShortDescription StartDate StartingPrice Status
```

`Category` is limited to `Id` and `Name`. `Place` is limited to `Cadastral`,
`Id`, `Municipality`, `Name`, and `ZipCode`. `ExecutorName` is retained because
the publicly named enforcement professional is directly relevant to the sale.

Everything else is denied by default. In particular, the policy excludes
`Thumbnail`, `ThumbnailType`, `Image`, `Images`, base64/binary data, transport
headers, authorization/session/token fields, unreviewed future fields, and
unrelated personal data. Adding an allowed field requires a new policy version,
fixture review, and a deliberate replay/hash impact decision.

Source values are copied as JSON nodes, preserving field case, strings,
booleans, arbitrary-precision decimal values (including trailing-zero scale),
and explicit nulls. The HTTP client parses floating-point tokens directly as
`BigDecimal`-backed nodes before DTO binding, so neither stored JSON nor auction
money fields pass through `double`. The same exact reader parses PostgreSQL
`canonical_payload` when a current detail is reused. Object keys are recursively
ordered and one fixed compact serializer is used for sizing, hashing, and
verification, so source key order, PostgreSQL JSONB key order, or
application-wide pretty-print settings cannot alter the hash. The serializer
writes `BigDecimal` values in plain form before hashing and storage, matching
JSONB normalization of exponent notation such as `1.596E5` to `159600`. The
maximum source-record depth is 32 and the minimized canonical payload is capped
at 64 KiB. The HTTP client also requires JSON content type, valid single-root
JSON, a non-null `Data` detail object, and a configured 16 MiB response limit
before snapshotting.

## Atomic publication and replay

A complete #17 sync publishes each candidate in one PostgreSQL transaction:

1. upsert normalized auction state;
2. insert `(auction_id, content_sha256)` with `ON CONFLICT DO NOTHING`;
3. select all hashes in `auctions.current_source_snapshot_sha256` with one
   set-based PostgreSQL update;
4. point the run observation at the same hash;
5. pass every existing success gate and mark the run `SUCCEEDED`.

Any later failure rolls all five changes back. An unchanged record reuses the
existing row and only adds its run observation. A correction changes the hash
and appends a row. Database triggers reject snapshot update/delete, and the
current-auction plus observation foreign keys prevent dangling lineage.
Every current snapshot loaded for reuse recomputes and verifies its stored
content address before the payload can enter staging.

Each listing page is defensively copied once, indexed by source ID once, and
immediately reduced to the minimized listing. Valid details are minimized
immediately as well. Thumbnails, images, tokens, and future unreviewed fields
therefore do not remain staged for the whole run. When a listing remains fresh,
sync combines that new pre-DTO listing with the detail JSON from the current
immutable snapshot. A legacy auction with no V16 snapshot must obtain one valid
detail response before it can advance. A raw row already represented by the
client's listing quarantine is skipped if minimization rejects it; an accepted
summary whose source row is missing or invalid still fails the page closed.
Malformed, null, over-depth, or oversized records never change the current
pointer.

`AuctionSourceSnapshotReplayParser` accepts only stored canonical JSON and has
no source-client dependency. The golden PostgreSQL regression reads
`canonical_payload::text` back from the table and replays both DTOs with live
network unavailable.

Successful promotion also derives the immutable
`enrichment-location-input-v2` projection from this exact snapshot. That
projection carries the source hash, structured `Place`, `Description`, and
`ShortDescription` fields required by issue #19. Reprocessing therefore traces
every normalized property reference back to the retained canonical source
without refetching eAukcija.

## Retention, export, and redaction

- **Retention:** keep source snapshots and observations indefinitely with the
  database backup/restore history. There is no automatic purge. Their size is
  bounded and they are required to reproduce historical parser decisions.
  `ON DELETE RESTRICT` is deliberate: once snapshotted, the auction and ingest
  run are audit identities and cannot use the older auction-cascade deletion
  path. Any future erasure workflow requires a reviewed policy/migration rather
  than silently discarding this ledger.
- **Export:** export only `canonical_payload` plus auction ID, content hash,
  schema/policy versions, endpoints, acquisition/source timestamps, and ingest
  run ID. Never export request/response headers or application logs as source
  evidence because neither is part of the retained contract.
- **Access:** treat database exports as operator/audit material. The public API
  and operator status API expose hashes/counts only, never canonical payloads.
- **Redaction/correction:** normal corrections append a new policy-versioned
  snapshot and move the current pointer. The old immutable row remains in the
  restricted audit store. If a policy defect captured prohibited data, stop
  exports, rotate access, preserve a secured incident backup, and ship a
  reviewed remediation migration; do not bypass triggers ad hoc.

A bounded sanitized export is:

```sql
SELECT auction_id, content_sha256, schema_version,
       minimization_policy_version, listing_endpoint, detail_endpoint,
       fetched_at, listing_fetched_at, detail_fetched_at,
       source_start_at, source_end_at, source_publication_at,
       ingest_run_id, canonical_payload
  FROM auction_source_snapshots
 ORDER BY auction_id, created_at, content_sha256;
```

## Verification and storage evidence

Useful lineage checks are:

```sql
SELECT auction.id, auction.current_source_snapshot_sha256,
       observation.run_id, observation.source_snapshot_sha256
  FROM auctions auction
  JOIN sync_run_auction_observations observation
    ON observation.auction_id = auction.id
 WHERE observation.source_snapshot_sha256 IS NOT NULL
 ORDER BY auction.id, observation.run_id;

SELECT schema_version, minimization_policy_version,
       count(*) AS snapshots,
       sum(pg_column_size(canonical_payload)) AS stored_bytes,
       max(pg_column_size(canonical_payload)) AS largest_snapshot_bytes
  FROM auction_source_snapshots
 GROUP BY schema_version, minimization_policy_version;
```

The checked-in golden listing/detail fixture measured 1,424 PostgreSQL bytes
for the JSONB column and 1,347 UTF-8 bytes for `canonical_payload::text` on
PostgreSQL 18.6. Neither binary sentinel nor the unreviewed listing field was
present. `SyncPersistenceIntegrationTest` retains the query and bounds so the
measurement is repeated on every real-PostgreSQL run.
