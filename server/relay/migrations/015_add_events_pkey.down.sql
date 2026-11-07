-- 015_add_events_pkey.down.sql

BEGIN;

DO $$
BEGIN
  IF EXISTS (
    SELECT 1
    FROM pg_constraint
    WHERE conname = 'events_pkey'
  ) THEN
    ALTER TABLE events
      DROP CONSTRAINT events_pkey;
  END IF;
END $$;

COMMIT;
