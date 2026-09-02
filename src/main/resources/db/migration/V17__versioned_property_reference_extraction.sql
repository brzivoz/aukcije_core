-- Issue #19: versioned property-reference extraction and retained deterministic replay evidence.
-- Per-run membership order is authoritative; the V7 row ordinal remains first-seen compatibility data.

ALTER TABLE property_references
    DROP CONSTRAINT property_references_reference_type_check,
    DROP CONSTRAINT uq_property_reference_order;

ALTER TABLE property_references
    ADD CONSTRAINT property_references_reference_type_check CHECK (
        reference_type IN (
            'STRUCTURED_LOCATION', 'PARCEL', 'CADASTRAL_MUNICIPALITY',
            'ADDRESS', 'STREET', 'LAND_REGISTER', 'OTHER'
        )
    ),
    ADD COLUMN source_snapshot_sha256 CHAR(64),
    ADD COLUMN input_snapshot_sha256 CHAR(64),
    ADD CONSTRAINT ck_property_reference_source_snapshot CHECK (
        source_snapshot_sha256 IS NULL
        OR source_snapshot_sha256 ~ '^[0-9a-f]{64}$'
    ),
    ADD CONSTRAINT ck_property_reference_input_snapshot CHECK (
        input_snapshot_sha256 IS NULL
        OR input_snapshot_sha256 ~ '^[0-9a-f]{64}$'
    ),
    ADD CONSTRAINT fk_property_reference_source_snapshot
        FOREIGN KEY (auction_id, source_snapshot_sha256)
        REFERENCES auction_source_snapshots (auction_id, content_sha256)
        ON DELETE RESTRICT,
    ADD CONSTRAINT fk_property_reference_input_snapshot
        FOREIGN KEY (auction_id, input_snapshot_sha256)
        REFERENCES auction_enrichment_input_snapshots (auction_id, snapshot_sha256)
        ON DELETE RESTRICT;

CREATE TABLE property_reference_extraction_runs (
    id                          UUID PRIMARY KEY,
    auction_id                  BIGINT NOT NULL REFERENCES auctions(id) ON DELETE RESTRICT,
    source_sync_run_id          UUID NOT NULL REFERENCES sync_runs(id) ON DELETE RESTRICT,
    source_snapshot_sha256      CHAR(64) NOT NULL
                                CHECK (source_snapshot_sha256 ~ '^[0-9a-f]{64}$'),
    input_snapshot_sha256       CHAR(64) NOT NULL
                                CHECK (input_snapshot_sha256 ~ '^[0-9a-f]{64}$'),
    parser_version              TEXT NOT NULL CHECK (btrim(parser_version) <> ''),
    result_sha256               CHAR(64) NOT NULL
                                CHECK (result_sha256 ~ '^[0-9a-f]{64}$'),
    result_json                 JSONB NOT NULL CHECK (jsonb_typeof(result_json) = 'object'),
    generated_reference_count  INTEGER NOT NULL CHECK (generated_reference_count >= 1),
    selected_reference_count   INTEGER NOT NULL CHECK (selected_reference_count >= 1),
    text_reference_count       INTEGER NOT NULL CHECK (text_reference_count >= 0),
    no_structured_count         INTEGER NOT NULL CHECK (no_structured_count BETWEEN 0 AND 1),
    ko_conflict_count           INTEGER NOT NULL CHECK (ko_conflict_count >= 0),
    quality_corpus_version      TEXT NOT NULL CHECK (btrim(quality_corpus_version) <> ''),
    quality_metrics_sha256      CHAR(64) NOT NULL
                                CHECK (quality_metrics_sha256 ~ '^[0-9a-f]{64}$'),
    held_out_precision          NUMERIC(6,5) NOT NULL
                                CHECK (held_out_precision BETWEEN 0 AND 1),
    held_out_recall             NUMERIC(6,5) NOT NULL
                                CHECK (held_out_recall BETWEEN 0 AND 1),
    held_out_negative_fp        INTEGER NOT NULL CHECK (held_out_negative_fp >= 0),
    created_at                  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (auction_id, input_snapshot_sha256, parser_version),
    UNIQUE (id, auction_id),
    FOREIGN KEY (auction_id, source_snapshot_sha256)
        REFERENCES auction_source_snapshots (auction_id, content_sha256) ON DELETE RESTRICT,
    FOREIGN KEY (auction_id, input_snapshot_sha256)
        REFERENCES auction_enrichment_input_snapshots (auction_id, snapshot_sha256) ON DELETE RESTRICT
);

