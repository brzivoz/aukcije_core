-- A parcel cache identity is feature type + dataset version + KO + parcel,
-- encoded by input_fingerprint. Capability/schema pins and resolver versions
-- are provenance, not additional identities that permit another fetch.
-- Keep legacy duplicate records and their attempt/geometry history intact;
-- the first retained terminal result is the authoritative cache entry.
ALTER TABLE location_resolution_cache_records
    ADD CONSTRAINT uq_location_cache_id_input UNIQUE (id, input_fingerprint);

CREATE TABLE rgz_parcel_cache_keys (
    input_fingerprint CHAR(64) PRIMARY KEY
        CHECK (input_fingerprint ~ '^[0-9a-f]{64}$'),
    cache_record_id UUID NOT NULL,
    FOREIGN KEY (cache_record_id, input_fingerprint)
        REFERENCES location_resolution_cache_records (id, input_fingerprint)
        ON DELETE RESTRICT
);

INSERT INTO rgz_parcel_cache_keys (input_fingerprint, cache_record_id)
SELECT DISTINCT ON (input_fingerprint) input_fingerprint, id
  FROM location_resolution_cache_records
 WHERE resolver = 'RGZ_WFS_PARCEL' AND source_dataset = 'RGZ_REGDKP_WFS'
 ORDER BY input_fingerprint, cached_at, id;

-- Ordinary enrichment discovers unhandled ERROR evidence independently of a
-- successful/coarse/terminal fallback outcome. Existing error attempts also
-- become discoverable; no mutable retry flag or destructive backfill is needed.
CREATE INDEX idx_rgz_retry_discovery
    ON location_resolution_attempts (
        source_dataset_version, property_reference_id,
        upstream_ko_match_input_fingerprint, input_fingerprint
    )
    WHERE resolver = 'RGZ_WFS_PARCEL' AND resolution_status = 'ERROR';
