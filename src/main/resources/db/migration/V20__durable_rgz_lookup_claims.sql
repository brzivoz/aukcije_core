-- Issue #21 correctness hardening: an outbound logical lookup is claimed in a
-- committed transaction before network I/O. The claim prevents retries in the
-- same enrichment run even if later cache/attempt persistence rolls back.
CREATE TABLE rgz_enrichment_run_lookup_claims (
    enrichment_run_id       UUID NOT NULL REFERENCES enrichment_runs(id) ON DELETE RESTRICT,
    input_fingerprint       CHAR(64) NOT NULL
        CHECK (input_fingerprint ~ '^[0-9a-f]{64}$'),
    source_dataset_version  TEXT NOT NULL
        CHECK (char_length(source_dataset_version) BETWEEN 1 AND 512),
    claimed_at              TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (enrichment_run_id, input_fingerprint)
);

CREATE INDEX idx_rgz_lookup_claims_fingerprint
    ON rgz_enrichment_run_lookup_claims (input_fingerprint, source_dataset_version);
