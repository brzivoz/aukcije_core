-- #11: an ordered projection over V16 evidence, not another snapshot ledger.
-- Older successful runs have no provable publication order/time. They remain
-- outside this boundary namespace; do not manufacture an order with UUIDs.
CREATE TABLE source_history_lineage (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    lineage UUID NOT NULL DEFAULT gen_random_uuid(),
    legacy_coverage BOOLEAN NOT NULL
);
INSERT INTO source_history_lineage(legacy_coverage) SELECT EXISTS(SELECT 1 FROM auctions);

CREATE TABLE source_publications (
    publication_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id UUID NOT NULL UNIQUE REFERENCES sync_runs(id) ON DELETE RESTRICT,
    published_at TIMESTAMPTZ NOT NULL,
    absent_count BIGINT NOT NULL DEFAULT 0 CHECK (absent_count >= 0),
    closed_count BIGINT NOT NULL DEFAULT 0 CHECK (closed_count >= 0),
    reopened_count BIGINT NOT NULL DEFAULT 0 CHECK (reopened_count >= 0),
    UNIQUE (publication_id, run_id)
);
CREATE INDEX idx_source_publications_time ON source_publications(published_at, publication_id);

CREATE FUNCTION guard_source_publication_success() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM sync_runs WHERE id = NEW.run_id AND status = 'SUCCEEDED') THEN
        RAISE EXCEPTION 'source publication requires committed successful sync';
    END IF;
    RETURN NEW;
END;
$$;
CREATE CONSTRAINT TRIGGER trg_source_publication_success AFTER INSERT ON source_publications
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION guard_source_publication_success();
CREATE FUNCTION guard_source_publication_write() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'source publications are immutable'; END IF;
    IF NOT EXISTS (SELECT 1 FROM sync_runs WHERE id = NEW.run_id AND status = 'RUNNING') THEN
        RAISE EXCEPTION 'source publications are immutable outside running promotion';
    END IF;
    IF TG_OP = 'UPDATE' AND (NEW.publication_id, NEW.run_id, NEW.published_at)
        IS DISTINCT FROM (OLD.publication_id, OLD.run_id, OLD.published_at) THEN
        RAISE EXCEPTION 'source publication identity and time are immutable';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_source_publications_write BEFORE INSERT OR UPDATE OR DELETE ON source_publications
    FOR EACH ROW EXECUTE FUNCTION guard_source_publication_write();
CREATE TRIGGER trg_source_history_lineage_immutable BEFORE UPDATE OR DELETE ON source_history_lineage
    FOR EACH ROW EXECUTE FUNCTION reject_auction_source_snapshot_mutation();

ALTER TABLE sync_run_auction_observations
    ADD COLUMN publication_id BIGINT REFERENCES source_publications(publication_id),
    ADD COLUMN content_delta TEXT CHECK (content_delta IN ('NEW','UPDATED','UNCHANGED','BASELINE')),
    ADD COLUMN comparison_policy TEXT,
    ADD COLUMN comparison_kind TEXT CHECK (comparison_kind IN
        ('SUBSTANTIVE','LIVE_BIDDING_ONLY','REPRESENTATION_ONLY','UNCHANGED','BASELINE','UNSUPPORTED')),
    ADD COLUMN changed_fields TEXT[] NOT NULL DEFAULT '{}',
    ADD COLUMN retained_end_at TIMESTAMPTZ,
    ADD CONSTRAINT fk_observation_publication_run FOREIGN KEY (publication_id, run_id)
        REFERENCES source_publications(publication_id, run_id);
CREATE INDEX idx_source_observations_revision ON sync_run_auction_observations(auction_id, publication_id DESC)
    WHERE publication_id IS NOT NULL;
CREATE INDEX idx_source_observations_activity ON sync_run_auction_observations(publication_id, auction_id)
    WHERE (content_delta <> 'UNCHANGED' OR comparison_kind = 'BASELINE') AND publication_id IS NOT NULL;
CREATE INDEX idx_source_observations_auction_activity ON sync_run_auction_observations(auction_id, publication_id DESC)
    WHERE (content_delta <> 'UNCHANGED' OR comparison_kind = 'BASELINE') AND publication_id IS NOT NULL;
CREATE INDEX idx_source_observations_content_change ON sync_run_auction_observations(auction_id, publication_id DESC)
    WHERE content_delta IN ('NEW','UPDATED') AND publication_id IS NOT NULL;

