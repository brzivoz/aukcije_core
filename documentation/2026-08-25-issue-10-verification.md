# Issue #10 verification — 2026-08-25

## Outcome

V16 adds immutable, content-addressed eAukcija listing+detail source snapshots.
The #17 client now carries the exact validated `Data` JSON beside its DTO; a
versioned deny-by-default policy removes binary, transport, unreviewed, and
unrelated fields before canonical hashing. Successful promotion inserts or
reuses the snapshot, moves the auction pointer, and adds the run observation in
one transaction. The existing V13 normalized enrichment input remains a
separate downstream contract.

## Acceptance evidence

| Contract | Evidence |
|---|---|
| Exact pre-normalization replay | `EAukcijaCallResult.sourceData` is captured from an exact `BigDecimal` JSON tree before DTO conversion. Golden and HTTP-to-snapshot tests preserve source names, Unicode, decimal scale, 19-significant-digit values, booleans, and nulls without a `double` round-trip. `AuctionSourceSnapshotReplayParser` replays the JSON read back from PostgreSQL and has no client/network dependency. |
| Versioned exclusions | `public-auction-fields-v1` allowlists listing/detail plus nested category/place fields. Tests prove `Thumbnail`, `ThumbnailType`, `Images`, base64 sentinels, transport tokens, and an unreviewed field do not affect or appear in the snapshot. |
| Deterministic source hash | `AuctionSourceSnapshotFactoryTest` proves identical SHA-256 under reversed key order, injected pretty printing, and changed excluded values; changing an allowed detail field produces a different hash. One fixed serializer performs canonical sizing, hashing, and independent record verification. |
| Append-only deduplication | V16 uses primary key `(auction_id, content_sha256)`, immutable update/delete triggers, and run/hash/version indexes. The golden PostgreSQL test stores exact money, reads the current payload through the production repository, combines it with a fresh listing, re-hashes and promotes it again, then proves one snapshot row and one stable current/observation hash. Correction tests append row two, keep both hashes, select the correction, and reject update/delete. |
| Current-state and run lineage | Composite foreign keys connect `auctions.current_source_snapshot_sha256` and `sync_run_auction_observations.source_snapshot_sha256` to the exact auction snapshot. New promotion selects the pointer before inserting its observation. Legacy observations remain nullable; any auction that already has a current source pointer cannot accept a pointerless observation. |
| Validation/failure safety | The client keeps JSON content-type, malformed/null envelope, valid-detail, and response-size gates. Snapshot creation adds auction-ID, scalar-type drift, 32-level depth, 64-KiB canonical size, timestamp, and listing/detail object gates. Legacy rows without source lineage force one detail refresh; invalid detail is rejected and never advances the pointer. Transient snapshot-read failures use `SOURCE_SNAPSHOT_READ_FAILED`, not corrupt-lineage evidence. Promotion rollback assertions include absence of source rows. |
| Retention/export/redaction | `SOURCE_SNAPSHOT_OPERATIONS.md` defines indefinite audit retention, sanitized export fields, restricted payload access, versioned correction, and incident remediation without ad hoc mutation. Public/operator DTOs expose hashes and counts, not JSON. |
| Representative storage | The exact-decimal golden fixture occupied 1,424 bytes as PostgreSQL JSONB and 1,347 bytes as text on PostgreSQL 18.6. The retained integration query also asserts no binary sentinel or unreviewed field leaked. |

## Review remediation — 2026-08-28

- Source floating-point tokens now enter an exact, non-normalizing
  `BigDecimal` tree before DTO binding. `159600.00` retains its scale and
  `12345678901234567.89` remains unchanged through HTTP, DTO, and snapshot.
- HTTP parsing, PostgreSQL snapshot read-back, and fixtures share the same exact
  source reader. Canonical sizing, hashing, and record verification share one
  recursively key-ordered compact serializer rather than injected application
  serialization settings. BigDecimals are written in plain form, so exponent
  source tokens hash and store exactly as PostgreSQL JSONB will return them. A
  loaded current snapshot rejects a payload whose computed hash differs from
  its stored address.
- Each listing page is copied and indexed once. Only typed minimized listing
  and detail wrappers survive staging; excluded binary does not remain in the
  run union. Rows already rejected by the client are skipped if they cannot be
  minimized, while an accepted summary without a valid source row still fails
  the page closed.
- Allowed fields that drift to arrays/objects fail closed. Snapshot database
  failures are distinguished from corrupt lineage and logged with safe run
  context.
- Current source hashes are selected for the full promotion with one set-based
  PostgreSQL update and an exact affected-row check.
- The restrictive auction/run foreign keys are confirmed as an intentional
  consequence of indefinite audit retention.
- Unexpected catch-all logging deliberately retains only the fixed run/code;
  source and driver exception text is omitted because it can contain response
  data.

## Fresh focused verification

Environment: Java 17, Gradle 8.5, Docker-backed pinned
`postgis/postgis:18-3.6`, PostgreSQL 18.6, Flyway V1–V16. No test contacted
eaukcija.sud.rs.

```text
./gradlew test \
  --tests 'rs.sud.eaukcija.snapshot.AuctionSourceSnapshotFactoryTest' \
  --tests 'rs.sud.eaukcija.client.EAukcijaClientTest' \
  --tests 'rs.sud.eaukcija.service.SyncServiceTest' --no-daemon
BUILD SUCCESSFUL
72/72 passed

./gradlew test \
  --tests 'rs.sud.eaukcija.PostgisSchemaIntegrationTest' \
  --tests 'rs.sud.eaukcija.sync.persistence.SyncPersistenceIntegrationTest' \
  --tests 'rs.sud.eaukcija.operations.PipelineStatusRepositoryIntegrationTest' \
  --no-daemon
BUILD SUCCESSFUL
40/40 passed
```

```text
./gradlew check --no-daemon
BUILD SUCCESSFUL in 1m 38s
408 tests, 0 failures, 4 ignored opt-in/full-dataset tests
basemapTest passed (up-to-date)
```

The terminal repository check includes the fresh V1-V16 migration path,
Hibernate validation, immutable PostgreSQL lineage/deduplication/correction
tests, offline replay, and the repository's standard basemap verification.
Operating and audit queries are in
[eAukcija source snapshot operations](SOURCE_SNAPSHOT_OPERATIONS.md).
