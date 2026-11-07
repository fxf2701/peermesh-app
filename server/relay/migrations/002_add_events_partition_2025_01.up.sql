-- 002_add_events_partition_2025_01.up.sql

DO $$
BEGIN
  IF EXISTS (
    SELECT 1
    FROM pg_partitioned_table pt
    JOIN pg_class c ON c.oid = pt.partrelid
    WHERE c.relname = 'events'
  ) THEN
    CREATE TABLE IF NOT EXISTS events_exp_2025_01
      PARTITION OF events FOR VALUES FROM (1735689600) TO (1738368000);
  ELSE
    RAISE NOTICE 'events is not partitioned; skipping partition creation';
  END IF;
END $$;
