-- Follow-up hardening for issue #17. Keep V10 immutable for databases that
-- already applied the initial sync ledger migration.

ALTER TABLE sync_runs
    ADD COLUMN details_quarantined BIGINT NOT NULL DEFAULT 0
        CHECK (details_quarantined >= 0),
    DROP CONSTRAINT ck_sync_run_detail_counts,
    DROP CONSTRAINT ck_sync_run_success_complete,
    ADD CONSTRAINT ck_sync_run_detail_counts CHECK (
        details_succeeded + details_quarantined <= details_required
        AND details_failed <= details_required
        AND details_attempted >= details_succeeded + details_quarantined + details_failed
    ),
    ADD CONSTRAINT ck_sync_run_success_complete CHECK (
        status <> 'SUCCEEDED'
        OR (
            stage = 'COMPLETED'
            AND category_tree_sha256 IS NOT NULL
            AND pages_completed = pages_expected
            AND details_succeeded + details_quarantined = details_required
            AND details_failed = 0
            AND unresolved_error_count = 0
        )
    );

ALTER TABLE sync_run_errors
    ADD COLUMN resolved BOOLEAN NOT NULL DEFAULT FALSE;

-- V10 terminalized every incomplete run as COMPLETED. Backfill the most
-- precise retained failure stage before enforcing the corrected invariant.
-- The trigger is disabled only for this bounded migration-owned repair and is
-- re-enabled before any new object or constraint is published.
ALTER TABLE sync_runs DISABLE TRIGGER trg_sync_runs_terminal_immutable;

UPDATE sync_runs run
   SET stage = COALESCE(
       (
           SELECT error.stage
             FROM sync_run_errors error
            WHERE error.run_id = run.id
            ORDER BY error.ordinal DESC
            LIMIT 1
       ),
       CASE
           WHEN run.details_attempted > 0 THEN 'DETAILS'
           WHEN run.pages_completed > 0 OR run.listing_rows_observed > 0 THEN 'LISTINGS'
           WHEN run.category_tree_sha256 IS NOT NULL THEN 'CATEGORIES'
           ELSE 'CLAIMED'
       END
   )
 WHERE run.status IN ('PARTIAL', 'FAILED')
   AND run.stage = 'COMPLETED';

ALTER TABLE sync_runs ENABLE TRIGGER trg_sync_runs_terminal_immutable;

ALTER TABLE sync_runs
    ADD CONSTRAINT ck_sync_run_completed_stage CHECK (
        (status = 'SUCCEEDED' AND stage = 'COMPLETED')
        OR (status <> 'SUCCEEDED' AND stage <> 'COMPLETED')
    );

-- A quarantined source record is deliberately independent of auctions: new
-- invalid detail payloads must not create current-state rows merely so the run
-- can retain evidence. These rows are inserted in the same transaction that
-- publishes all valid candidates.
CREATE TABLE sync_run_detail_quarantines (
    run_id                     UUID NOT NULL REFERENCES sync_runs(id),
    auction_id                 BIGINT NOT NULL CHECK (auction_id > 0),
    listing_fingerprint        VARCHAR(64) NOT NULL
                               CHECK (listing_fingerprint ~ '^[0-9a-f]{64}$'),
    error_code                 VARCHAR(64) NOT NULL
                               CHECK (error_code ~ '^[A-Z0-9_]+$'),
    occurred_at                TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (run_id, auction_id)
);

CREATE INDEX idx_sync_detail_quarantines_auction
    ON sync_run_detail_quarantines (auction_id, run_id);

CREATE TRIGGER trg_sync_detail_quarantines_mutable_only_while_running
BEFORE INSERT OR UPDATE OR DELETE ON sync_run_detail_quarantines
FOR EACH ROW EXECUTE FUNCTION guard_sync_run_child_evidence();

-- Observation evidence is terminally immutable, so a cascade can never
-- legally delete it. Make the real restrictive behavior explicit.
ALTER TABLE sync_run_auction_observations
    DROP CONSTRAINT sync_run_auction_observations_auction_id_fkey,
    ADD CONSTRAINT sync_run_auction_observations_auction_id_fkey
        FOREIGN KEY (auction_id) REFERENCES auctions(id);
