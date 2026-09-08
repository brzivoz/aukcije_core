-- A standalone #33 run can change its premise while a WFS call is in flight.
-- Keep the late result as immutable evidence, but never install a stale pointer.
CREATE FUNCTION guard_current_rgz_parcel_selection()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    parcel_attempt location_resolution_attempts%ROWTYPE;
    current_fingerprint CHAR(64);
BEGIN
    SELECT * INTO parcel_attempt FROM location_resolution_attempts
     WHERE id = NEW.resolution_attempt_id;
    IF parcel_attempt.resolver <> 'RGZ_WFS_PARCEL' THEN
        RETURN NEW;
    END IF;

    SELECT current_match.input_fingerprint INTO current_fingerprint
      FROM current_property_reference_ko_matches current_match
      JOIN property_reference_ko_match_results result
        ON result.reference_id = current_match.reference_id
       AND result.input_fingerprint = current_match.input_fingerprint
     WHERE current_match.reference_id = NEW.property_reference_id
       AND result.status = 'MATCHED'
       AND result.reconciliation_status <> 'STRUCTURED_ONLY'
       AND EXISTS (
           SELECT 1 FROM property_reference_extraction_memberships membership
           JOIN current_property_reference_extractions extraction
             ON extraction.auction_id = membership.auction_id
            AND extraction.extraction_run_id = membership.extraction_run_id
           WHERE membership.reference_id = NEW.property_reference_id
       )
     FOR SHARE OF current_match;
    IF current_fingerprint IS NULL
       OR current_fingerprint IS DISTINCT FROM parcel_attempt.upstream_ko_match_input_fingerprint THEN
        RETURN NULL;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_guard_current_rgz_parcel_selection
    BEFORE INSERT OR UPDATE ON current_location_resolutions
    FOR EACH ROW EXECUTE FUNCTION guard_current_rgz_parcel_selection();

-- Removing a current KO result must also remove the current selection, not its history.
CREATE FUNCTION invalidate_deleted_rgz_ko_selection()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    DELETE FROM current_location_resolutions current_resolution
     USING location_resolution_attempts attempt
     WHERE current_resolution.property_reference_id = OLD.reference_id
       AND attempt.id = current_resolution.resolution_attempt_id
       AND attempt.resolver = 'RGZ_WFS_PARCEL';
    RETURN OLD;
END;
$$;

CREATE TRIGGER trg_deleted_ko_match_invalidates_rgz_parcel
    AFTER DELETE ON current_property_reference_ko_matches
    FOR EACH ROW EXECUTE FUNCTION invalidate_deleted_rgz_ko_selection();

-- Repair any stale pointers left by an earlier in-flight lookup. Evidence is untouched.
DELETE FROM current_location_resolutions selection
 USING location_resolution_attempts attempt
 WHERE attempt.id = selection.resolution_attempt_id
   AND attempt.resolver = 'RGZ_WFS_PARCEL'
   AND NOT EXISTS (
       SELECT 1 FROM current_property_reference_ko_matches current_match
       JOIN property_reference_ko_match_results result
         ON result.reference_id = current_match.reference_id
        AND result.input_fingerprint = current_match.input_fingerprint
       WHERE current_match.reference_id = selection.property_reference_id
         AND current_match.input_fingerprint = attempt.upstream_ko_match_input_fingerprint
         AND result.status = 'MATCHED'
         AND result.reconciliation_status <> 'STRUCTURED_ONLY'
   );
