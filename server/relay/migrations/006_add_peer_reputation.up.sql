CREATE TABLE IF NOT EXISTS peer_reputation (
  id SERIAL PRIMARY KEY,
  peer_id TEXT NOT NULL,
  score INTEGER NOT NULL DEFAULT 0,
  note TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS peer_reputation_peer_id_idx
  ON peer_reputation (peer_id);
