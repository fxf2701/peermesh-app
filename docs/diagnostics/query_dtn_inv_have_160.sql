-- Read-only diagnostic for Device A's 2026-08-01 13:48:39 +07 INV_HAVE.
-- Run with: sqlite3 -readonly peer_mesh.db < query_dtn_inv_have_160.sql
-- The timestamp is 1785566919 (2026-08-01 13:48:39 +07).

-- 1. Raw valid source window in the Room cursor order.
SELECT
  id, gid, event_id, type, source, created_t, inserted_at, actor_did,
  ref_root, ref_parent, ref_target, invalid_at, invalid_reason
FROM events
WHERE gid = 'H-MfE3z9pVj2M4FIOt5JLxLTguKOFp5q1kOQ-Mq0k4Q'
  AND invalid_at IS NULL
ORDER BY inserted_at ASC, event_id ASC;

SELECT COUNT(*) AS valid_event_count
FROM events
WHERE gid = 'H-MfE3z9pVj2M4FIOt5JLxLTguKOFp5q1kOQ-Mq0k4Q'
  AND invalid_at IS NULL;

-- 2. Candidate query. This reproduces the NORMAL tier at the recorded time.
-- DtnInventoryBuilder requests limit=250 and scanLimit=1000; the source set
-- has 160 NORMAL events, so the single cursor scan is exhaustive.
WITH
  params(now_s) AS (VALUES(1785566919)),
  candidates AS (
    SELECT
      e.event_id,
      e.type,
      e.source,
      e.created_t,
      e.inserted_at,
      p.now_s - e.created_t AS age_seconds_at_build,
      e.actor_did,
      e.ref_root,
      e.ref_parent,
      e.ref_target,
      e.invalid_at,
      'normal' AS inventory_tier,
      CASE
        WHEN e.type LIKE 'SYS.ALERT%' THEN 0
        WHEN e.type LIKE 'SYS.SAFETY%' THEN 1
        WHEN e.type LIKE 'MAP.%' THEN 2
        WHEN e.type LIKE 'CHAT.%' THEN 3
        ELSE 4
      END AS priority,
      CASE
        WHEN e.type LIKE 'CHAT.%' THEN 'CHAT'
        WHEN e.type LIKE 'MAP.%' AND
             (e.type LIKE '%.ADD' OR e.type LIKE '%.UPD' OR e.type LIKE '%.DEL')
          THEN 'MAP_CANONICAL'
        WHEN e.type LIKE 'MAP.%' THEN 'MAP_ACTION'
        WHEN e.type LIKE 'SYS.%' THEN 'SYSTEM'
        ELSE 'OTHER'
      END AS category
    FROM events e, params p
    WHERE e.gid = 'H-MfE3z9pVj2M4FIOt5JLxLTguKOFp5q1kOQ-Mq0k4Q'
      AND e.invalid_at IS NULL
      AND e.inserted_at <= p.now_s
      AND e.type <> 'SYS.ACK'
      AND NOT (
        e.type IN ('CHAT.MSG', 'CHAT.REPLY', 'CHAT.EDIT', 'CHAT.DEL')
        AND e.created_t BETWEEN p.now_s - 3600 AND p.now_s
      )
  )
SELECT
  row_number() OVER (ORDER BY priority, created_t, event_id) AS ordinal,
  event_id, type, source, created_t, inserted_at, age_seconds_at_build,
  actor_did, ref_root, ref_parent, ref_target, invalid_at,
  inventory_tier, priority,
  0 AS suppressed_for_peer, -- peer suppression is process-memory only
  category
FROM candidates
ORDER BY priority, created_t, event_id;

-- 3. Counts by type/source and age bucket for the same candidate set.
WITH params(now_s) AS (VALUES(1785566919))
SELECT type, source, COUNT(*) AS count
FROM events e, params p
WHERE e.gid = 'H-MfE3z9pVj2M4FIOt5JLxLTguKOFp5q1kOQ-Mq0k4Q'
  AND e.invalid_at IS NULL AND e.inserted_at <= p.now_s
  AND e.type <> 'SYS.ACK'
  AND NOT (e.type IN ('CHAT.MSG', 'CHAT.REPLY', 'CHAT.EDIT', 'CHAT.DEL')
           AND e.created_t BETWEEN p.now_s - 3600 AND p.now_s)
