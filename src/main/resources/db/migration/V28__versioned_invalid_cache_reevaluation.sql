-- A deliberately requested validator-recheck epoch is additional evaluation
-- provenance, NOT a new parcel identity or a reason to refetch successful data.
ALTER TABLE location_resolution_cache_records DROP CONSTRAINT uq_location_resolution_cache_input;
CREATE UNIQUE INDEX uq_location_resolution_cache_input ON location_resolution_cache_records (
    resolver, resolver_version, input_fingerprint, source_dataset, source_dataset_version, source_dataset_sha256,
    (COALESCE(candidate_evidence ->> 'invalidResultRecheckVersion', ''))
);

CREATE FUNCTION guard_rgz_cache_recheck() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    previous location_resolution_cache_records%ROWTYPE;
    proposed location_resolution_cache_records%ROWTYPE;
BEGIN
    IF NEW.cache_record_id = OLD.cache_record_id THEN RETURN NEW; END IF;
    SELECT * INTO previous FROM location_resolution_cache_records WHERE id = OLD.cache_record_id;
    SELECT * INTO proposed FROM location_resolution_cache_records WHERE id = NEW.cache_record_id;
    IF previous.resolution_status <> 'INVALID'
       OR COALESCE(proposed.candidate_evidence ->> 'invalidResultRecheckVersion', '') = ''
       OR proposed.candidate_evidence ->> 'invalidResultRecheckVersion'
            IS NOT DISTINCT FROM previous.candidate_evidence ->> 'invalidResultRecheckVersion' THEN
        RAISE EXCEPTION 'only an explicit new INVALID-result evaluation may replace an RGZ cache pointer';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_rgz_cache_recheck BEFORE UPDATE ON rgz_parcel_cache_keys
    FOR EACH ROW EXECUTE FUNCTION guard_rgz_cache_recheck();
