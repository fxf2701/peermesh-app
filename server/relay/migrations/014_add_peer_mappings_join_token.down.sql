ALTER TABLE peer_mappings
  DROP COLUMN IF EXISTS join_token,
  DROP COLUMN IF EXISTS join_token_expires_at;
