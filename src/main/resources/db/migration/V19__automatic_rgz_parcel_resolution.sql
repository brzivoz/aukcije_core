-- Issue #21: automatic, cache-first RGZ parcel resolution under the #41
-- access decision, tied to the exact current #33 KO match that authorized
-- each lookup.

ALTER TABLE location_resolution_attempts
    ADD COLUMN enrichment_run_id UUID REFERENCES enrichment_runs(id) ON DELETE RESTRICT,
    ADD COLUMN upstream_ko_match_input_fingerprint CHAR(64)
        CHECK (
            upstream_ko_match_input_fingerprint IS NULL
            OR upstream_ko_match_input_fingerprint ~ '^[0-9a-f]{64}$'
        ),
    ADD CONSTRAINT fk_location_attempt_upstream_ko_match
        FOREIGN KEY (property_reference_id, upstream_ko_match_input_fingerprint)
        REFERENCES property_reference_ko_match_results (reference_id, input_fingerprint)
        ON DELETE RESTRICT,
    ADD CONSTRAINT ck_rgz_parcel_attempt_provenance CHECK (
        resolver <> 'RGZ_WFS_PARCEL'
        OR (
            upstream_ko_match_input_fingerprint IS NOT NULL
            AND (
                (resolution_status = 'RESOLVED' AND location_precision = 'PARCEL')
                OR (resolution_status <> 'RESOLVED' AND location_precision = 'NONE')
            )
        )
    );

CREATE INDEX idx_location_attempts_upstream_ko_match
    ON location_resolution_attempts (
        property_reference_id, upstream_ko_match_input_fingerprint
    )
    WHERE upstream_ko_match_input_fingerprint IS NOT NULL;

CREATE UNIQUE INDEX uq_rgz_attempt_reference_cache_match
    ON location_resolution_attempts (
        property_reference_id,
        used_cache_record_id,
        upstream_ko_match_input_fingerprint
    )
    WHERE resolver = 'RGZ_WFS_PARCEL' AND used_cache_record_id IS NOT NULL;

-- The per-run external-lookup ceiling survives process restarts. Cache hits do
-- not consume the limit; one row is claimed transactionally for each miss.
CREATE TABLE rgz_enrichment_run_usage (
    enrichment_run_id          UUID PRIMARY KEY REFERENCES enrichment_runs(id) ON DELETE RESTRICT,
    logical_lookup_count       INTEGER NOT NULL CHECK (logical_lookup_count BETWEEN 1 AND 10000),
    updated_at                 TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- A current parcel selection is only a pointer. If standalone #33 matching
-- changes the current evidence, remove that pointer atomically while retaining
-- all immutable attempt/cache/geometry rows for audit and possible later reuse.
CREATE OR REPLACE FUNCTION invalidate_stale_rgz_parcel_selection()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    DELETE FROM current_location_resolutions current_resolution
     USING location_resolution_attempts attempt,
           property_reference_ko_match_results match_result
     WHERE current_resolution.property_reference_id = NEW.reference_id
       AND attempt.id = current_resolution.resolution_attempt_id
       AND attempt.property_reference_id = current_resolution.property_reference_id
       AND attempt.resolver = 'RGZ_WFS_PARCEL'
       AND match_result.reference_id = NEW.reference_id
       AND match_result.input_fingerprint = NEW.input_fingerprint
       AND (
           attempt.upstream_ko_match_input_fingerprint IS DISTINCT FROM NEW.input_fingerprint
           OR match_result.status <> 'MATCHED'
           OR match_result.reconciliation_status = 'STRUCTURED_ONLY'
       );
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_current_ko_match_invalidates_rgz_parcel
    AFTER INSERT OR UPDATE OF input_fingerprint
    ON current_property_reference_ko_matches
    FOR EACH ROW EXECUTE FUNCTION invalidate_stale_rgz_parcel_selection();
