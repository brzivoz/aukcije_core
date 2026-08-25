-- Issue #29: deterministic, idempotent enrichment reprocessing.
--
-- The coordinator deliberately has no claim/lease queue. Work is discovered by
-- comparing the current immutable local input snapshot and active versions with
-- one current state row per auction. Run/item rows are retained as evidence.

CREATE TABLE auction_enrichment_input_snapshots (
    auction_id                  BIGINT NOT NULL REFERENCES auctions(id) ON DELETE RESTRICT,
    snapshot_sha256             CHAR(64) NOT NULL
                                CHECK (snapshot_sha256 ~ '^[0-9a-f]{64}$'),
    canonical_input             JSONB NOT NULL
                                CHECK (jsonb_typeof(canonical_input) = 'object'),
    created_at                  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (auction_id, snapshot_sha256)
);

CREATE TABLE auction_enrichment_snapshot_observations (
    source_sync_run_id          UUID NOT NULL,
    auction_id                  BIGINT NOT NULL,
    snapshot_sha256             CHAR(64) NOT NULL,
    observed_at                 TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (source_sync_run_id, auction_id),
    FOREIGN KEY (source_sync_run_id, auction_id)
        REFERENCES sync_run_auction_observations(run_id, auction_id) ON DELETE RESTRICT,
    FOREIGN KEY (auction_id, snapshot_sha256)
        REFERENCES auction_enrichment_input_snapshots(auction_id, snapshot_sha256)
        ON DELETE RESTRICT
);

CREATE INDEX idx_enrichment_snapshot_observations_current
    ON auction_enrichment_snapshot_observations (auction_id, observed_at DESC, source_sync_run_id);

ALTER TABLE auctions
    ADD COLUMN current_enrichment_snapshot_sha256 CHAR(64),
    ADD CONSTRAINT ck_auctions_enrichment_snapshot_sha256 CHECK (
        current_enrichment_snapshot_sha256 IS NULL
        OR current_enrichment_snapshot_sha256 ~ '^[0-9a-f]{64}$'
    ),
    ADD CONSTRAINT fk_auctions_current_enrichment_snapshot
        FOREIGN KEY (id, current_enrichment_snapshot_sha256)
        REFERENCES auction_enrichment_input_snapshots(auction_id, snapshot_sha256)
        ON DELETE RESTRICT;

CREATE FUNCTION reject_enrichment_input_snapshot_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'auction enrichment input snapshots are immutable';
END;
$$;

CREATE TRIGGER trg_enrichment_input_snapshots_immutable
BEFORE UPDATE OR DELETE ON auction_enrichment_input_snapshots
FOR EACH ROW EXECUTE FUNCTION reject_enrichment_input_snapshot_mutation();

CREATE FUNCTION reject_enrichment_snapshot_observation_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'auction enrichment snapshot observations are immutable';
END;
$$;

CREATE TRIGGER trg_enrichment_snapshot_observations_immutable
BEFORE UPDATE OR DELETE ON auction_enrichment_snapshot_observations
FOR EACH ROW EXECUTE FUNCTION reject_enrichment_snapshot_observation_mutation();

CREATE FUNCTION guard_enrichment_snapshot_success_only()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    parent_status VARCHAR(16);
BEGIN
    SELECT status INTO parent_status FROM sync_runs WHERE id = NEW.source_sync_run_id;
    IF parent_status IS DISTINCT FROM 'SUCCEEDED' THEN
        RAISE EXCEPTION 'enrichment inputs may only be published by a successful sync run';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_enrichment_snapshot_success_only
BEFORE INSERT ON auction_enrichment_snapshot_observations
FOR EACH ROW EXECUTE FUNCTION guard_enrichment_snapshot_success_only();

CREATE TABLE enrichment_control (
    singleton                   BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    paused                      BOOLEAN NOT NULL DEFAULT FALSE,
    changed_at                  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    change_code                 VARCHAR(32) NOT NULL DEFAULT 'INITIAL'
                                CHECK (change_code ~ '^[A-Z0-9_]+$')
);

INSERT INTO enrichment_control(singleton) VALUES (TRUE);

