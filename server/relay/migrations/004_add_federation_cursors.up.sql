-- 004_add_federation_cursors.up.sql

CREATE TABLE IF NOT EXISTS federation_cursors (
  peer_url TEXT NOT NULL,
  gid TEXT NOT NULL,
  cursor TEXT,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (peer_url, gid)
);
