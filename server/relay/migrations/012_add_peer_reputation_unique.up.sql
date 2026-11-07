CREATE UNIQUE INDEX IF NOT EXISTS peer_reputation_peer_id_unique_idx
  ON peer_reputation (peer_id);
