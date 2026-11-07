CREATE TABLE IF NOT EXISTS peer_banlist (
  id SERIAL PRIMARY KEY,
  type TEXT NOT NULL,
  value TEXT NOT NULL,
  reason TEXT,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS peer_banlist_value_idx
  ON peer_banlist (value);
