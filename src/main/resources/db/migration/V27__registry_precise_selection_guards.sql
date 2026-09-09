-- Exact registry resolution is tied to the same current KO premise as RGZ.
-- Keep the existing function/trigger names so all entry points (including
-- standalone #33, in-flight responses and deletion) retain one guard contract.
CREATE OR REPLACE FUNCTION guard_current_rgz_parcel_selection()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    precise_attempt location_resolution_attempts%ROWTYPE;
    current_fingerprint CHAR(64);
BEGIN
    SELECT * INTO precise_attempt FROM location_resolution_attempts WHERE id = NEW.resolution_attempt_id;
    IF precise_attempt.resolver NOT IN ('RGZ_WFS_PARCEL', 'OFFICIAL_ADDRESS_REGISTRY') THEN
        RETURN NEW;
    END IF;
    SELECT current_match.input_fingerprint INTO current_fingerprint
      FROM current_property_reference_ko_matches current_match
      JOIN property_reference_ko_match_results result
        ON result.reference_id = current_match.reference_id
       AND result.input_fingerprint = current_match.input_fingerprint
     WHERE current_match.reference_id = NEW.property_reference_id
       AND result.status = 'MATCHED' AND result.reconciliation_status <> 'STRUCTURED_ONLY'
       AND EXISTS (
           SELECT 1 FROM property_reference_extraction_memberships membership
           JOIN current_property_reference_extractions extraction
             ON extraction.auction_id = membership.auction_id AND extraction.extraction_run_id = membership.extraction_run_id
           WHERE membership.reference_id = NEW.property_reference_id
       ) FOR SHARE OF current_match;
    IF current_fingerprint IS NULL
       OR current_fingerprint IS DISTINCT FROM precise_attempt.upstream_ko_match_input_fingerprint THEN
        RETURN NULL;
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION invalidate_stale_rgz_parcel_selection()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    DELETE FROM current_location_resolutions selection
     USING location_resolution_attempts attempt, property_reference_ko_match_results match_result
     WHERE selection.property_reference_id = NEW.reference_id
       AND attempt.id = selection.resolution_attempt_id
       AND attempt.resolver IN ('RGZ_WFS_PARCEL', 'OFFICIAL_ADDRESS_REGISTRY')
       AND match_result.reference_id = NEW.reference_id AND match_result.input_fingerprint = NEW.input_fingerprint
       AND (attempt.upstream_ko_match_input_fingerprint IS DISTINCT FROM NEW.input_fingerprint
            OR match_result.status <> 'MATCHED' OR match_result.reconciliation_status = 'STRUCTURED_ONLY');
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION invalidate_deleted_rgz_ko_selection()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    DELETE FROM current_location_resolutions selection USING location_resolution_attempts attempt
     WHERE selection.property_reference_id = OLD.reference_id AND attempt.id = selection.resolution_attempt_id
       AND attempt.resolver IN ('RGZ_WFS_PARCEL', 'OFFICIAL_ADDRESS_REGISTRY');
    RETURN OLD;
END;
$$;

-- Bounded lookups use the same name normalization as the importer, plus the
-- already matched official KO. Preserve the importer's ID-based indexes too.
CREATE INDEX idx_registry_ko_normalized_address ON address_registry_points (
    snapshot_id, ko_id, municipality_name_normalized, settlement_name_normalized,
    street_name_normalized, house_number_normalized
) WHERE street_name_normalized IS NOT NULL;

CREATE INDEX idx_location_refinement_latest ON location_resolution_attempts (
    property_reference_id, resolver, completed_at DESC, id DESC
);
