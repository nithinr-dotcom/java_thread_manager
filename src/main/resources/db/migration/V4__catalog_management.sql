-- Catalog management and inbound HQ sync. version is an epoch-millisecond, last-writer-wins version
-- shared with HQ (local edits use max(version + 1, now)).
ALTER TABLE product ADD COLUMN station  VARCHAR(32) NOT NULL DEFAULT 'KITCHEN';
ALTER TABLE product ADD COLUMN category VARCHAR(64) NOT NULL DEFAULT 'General';
ALTER TABLE product ADD COLUMN active   BOOLEAN     NOT NULL DEFAULT TRUE;

-- Small key/value store for sync high-water marks (e.g. the last HQ catalog version pulled).
CREATE TABLE sync_state (
    name       VARCHAR(64) PRIMARY KEY,
    value      BIGINT      NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
