-- 001_initial.up.sql

CREATE TABLE IF NOT EXISTS events (
  eid TEXT PRIMARY KEY,
  gid TEXT NOT NULL,
  ts BIGINT NOT NULL,
  exp BIGINT NOT NULL,
  body JSONB NOT NULL,
  sig TEXT
);

CREATE INDEX IF NOT EXISTS events_gid_ts_eid_idx
  ON events (gid, ts, eid);

CREATE INDEX IF NOT EXISTS events_exp_idx
  ON events (exp);

CREATE TABLE IF NOT EXISTS schema_migrations (
  version TEXT PRIMARY KEY,
  applied_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

INSERT INTO schema_migrations (version)
VALUES ('001_initial')
ON CONFLICT (version) DO NOTHING;
