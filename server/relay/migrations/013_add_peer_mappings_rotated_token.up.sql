ALTER TABLE peer_mappings
  ALTER COLUMN peer_token DROP NOT NULL;

ALTER TABLE peer_mappings
  ADD COLUMN rotated_token TEXT,
  ADD COLUMN rotated_expires_at TIMESTAMPTZ;