CREATE TABLE enrichment_runs (
    id                          UUID PRIMARY KEY,
    idempotency_key_sha256      CHAR(64) NOT NULL UNIQUE
                                CHECK (idempotency_key_sha256 ~ '^[0-9a-f]{64}$'),
    trigger_kind                VARCHAR(16) NOT NULL
                                CHECK (trigger_kind IN ('MANUAL', 'SCHEDULED', 'REPLAY', 'RECOVERY')),
    status                      VARCHAR(16) NOT NULL
                                CHECK (status IN (
                                    'RUNNING', 'SUCCEEDED', 'PARTIAL', 'PAUSED', 'FAILED', 'INTERRUPTED'
                                )),
    started_at                  TIMESTAMP WITH TIME ZONE NOT NULL,
    heartbeat_at                TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at                 TIMESTAMP WITH TIME ZONE,
    parser_version              TEXT NOT NULL CHECK (btrim(parser_version) <> ''),
    resolver_version            TEXT NOT NULL CHECK (btrim(resolver_version) <> ''),
    dataset_version             TEXT NOT NULL CHECK (btrim(dataset_version) <> ''),
    selector_type               VARCHAR(24) NOT NULL DEFAULT 'NONE'
                                CHECK (selector_type IN (
                                    'NONE', 'SOURCE_SYNC_RUN', 'ENRICHMENT_RUN',
                                    'AUCTION', 'VERSION', 'RECOVERY_RUN'
                                )),
    selector_value              TEXT,
    max_items                   INTEGER NOT NULL CHECK (max_items BETWEEN 1 AND 1000),
    candidate_count             BIGINT NOT NULL DEFAULT 0 CHECK (candidate_count >= 0),
    attempted_count             BIGINT NOT NULL DEFAULT 0 CHECK (attempted_count >= 0),
    succeeded_count             BIGINT NOT NULL DEFAULT 0 CHECK (succeeded_count >= 0),
    retryable_failure_count     BIGINT NOT NULL DEFAULT 0 CHECK (retryable_failure_count >= 0),
    terminal_not_found_count    BIGINT NOT NULL DEFAULT 0 CHECK (terminal_not_found_count >= 0),
    ambiguous_count             BIGINT NOT NULL DEFAULT 0 CHECK (ambiguous_count >= 0),
    permanent_failure_count     BIGINT NOT NULL DEFAULT 0 CHECK (permanent_failure_count >= 0),
    attempt_limit_count         BIGINT NOT NULL DEFAULT 0 CHECK (attempt_limit_count >= 0),
    CONSTRAINT ck_enrichment_run_time CHECK (
        (status = 'RUNNING' AND finished_at IS NULL)
        OR (status <> 'RUNNING' AND finished_at IS NOT NULL AND finished_at >= started_at)
    ),
    CONSTRAINT ck_enrichment_run_selector CHECK (
        (selector_type = 'NONE' AND selector_value IS NULL)
        OR (selector_type <> 'NONE' AND selector_value IS NOT NULL AND btrim(selector_value) <> '')
    ),
    CONSTRAINT ck_enrichment_run_counts CHECK (
        attempted_count <= candidate_count
        AND attempted_count = succeeded_count + retryable_failure_count
                              + terminal_not_found_count + ambiguous_count
                              + permanent_failure_count + attempt_limit_count
    )
);

CREATE UNIQUE INDEX uq_enrichment_runs_single_running
    ON enrichment_runs ((TRUE)) WHERE status = 'RUNNING';
CREATE INDEX idx_enrichment_runs_started
    ON enrichment_runs (started_at DESC, id DESC);

