-- 015_add_events_pkey.up.sql

BEGIN;

-- Remove duplicate rows by (eid, exp) to allow a primary key constraint.
WITH ranked AS (
  SELECT ctid,
         row_number() OVER (PARTITION BY eid, exp ORDER BY ts ASC) AS rn
  FROM events
)
DELETE FROM events
WHERE ctid IN (SELECT ctid FROM ranked WHERE rn > 1);

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint
    WHERE conname = 'events_pkey'
  ) THEN
    ALTER TABLE events
      ADD CONSTRAINT events_pkey PRIMARY KEY (eid, exp);
  END IF;
END $$;

COMMIT;
