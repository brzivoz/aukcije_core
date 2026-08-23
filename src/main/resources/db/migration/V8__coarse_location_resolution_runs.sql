-- Issue #38: retained population evidence for deterministic coarse location
-- resolution. Per-auction evidence and history remain in the canonical V7
-- reference/cache/attempt/current-selection model.

CREATE TABLE coarse_location_resolution_runs (
    id                                  UUID PRIMARY KEY,
    started_at                          TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at                         TIMESTAMP WITH TIME ZONE NOT NULL,
    resolver_version                    TEXT NOT NULL CHECK (btrim(resolver_version) <> ''),
    extract_version                     TEXT NOT NULL CHECK (btrim(extract_version) <> ''),
    extract_source_sha256               CHAR(64) NOT NULL
                                        CHECK (extract_source_sha256 ~ '^[0-9a-f]{64}$'),
    population_count                    BIGINT NOT NULL CHECK (population_count >= 0),
    processed_count                     BIGINT NOT NULL CHECK (processed_count >= 0),
    unchanged_count                     BIGINT NOT NULL CHECK (unchanged_count >= 0),
    cadastral_municipality_count        BIGINT NOT NULL CHECK (cadastral_municipality_count >= 0),
    settlement_count                    BIGINT NOT NULL CHECK (settlement_count >= 0),
    municipality_count                  BIGINT NOT NULL CHECK (municipality_count >= 0),
    none_count                          BIGINT NOT NULL CHECK (none_count >= 0),
    municipality_alias_ko_count         BIGINT NOT NULL CHECK (municipality_alias_ko_count >= 0),
    structured_ko_status_counts         JSONB NOT NULL
                                        CHECK (jsonb_typeof(structured_ko_status_counts) = 'object'),
    rationale_counts                    JSONB NOT NULL
                                        CHECK (jsonb_typeof(rationale_counts) = 'object'),
    CONSTRAINT ck_coarse_location_run_time CHECK (finished_at >= started_at),
    CONSTRAINT ck_coarse_location_run_processing CHECK (
        population_count = processed_count + unchanged_count
    ),
    CONSTRAINT ck_coarse_location_run_tiers CHECK (
        population_count = cadastral_municipality_count + settlement_count
                           + municipality_count + none_count
    ),
    CONSTRAINT ck_coarse_location_alias_count CHECK (
        municipality_alias_ko_count <= cadastral_municipality_count
    )
);

CREATE INDEX idx_coarse_location_resolution_runs_finished
    ON coarse_location_resolution_runs (finished_at DESC);
