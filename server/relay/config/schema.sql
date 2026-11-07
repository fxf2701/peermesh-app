-- Relay server schema (Postgres)

CREATE SEQUENCE IF NOT EXISTS events_ingest_order_seq;

CREATE TABLE IF NOT EXISTS events (
  eid TEXT PRIMARY KEY,
  ingest_order BIGINT NOT NULL DEFAULT nextval('events_ingest_order_seq'),
  gid TEXT NOT NULL,
  ts BIGINT NOT NULL,
  exp BIGINT NOT NULL,
  body JSONB NOT NULL,
  sig TEXT
);

CREATE INDEX IF NOT EXISTS events_gid_ts_eid_idx
  ON events (gid, ts, eid);

CREATE INDEX IF NOT EXISTS events_gid_ingest_order_idx
  ON events (gid, ingest_order);

CREATE INDEX IF NOT EXISTS events_exp_idx
  ON events (exp);

CREATE TABLE IF NOT EXISTS schema_migrations (
  version TEXT PRIMARY KEY,
  applied_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS federation_cursors (
  peer_url TEXT NOT NULL,
  gid TEXT NOT NULL,
  cursor TEXT,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (peer_url, gid)
);

INSERT INTO schema_migrations (version)
VALUES ('001_initial')
ON CONFLICT (version) DO NOTHING;
