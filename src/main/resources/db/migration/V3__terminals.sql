-- Registered devices (POS, kiosk, KDS screen, back office, HQ sync client). A device authenticates
-- with a bearer API key; only the SHA-256 of the key is stored. terminal_id is the business identity
-- stamped on orders; id is the UUIDv7 entity key.
CREATE TABLE terminal (
    id           UUID         PRIMARY KEY,
    terminal_id  VARCHAR(64)  NOT NULL UNIQUE,
    name         VARCHAR(100) NOT NULL,
    role         VARCHAR(16)  NOT NULL CHECK (role IN ('POS', 'KITCHEN', 'MANAGER', 'HQ')),
    api_key_hash CHAR(64)     NOT NULL UNIQUE,
    created_at   TIMESTAMPTZ  NOT NULL,
    revoked_at   TIMESTAMPTZ
);
