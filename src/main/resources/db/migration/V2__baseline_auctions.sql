-- Issue #16: baseline schema for the existing Auction entity, so Hibernate can
-- start with ddl-auto=validate against a Flyway-migrated database.
-- The spatial columns, provenance, and lifecycle schema are #15/#20, not this
-- issue; this table mirrors the entity exactly as it stands today.
CREATE TABLE auctions (
    id                  BIGINT           NOT NULL,
    auction_number      VARCHAR(255),
    start_date          TIMESTAMP(6) WITH TIME ZONE,
    end_date            TIMESTAMP(6) WITH TIME ZONE,
    publication_date    TIMESTAMP(6) WITH TIME ZONE,
    starting_price      NUMERIC(38, 2),
    estimated_price     NUMERIC(38, 2),
    current_price       NUMERIC(38, 2),
    max_offered_price   NUMERIC(38, 2),
    bid_step            NUMERIC(38, 2),
    short_description   VARCHAR(2000),
    description         VARCHAR(4000),
    status              VARCHAR(255),
    first_sale          BOOLEAN          NOT NULL,
    property_type       VARCHAR(255),
    executor_name       VARCHAR(255),
    category_name       VARCHAR(255),
    place_name          VARCHAR(255),
    place_zip_code      VARCHAR(255),
    municipality        VARCHAR(255),
    cadastral           VARCHAR(255),
    details_fetched     BOOLEAN          NOT NULL,
    CONSTRAINT pk_auctions PRIMARY KEY (id)
);
