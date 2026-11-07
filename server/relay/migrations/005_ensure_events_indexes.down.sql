-- 005_ensure_events_indexes.down.sql

DROP INDEX IF EXISTS events_gid_ts_eid_idx;
DROP INDEX IF EXISTS events_exp_idx;
