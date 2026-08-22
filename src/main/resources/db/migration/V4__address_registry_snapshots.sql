-- Issue #22: immutable Address Registry snapshots, an atomic active pointer,
-- retained import evidence, address/parcel lookup indexes, and coarse
-- centroids. Auction-side spatial columns remain owned by #20.

CREATE TABLE address_registry_snapshots (
    id                          UUID PRIMARY KEY,
    canonical_url               TEXT NOT NULL,
    download_uri                TEXT NOT NULL,
    downloaded_at               TIMESTAMP WITH TIME ZONE NOT NULL,
    source_date                 DATE NOT NULL,
    source_bytes                BIGINT NOT NULL CHECK (source_bytes > 0),
    source_sha256               CHAR(64) NOT NULL CHECK (source_sha256 ~ '^[0-9a-f]{64}$'),
    archive_member              TEXT,
    gpkg_bytes                  BIGINT NOT NULL CHECK (gpkg_bytes > 0),
    gpkg_sha256                 CHAR(64) NOT NULL CHECK (gpkg_sha256 ~ '^[0-9a-f]{64}$'),
    schema_sha256               CHAR(64) NOT NULL CHECK (schema_sha256 ~ '^[0-9a-f]{64}$'),
    source_table                TEXT NOT NULL,
    geometry_column             TEXT NOT NULL,
    source_crs                  INTEGER NOT NULL CHECK (source_crs = 25834),
    target_crs                  INTEGER NOT NULL CHECK (target_crs = 4326),
    source_row_count            BIGINT NOT NULL CHECK (source_row_count >= 0),
    imported_row_count          BIGINT NOT NULL CHECK (imported_row_count >= 0),
    active_source_row_count     BIGINT NOT NULL CHECK (active_source_row_count >= 0),
    inactive_source_row_count   BIGINT NOT NULL CHECK (inactive_source_row_count >= 0),
    retired_source_row_count    BIGINT NOT NULL CHECK (retired_source_row_count >= 0),
    rejected_row_count          BIGINT NOT NULL CHECK (rejected_row_count >= 0),
    duplicate_parcel_identities BIGINT NOT NULL CHECK (duplicate_parcel_identities >= 0),
    ambiguous_parent_identities BIGINT NOT NULL CHECK (ambiguous_parent_identities >= 0),
    imported_at                 TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_address_registry_snapshot_gpkg UNIQUE (gpkg_sha256),
    CONSTRAINT ck_address_registry_accounting CHECK (
        source_row_count = active_source_row_count + inactive_source_row_count
        AND imported_row_count = active_source_row_count
        AND rejected_row_count = 0
    )
);

CREATE TABLE address_registry_points (
    snapshot_id                 UUID NOT NULL REFERENCES address_registry_snapshots(id) ON DELETE CASCADE,
    source_fid                  BIGINT NOT NULL,
    source_primary_key          BIGINT NOT NULL,
    house_number_id             TEXT,
    house_number                TEXT,
    house_number_latin          TEXT,
    house_number_normalized     TEXT,
    status_name                 TEXT,
    status_name_latin           TEXT,
    source_created              TEXT,
    source_modified             TEXT,
    type_name                   TEXT,
    type_name_latin             TEXT,
    street_id                   TEXT,
    street_name                 TEXT,
    street_name_latin           TEXT,
    street_name_normalized      TEXT,
    parcel_number               TEXT,
    parcel_number_normalized    TEXT,
    parcel_part                 TEXT,
    ko_id                       TEXT NOT NULL,
    ko_name                     TEXT NOT NULL,
    ko_name_latin               TEXT,
    ko_name_normalized          TEXT NOT NULL,
    settlement_id               TEXT NOT NULL,
    settlement_name             TEXT NOT NULL,
    settlement_name_latin       TEXT,
    settlement_name_normalized  TEXT NOT NULL,
    municipality_id             TEXT NOT NULL,
    municipality_name           TEXT NOT NULL,
    municipality_name_latin     TEXT,
    municipality_name_normalized TEXT NOT NULL,
    location                    geometry(Point, 4326) NOT NULL,
    CONSTRAINT pk_address_registry_points PRIMARY KEY (snapshot_id, source_primary_key),
    CONSTRAINT uq_address_registry_source_fid UNIQUE (snapshot_id, source_fid)
);

