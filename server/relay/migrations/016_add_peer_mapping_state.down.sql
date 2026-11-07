DROP INDEX IF EXISTS peer_mappings_peer_state_idx;

ALTER TABLE peer_mappings
  DROP COLUMN IF EXISTS quarantined_until;

ALTER TABLE peer_mappings
  DROP COLUMN IF EXISTS quarantine_reason;

ALTER TABLE peer_mappings
  DROP COLUMN IF EXISTS peer_state;
