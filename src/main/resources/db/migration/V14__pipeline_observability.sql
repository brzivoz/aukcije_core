-- Issue #30: make every operator-visible run/import metric durable and protect
-- the remaining terminal job evidence with the same append-only contract used
-- by sync and enrichment runs.

CREATE INDEX idx_sync_run_errors_code
    ON sync_run_errors (run_id, error_code, resolved);

CREATE INDEX idx_enrichment_state_error_class
    ON enrichment_state (error_class)
    WHERE error_class IS NOT NULL;

CREATE INDEX idx_property_references_parser_status
    ON property_references (parser_version, extraction_status);

-- Promotion is terminal before post-commit retention starts. New code leaves
-- this V4 compatibility column null and records the real duration below.
COMMENT ON COLUMN address_registry_import_runs.retention_millis IS
    'Legacy compatibility column; post-commit duration is in address_registry_retention_jobs';

-- Retention happens after snapshot promotion commits. Keep that post-commit
-- job in its own append-only row instead of reopening a terminal import run.
CREATE TABLE address_registry_retention_jobs (
    import_run_id              UUID PRIMARY KEY REFERENCES address_registry_import_runs(id),
    started_at                 TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at                TIMESTAMP WITH TIME ZONE NOT NULL,
    outcome                    VARCHAR(16) NOT NULL CHECK (outcome IN ('SUCCEEDED', 'FAILED')),
    retained_snapshot_count    INTEGER CHECK (retained_snapshot_count IS NULL OR retained_snapshot_count > 0),
    duration_millis            BIGINT NOT NULL CHECK (duration_millis >= 0),
    error_code                 VARCHAR(64) CHECK (error_code IS NULL OR error_code ~ '^[A-Z0-9_]+$'),
    CONSTRAINT ck_address_registry_retention_outcome CHECK (
        (outcome = 'SUCCEEDED' AND retained_snapshot_count IS NOT NULL AND error_code IS NULL)
        OR (outcome = 'FAILED' AND retained_snapshot_count IS NULL AND error_code IS NOT NULL)
    ),
    CONSTRAINT ck_address_registry_retention_time CHECK (finished_at >= started_at)
);

CREATE FUNCTION guard_address_registry_import_run_evidence()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'address registry import run evidence cannot be deleted';
    END IF;
    IF OLD.outcome <> 'RUNNING' THEN
        RAISE EXCEPTION 'terminal address registry import run evidence is immutable';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.action IS DISTINCT FROM OLD.action
       OR NEW.started_at IS DISTINCT FROM OLD.started_at THEN
        RAISE EXCEPTION 'address registry import run identity is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_address_registry_import_runs_terminal_immutable
BEFORE UPDATE OR DELETE ON address_registry_import_runs
FOR EACH ROW EXECUTE FUNCTION guard_address_registry_import_run_evidence();

CREATE FUNCTION reject_completed_pipeline_run_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'completed pipeline run evidence is immutable';
END;
$$;

CREATE TRIGGER trg_structured_ko_match_runs_immutable
BEFORE UPDATE OR DELETE ON structured_ko_match_runs
FOR EACH ROW EXECUTE FUNCTION reject_completed_pipeline_run_mutation();

CREATE TRIGGER trg_coarse_location_resolution_runs_immutable
BEFORE UPDATE OR DELETE ON coarse_location_resolution_runs
FOR EACH ROW EXECUTE FUNCTION reject_completed_pipeline_run_mutation();

CREATE TRIGGER trg_address_registry_retention_jobs_immutable
BEFORE UPDATE OR DELETE ON address_registry_retention_jobs
FOR EACH ROW EXECUTE FUNCTION reject_completed_pipeline_run_mutation();

-- A stable query boundary for sync source/delta/duration metrics. Raw snapshot
-- new/changed/unchanged counts remain calculated from immutable successful-run
-- observations because failed/partial runs intentionally publish no snapshot.
CREATE VIEW pipeline_sync_run_metrics AS
SELECT run.id,
       run.trigger_kind,
       run.status,
       run.stage,
       run.started_at,
       run.finished_at,
       CASE WHEN run.finished_at IS NULL THEN NULL
            ELSE GREATEST(0, FLOOR(EXTRACT(EPOCH FROM (run.finished_at - run.started_at)) * 1000))::bigint
       END AS duration_millis,
       run.unique_auction_count AS source_count,
       run.listing_rows_observed,
       run.listing_rows_quarantined,
       run.duplicate_auction_count,
       run.details_succeeded,
       run.details_quarantined,
       run.retry_count,
       run.error_count,
       run.unresolved_error_count,
       previous.source_count AS previous_successful_source_count,
       CASE WHEN run.status <> 'SUCCEEDED' OR previous.source_count IS NULL THEN NULL
            ELSE run.unique_auction_count - previous.source_count
       END AS source_delta
  FROM sync_runs run
  LEFT JOIN LATERAL (
      SELECT prior.unique_auction_count AS source_count
        FROM sync_runs prior
       WHERE prior.status = 'SUCCEEDED'
         AND (prior.started_at, prior.id) < (run.started_at, run.id)
       ORDER BY prior.started_at DESC, prior.id DESC
       LIMIT 1
  ) previous ON TRUE;
