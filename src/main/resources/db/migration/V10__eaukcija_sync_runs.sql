-- Issues #12/#17: durable, source-safe eAukcija run evidence and an atomic
-- publication boundary for current auction state. Network work is deliberately
-- performed outside database transactions; only a complete run may populate
-- the success observation/membership tables below.

CREATE TABLE eaukcija_taxonomies (
    tree_sha256                VARCHAR(64) PRIMARY KEY
                               CHECK (tree_sha256 ~ '^[0-9a-f]{64}$'),
    normalizer_version         TEXT NOT NULL CHECK (btrim(normalizer_version) <> ''),
    canonical_tree             JSONB NOT NULL
                               CHECK (jsonb_typeof(canonical_tree) = 'array'),
    first_observed_at          TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE sync_runs (
    id                         UUID PRIMARY KEY,
    idempotency_key_sha256     VARCHAR(64) NOT NULL UNIQUE
                               CHECK (idempotency_key_sha256 ~ '^[0-9a-f]{64}$'),
    trigger_kind               VARCHAR(16) NOT NULL
                               CHECK (trigger_kind IN ('MANUAL', 'SCHEDULED')),
    status                     VARCHAR(16) NOT NULL
                               CHECK (status IN ('RUNNING', 'SUCCEEDED', 'PARTIAL', 'FAILED')),
    stage                      VARCHAR(16) NOT NULL
                               CHECK (stage IN (
                                   'CLAIMED', 'CATEGORIES', 'LISTINGS',
                                   'DETAILS', 'PROMOTING', 'COMPLETED'
                               )),
    started_at                 TIMESTAMP WITH TIME ZONE NOT NULL,
    heartbeat_at               TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at                TIMESTAMP WITH TIME ZONE,
    configured_roots           JSONB NOT NULL
                               CHECK (
                                   jsonb_typeof(configured_roots) = 'array'
                                   AND jsonb_array_length(configured_roots) > 0
                               ),
    page_size                  INTEGER NOT NULL CHECK (page_size BETWEEN 1 AND 3000),
    category_tree_sha256       VARCHAR(64) REFERENCES eaukcija_taxonomies(tree_sha256),
    category_tree_observed_at  TIMESTAMP WITH TIME ZONE,
    pages_expected             INTEGER NOT NULL DEFAULT 0 CHECK (pages_expected >= 0),
    pages_completed            INTEGER NOT NULL DEFAULT 0 CHECK (pages_completed >= 0),
    listing_rows_observed      BIGINT NOT NULL DEFAULT 0 CHECK (listing_rows_observed >= 0),
    unique_auction_count       BIGINT NOT NULL DEFAULT 0 CHECK (unique_auction_count >= 0),
    duplicate_auction_count    BIGINT NOT NULL DEFAULT 0 CHECK (duplicate_auction_count >= 0),
    unknown_property_kind_count BIGINT NOT NULL DEFAULT 0 CHECK (unknown_property_kind_count >= 0),
    details_required           BIGINT NOT NULL DEFAULT 0 CHECK (details_required >= 0),
    details_attempted          BIGINT NOT NULL DEFAULT 0 CHECK (details_attempted >= 0),
    details_succeeded          BIGINT NOT NULL DEFAULT 0 CHECK (details_succeeded >= 0),
    details_failed             BIGINT NOT NULL DEFAULT 0 CHECK (details_failed >= 0),
    retry_count                BIGINT NOT NULL DEFAULT 0 CHECK (retry_count >= 0),
    error_count                BIGINT NOT NULL DEFAULT 0 CHECK (error_count >= 0),
    unresolved_error_count     BIGINT NOT NULL DEFAULT 0 CHECK (unresolved_error_count >= 0),
    CONSTRAINT ck_sync_run_time CHECK (
        (status = 'RUNNING' AND finished_at IS NULL)
        OR (status <> 'RUNNING' AND finished_at IS NOT NULL AND finished_at >= started_at)
    ),
    CONSTRAINT ck_sync_run_category_observation CHECK (
        (category_tree_sha256 IS NULL AND category_tree_observed_at IS NULL)
        OR (category_tree_sha256 IS NOT NULL AND category_tree_observed_at IS NOT NULL)
    ),
    CONSTRAINT ck_sync_run_page_counts CHECK (pages_completed <= pages_expected),
    CONSTRAINT ck_sync_run_detail_counts CHECK (
        details_succeeded <= details_required
        AND details_failed <= details_required
        AND details_attempted >= details_succeeded + details_failed
    ),
    CONSTRAINT ck_sync_run_error_counts CHECK (unresolved_error_count <= error_count),
    CONSTRAINT ck_sync_run_success_complete CHECK (
        status <> 'SUCCEEDED'
        OR (
            stage = 'COMPLETED'
            AND category_tree_sha256 IS NOT NULL
            AND pages_completed = pages_expected
            AND details_succeeded = details_required
            AND details_failed = 0
            AND unresolved_error_count = 0
        )
    )
);

-- PostgreSQL, rather than an in-memory flag, is the authority for the one-run
-- invariant across controller requests and application instances.
CREATE UNIQUE INDEX uq_sync_runs_single_running
    ON sync_runs ((TRUE))
    WHERE status = 'RUNNING';
CREATE INDEX idx_sync_runs_started ON sync_runs (started_at DESC);
CREATE INDEX idx_sync_runs_terminal ON sync_runs (finished_at DESC)
    WHERE status <> 'RUNNING';

CREATE TABLE sync_run_root_results (
    run_id                     UUID NOT NULL REFERENCES sync_runs(id),
    root_category_id           INTEGER NOT NULL CHECK (root_category_id > 0),
    source_total_count         BIGINT NOT NULL CHECK (source_total_count >= 0),
    rows_observed              BIGINT NOT NULL CHECK (rows_observed >= 0),
    unique_ids                 BIGINT NOT NULL CHECK (unique_ids >= 0),
    duplicate_ids              BIGINT NOT NULL CHECK (duplicate_ids >= 0),
    pages_expected             INTEGER NOT NULL CHECK (pages_expected >= 0),
    pages_completed            INTEGER NOT NULL CHECK (pages_completed >= 0),
    total_consistent           BOOLEAN NOT NULL,
    complete                   BOOLEAN NOT NULL,
    PRIMARY KEY (run_id, root_category_id),
    CONSTRAINT ck_sync_root_page_counts CHECK (pages_completed <= pages_expected),
    CONSTRAINT ck_sync_root_row_counts CHECK (
        unique_ids <= rows_observed
        AND duplicate_ids = rows_observed - unique_ids
    ),
    CONSTRAINT ck_sync_root_complete CHECK (
        NOT complete
        OR (
            total_consistent
            AND pages_completed = pages_expected
            AND unique_ids = source_total_count
        )
    )
);

-- Child-category endpoints are discovery/classification evidence, not an
-- independent source of auctions. Their rows therefore stay separate from
-- root union counters while retaining the same pagination completeness proof.
CREATE TABLE sync_run_child_results (
    run_id                     UUID NOT NULL REFERENCES sync_runs(id),
    parent_root_category_id    INTEGER NOT NULL CHECK (parent_root_category_id > 0),
    child_category_id          INTEGER NOT NULL CHECK (child_category_id > 0),
    source_total_count         BIGINT NOT NULL CHECK (source_total_count >= 0),
    rows_observed              BIGINT NOT NULL CHECK (rows_observed >= 0),
    unique_ids                 BIGINT NOT NULL CHECK (unique_ids >= 0),
    duplicate_ids              BIGINT NOT NULL CHECK (duplicate_ids >= 0),
    pages_expected             INTEGER NOT NULL CHECK (pages_expected >= 0),
    pages_completed            INTEGER NOT NULL CHECK (pages_completed >= 0),
    total_consistent           BOOLEAN NOT NULL,
    subset_of_parent_root      BOOLEAN NOT NULL,
    complete                   BOOLEAN NOT NULL,
    PRIMARY KEY (run_id, parent_root_category_id, child_category_id),
    CONSTRAINT ck_sync_child_distinct_ids CHECK (
        child_category_id <> parent_root_category_id
    ),
    CONSTRAINT ck_sync_child_page_counts CHECK (pages_completed <= pages_expected),
    CONSTRAINT ck_sync_child_row_counts CHECK (
        unique_ids <= rows_observed
        AND duplicate_ids = rows_observed - unique_ids
    ),
    CONSTRAINT ck_sync_child_complete CHECK (
        NOT complete
        OR (
            total_consistent
            AND subset_of_parent_root
            AND pages_completed = pages_expected
            AND unique_ids = source_total_count
        )
    )
);

CREATE TABLE sync_run_errors (
    run_id                     UUID NOT NULL REFERENCES sync_runs(id),
    ordinal                    INTEGER NOT NULL CHECK (ordinal > 0),
    occurred_at                TIMESTAMP WITH TIME ZONE NOT NULL,
    stage                      VARCHAR(16) NOT NULL
                               CHECK (stage IN ('CATEGORIES', 'LISTINGS', 'DETAILS', 'PROMOTING')),
    root_category_id           INTEGER,
    child_category_id          INTEGER CHECK (child_category_id IS NULL OR child_category_id > 0),
    page_number                INTEGER CHECK (page_number IS NULL OR page_number > 0),
    auction_id                 BIGINT,
    http_status                INTEGER CHECK (http_status IS NULL OR http_status BETWEEN 100 AND 599),
    error_code                 VARCHAR(64) NOT NULL CHECK (error_code ~ '^[A-Z0-9_]+$'),
    retryable                  BOOLEAN NOT NULL,
    attempt_number             INTEGER NOT NULL CHECK (attempt_number > 0),
    PRIMARY KEY (run_id, ordinal)
);

ALTER TABLE auctions
    ADD COLUMN listing_fingerprint VARCHAR(64),
    ADD COLUMN details_fetched_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN source_detail_category_id INTEGER,
    ADD COLUMN sale_scope VARCHAR(16),
    ADD COLUMN normalized_property_kind VARCHAR(16),
    ADD COLUMN taxonomy_sha256 VARCHAR(64),
    ADD COLUMN last_successful_sync_run_id UUID,
    ADD COLUMN absence_count BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN last_seen_at TIMESTAMP WITH TIME ZONE,
    ADD CONSTRAINT ck_auctions_listing_fingerprint CHECK (
        listing_fingerprint IS NULL OR listing_fingerprint ~ '^[0-9a-f]{64}$'
    ),
    ADD CONSTRAINT ck_auctions_detail_timestamp CHECK (
        details_fetched_at IS NULL OR details_fetched
    ),
    ADD CONSTRAINT ck_auctions_sale_scope CHECK (
        sale_scope IS NULL OR sale_scope IN ('IMMOVABLE', 'COMMON')
    ),
    ADD CONSTRAINT ck_auctions_normalized_property_kind CHECK (
        normalized_property_kind IS NULL
        OR normalized_property_kind IN ('PARCEL', 'BUILDING', 'UNIT', 'UNKNOWN')
    ),
    ADD CONSTRAINT ck_auctions_absence_count CHECK (absence_count >= 0),
    ADD CONSTRAINT fk_auctions_taxonomy
        FOREIGN KEY (taxonomy_sha256) REFERENCES eaukcija_taxonomies(tree_sha256),
    ADD CONSTRAINT fk_auctions_last_sync_run
        FOREIGN KEY (last_successful_sync_run_id) REFERENCES sync_runs(id);

CREATE INDEX idx_auctions_sale_scope_kind
    ON auctions (sale_scope, normalized_property_kind);
CREATE INDEX idx_auctions_last_successful_sync_run
    ON auctions (last_successful_sync_run_id);

CREATE TABLE auction_source_category_memberships (
    auction_id                 BIGINT NOT NULL REFERENCES auctions(id) ON DELETE CASCADE,
    category_id               INTEGER NOT NULL CHECK (category_id > 0),
    membership_type           VARCHAR(16) NOT NULL
                               CHECK (membership_type IN ('ROOT', 'CHILD', 'DETAIL')),
    category_name             TEXT,
    taxonomy_sha256           VARCHAR(64) NOT NULL REFERENCES eaukcija_taxonomies(tree_sha256),
    last_successful_sync_run_id UUID NOT NULL REFERENCES sync_runs(id),
    PRIMARY KEY (auction_id, category_id, membership_type)
);

CREATE INDEX idx_auction_source_categories_category
    ON auction_source_category_memberships (category_id, membership_type);
CREATE INDEX idx_auction_source_categories_run
    ON auction_source_category_memberships (last_successful_sync_run_id);

-- This is the success gate consumed by future lifecycle/enrichment work. A
-- PARTIAL/FAILED run has no rows here because insertion and terminal success
-- happen in one transaction.
CREATE TABLE sync_run_auction_observations (
    run_id                     UUID NOT NULL REFERENCES sync_runs(id),
    auction_id                 BIGINT NOT NULL REFERENCES auctions(id) ON DELETE CASCADE,
    listing_fingerprint        VARCHAR(64) NOT NULL
                               CHECK (listing_fingerprint ~ '^[0-9a-f]{64}$'),
    detail_refreshed           BOOLEAN NOT NULL,
    enrichment_eligible       BOOLEAN NOT NULL,
    enrichment_reason         VARCHAR(24) NOT NULL
                               CHECK (enrichment_reason IN (
                                   'NEW', 'LISTING_CHANGED', 'DETAIL_REFRESHED', 'NONE'
                               )),
    PRIMARY KEY (run_id, auction_id),
    CONSTRAINT ck_sync_observation_enrichment CHECK (
        (enrichment_eligible AND enrichment_reason <> 'NONE')
        OR (NOT enrichment_eligible AND enrichment_reason = 'NONE')
    )
);

CREATE INDEX idx_sync_observations_auction
    ON sync_run_auction_observations (auction_id, run_id);
CREATE INDEX idx_sync_observations_enrichment
    ON sync_run_auction_observations (run_id, auction_id)
    WHERE enrichment_eligible;

CREATE TABLE sync_enrichment_queue (
    run_id                     UUID NOT NULL REFERENCES sync_runs(id),
    auction_id                 BIGINT NOT NULL REFERENCES auctions(id) ON DELETE CASCADE,
    status                     VARCHAR(16) NOT NULL DEFAULT 'PENDING'
                               CHECK (status = 'PENDING'),
    reason                     VARCHAR(24) NOT NULL
                               CHECK (reason IN ('NEW', 'LISTING_CHANGED', 'DETAIL_REFRESHED')),
    created_at                 TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (run_id, auction_id)
);

CREATE INDEX idx_sync_enrichment_queue_pending
    ON sync_enrichment_queue (created_at, auction_id)
    WHERE status = 'PENDING';

CREATE FUNCTION guard_sync_run_terminal_immutability()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'sync run evidence cannot be deleted';
    END IF;
    IF OLD.status <> 'RUNNING' THEN
        RAISE EXCEPTION 'terminal sync run evidence is immutable';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.idempotency_key_sha256 IS DISTINCT FROM OLD.idempotency_key_sha256
       OR NEW.trigger_kind IS DISTINCT FROM OLD.trigger_kind
       OR NEW.started_at IS DISTINCT FROM OLD.started_at
       OR NEW.configured_roots IS DISTINCT FROM OLD.configured_roots
       OR NEW.page_size IS DISTINCT FROM OLD.page_size THEN
        RAISE EXCEPTION 'sync run identity and configuration are immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_sync_runs_terminal_immutable
BEFORE UPDATE OR DELETE ON sync_runs
FOR EACH ROW EXECUTE FUNCTION guard_sync_run_terminal_immutability();

CREATE FUNCTION guard_sync_run_child_evidence()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    parent_status VARCHAR(16);
    target_run_id UUID;
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.run_id IS DISTINCT FROM OLD.run_id THEN
        RAISE EXCEPTION 'sync run child evidence identity is immutable';
    END IF;
    target_run_id := CASE WHEN TG_OP = 'INSERT' THEN NEW.run_id ELSE OLD.run_id END;
    SELECT status INTO parent_status FROM sync_runs WHERE id = target_run_id;
    IF parent_status IS DISTINCT FROM 'RUNNING' THEN
        RAISE EXCEPTION 'terminal sync run child evidence is immutable';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_sync_root_results_mutable_only_while_running
BEFORE INSERT OR UPDATE OR DELETE ON sync_run_root_results
FOR EACH ROW EXECUTE FUNCTION guard_sync_run_child_evidence();

CREATE TRIGGER trg_sync_child_results_mutable_only_while_running
BEFORE INSERT OR UPDATE OR DELETE ON sync_run_child_results
FOR EACH ROW EXECUTE FUNCTION guard_sync_run_child_evidence();

CREATE FUNCTION guard_sync_child_result_identity()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.run_id IS DISTINCT FROM OLD.run_id
       OR NEW.parent_root_category_id IS DISTINCT FROM OLD.parent_root_category_id
       OR NEW.child_category_id IS DISTINCT FROM OLD.child_category_id THEN
        RAISE EXCEPTION 'sync child result identity is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_sync_child_result_identity
BEFORE UPDATE ON sync_run_child_results
FOR EACH ROW EXECUTE FUNCTION guard_sync_child_result_identity();

CREATE FUNCTION guard_sync_root_result_scope()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    parent_configured_roots JSONB;
BEGIN
    SELECT configured_roots
      INTO parent_configured_roots
      FROM sync_runs
     WHERE id = NEW.run_id;
    IF parent_configured_roots IS NULL
       OR NOT parent_configured_roots @> jsonb_build_array(NEW.root_category_id) THEN
        RAISE EXCEPTION 'root result must belong to the run configured roots';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_sync_root_result_scope
BEFORE INSERT OR UPDATE OF run_id, root_category_id ON sync_run_root_results
FOR EACH ROW EXECUTE FUNCTION guard_sync_root_result_scope();

CREATE FUNCTION guard_sync_child_result_scope()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    child_is_in_scope BOOLEAN;
BEGIN
    SELECT EXISTS (
        SELECT 1
          FROM sync_runs run
          JOIN eaukcija_taxonomies taxonomy
            ON taxonomy.tree_sha256 = run.category_tree_sha256
         CROSS JOIN LATERAL jsonb_array_elements(taxonomy.canonical_tree) root_node
         CROSS JOIN LATERAL jsonb_array_elements(
             CASE
                 WHEN jsonb_typeof(root_node -> 'children') = 'array'
                 THEN root_node -> 'children'
                 ELSE '[]'::jsonb
             END
         ) child_node
         WHERE run.id = NEW.run_id
           AND run.configured_roots @> jsonb_build_array(NEW.parent_root_category_id)
           AND root_node -> 'value' = to_jsonb(NEW.parent_root_category_id)
           AND child_node -> 'value' = to_jsonb(NEW.child_category_id)
    ) INTO child_is_in_scope;

    IF NOT child_is_in_scope THEN
        RAISE EXCEPTION 'child result must be a captured direct child of a configured root';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_sync_child_result_scope
BEFORE INSERT OR UPDATE OF run_id, parent_root_category_id, child_category_id
ON sync_run_child_results
FOR EACH ROW EXECUTE FUNCTION guard_sync_child_result_scope();

CREATE TRIGGER trg_sync_errors_mutable_only_while_running
BEFORE INSERT OR UPDATE OR DELETE ON sync_run_errors
FOR EACH ROW EXECUTE FUNCTION guard_sync_run_child_evidence();

CREATE TRIGGER trg_sync_observations_mutable_only_while_running
BEFORE INSERT OR UPDATE OR DELETE ON sync_run_auction_observations
FOR EACH ROW EXECUTE FUNCTION guard_sync_run_child_evidence();

CREATE FUNCTION guard_eaukcija_taxonomy_immutability()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'eAukcija taxonomy snapshots are immutable';
END;
$$;

CREATE TRIGGER trg_eaukcija_taxonomies_immutable
BEFORE UPDATE OR DELETE ON eaukcija_taxonomies
FOR EACH ROW EXECUTE FUNCTION guard_eaukcija_taxonomy_immutability();

CREATE FUNCTION guard_sync_enrichment_success_only()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    parent_status VARCHAR(16);
BEGIN
    SELECT status INTO parent_status FROM sync_runs WHERE id = NEW.run_id;
    IF parent_status IS DISTINCT FROM 'SUCCEEDED' THEN
        RAISE EXCEPTION 'enrichment work may only be published by a successful sync run';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_sync_enrichment_success_only
BEFORE INSERT OR UPDATE ON sync_enrichment_queue
FOR EACH ROW EXECUTE FUNCTION guard_sync_enrichment_success_only();
