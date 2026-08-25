-- Issue #40: one durable source-to-map workflow shared by manual and scheduled use.

CREATE TABLE refresh_runs (
    id                          UUID PRIMARY KEY,
    idempotency_key_sha256      CHAR(64) NOT NULL UNIQUE
                                CHECK (idempotency_key_sha256 ~ '^[0-9a-f]{64}$'),
    trigger_kind                VARCHAR(16) NOT NULL
                                CHECK (trigger_kind IN ('MANUAL', 'SCHEDULED')),
    status                      VARCHAR(16) NOT NULL
                                CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    stage                       VARCHAR(24) NOT NULL
                                CHECK (stage IN (
                                    'DOWNLOAD_LISTINGS', 'DOWNLOAD_DETAILS',
                                    'PROCESS_LOCATIONS', 'PREPARE_MAP', 'COMPLETED'
                                )),
    started_at                  TIMESTAMP WITH TIME ZONE NOT NULL,
    heartbeat_at                TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at                 TIMESTAMP WITH TIME ZONE,
    source_sync_run_id          UUID REFERENCES sync_runs(id) ON DELETE RESTRICT,
    enrichment_run_id           UUID REFERENCES enrichment_runs(id) ON DELETE RESTRICT,
    map_resolution_run_id       UUID REFERENCES coarse_location_resolution_runs(id) ON DELETE RESTRICT,
    parser_version              TEXT,
    resolver_version            TEXT,
    dataset_version             TEXT,
    listings_processed          BIGINT NOT NULL DEFAULT 0 CHECK (listings_processed >= 0),
    listings_total              BIGINT NOT NULL DEFAULT 0 CHECK (listings_total >= 0),
    details_processed           BIGINT NOT NULL DEFAULT 0 CHECK (details_processed >= 0),
    details_total               BIGINT NOT NULL DEFAULT 0 CHECK (details_total >= 0),
    locations_processed         BIGINT NOT NULL DEFAULT 0 CHECK (locations_processed >= 0),
    locations_total             BIGINT NOT NULL DEFAULT 0 CHECK (locations_total >= 0),
    mapped_count                BIGINT NOT NULL DEFAULT 0 CHECK (mapped_count >= 0),
    population_count            BIGINT NOT NULL DEFAULT 0 CHECK (population_count >= 0),
    precision_counts            JSONB NOT NULL DEFAULT '{}'::jsonb
                                CHECK (jsonb_typeof(precision_counts) = 'object'),
    map_data_version            TEXT,
    map_ready_at                TIMESTAMP WITH TIME ZONE,
    failure_code                VARCHAR(64)
                                CHECK (failure_code IS NULL OR failure_code ~ '^[A-Z0-9_]+$'),
    CONSTRAINT ck_refresh_run_time CHECK (
        (status = 'RUNNING' AND finished_at IS NULL)
        OR (status <> 'RUNNING' AND finished_at IS NOT NULL AND finished_at >= started_at)
    ),
    CONSTRAINT ck_refresh_run_terminal CHECK (
        (status = 'RUNNING' AND failure_code IS NULL AND stage <> 'COMPLETED')
        OR (status = 'FAILED' AND failure_code IS NOT NULL AND stage <> 'COMPLETED')
        OR (status = 'SUCCEEDED' AND failure_code IS NULL AND stage = 'COMPLETED'
            AND source_sync_run_id IS NOT NULL
            AND enrichment_run_id IS NOT NULL
            AND map_resolution_run_id IS NOT NULL
            AND map_data_version IS NOT NULL
            AND map_ready_at IS NOT NULL)
    ),
    CONSTRAINT ck_refresh_run_versions CHECK (
        (parser_version IS NULL AND resolver_version IS NULL AND dataset_version IS NULL)
        OR (parser_version IS NOT NULL AND resolver_version IS NOT NULL AND dataset_version IS NOT NULL)
    ),
    CONSTRAINT ck_refresh_run_counts CHECK (
        listings_processed <= listings_total
        AND details_processed <= details_total
        AND locations_processed <= locations_total
        AND mapped_count <= population_count
    )
);

CREATE UNIQUE INDEX uq_refresh_runs_single_running
    ON refresh_runs ((TRUE)) WHERE status = 'RUNNING';
CREATE INDEX idx_refresh_runs_started
    ON refresh_runs (started_at DESC, id DESC);
CREATE INDEX idx_refresh_runs_success
    ON refresh_runs (finished_at DESC, id DESC) WHERE status = 'SUCCEEDED';

ALTER TABLE coarse_location_resolution_runs
    ADD COLUMN refresh_run_id UUID REFERENCES refresh_runs(id) ON DELETE RESTRICT;

CREATE UNIQUE INDEX uq_coarse_location_refresh_run
    ON coarse_location_resolution_runs (refresh_run_id)
    WHERE refresh_run_id IS NOT NULL;

CREATE FUNCTION guard_refresh_run_evidence()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'refresh run evidence cannot be deleted';
    END IF;
    IF OLD.status <> 'RUNNING' THEN
        RAISE EXCEPTION 'terminal refresh run evidence is immutable';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.idempotency_key_sha256 IS DISTINCT FROM OLD.idempotency_key_sha256
       OR NEW.trigger_kind IS DISTINCT FROM OLD.trigger_kind
       OR NEW.started_at IS DISTINCT FROM OLD.started_at THEN
        RAISE EXCEPTION 'refresh run identity is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_refresh_runs_terminal_immutable
BEFORE UPDATE OR DELETE ON refresh_runs
FOR EACH ROW EXECUTE FUNCTION guard_refresh_run_evidence();