ALTER TABLE auctions
    ADD COLUMN first_reliable_observed_at TIMESTAMPTZ,
    ADD COLUMN first_reliable_sync_run_id UUID REFERENCES sync_runs(id),
    ADD COLUMN last_observation_publication BIGINT REFERENCES source_publications(publication_id),
    ADD COLUMN last_source_change_publication BIGINT REFERENCES source_publications(publication_id),
    ADD COLUMN review_publication BIGINT REFERENCES source_publications(publication_id),
    ADD COLUMN first_absent_at TIMESTAMPTZ,
    ADD COLUMN first_absence_publication BIGINT REFERENCES source_publications(publication_id),
    ADD COLUMN last_absence_sync_run_id UUID REFERENCES sync_runs(id),
    ADD COLUMN absence_closed_effective_at TIMESTAMPTZ,
    ADD COLUMN lifecycle_state TEXT NOT NULL DEFAULT 'UNKNOWN'
        CHECK (lifecycle_state IN ('UNKNOWN','NOT_ENDED','ENDED')),
    ADD COLUMN lifecycle_reason TEXT NOT NULL DEFAULT 'UNKNOWN'
        CHECK (lifecycle_reason IN ('UNKNOWN','FUTURE_END','END_DATE','ABSENCE','END_DATE_AND_ABSENCE')),
    ADD COLUMN lifecycle_end_at TIMESTAMPTZ,
    ADD COLUMN closed_recorded_at TIMESTAMPTZ,
    ADD COLUMN closed_effective_at TIMESTAMPTZ,
    ADD COLUMN closed_reason TEXT,
    ADD COLUMN reopened_recorded_at TIMESTAMPTZ,
    ADD COLUMN reopened_reason TEXT;

-- Only reliable local observation evidence is backfilled, never snapshot
-- creation, source PublicationDate, fetch time, or a guessed edit time.
WITH reliable AS (
    SELECT o.auction_id, r.id, r.category_tree_observed_at,
           min(r.category_tree_observed_at) OVER (PARTITION BY o.auction_id) first_at
      FROM sync_run_auction_observations o JOIN sync_runs r ON r.id = o.run_id
     WHERE r.status = 'SUCCEEDED' AND r.category_tree_observed_at IS NOT NULL
), first_observation AS (
    SELECT auction_id, first_at,
           CASE WHEN count(*) = 1 THEN (array_agg(id))[1] ELSE NULL END run_id
      FROM reliable WHERE category_tree_observed_at = first_at GROUP BY auction_id, first_at
)
UPDATE auctions a SET first_reliable_observed_at = f.first_at,
                      first_reliable_sync_run_id = f.run_id
  FROM first_observation f WHERE f.auction_id = a.id;
-- Historical absence counts are retained, but no first-absence time is invented.
-- Two fresh eligible absences are required to establish a timed closure.

CREATE TABLE auction_lifecycle_transitions (
    auction_id BIGINT NOT NULL REFERENCES auctions(id) ON DELETE RESTRICT,
    publication_id BIGINT NOT NULL REFERENCES source_publications(publication_id),
    transition_kind TEXT NOT NULL,
    from_state TEXT NOT NULL,
    to_state TEXT NOT NULL,
    from_reason TEXT NOT NULL,
    to_reason TEXT NOT NULL,
    previous_end_at TIMESTAMPTZ,
    retained_end_at TIMESTAMPTZ,
    absence_effective_at TIMESTAMPTZ,
    effective_at TIMESTAMPTZ,
    PRIMARY KEY (auction_id, publication_id)
);
CREATE INDEX idx_lifecycle_publication ON auction_lifecycle_transitions(publication_id, auction_id);
CREATE TRIGGER trg_lifecycle_transitions_immutable BEFORE UPDATE OR DELETE ON auction_lifecycle_transitions
    FOR EACH ROW EXECUTE FUNCTION reject_auction_source_snapshot_mutation();
CREATE FUNCTION guard_lifecycle_promotion() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM source_publications p JOIN sync_runs r ON r.id = p.run_id
        WHERE p.publication_id = NEW.publication_id AND r.status = 'RUNNING') THEN
        RAISE EXCEPTION 'lifecycle transitions require running promotion';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_lifecycle_promotion BEFORE INSERT ON auction_lifecycle_transitions
    FOR EACH ROW EXECUTE FUNCTION guard_lifecycle_promotion();
