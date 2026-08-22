-- Issue #37: deterministic, auditable matching of the structured
-- auctions.cadastral field against an immutable #14 KO dictionary version.

CREATE TABLE auction_structured_ko_matches (
    auction_id                   BIGINT PRIMARY KEY REFERENCES auctions(id) ON DELETE CASCADE,
    source_cadastral             TEXT,
    source_place_name            TEXT,
    source_municipality          TEXT,
    input_fingerprint            CHAR(64) NOT NULL CHECK (input_fingerprint ~ '^[0-9a-f]{64}$'),
    status                       VARCHAR(16) NOT NULL
                                 CHECK (status IN ('MATCHED', 'AMBIGUOUS', 'NOT_FOUND', 'INVALID')),
    method                       VARCHAR(32) NOT NULL
                                 CHECK (method IN (
                                     'EXACT_CODE',
                                     'EXACT_NORMALIZED_NAME',
                                     'REVIEWED_ALIAS',
                                     'MUNICIPALITY_CONTEXT',
                                     'FUZZY_REVIEW',
                                     'NONE'
                                 )),
    rationale                    TEXT NOT NULL CHECK (btrim(rationale) <> ''),
    matched_ko_code              TEXT,
    dictionary_version           TEXT NOT NULL CHECK (btrim(dictionary_version) <> ''),
    dictionary_source_sha256     CHAR(64) NOT NULL
                                 CHECK (dictionary_source_sha256 ~ '^[0-9a-f]{64}$'),
    normalizer_version           TEXT NOT NULL CHECK (btrim(normalizer_version) <> ''),
    alias_dataset_version        TEXT NOT NULL CHECK (btrim(alias_dataset_version) <> ''),
    alias_sha256                 CHAR(64) NOT NULL CHECK (alias_sha256 ~ '^[0-9a-f]{64}$'),
    candidates                   JSONB NOT NULL CHECK (jsonb_typeof(candidates) = 'array'),
    resolved_at                  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_structured_ko_match_resolution CHECK (
        (status = 'MATCHED' AND matched_ko_code IS NOT NULL AND jsonb_array_length(candidates) >= 1)
        OR (status = 'AMBIGUOUS' AND matched_ko_code IS NULL AND jsonb_array_length(candidates) >= 2)
        OR (status = 'NOT_FOUND' AND matched_ko_code IS NULL)
        OR (status = 'INVALID' AND matched_ko_code IS NULL AND jsonb_array_length(candidates) = 0)
    ),
    CONSTRAINT ck_structured_ko_match_method CHECK (
        (status = 'MATCHED' AND method IN (
            'EXACT_CODE', 'EXACT_NORMALIZED_NAME', 'REVIEWED_ALIAS', 'MUNICIPALITY_CONTEXT'))
        OR (status = 'AMBIGUOUS' AND method IN ('EXACT_NORMALIZED_NAME', 'MUNICIPALITY_CONTEXT'))
        OR (status = 'NOT_FOUND' AND method IN ('FUZZY_REVIEW', 'NONE'))
        OR (status = 'INVALID' AND method = 'NONE')
    )
);

CREATE INDEX idx_structured_ko_matches_status
    ON auction_structured_ko_matches (status);
CREATE INDEX idx_structured_ko_matches_ko_code
    ON auction_structured_ko_matches (matched_ko_code)
    WHERE matched_ko_code IS NOT NULL;
CREATE INDEX idx_structured_ko_matches_dictionary
    ON auction_structured_ko_matches (dictionary_version);
CREATE INDEX idx_structured_ko_matches_candidates
    ON auction_structured_ko_matches USING GIN (candidates);

CREATE TABLE structured_ko_match_runs (
    id                            UUID PRIMARY KEY,
    started_at                    TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at                   TIMESTAMP WITH TIME ZONE NOT NULL,
    dictionary_version            TEXT NOT NULL CHECK (btrim(dictionary_version) <> ''),
    dictionary_source_sha256      CHAR(64) NOT NULL
                                  CHECK (dictionary_source_sha256 ~ '^[0-9a-f]{64}$'),
    normalizer_version            TEXT NOT NULL CHECK (btrim(normalizer_version) <> ''),
    alias_dataset_version         TEXT NOT NULL CHECK (btrim(alias_dataset_version) <> ''),
    alias_sha256                  CHAR(64) NOT NULL CHECK (alias_sha256 ~ '^[0-9a-f]{64}$'),
    population_count              BIGINT NOT NULL CHECK (population_count >= 0),
    processed_count               BIGINT NOT NULL CHECK (processed_count >= 0),
    unchanged_count               BIGINT NOT NULL CHECK (unchanged_count >= 0),
    matched_count                 BIGINT NOT NULL CHECK (matched_count >= 0),
    ambiguous_count               BIGINT NOT NULL CHECK (ambiguous_count >= 0),
    not_found_count               BIGINT NOT NULL CHECK (not_found_count >= 0),
    invalid_count                 BIGINT NOT NULL CHECK (invalid_count >= 0),
    method_counts                 JSONB NOT NULL CHECK (jsonb_typeof(method_counts) = 'object'),
    CONSTRAINT ck_structured_ko_run_time CHECK (finished_at >= started_at),
    CONSTRAINT ck_structured_ko_run_accounting CHECK (
        population_count = processed_count + unchanged_count
        AND population_count = matched_count + ambiguous_count + not_found_count + invalid_count
    )
);

CREATE INDEX idx_structured_ko_match_runs_finished
    ON structured_ko_match_runs (finished_at DESC);
