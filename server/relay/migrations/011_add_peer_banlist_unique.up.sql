CREATE UNIQUE INDEX IF NOT EXISTS peer_banlist_unique_idx
  ON peer_banlist (type, value);
