CREATE SEQUENCE IF NOT EXISTS events_ingest_order_seq;

ALTER TABLE events
  ADD COLUMN IF NOT EXISTS ingest_order BIGINT;

ALTER TABLE events
  ALTER COLUMN ingest_order SET DEFAULT nextval('events_ingest_order_seq');

UPDATE events
SET ingest_order = nextval('events_ingest_order_seq')
WHERE ingest_order IS NULL;

ALTER TABLE events
  ALTER COLUMN ingest_order SET NOT NULL;

CREATE INDEX IF NOT EXISTS events_gid_ingest_order_idx
  ON events (gid, ingest_order);
