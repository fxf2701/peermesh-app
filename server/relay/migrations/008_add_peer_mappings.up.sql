CREATE TABLE IF NOT EXISTS peer_mappings (
  id SERIAL PRIMARY KEY,
  peer_url TEXT NOT NULL UNIQUE,
  peer_token TEXT NOT NULL,
  sync_gids JSONB NOT NULL DEFAULT '[]'::jsonb,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  self_id TEXT,
  joined_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS peer_mappings_peer_url_idx
  ON peer_mappings (peer_url);
