-- Issue #39: retain the independently hashed reviewed municipality-alias
-- evidence used to disambiguate structured KO names. Historical V5 rows remain
-- nullable because that evidence did not exist and must not be invented.

ALTER TABLE auction_structured_ko_matches
    ADD COLUMN municipality_alias_dataset_version TEXT,
    ADD COLUMN municipality_alias_sha256 CHAR(64),
    ADD CONSTRAINT ck_structured_ko_match_municipality_alias_evidence CHECK (
        (municipality_alias_dataset_version IS NULL AND municipality_alias_sha256 IS NULL)
        OR (
            btrim(municipality_alias_dataset_version) <> ''
            AND municipality_alias_sha256 ~ '^[0-9a-f]{64}$'
        )
    );

ALTER TABLE structured_ko_match_runs
    ADD COLUMN municipality_alias_dataset_version TEXT,
    ADD COLUMN municipality_alias_sha256 CHAR(64),
    ADD CONSTRAINT ck_structured_ko_run_municipality_alias_evidence CHECK (
        (municipality_alias_dataset_version IS NULL AND municipality_alias_sha256 IS NULL)
        OR (
            btrim(municipality_alias_dataset_version) <> ''
            AND municipality_alias_sha256 ~ '^[0-9a-f]{64}$'
        )
    );
