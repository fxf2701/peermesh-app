-- Optional partitioned schema for large datasets (Postgres).
-- This is not applied by default. Use only if you plan to manage partitions.

CREATE SEQUENCE IF NOT EXISTS events_ingest_order_seq;

CREATE TABLE IF NOT EXISTS events (
  eid TEXT NOT NULL,
  ingest_order BIGINT NOT NULL DEFAULT nextval('events_ingest_order_seq'),
  gid TEXT NOT NULL,
  ts BIGINT NOT NULL,
  exp BIGINT NOT NULL,
  body JSONB NOT NULL,
  sig TEXT
) PARTITION BY RANGE (exp);

ALTER TABLE events
  ADD CONSTRAINT events_pkey PRIMARY KEY (eid, exp);

-- Example partitions (create partitions for your exp ranges):
-- CREATE TABLE IF NOT EXISTS events_exp_2025_01
--   PARTITION OF events FOR VALUES FROM (1735689600) TO (1738368000);

CREATE INDEX IF NOT EXISTS events_gid_ts_eid_idx
  ON events (gid, ts, eid);

CREATE INDEX IF NOT EXISTS events_gid_ingest_order_idx
  ON events (gid, ingest_order);

CREATE INDEX IF NOT EXISTS events_exp_idx
  ON events (exp);

CREATE TABLE IF NOT EXISTS federation_cursors (
  peer_url TEXT NOT NULL,
  gid TEXT NOT NULL,
  cursor TEXT,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (peer_url, gid)
);
