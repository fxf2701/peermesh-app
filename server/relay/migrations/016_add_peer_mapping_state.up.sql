ALTER TABLE peer_mappings
  ADD COLUMN IF NOT EXISTS peer_state TEXT NOT NULL DEFAULT 'active';

ALTER TABLE peer_mappings
  ADD COLUMN IF NOT EXISTS quarantine_reason TEXT;

ALTER TABLE peer_mappings
  ADD COLUMN IF NOT EXISTS quarantined_until TIMESTAMPTZ;

UPDATE peer_mappings
SET peer_state = 'active'
WHERE peer_state IS NULL OR btrim(peer_state) = '';

CREATE INDEX IF NOT EXISTS peer_mappings_peer_state_idx
  ON peer_mappings (peer_state);