CREATE INDEX idx_address_registry_ko_parcel
    ON address_registry_points (snapshot_id, ko_id, parcel_number_normalized)
    WHERE parcel_number_normalized IS NOT NULL;
CREATE INDEX idx_address_registry_named_ko_parcel
    ON address_registry_points (snapshot_id, ko_name_normalized, parcel_number_normalized)
    WHERE parcel_number_normalized IS NOT NULL;
CREATE INDEX idx_address_registry_exact_address
    ON address_registry_points (
        snapshot_id, municipality_id, settlement_id,
        street_name_normalized, house_number_normalized
    )
    WHERE street_name_normalized IS NOT NULL AND house_number_normalized IS NOT NULL;
CREATE INDEX idx_address_registry_street
    ON address_registry_points (
        snapshot_id, municipality_id, settlement_id, street_name_normalized
    )
    WHERE street_name_normalized IS NOT NULL;
CREATE INDEX idx_address_registry_ko_name
    ON address_registry_points (snapshot_id, municipality_id, ko_name_normalized);
CREATE INDEX idx_address_registry_location
    ON address_registry_points USING GIST (location);

CREATE TABLE address_registry_centroids (
    snapshot_id                 UUID NOT NULL REFERENCES address_registry_snapshots(id) ON DELETE CASCADE,
    level                       VARCHAR(16) NOT NULL CHECK (level IN ('KO', 'SETTLEMENT', 'MUNICIPALITY')),
    official_id                 TEXT NOT NULL,
    name                        TEXT NOT NULL,
    name_latin                  TEXT,
    name_normalized             TEXT NOT NULL,
    municipality_id             TEXT,
    parent_variant_count        BIGINT NOT NULL CHECK (parent_variant_count >= 0),
    member_point_count          BIGINT NOT NULL CHECK (member_point_count > 0),
    location                    geometry(Point, 4326) NOT NULL,
    CONSTRAINT pk_address_registry_centroids PRIMARY KEY (snapshot_id, level, official_id)
);

CREATE INDEX idx_address_registry_centroid_lookup
    ON address_registry_centroids (snapshot_id, level, name_normalized);
CREATE INDEX idx_address_registry_centroid_location
    ON address_registry_centroids USING GIST (location);

CREATE TABLE address_registry_active_snapshot (
    singleton                   BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    snapshot_id                 UUID NOT NULL REFERENCES address_registry_snapshots(id),
    previous_snapshot_id        UUID REFERENCES address_registry_snapshots(id),
    activated_at                TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_address_registry_distinct_active_previous
        CHECK (previous_snapshot_id IS NULL OR previous_snapshot_id <> snapshot_id)
);

CREATE TABLE address_registry_import_runs (
    id                          UUID PRIMARY KEY,
    action                      VARCHAR(16) NOT NULL CHECK (action IN ('IMPORT', 'ROLLBACK')),
    outcome                     VARCHAR(16) NOT NULL CHECK (outcome IN ('RUNNING', 'SUCCEEDED', 'UNCHANGED', 'FAILED')),
    started_at                  TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at                 TIMESTAMP WITH TIME ZONE,
    snapshot_id                 UUID,
    previous_snapshot_id        UUID,
    source_date                 DATE,
    canonical_url               TEXT,
    source_sha256               CHAR(64),
    gpkg_sha256                 CHAR(64),
    source_bytes                BIGINT,
    gpkg_bytes                  BIGINT,
    source_row_count            BIGINT,
    imported_row_count          BIGINT,
    inactive_source_row_count   BIGINT,
    retired_source_row_count    BIGINT,
    duplicate_parcel_identities BIGINT,
    ambiguous_parent_identities BIGINT,
    download_millis             BIGINT,
    validation_millis           BIGINT,
    load_millis                 BIGINT,
    centroid_millis             BIGINT,
    total_millis                BIGINT,
    error_code                  VARCHAR(64),
    error_message               TEXT
);

CREATE INDEX idx_address_registry_import_runs_started
    ON address_registry_import_runs (started_at DESC);
