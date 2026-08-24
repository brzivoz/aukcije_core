-- A malformed listing row must not make every otherwise-complete source
-- snapshot permanently unpublishable. Retain bounded, redacted evidence while
-- keeping the rejected row out of current auction state.

ALTER TABLE sync_runs
    ADD COLUMN listing_rows_quarantined BIGINT NOT NULL DEFAULT 0
        CHECK (listing_rows_quarantined >= 0),
    DROP CONSTRAINT ck_sync_run_success_complete,
    ADD CONSTRAINT ck_sync_run_success_complete CHECK (
        status <> 'SUCCEEDED'
        OR (
            stage = 'COMPLETED'
            AND category_tree_sha256 IS NOT NULL
            AND pages_completed = pages_expected
            AND details_succeeded + details_quarantined = details_required
            AND details_failed = 0
            AND unresolved_error_count = 0
            AND listing_rows_quarantined <= listing_rows_observed
            AND listing_rows_quarantined <= unique_auction_count
            AND error_count >= details_quarantined + listing_rows_quarantined
        )
    );

-- Listing quarantine evidence is independent of auctions for the same reason
-- as detail quarantine evidence: a rejected new source row must never create a
-- current-state auction merely so its failure can be retained.
CREATE TABLE sync_run_listing_quarantines (
    run_id                     UUID NOT NULL REFERENCES sync_runs(id),
    auction_id                 BIGINT NOT NULL CHECK (auction_id > 0),
    source_row_sha256          VARCHAR(64) NOT NULL
                               CHECK (source_row_sha256 ~ '^[0-9a-f]{64}$'),
    error_code                 VARCHAR(64) NOT NULL
                               CHECK (error_code = 'INVALID_DATA'),
    root_category_id           INTEGER NOT NULL CHECK (root_category_id > 0),
    child_category_id          INTEGER
                               CHECK (child_category_id IS NULL OR child_category_id > 0),
    page_number                INTEGER NOT NULL CHECK (page_number > 0),
    occurred_at                TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (run_id, auction_id),
    CONSTRAINT ck_sync_listing_quarantine_category_ids CHECK (
        child_category_id IS NULL OR child_category_id <> root_category_id
    )
);

CREATE INDEX idx_sync_listing_quarantines_auction
    ON sync_run_listing_quarantines (auction_id, run_id);

CREATE INDEX idx_sync_listing_quarantines_source_location
    ON sync_run_listing_quarantines (
        root_category_id, child_category_id, page_number, run_id
    );

CREATE FUNCTION guard_sync_listing_quarantine_scope()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM sync_runs run
         WHERE run.id = NEW.run_id
           AND run.configured_roots @> jsonb_build_array(NEW.root_category_id)
    ) THEN
        RAISE EXCEPTION 'listing quarantine root is outside configured roots';
    END IF;
    IF NEW.child_category_id IS NOT NULL AND NOT EXISTS (
        SELECT 1
          FROM sync_run_child_results child
         WHERE child.run_id = NEW.run_id
           AND child.parent_root_category_id = NEW.root_category_id
           AND child.child_category_id = NEW.child_category_id
    ) THEN
        RAISE EXCEPTION 'listing quarantine child is outside captured taxonomy';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_sync_listing_quarantines_scope
BEFORE INSERT OR UPDATE ON sync_run_listing_quarantines
FOR EACH ROW EXECUTE FUNCTION guard_sync_listing_quarantine_scope();

CREATE TRIGGER trg_sync_listing_quarantines_mutable_only_while_running
BEFORE INSERT OR UPDATE OR DELETE ON sync_run_listing_quarantines
FOR EACH ROW EXECUTE FUNCTION guard_sync_run_child_evidence();
