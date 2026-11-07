UPDATE peer_mappings
  SET peer_token = ''
  WHERE peer_token IS NULL;

ALTER TABLE peer_mappings
  ALTER COLUMN peer_token SET NOT NULL;

ALTER TABLE peer_mappings
  DROP COLUMN IF EXISTS rotated_token,
  DROP COLUMN IF EXISTS rotated_expires_at;
