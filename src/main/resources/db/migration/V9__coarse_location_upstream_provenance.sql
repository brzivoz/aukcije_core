-- Issue #38 hardening: retain the exact #37/#39 inputs behind every new
-- coarse-location population report. Existing V8 reports predate this
-- contract and remain identifiable by an all-NULL provenance tuple.

ALTER TABLE coarse_location_resolution_runs
    ADD COLUMN dictionary_version TEXT,
    ADD COLUMN dictionary_source_sha256 CHAR(64),
    ADD COLUMN normalizer_version TEXT,
    ADD COLUMN alias_dataset_version TEXT,
    ADD COLUMN alias_sha256 CHAR(64),
    ADD COLUMN municipality_alias_dataset_version TEXT,
    ADD COLUMN municipality_alias_sha256 CHAR(64),
    ADD CONSTRAINT ck_coarse_location_run_upstream_provenance CHECK (
        (
            dictionary_version IS NULL
            AND dictionary_source_sha256 IS NULL
            AND normalizer_version IS NULL
            AND alias_dataset_version IS NULL
            AND alias_sha256 IS NULL
            AND municipality_alias_dataset_version IS NULL
            AND municipality_alias_sha256 IS NULL
        )
        OR
        (
            dictionary_version IS NOT NULL AND btrim(dictionary_version) <> ''
            AND dictionary_source_sha256 IS NOT NULL
            AND dictionary_source_sha256 ~ '^[0-9a-f]{64}$'
            AND normalizer_version IS NOT NULL AND btrim(normalizer_version) <> ''
            AND alias_dataset_version IS NOT NULL AND btrim(alias_dataset_version) <> ''
            AND alias_sha256 IS NOT NULL AND alias_sha256 ~ '^[0-9a-f]{64}$'
            AND municipality_alias_dataset_version IS NOT NULL
            AND btrim(municipality_alias_dataset_version) <> ''
            AND municipality_alias_sha256 IS NOT NULL
            AND municipality_alias_sha256 ~ '^[0-9a-f]{64}$'
        )
    );
