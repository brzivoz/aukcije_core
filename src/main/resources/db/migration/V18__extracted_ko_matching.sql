-- Issue #33: immutable, versioned matching of current extracted property-reference
-- KO names, with explicit reconciliation against the current structured #37 result.

ALTER TABLE property_references
    ADD CONSTRAINT uq_property_reference_id_auction UNIQUE (id, auction_id);

CREATE TABLE property_reference_ko_match_results (
    reference_id                       UUID NOT NULL,
    auction_id                         BIGINT NOT NULL,
    input_fingerprint                  CHAR(64) NOT NULL
                                       CHECK (input_fingerprint ~ '^[0-9a-f]{64}$'),
    source_raw_ko                      TEXT,
    source_normalized_ko               TEXT,
    query_normalized_ko                TEXT,
    source_place_name                  TEXT,
    source_municipality                TEXT,
    ko_provenance                      VARCHAR(24) NOT NULL
                                       CHECK (ko_provenance IN (
                                           'TEXT_EXTRACTED', 'STRUCTURED_FALLBACK', 'UNRESOLVED'
                                       )),
    status                             VARCHAR(16) NOT NULL
                                       CHECK (status IN ('MATCHED', 'AMBIGUOUS', 'NOT_FOUND', 'INVALID')),
    method                             VARCHAR(32) NOT NULL
                                       CHECK (method IN (
                                           'EXACT_CODE', 'EXACT_NORMALIZED_NAME', 'REVIEWED_ALIAS',
                                           'MUNICIPALITY_CONTEXT', 'FUZZY_REVIEW',
                                           'STRUCTURED_CONFLICT', 'NONE'
                                       )),
    rationale                          TEXT NOT NULL CHECK (btrim(rationale) <> ''),
    matched_ko_code                    TEXT,
    text_status                        VARCHAR(16) NOT NULL
                                       CHECK (text_status IN ('MATCHED', 'AMBIGUOUS', 'NOT_FOUND', 'INVALID')),
    text_method                        VARCHAR(32) NOT NULL
                                       CHECK (text_method IN (
                                           'EXACT_CODE', 'EXACT_NORMALIZED_NAME', 'REVIEWED_ALIAS',
                                           'MUNICIPALITY_CONTEXT', 'FUZZY_REVIEW', 'NONE'
                                       )),
    text_matched_ko_code               TEXT,
    reconciliation_status              VARCHAR(24) NOT NULL
                                       CHECK (reconciliation_status IN (
                                           'AGREES', 'CONFLICT', 'TEXT_ONLY',
                                           'STRUCTURED_ONLY', 'BOTH_UNRESOLVED'
                                       )),
    structured_match_input_fingerprint CHAR(64) NOT NULL
                                       CHECK (structured_match_input_fingerprint ~ '^[0-9a-f]{64}$'),
    structured_status                  VARCHAR(16) NOT NULL
                                       CHECK (structured_status IN (
                                           'MATCHED', 'AMBIGUOUS', 'NOT_FOUND', 'INVALID'
                                       )),
    structured_method                  VARCHAR(32) NOT NULL
                                       CHECK (structured_method IN (
                                           'EXACT_CODE', 'EXACT_NORMALIZED_NAME', 'REVIEWED_ALIAS',
                                           'MUNICIPALITY_CONTEXT', 'FUZZY_REVIEW', 'NONE'
                                       )),
    structured_matched_ko_code         TEXT,
    dictionary_version                 TEXT NOT NULL CHECK (btrim(dictionary_version) <> ''),
    dictionary_source_sha256           CHAR(64) NOT NULL
                                       CHECK (dictionary_source_sha256 ~ '^[0-9a-f]{64}$'),
    normalizer_version                 TEXT NOT NULL CHECK (btrim(normalizer_version) <> ''),
    alias_dataset_version              TEXT NOT NULL CHECK (btrim(alias_dataset_version) <> ''),
    alias_sha256                       CHAR(64) NOT NULL CHECK (alias_sha256 ~ '^[0-9a-f]{64}$'),
    municipality_alias_dataset_version TEXT NOT NULL
                                       CHECK (btrim(municipality_alias_dataset_version) <> ''),
    municipality_alias_sha256          CHAR(64) NOT NULL
                                       CHECK (municipality_alias_sha256 ~ '^[0-9a-f]{64}$'),
    candidates                         JSONB NOT NULL CHECK (jsonb_typeof(candidates) = 'array'),
    reconciliation_evidence            JSONB NOT NULL
                                       CHECK (jsonb_typeof(reconciliation_evidence) = 'object'),
    resolved_at                        TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (reference_id, input_fingerprint),
    UNIQUE (reference_id, auction_id, input_fingerprint),
    FOREIGN KEY (reference_id, auction_id)
        REFERENCES property_references (id, auction_id) ON DELETE RESTRICT,
    CONSTRAINT ck_property_reference_ko_final_result CHECK (
        (status = 'MATCHED' AND matched_ko_code IS NOT NULL
            AND jsonb_array_length(candidates) >= 1
            AND method IN (
                'EXACT_CODE', 'EXACT_NORMALIZED_NAME', 'REVIEWED_ALIAS', 'MUNICIPALITY_CONTEXT'
            ))
        OR (status = 'AMBIGUOUS' AND matched_ko_code IS NULL AND (
            (method = 'STRUCTURED_CONFLICT' AND jsonb_array_length(candidates) >= 1)
            OR (method IN ('EXACT_NORMALIZED_NAME', 'MUNICIPALITY_CONTEXT')
                AND jsonb_array_length(candidates) >= 2)
        ))
        OR (status = 'NOT_FOUND' AND matched_ko_code IS NULL
            AND method IN ('FUZZY_REVIEW', 'NONE'))
        OR (status = 'INVALID' AND matched_ko_code IS NULL
            AND method = 'NONE' AND jsonb_array_length(candidates) = 0)
    ),
    CONSTRAINT ck_property_reference_ko_text_result CHECK (
        (text_status = 'MATCHED' AND text_matched_ko_code IS NOT NULL
            AND text_method IN (
                'EXACT_CODE', 'EXACT_NORMALIZED_NAME', 'REVIEWED_ALIAS', 'MUNICIPALITY_CONTEXT'
            ))
        OR (text_status = 'AMBIGUOUS' AND text_matched_ko_code IS NULL
            AND text_method IN ('EXACT_NORMALIZED_NAME', 'MUNICIPALITY_CONTEXT'))
        OR (text_status = 'NOT_FOUND' AND text_matched_ko_code IS NULL
            AND text_method IN ('FUZZY_REVIEW', 'NONE'))
        OR (text_status = 'INVALID' AND text_matched_ko_code IS NULL AND text_method = 'NONE')
    ),
    CONSTRAINT ck_property_reference_ko_structured_result CHECK (
        (structured_status = 'MATCHED' AND structured_matched_ko_code IS NOT NULL
            AND structured_method IN (
                'EXACT_CODE', 'EXACT_NORMALIZED_NAME', 'REVIEWED_ALIAS', 'MUNICIPALITY_CONTEXT'
            ))
        OR (structured_status = 'AMBIGUOUS' AND structured_matched_ko_code IS NULL
            AND structured_method IN ('EXACT_NORMALIZED_NAME', 'MUNICIPALITY_CONTEXT'))
        OR (structured_status = 'NOT_FOUND' AND structured_matched_ko_code IS NULL
            AND structured_method IN ('FUZZY_REVIEW', 'NONE'))
        OR (structured_status = 'INVALID' AND structured_matched_ko_code IS NULL
            AND structured_method = 'NONE')
    ),
    CONSTRAINT ck_property_reference_ko_reconciliation CHECK (
        (reconciliation_status = 'AGREES'
            AND status = 'MATCHED'
            AND matched_ko_code = text_matched_ko_code
            AND matched_ko_code = structured_matched_ko_code)
        OR (reconciliation_status = 'CONFLICT'
            AND status = 'AMBIGUOUS' AND method = 'STRUCTURED_CONFLICT'
            AND matched_ko_code IS NULL
            AND text_matched_ko_code IS NOT NULL
            AND structured_matched_ko_code IS NOT NULL
            AND text_matched_ko_code <> structured_matched_ko_code)
        OR (reconciliation_status = 'TEXT_ONLY'
            AND status = 'MATCHED' AND matched_ko_code = text_matched_ko_code
            AND structured_matched_ko_code IS NULL)
        OR (reconciliation_status = 'STRUCTURED_ONLY'
            AND status <> 'MATCHED' AND matched_ko_code IS NULL
            AND text_matched_ko_code IS NULL AND structured_matched_ko_code IS NOT NULL)
        OR (reconciliation_status = 'BOTH_UNRESOLVED'
            AND matched_ko_code IS NULL
            AND text_matched_ko_code IS NULL AND structured_matched_ko_code IS NULL)
    )
);

