DROP INDEX IF EXISTS events_gid_ingest_order_idx;

ALTER TABLE events
  ALTER COLUMN ingest_order DROP DEFAULT;

ALTER TABLE events
  DROP COLUMN IF EXISTS ingest_order;

DROP SEQUENCE IF EXISTS events_ingest_order_seq;
