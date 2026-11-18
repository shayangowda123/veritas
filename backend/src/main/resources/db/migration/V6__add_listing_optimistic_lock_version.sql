-- V6: Add optimistic-locking version column to listings (Phase 3B.6C, lost-update prevention).
-- Additive and backward compatible: every existing row is backfilled to 0, and newly persisted
-- listings start at version 0 (Hibernate manages @Version increments from here on). This column is
-- the JPA optimistic-lock token; it adds no index and alters no existing constraint or index.
ALTER TABLE listings
    ADD COLUMN version INTEGER NOT NULL DEFAULT 0;