CREATE INDEX idx_property_reference_ko_results_status
    ON property_reference_ko_match_results (status);
CREATE INDEX idx_property_reference_ko_results_code
    ON property_reference_ko_match_results (matched_ko_code)
    WHERE matched_ko_code IS NOT NULL;
CREATE INDEX idx_property_reference_ko_results_dictionary
    ON property_reference_ko_match_results (dictionary_version);
CREATE INDEX idx_property_reference_ko_results_candidates
    ON property_reference_ko_match_results USING GIN (candidates);
CREATE INDEX idx_property_reference_ko_results_reconciliation
    ON property_reference_ko_match_results (reconciliation_status);

CREATE TABLE current_property_reference_ko_matches (
    reference_id       UUID PRIMARY KEY,
    auction_id         BIGINT NOT NULL,
    input_fingerprint  CHAR(64) NOT NULL CHECK (input_fingerprint ~ '^[0-9a-f]{64}$'),
    selected_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (reference_id, auction_id, input_fingerprint)
        REFERENCES property_reference_ko_match_results (
            reference_id, auction_id, input_fingerprint
        ) ON DELETE RESTRICT,
    FOREIGN KEY (reference_id, auction_id)
        REFERENCES property_references (id, auction_id) ON DELETE RESTRICT
);

CREATE TABLE extracted_ko_match_runs (
    id                                   UUID PRIMARY KEY,
    started_at                           TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at                          TIMESTAMP WITH TIME ZONE NOT NULL,
    matcher_version                      TEXT NOT NULL CHECK (btrim(matcher_version) <> ''),
    dictionary_version                   TEXT NOT NULL CHECK (btrim(dictionary_version) <> ''),
    dictionary_source_sha256             CHAR(64) NOT NULL
                                         CHECK (dictionary_source_sha256 ~ '^[0-9a-f]{64}$'),
    normalizer_version                   TEXT NOT NULL CHECK (btrim(normalizer_version) <> ''),
    alias_dataset_version                TEXT NOT NULL CHECK (btrim(alias_dataset_version) <> ''),
    alias_sha256                         CHAR(64) NOT NULL CHECK (alias_sha256 ~ '^[0-9a-f]{64}$'),
    municipality_alias_dataset_version   TEXT NOT NULL
                                         CHECK (btrim(municipality_alias_dataset_version) <> ''),
    municipality_alias_sha256            CHAR(64) NOT NULL
                                         CHECK (municipality_alias_sha256 ~ '^[0-9a-f]{64}$'),
    population_count                     BIGINT NOT NULL CHECK (population_count >= 0),
    processed_count                      BIGINT NOT NULL CHECK (processed_count >= 0),
    unchanged_count                      BIGINT NOT NULL CHECK (unchanged_count >= 0),
    matched_count                        BIGINT NOT NULL CHECK (matched_count >= 0),
    ambiguous_count                      BIGINT NOT NULL CHECK (ambiguous_count >= 0),
    not_found_count                      BIGINT NOT NULL CHECK (not_found_count >= 0),
    invalid_count                        BIGINT NOT NULL CHECK (invalid_count >= 0),
    conflict_count                       BIGINT NOT NULL CHECK (conflict_count >= 0),
    text_extracted_count                 BIGINT NOT NULL CHECK (text_extracted_count >= 0),
    structured_fallback_count            BIGINT NOT NULL CHECK (structured_fallback_count >= 0),
    unresolved_ko_provenance_count       BIGINT NOT NULL CHECK (unresolved_ko_provenance_count >= 0),
    text_extracted_matched_count         BIGINT NOT NULL CHECK (text_extracted_matched_count >= 0),
    structured_fallback_matched_count    BIGINT NOT NULL CHECK (structured_fallback_matched_count >= 0),
    method_counts                        JSONB NOT NULL CHECK (jsonb_typeof(method_counts) = 'object'),
    reconciliation_counts                JSONB NOT NULL CHECK (jsonb_typeof(reconciliation_counts) = 'object'),
    reconciliation_by_ko_provenance      JSONB NOT NULL
                                         CHECK (jsonb_typeof(reconciliation_by_ko_provenance) = 'object'),
    CONSTRAINT ck_extracted_ko_run_time CHECK (finished_at >= started_at),
    CONSTRAINT ck_extracted_ko_run_accounting CHECK (
        population_count = processed_count + unchanged_count
        AND population_count = matched_count + ambiguous_count + not_found_count + invalid_count
        AND population_count = text_extracted_count + structured_fallback_count
                             + unresolved_ko_provenance_count
        AND matched_count = text_extracted_matched_count + structured_fallback_matched_count
        AND text_extracted_matched_count <= text_extracted_count
        AND structured_fallback_matched_count <= structured_fallback_count
        AND conflict_count <= ambiguous_count
    )
);

