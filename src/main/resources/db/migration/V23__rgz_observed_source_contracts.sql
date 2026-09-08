-- Owner-directed POC bootstrap: first observed service metadata, NOT a publisher edition.
-- Only sanitized hashes/contract fields survive; raw capabilities/schema XML never does.
CREATE TABLE rgz_observed_source_contracts (
    source_key                 CHAR(64) PRIMARY KEY CHECK (source_key ~ '^[0-9a-f]{64}$'),
    feature_type               TEXT NOT NULL CHECK (feature_type = 'dkp:dkp_parcels_weekly_only_utm'),
    dataset_version            TEXT NOT NULL CHECK (btrim(dataset_version) <> ''),
    dataset_version_policy     TEXT NOT NULL CHECK (dataset_version_policy IN ('PRIVATE_FIRST_OBSERVATION', 'OPERATOR_PINNED')),
    capabilities_sha256        CHAR(64) NOT NULL CHECK (capabilities_sha256 ~ '^[0-9a-f]{64}$'),
    schema_sha256              CHAR(64) NOT NULL CHECK (schema_sha256 ~ '^[0-9a-f]{64}$'),
    wfs_version                TEXT NOT NULL CHECK (wfs_version = '2.0.0'),
    source_crs                 TEXT NOT NULL CHECK (source_crs = 'EPSG:25834'),
    decision_version           TEXT NOT NULL,
    observed_at                TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE FUNCTION reject_rgz_source_contract_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'rgz_observed_source_contracts is append-only';
END;
$$;
CREATE TRIGGER trg_rgz_source_contract_append_only
    BEFORE UPDATE OR DELETE ON rgz_observed_source_contracts
    FOR EACH ROW EXECUTE FUNCTION reject_rgz_source_contract_mutation();