CREATE TABLE enrichment_state (
    auction_id                  BIGINT PRIMARY KEY REFERENCES auctions(id) ON DELETE RESTRICT,
    source_sync_run_id          UUID NOT NULL REFERENCES sync_runs(id) ON DELETE RESTRICT,
    snapshot_sha256             CHAR(64) NOT NULL,
    parser_version              TEXT NOT NULL CHECK (btrim(parser_version) <> ''),
    resolver_version            TEXT NOT NULL CHECK (btrim(resolver_version) <> ''),
    dataset_version             TEXT NOT NULL CHECK (btrim(dataset_version) <> ''),
    dependency_sha256           CHAR(64) NOT NULL
                                CHECK (dependency_sha256 ~ '^[0-9a-f]{64}$'),
    work_key_sha256             CHAR(64) NOT NULL
                                CHECK (work_key_sha256 ~ '^[0-9a-f]{64}$'),
    status                      VARCHAR(32) NOT NULL
                                CHECK (status IN (
                                    'PENDING', 'RUNNING', 'SUCCEEDED', 'RETRYABLE_FAILURE',
                                    'TERMINAL_NOT_FOUND', 'AMBIGUOUS', 'PERMANENT_FAILURE',
                                    'ATTEMPT_LIMIT_REACHED'
                                )),
    attempt_count               INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    pending_since               TIMESTAMP WITH TIME ZONE NOT NULL,
    last_attempt_at             TIMESTAMP WITH TIME ZONE,
    completed_at                TIMESTAMP WITH TIME ZONE,
    last_enrichment_run_id      UUID REFERENCES enrichment_runs(id) ON DELETE RESTRICT,
    last_stage                  VARCHAR(24)
                                CHECK (last_stage IS NULL OR last_stage IN (
                                    'PARSE', 'KO_MATCHING', 'PARCEL_PATH',
                                    'ADDRESS_FALLBACK', 'SELECTED_RESOLUTION'
                                )),
    output_sha256               CHAR(64)
                                CHECK (output_sha256 IS NULL OR output_sha256 ~ '^[0-9a-f]{64}$'),
    error_class                 VARCHAR(64)
                                CHECK (error_class IS NULL OR error_class ~ '^[A-Z0-9_]+$'),
    error_message               VARCHAR(160)
                                CHECK (error_message IS NULL OR error_message ~ '^[A-Z0-9_]+$'),
    FOREIGN KEY (auction_id, snapshot_sha256)
        REFERENCES auction_enrichment_input_snapshots(auction_id, snapshot_sha256)
        ON DELETE RESTRICT,
    CONSTRAINT ck_enrichment_state_completion CHECK (
        (status IN ('PENDING', 'RUNNING', 'RETRYABLE_FAILURE') AND completed_at IS NULL)
        OR (status NOT IN ('PENDING', 'RUNNING', 'RETRYABLE_FAILURE') AND completed_at IS NOT NULL)
    ),
    CONSTRAINT ck_enrichment_state_error CHECK (
        (status IN ('RETRYABLE_FAILURE', 'PERMANENT_FAILURE', 'ATTEMPT_LIMIT_REACHED')
             AND error_class IS NOT NULL AND error_message IS NOT NULL)
        OR (status NOT IN ('RETRYABLE_FAILURE', 'PERMANENT_FAILURE', 'ATTEMPT_LIMIT_REACHED')
             AND error_class IS NULL AND error_message IS NULL)
    ),
    CONSTRAINT ck_enrichment_state_output CHECK (
        (status IN ('SUCCEEDED', 'TERMINAL_NOT_FOUND', 'AMBIGUOUS') AND output_sha256 IS NOT NULL)
        OR (status NOT IN ('SUCCEEDED', 'TERMINAL_NOT_FOUND', 'AMBIGUOUS') AND output_sha256 IS NULL)
    )
);

CREATE INDEX idx_enrichment_state_status
    ON enrichment_state (status, pending_since, auction_id);
CREATE INDEX idx_enrichment_state_versions
    ON enrichment_state (parser_version, resolver_version, dataset_version, auction_id);

