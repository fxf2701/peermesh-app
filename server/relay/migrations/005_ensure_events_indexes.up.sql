-- 005_ensure_events_indexes.up.sql

CREATE INDEX IF NOT EXISTS events_gid_ts_eid_idx
  ON events (gid, ts, eid);

CREATE INDEX IF NOT EXISTS events_exp_idx
  ON events (exp);
