-- v1 scores measured snippets, not full-description v2 parsing. Preserve them
-- for historical runs; an unevaluated parser must carry no borrowed scores.
ALTER TABLE property_reference_extraction_runs
    ALTER COLUMN quality_corpus_version DROP NOT NULL,
    ALTER COLUMN quality_metrics_sha256 DROP NOT NULL,
    ALTER COLUMN held_out_precision DROP NOT NULL,
    ALTER COLUMN held_out_recall DROP NOT NULL,
    ALTER COLUMN held_out_negative_fp DROP NOT NULL,
    ADD CONSTRAINT ck_extraction_quality_complete CHECK (
        (quality_corpus_version IS NULL AND quality_metrics_sha256 IS NULL
            AND held_out_precision IS NULL AND held_out_recall IS NULL AND held_out_negative_fp IS NULL)
        OR (quality_corpus_version IS NOT NULL AND quality_metrics_sha256 IS NOT NULL
            AND held_out_precision IS NOT NULL AND held_out_recall IS NOT NULL AND held_out_negative_fp IS NOT NULL)
    );