CREATE TABLE enrichment_run_items (
    run_id                      UUID NOT NULL REFERENCES enrichment_runs(id) ON DELETE RESTRICT,
    ordinal                     INTEGER NOT NULL CHECK (ordinal > 0),
    auction_id                  BIGINT NOT NULL REFERENCES auctions(id) ON DELETE RESTRICT,
    work_key_sha256             CHAR(64) NOT NULL
                                CHECK (work_key_sha256 ~ '^[0-9a-f]{64}$'),
    attempt_number              INTEGER NOT NULL CHECK (attempt_number > 0),
    status                      VARCHAR(32) NOT NULL
                                CHECK (status IN (
                                    'RUNNING', 'SUCCEEDED', 'RETRYABLE_FAILURE',
                                    'TERMINAL_NOT_FOUND', 'AMBIGUOUS', 'PERMANENT_FAILURE',
                                    'ATTEMPT_LIMIT_REACHED', 'INTERRUPTED'
                                )),
    last_stage                  VARCHAR(24)
                                CHECK (last_stage IS NULL OR last_stage IN (
                                    'PARSE', 'KO_MATCHING', 'PARCEL_PATH',
                                    'ADDRESS_FALLBACK', 'SELECTED_RESOLUTION'
                                )),
    started_at                  TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at                 TIMESTAMP WITH TIME ZONE,
    output_sha256               CHAR(64)
                                CHECK (output_sha256 IS NULL OR output_sha256 ~ '^[0-9a-f]{64}$'),
    error_class                 VARCHAR(64)
                                CHECK (error_class IS NULL OR error_class ~ '^[A-Z0-9_]+$'),
    error_message               VARCHAR(160)
                                CHECK (error_message IS NULL OR error_message ~ '^[A-Z0-9_]+$'),
    PRIMARY KEY (run_id, auction_id),
    UNIQUE (run_id, ordinal),
    CONSTRAINT ck_enrichment_item_time CHECK (
        (status = 'RUNNING' AND finished_at IS NULL)
        OR (status <> 'RUNNING' AND finished_at IS NOT NULL AND finished_at >= started_at)
    ),
    CONSTRAINT ck_enrichment_item_error CHECK (
        (status IN (
            'RETRYABLE_FAILURE', 'PERMANENT_FAILURE',
            'ATTEMPT_LIMIT_REACHED', 'INTERRUPTED'
         ) AND error_class IS NOT NULL AND error_message IS NOT NULL)
        OR (status NOT IN (
            'RETRYABLE_FAILURE', 'PERMANENT_FAILURE',
            'ATTEMPT_LIMIT_REACHED', 'INTERRUPTED'
         ) AND error_class IS NULL AND error_message IS NULL)
    ),
    CONSTRAINT ck_enrichment_item_output CHECK (
        (status IN ('SUCCEEDED', 'TERMINAL_NOT_FOUND', 'AMBIGUOUS') AND output_sha256 IS NOT NULL)
        OR (status NOT IN ('SUCCEEDED', 'TERMINAL_NOT_FOUND', 'AMBIGUOUS') AND output_sha256 IS NULL)
    )
);

CREATE INDEX idx_enrichment_run_items_auction
    ON enrichment_run_items (auction_id, started_at DESC, run_id);

CREATE FUNCTION guard_enrichment_run_terminal_immutability()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'enrichment run evidence cannot be deleted';
    END IF;
    IF OLD.status <> 'RUNNING' THEN
        RAISE EXCEPTION 'terminal enrichment run evidence is immutable';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.idempotency_key_sha256 IS DISTINCT FROM OLD.idempotency_key_sha256
       OR NEW.trigger_kind IS DISTINCT FROM OLD.trigger_kind
       OR NEW.started_at IS DISTINCT FROM OLD.started_at
       OR NEW.parser_version IS DISTINCT FROM OLD.parser_version
       OR NEW.resolver_version IS DISTINCT FROM OLD.resolver_version
       OR NEW.dataset_version IS DISTINCT FROM OLD.dataset_version
       OR NEW.selector_type IS DISTINCT FROM OLD.selector_type
       OR NEW.selector_value IS DISTINCT FROM OLD.selector_value
       OR NEW.max_items IS DISTINCT FROM OLD.max_items THEN
        RAISE EXCEPTION 'enrichment run identity and configuration are immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_enrichment_runs_terminal_immutable
BEFORE UPDATE OR DELETE ON enrichment_runs
FOR EACH ROW EXECUTE FUNCTION guard_enrichment_run_terminal_immutability();

CREATE FUNCTION guard_enrichment_run_item_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'enrichment run item evidence cannot be deleted';
    END IF;
    IF OLD.status <> 'RUNNING' THEN
        RAISE EXCEPTION 'terminal enrichment run item evidence is immutable';
    END IF;
    IF NEW.run_id IS DISTINCT FROM OLD.run_id
       OR NEW.ordinal IS DISTINCT FROM OLD.ordinal
       OR NEW.auction_id IS DISTINCT FROM OLD.auction_id
       OR NEW.work_key_sha256 IS DISTINCT FROM OLD.work_key_sha256
       OR NEW.attempt_number IS DISTINCT FROM OLD.attempt_number
       OR NEW.started_at IS DISTINCT FROM OLD.started_at THEN
        RAISE EXCEPTION 'enrichment run item identity is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_enrichment_run_items_terminal_immutable
BEFORE UPDATE OR DELETE ON enrichment_run_items
FOR EACH ROW EXECUTE FUNCTION guard_enrichment_run_item_mutation();
