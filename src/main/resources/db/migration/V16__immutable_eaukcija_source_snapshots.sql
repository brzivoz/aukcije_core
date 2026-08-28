-- Issue #10: exact, minimized eAukcija listing+detail source replay.
--
-- This ledger is deliberately separate from V13's normalized enrichment input
-- snapshots. Source JSON is captured before DTO/entity normalization, while
-- V13 remains the small deterministic input used by the current parser stages.

CREATE TABLE auction_source_snapshots (
    -- RESTRICT is intentional: retained snapshots make both auction and run
    -- audit identities non-deletable until a reviewed erasure migration exists.
    auction_id                  BIGINT NOT NULL REFERENCES auctions(id) ON DELETE RESTRICT,
    content_sha256              CHAR(64) NOT NULL
                                CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    schema_version              TEXT NOT NULL CHECK (btrim(schema_version) <> ''),
    minimization_policy_version TEXT NOT NULL
                                CHECK (btrim(minimization_policy_version) <> ''),
    listing_endpoint            TEXT NOT NULL CHECK (btrim(listing_endpoint) <> ''),
    detail_endpoint             TEXT NOT NULL CHECK (btrim(detail_endpoint) <> ''),
    canonical_payload           JSONB NOT NULL
                                CHECK (
                                    jsonb_typeof(canonical_payload) = 'object'
                                    AND jsonb_typeof(canonical_payload -> 'listing') = 'object'
                                    AND jsonb_typeof(canonical_payload -> 'detail') = 'object'
                                ),
    fetched_at                  TIMESTAMP WITH TIME ZONE NOT NULL,
    listing_fetched_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    detail_fetched_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    source_start_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    source_end_at               TIMESTAMP WITH TIME ZONE NOT NULL,
    source_publication_at       TIMESTAMP WITH TIME ZONE,
    ingest_run_id               UUID NOT NULL REFERENCES sync_runs(id) ON DELETE RESTRICT,
    created_at                  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (auction_id, content_sha256),
    CONSTRAINT ck_auction_source_snapshot_times CHECK (
        source_end_at >= source_start_at
        AND fetched_at >= listing_fetched_at
        AND fetched_at >= detail_fetched_at
    )
);

CREATE INDEX idx_auction_source_snapshots_run
    ON auction_source_snapshots (ingest_run_id, auction_id, content_sha256);
CREATE INDEX idx_auction_source_snapshots_hash
    ON auction_source_snapshots (content_sha256, auction_id);
CREATE INDEX idx_auction_source_snapshots_version
    ON auction_source_snapshots (
        schema_version, minimization_policy_version, created_at, auction_id
    );

ALTER TABLE auctions
    ADD COLUMN current_source_snapshot_sha256 CHAR(64),
    ADD CONSTRAINT ck_auctions_source_snapshot_sha256 CHECK (
        current_source_snapshot_sha256 IS NULL
        OR current_source_snapshot_sha256 ~ '^[0-9a-f]{64}$'
    ),
    ADD CONSTRAINT fk_auctions_current_source_snapshot
        FOREIGN KEY (id, current_source_snapshot_sha256)
        REFERENCES auction_source_snapshots(auction_id, content_sha256)
        ON DELETE RESTRICT;

-- Historical observations predate #10 and intentionally remain NULL. Every
-- observation inserted after this migration is guarded below and must point to
-- the exact current source snapshot selected in the same promotion transaction.
ALTER TABLE sync_run_auction_observations
    ADD COLUMN source_snapshot_sha256 CHAR(64),
    ADD CONSTRAINT ck_sync_observation_source_snapshot_sha256 CHECK (
        source_snapshot_sha256 IS NULL
        OR source_snapshot_sha256 ~ '^[0-9a-f]{64}$'
    ),
    ADD CONSTRAINT fk_sync_observation_source_snapshot
        FOREIGN KEY (auction_id, source_snapshot_sha256)
        REFERENCES auction_source_snapshots(auction_id, content_sha256)
        ON DELETE RESTRICT;

CREATE INDEX idx_sync_observations_source_snapshot
    ON sync_run_auction_observations (auction_id, source_snapshot_sha256, run_id);

CREATE FUNCTION reject_auction_source_snapshot_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'auction source snapshots are immutable';
END;
$$;

CREATE TRIGGER trg_auction_source_snapshots_immutable
BEFORE UPDATE OR DELETE ON auction_source_snapshots
FOR EACH ROW EXECUTE FUNCTION reject_auction_source_snapshot_mutation();

CREATE FUNCTION guard_source_snapshot_ingest_run()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    parent_status VARCHAR(16);
BEGIN
    SELECT status INTO parent_status FROM sync_runs WHERE id = NEW.ingest_run_id;
    IF parent_status IS DISTINCT FROM 'RUNNING' THEN
        RAISE EXCEPTION 'source snapshots may only be inserted by a running sync run';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_auction_source_snapshots_running_run
BEFORE INSERT ON auction_source_snapshots
FOR EACH ROW EXECUTE FUNCTION guard_source_snapshot_ingest_run();

CREATE FUNCTION guard_sync_observation_source_snapshot()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    selected_sha256 CHAR(64);
BEGIN
    SELECT current_source_snapshot_sha256
      INTO selected_sha256
      FROM auctions
     WHERE id = NEW.auction_id;
    IF selected_sha256 IS NOT NULL AND NEW.source_snapshot_sha256 IS NULL THEN
        RAISE EXCEPTION 'new sync observations require a source snapshot';
    END IF;
    IF NEW.source_snapshot_sha256 IS NOT NULL
       AND selected_sha256 IS DISTINCT FROM NEW.source_snapshot_sha256 THEN
        RAISE EXCEPTION 'sync observation source snapshot is not current';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_sync_observations_require_source_snapshot
BEFORE INSERT OR UPDATE OF auction_id, source_snapshot_sha256
ON sync_run_auction_observations
FOR EACH ROW EXECUTE FUNCTION guard_sync_observation_source_snapshot();
