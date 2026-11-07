-- 003_convert_events_to_partitioned.down.sql

BEGIN;

LOCK TABLE events IN ACCESS EXCLUSIVE MODE;

ALTER TABLE events RENAME TO events_partitioned;

CREATE TABLE events (
  eid TEXT NOT NULL,
  gid TEXT NOT NULL,
  ts BIGINT NOT NULL,
  exp BIGINT NOT NULL,
  body JSONB NOT NULL,
  sig TEXT
);

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint
    WHERE conname = 'events_pkey'
  ) THEN
    ALTER TABLE events
      ADD CONSTRAINT events_pkey PRIMARY KEY (eid);
  END IF;
END $$;

CREATE INDEX IF NOT EXISTS events_gid_ts_eid_idx
  ON events (gid, ts, eid);

CREATE INDEX IF NOT EXISTS events_exp_idx
  ON events (exp);

INSERT INTO events (eid, gid, ts, exp, body, sig)
SELECT eid, gid, ts, exp, body, sig
FROM events_partitioned;

DROP TABLE events_partitioned;

COMMIT;
