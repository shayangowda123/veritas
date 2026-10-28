-- V4: Enhance Property, Listing, PropertyImage and add Location Verification Foundation

-- 1. Support ADMIN role for authorized verification workflows
ALTER TABLE users DROP CONSTRAINT IF EXISTS chk_users_role;
ALTER TABLE users
    ADD CONSTRAINT chk_users_role
        CHECK (role IN ('OWNER', 'RENTER', 'ADMIN'));

-- 2. Enhance properties table with locality and location verification metadata
ALTER TABLE properties
    ADD COLUMN locality VARCHAR(255),
    ADD COLUMN city VARCHAR(100),
    ADD COLUMN location_verification_status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN verified_at TIMESTAMPTZ,
    ADD COLUMN verification_notes TEXT,
    ADD COLUMN verification_method VARCHAR(50);

ALTER TABLE properties
    ADD CONSTRAINT chk_property_verification_status
        CHECK (location_verification_status IN ('PENDING', 'REVIEW_REQUIRED', 'VERIFIED', 'REJECTED'));

CREATE INDEX idx_properties_verification_status
    ON properties(location_verification_status);

-- 3. Enhance listings table with additional residential features and status constraints
ALTER TABLE listings
    ADD COLUMN bathrooms INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN furnishing_status VARCHAR(30) NOT NULL DEFAULT 'UNFURNISHED',
    ADD COLUMN parking_available BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN pets_allowed BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE listings
    ADD CONSTRAINT chk_listing_bathrooms
        CHECK (bathrooms > 0);

ALTER TABLE listings
    ADD CONSTRAINT chk_listing_furnishing_status
        CHECK (furnishing_status IN ('UNFURNISHED', 'SEMI_FURNISHED', 'FULLY_FURNISHED'));

ALTER TABLE listings
    ADD CONSTRAINT chk_listing_status
        CHECK (status IN ('DRAFT', 'PUBLISHED', 'RENTED', 'ARCHIVED', 'UNAVAILABLE'));

-- Prevent multiple conflicting active published listings for the same physical property
CREATE UNIQUE INDEX uq_active_published_listing_per_property
    ON listings(property_id)
    WHERE status = 'PUBLISHED';

-- 4. Enhance property_images table with file metadata
ALTER TABLE property_images
    ADD COLUMN original_filename VARCHAR(255),
    ADD COLUMN content_type VARCHAR(100),
    ADD COLUMN file_size_bytes BIGINT;
