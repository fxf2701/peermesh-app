ALTER TABLE peer_mappings
  ADD COLUMN IF NOT EXISTS join_token TEXT,
  ADD COLUMN IF NOT EXISTS join_token_expires_at TIMESTAMPTZ;
