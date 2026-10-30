-- V5: Enhance POIs with discovery metadata and deduplication support for nearby-place discovery.
-- All new columns are nullable so the migration is safe to apply to a database that already contains POI rows.

ALTER TABLE pois
    ADD COLUMN city VARCHAR(100),
    ADD COLUMN locality VARCHAR(255),
    ADD COLUMN address TEXT,
    ADD COLUMN source VARCHAR(50),
    ADD COLUMN external_id VARCHAR(255),
    ADD COLUMN source_url VARCHAR(1000),
    ADD COLUMN licence VARCHAR(255),
    ADD COLUMN updated_at TIMESTAMPTZ,
    ADD COLUMN last_seen_at TIMESTAMPTZ;

-- Support category-based POI filtering. Spatial filtering continues to use the
-- existing V1 GiST index idx_pois_location, which is intentionally left unchanged.
CREATE INDEX idx_pois_type
    ON pois(type);

-- Deduplicate externally sourced POIs by provider identity. PostgreSQL treats NULLs as
-- distinct within a unique index, so manually created POIs (NULL source / external_id) and
-- existing rows are never blocked from being inserted or migrated.
CREATE UNIQUE INDEX uq_pois_source_external_id
    ON pois(source, external_id);