CREATE INDEX idx_property_reference_extraction_runs_source
    ON property_reference_extraction_runs (auction_id, source_snapshot_sha256, parser_version);

CREATE TABLE property_reference_extraction_memberships (
    extraction_run_id           UUID NOT NULL,
    auction_id                  BIGINT NOT NULL,
    reference_id                UUID NOT NULL REFERENCES property_references(id) ON DELETE RESTRICT,
    reference_order             INTEGER NOT NULL CHECK (reference_order >= 0),
    PRIMARY KEY (extraction_run_id, reference_id),
    UNIQUE (extraction_run_id, reference_order),
    FOREIGN KEY (extraction_run_id, auction_id)
        REFERENCES property_reference_extraction_runs (id, auction_id) ON DELETE RESTRICT
);

CREATE TABLE current_property_reference_extractions (
    auction_id                  BIGINT PRIMARY KEY REFERENCES auctions(id) ON DELETE RESTRICT,
    extraction_run_id           UUID NOT NULL UNIQUE,
    selected_at                 TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (extraction_run_id, auction_id)
        REFERENCES property_reference_extraction_runs (id, auction_id) ON DELETE RESTRICT
);

CREATE TABLE property_reference_extraction_observations (
    enrichment_run_id           UUID NOT NULL,
    auction_id                  BIGINT NOT NULL,
    extraction_run_id           UUID NOT NULL,
    observed_at                 TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (enrichment_run_id, auction_id),
    FOREIGN KEY (enrichment_run_id, auction_id)
        REFERENCES enrichment_run_items (run_id, auction_id) ON DELETE RESTRICT,
    FOREIGN KEY (extraction_run_id, auction_id)
        REFERENCES property_reference_extraction_runs (id, auction_id) ON DELETE RESTRICT
);

CREATE INDEX idx_property_reference_extraction_observations_run
    ON property_reference_extraction_observations (enrichment_run_id, extraction_run_id);

ALTER TABLE enrichment_runs
    ADD COLUMN property_reference_extraction_success_count BIGINT NOT NULL DEFAULT 0
        CHECK (property_reference_extraction_success_count >= 0),
    ADD COLUMN property_reference_parse_failure_count BIGINT NOT NULL DEFAULT 0
        CHECK (property_reference_parse_failure_count >= 0),
    ADD COLUMN property_reference_count BIGINT NOT NULL DEFAULT 0
        CHECK (property_reference_count >= 0),
    ADD COLUMN text_reference_count BIGINT NOT NULL DEFAULT 0
        CHECK (text_reference_count >= 0),
    ADD COLUMN no_structured_reference_count BIGINT NOT NULL DEFAULT 0
        CHECK (no_structured_reference_count >= 0),
    ADD COLUMN ko_conflict_count BIGINT NOT NULL DEFAULT 0
        CHECK (ko_conflict_count >= 0),
    ADD COLUMN property_reference_quality_corpus_version TEXT,
    ADD COLUMN property_reference_quality_metrics_sha256 CHAR(64)
        CHECK (
            property_reference_quality_metrics_sha256 IS NULL
            OR property_reference_quality_metrics_sha256 ~ '^[0-9a-f]{64}$'
        ),
    ADD CONSTRAINT ck_enrichment_run_property_reference_quality CHECK (
        (property_reference_quality_corpus_version IS NULL
            AND property_reference_quality_metrics_sha256 IS NULL)
        OR (btrim(property_reference_quality_corpus_version) <> ''
            AND property_reference_quality_metrics_sha256 IS NOT NULL)
    );

CREATE FUNCTION reject_property_reference_extraction_evidence_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'property-reference extraction evidence is immutable';
END;
$$;

CREATE TRIGGER trg_property_reference_extraction_runs_immutable
BEFORE UPDATE OR DELETE ON property_reference_extraction_runs
FOR EACH ROW EXECUTE FUNCTION reject_property_reference_extraction_evidence_mutation();

CREATE TRIGGER trg_property_reference_extraction_memberships_immutable
BEFORE UPDATE OR DELETE ON property_reference_extraction_memberships
FOR EACH ROW EXECUTE FUNCTION reject_property_reference_extraction_evidence_mutation();

CREATE TRIGGER trg_property_reference_extraction_observations_immutable
BEFORE UPDATE OR DELETE ON property_reference_extraction_observations
FOR EACH ROW EXECUTE FUNCTION reject_property_reference_extraction_evidence_mutation();