GROUP BY type, source
ORDER BY count DESC, type, source;

WITH params(now_s) AS (VALUES(1785566919))
SELECT
  CASE
    WHEN p.now_s - e.created_t < 300 THEN '<5m'
    WHEN p.now_s - e.created_t < 1800 THEN '5-30m'
    WHEN p.now_s - e.created_t < 21600 THEN '30m-6h'
    WHEN p.now_s - e.created_t < 86400 THEN '6-24h'
    WHEN p.now_s - e.created_t < 604800 THEN '1-7d'
    ELSE '>7d'
  END AS age_bucket,
  COUNT(*) AS count
FROM events e, params p
WHERE e.gid = 'H-MfE3z9pVj2M4FIOt5JLxLTguKOFp5q1kOQ-Mq0k4Q'
  AND e.invalid_at IS NULL AND e.inserted_at <= p.now_s
  AND e.type <> 'SYS.ACK'
  AND NOT (e.type IN ('CHAT.MSG', 'CHAT.REPLY', 'CHAT.EDIT', 'CHAT.DEL')
           AND e.created_t BETWEEN p.now_s - 3600 AND p.now_s)
GROUP BY age_bucket;

-- 4. Outbound-batch correspondence. Inventory membership is independent of
-- current APP outbound queue membership.
WITH
  params(now_s) AS (VALUES(1785566919)),
  candidates AS (
    SELECT e.event_id
    FROM events e, params p
    WHERE e.gid = 'H-MfE3z9pVj2M4FIOt5JLxLTguKOFp5q1kOQ-Mq0k4Q'
      AND e.invalid_at IS NULL AND e.inserted_at <= p.now_s
      AND e.type <> 'SYS.ACK'
      AND NOT (e.type IN ('CHAT.MSG', 'CHAT.REPLY', 'CHAT.EDIT', 'CHAT.DEL')
               AND e.created_t BETWEEN p.now_s - 3600 AND p.now_s)
  )
SELECT
  c.event_id, b.batch_id, b.status AS batch_state, b.attempt_count,
  d.next_retry_at, d.device_id AS target_device_id, b.created_at, b.updated_at,
  b.last_error AS last_failure_reason, b.sent_at
FROM candidates c
LEFT JOIN outbound_batch_event be ON be.event_id = c.event_id
LEFT JOIN outbound_batch b ON b.batch_id = be.batch_id
LEFT JOIN outbound_batch_peer_delivery d ON d.batch_id = b.batch_id
ORDER BY c.event_id, b.batch_id, d.device_id;

-- 5. Cursor/advertised/suppression-related persisted state. The active
-- DtnRepairSuppressionStore is InMemoryDtnRepairSuppressionStore, so it has
-- no Room rows and cannot reconstruct historical suppression after restart.
SELECT * FROM dtn_sync_cursor
WHERE peer_id = '3D3FA1C865F3B265'
ORDER BY updated_at DESC;

SELECT * FROM dtn_advertised
WHERE peer_id = '3D3FA1C865F3B265'
  AND gid = 'H-MfE3z9pVj2M4FIOt5JLxLTguKOFp5q1kOQ-Mq0k4Q'
ORDER BY advertised_at DESC;

SELECT * FROM pending_canonical_map_events
WHERE gid = 'H-MfE3z9pVj2M4FIOt5JLxLTguKOFp5q1kOQ-Mq0k4Q'
ORDER BY received_at ASC;

SELECT a.materialization_state, e.event_id, e.type, e.ref_root, e.ref_target
FROM map_item_actions a
JOIN events e ON e.gid = a.gid AND e.event_id = a.event_id
WHERE a.gid = 'H-MfE3z9pVj2M4FIOt5JLxLTguKOFp5q1kOQ-Mq0k4Q'
ORDER BY a.materialization_state, e.inserted_at, e.event_id;