CREATE INDEX idx_extracted_ko_match_runs_finished
    ON extracted_ko_match_runs (finished_at DESC);

CREATE TABLE extracted_ko_match_run_results (
    run_id             UUID NOT NULL REFERENCES extracted_ko_match_runs(id) ON DELETE RESTRICT,
    ordinal            INTEGER NOT NULL CHECK (ordinal > 0),
    reference_id       UUID NOT NULL,
    auction_id         BIGINT NOT NULL,
    input_fingerprint  CHAR(64) NOT NULL CHECK (input_fingerprint ~ '^[0-9a-f]{64}$'),
    processed          BOOLEAN NOT NULL,
    PRIMARY KEY (run_id, reference_id),
    UNIQUE (run_id, ordinal),
    FOREIGN KEY (reference_id, auction_id, input_fingerprint)
        REFERENCES property_reference_ko_match_results (
            reference_id, auction_id, input_fingerprint
        ) ON DELETE RESTRICT
);

CREATE TABLE property_reference_ko_match_observations (
    enrichment_run_id  UUID NOT NULL,
    auction_id         BIGINT NOT NULL,
    reference_id       UUID NOT NULL,
    input_fingerprint  CHAR(64) NOT NULL CHECK (input_fingerprint ~ '^[0-9a-f]{64}$'),
    observed_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (enrichment_run_id, reference_id),
    FOREIGN KEY (enrichment_run_id, auction_id)
        REFERENCES enrichment_run_items (run_id, auction_id) ON DELETE RESTRICT,
    FOREIGN KEY (reference_id, auction_id, input_fingerprint)
        REFERENCES property_reference_ko_match_results (
            reference_id, auction_id, input_fingerprint
        ) ON DELETE RESTRICT
);

CREATE INDEX idx_property_reference_ko_observations_result
    ON property_reference_ko_match_observations (reference_id, input_fingerprint);

CREATE FUNCTION reject_extracted_ko_match_evidence_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'extracted KO match evidence is immutable';
END;
$$;

CREATE TRIGGER trg_property_reference_ko_results_immutable
BEFORE UPDATE OR DELETE ON property_reference_ko_match_results
FOR EACH ROW EXECUTE FUNCTION reject_extracted_ko_match_evidence_mutation();

CREATE TRIGGER trg_extracted_ko_match_runs_immutable
BEFORE UPDATE OR DELETE ON extracted_ko_match_runs
FOR EACH ROW EXECUTE FUNCTION reject_extracted_ko_match_evidence_mutation();

CREATE TRIGGER trg_extracted_ko_match_run_results_immutable
BEFORE UPDATE OR DELETE ON extracted_ko_match_run_results
FOR EACH ROW EXECUTE FUNCTION reject_extracted_ko_match_evidence_mutation();

CREATE TRIGGER trg_property_reference_ko_observations_immutable
BEFORE UPDATE OR DELETE ON property_reference_ko_match_observations
FOR EACH ROW EXECUTE FUNCTION reject_extracted_ko_match_evidence_mutation();
