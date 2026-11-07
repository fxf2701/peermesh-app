// Postgres storage adapter for relay node.

const { createPool } = require("./db");
const { decodeCursor, encodeCursor } = require("./utils");
const { validateEventEnvelope } = require("./validation");
const { logEvent } = require("./logger");
const { effectivePeerState } = require("./peer_mapping_state");

function createStorage({ config, redis }) {
  const requiredTables = [
    "schema_migrations",
    "events",
    "federation_cursors",
    "peer_reputation",
    "peer_banlist",
    "peer_mappings",
  ];
  const schemaValidationTimeoutMs =
    Number.isFinite(config.startupSchemaValidationTimeoutMs) &&
    config.startupSchemaValidationTimeoutMs > 0
      ? config.startupSchemaValidationTimeoutMs
      : 5000;
  const cleanupWatchdogTimeoutMs =
    Number.isFinite(config.cleanupWatchdogTimeoutSeconds) &&
    config.cleanupWatchdogTimeoutSeconds > 0
      ? config.cleanupWatchdogTimeoutSeconds * 1000
      : 120000;
  const cleanupTestHangMs =
    Number.isFinite(config.cleanupTestHangMs) && config.cleanupTestHangMs > 0
      ? config.cleanupTestHangMs
      : 0;
  const pool = createPool({
    databaseUrl: config.databaseUrl,
    poolMax: config.dbPoolMax,
    idleTimeoutMs: config.dbIdleTimeoutMs,
    connTimeoutMs: config.dbConnTimeoutMs,
    circuitBreakerEnabled: config.dbCircuitBreakerEnabled,
    circuitBreakerFailureThreshold: config.dbCircuitBreakerFailureThreshold,
    circuitBreakerBaseBackoffMs: config.dbCircuitBreakerBaseBackoffMs,
    circuitBreakerMaxBackoffMs: config.dbCircuitBreakerMaxBackoffMs,
  });
  const joinNonceCache = createNonceCache({
    maxEntries: config.nonceCacheMaxEntries,
    ttlSeconds: config.nonceCacheTtlSeconds,
    redis,
    prefix: "join_nonce",
  });

  let cleanupTimer = null;
  let closed = false;
  let cleanupInFlight = false;
  let cleanupStartMs = 0;
  let cleanupLastDurationMs = 0;
  let cleanupStuckCount = 0;
  let cleanupStuckReported = false;
  if (config.cleanupIntervalSeconds > 0) {
    const intervalMs = config.cleanupIntervalSeconds * 1000;
    cleanupTimer = setInterval(async () => {
      if (closed) return;
      if (cleanupInFlight) {
        const elapsedMs = Date.now() - cleanupStartMs;
        if (!cleanupStuckReported && elapsedMs >= cleanupWatchdogTimeoutMs) {
          cleanupStuckReported = true;
          cleanupStuckCount += 1;
          logEvent("warn", "cleanup_stuck", {
            duration_ms: elapsedMs,
            timeout_ms: cleanupWatchdogTimeoutMs,
          });
          if (typeof config.onCleanupStuck === "function") {
            config.onCleanupStuck({ durationMs: elapsedMs });
          }
        }
        return;
      }

      cleanupInFlight = true;
      cleanupStartMs = Date.now();
      cleanupStuckReported = false;
      try {
        await cleanupExpired();
      } catch (err) {
        logEvent("warn", "cleanup_failed", { error: err });
      } finally {
        cleanupLastDurationMs = Date.now() - cleanupStartMs;
        cleanupInFlight = false;
      }
    }, intervalMs);
  }

  function isLegacyStoredEnvelope(value) {
    return value &&
      typeof value === "object" &&
      !Array.isArray(value) &&
      typeof value.eid === "string" &&
      typeof value.gid === "string" &&
      Number.isInteger(value.ts) &&
      Number.isInteger(value.exp) &&
      Object.prototype.hasOwnProperty.call(value, "body");
  }

  function payloadFromStoredBody(body) {
    if (isLegacyStoredEnvelope(body)) {
      return body.body;
    }
    return body;
  }

  function rowToEventEnvelope(row) {
    return {
      eid: row.eid,
      gid: row.gid,
      ts: Number(row.ts),
      exp: Number(row.exp),
      body: payloadFromStoredBody(row.body),
      sig: row.sig,
    };
  }

  function mapPeerMappingRow(row) {
    if (!row) return null;
    return {
      ...row,
      sync_gids: Array.isArray(row.sync_gids) ? row.sync_gids : [],
      effective_state: effectivePeerState(row),
    };
  }

  async function ingestEvents(events) {
    const accepted = [];
    const rejected = [];
    const now = Math.floor(Date.now() / 1000);
    let duplicateInBatch = 0;
    let duplicateOnInsert = 0;
    const duplicateSample = [];

    const client = await pool.connect();
    try {
      await client.query("BEGIN");

      const validEvents = [];
      const seenValidEids = new Set();

      for (const event of events) {
        const validation = validateEventEnvelope(event, config, now);
        if (!validation.ok) {
          rejected.push({
            eid: event && event.eid ? event.eid : null,
            reason: validation.reason,
            details: validation.details || null,
          });
          continue;
        }

        if (seenValidEids.has(event.eid)) {
          rejected.push({ eid: event.eid, reason: "duplicate" });
          duplicateInBatch += 1;
          if (duplicateSample.length < 3) duplicateSample.push(event.eid);
          continue;
        }

        seenValidEids.add(event.eid);
        validEvents.push(event);
      }

      if (validEvents.length > 0) {
        const values = [];
        const placeholders = validEvents.map((event, index) => {
          const base = index * 6;
          values.push(event.eid, event.gid, event.ts, event.exp, event.body, event.sig || null);
          return `($${base + 1}, $${base + 2}, $${base + 3}, $${base + 4}, $${base + 5}, $${
            base + 6
          })`;
        });

        const query = `
          INSERT INTO events (eid, gid, ts, exp, body, sig)
          VALUES ${placeholders.join(", ")}
          ON CONFLICT DO NOTHING
          RETURNING eid
        `;

        const result = await client.query(query, values);
        const acceptedSet = new Set(result.rows.map((row) => row.eid));
        for (const event of validEvents) {
          if (acceptedSet.has(event.eid)) {
            accepted.push(event.eid);
          } else {
            rejected.push({ eid: event.eid, reason: "duplicate" });
            duplicateOnInsert += 1;
            if (duplicateSample.length < 3) duplicateSample.push(event.eid);
          }
        }
      }

      await client.query("COMMIT");
      if (duplicateInBatch > 0 || duplicateOnInsert > 0) {
        logEvent("warn", "ingest_duplicate", {
          duplicate_in_batch: duplicateInBatch,
          duplicate_on_insert: duplicateOnInsert,
          sample_eids: duplicateSample,
          total_events: events.length,
        });
      }
    } catch (err) {
      await client.query("ROLLBACK");
      throw err;
    } finally {
      client.release();
    }

    return { accepted, rejected };
  }

  async function isJoinNonceReplay(nonce, now) {
    if (!nonce || !joinNonceCache) return false;
    const remembered = await joinNonceCache.remember(nonce, now);
    return !remembered;
  }

  async function fetchEvents({ gid, cursor, cursorDecoded, limit, eventIds = [] }) {
    const now = Math.floor(Date.now() / 1000);
    const decoded = cursorDecoded || decodeCursor(cursor);

    const params = [gid, now, limit];
    let whereClause = "gid = $1 AND exp > $2";

    if (eventIds.length > 0) {
      params.push(eventIds);
      whereClause += " AND eid = ANY($4::text[])";
    } else if (decoded) {
      if (Number.isInteger(decoded.order) && decoded.order > 0) {
        params.push(decoded.order);
        whereClause += ` AND ingest_order > $4`;
      } else {
        params.push(decoded.ts, decoded.eid);
        whereClause += ` AND (ts, eid) > ($4, $5)`;
      }
    }

    const query = `\n      SELECT eid, gid, ts, exp, body, sig, ingest_order\n      FROM events\n      WHERE ${whereClause}\n      ORDER BY ingest_order ASC\n      LIMIT $3\n    `;

    const { rows } = await pool.query(query, params);
    const events = rows.map(rowToEventEnvelope);

    const last = rows[rows.length - 1];
    const nextCursor = eventIds.length > 0
      ? cursor || null
      : last
      ? encodeCursor({
          ts: Number(last.ts),
          eid: last.eid,
          order: Number(last.ingest_order),
        })
      : cursor || null;

    return { events, next_cursor: nextCursor };
  }

  async function cleanupExpired() {
    if (closed) return;
    if (cleanupTestHangMs > 0) {
      await new Promise((resolve) => setTimeout(resolve, cleanupTestHangMs));
    }
    if (closed) return;
    const now = Math.floor(Date.now() / 1000);
    const cutoff = now - config.cleanupGraceSeconds;
    // Retention uses exp only; ts does not affect cleanup.
    const lockKey = 762541;
    let locked = true;

    if (config.cleanupUseAdvisoryLock) {
      const lockResult = await pool.query("SELECT pg_try_advisory_lock($1) AS locked", [lockKey]);
      locked = lockResult.rows[0] && lockResult.rows[0].locked;
    }

    if (!locked) return;

    try {
      await pool.query("DELETE FROM events WHERE exp <= $1", [cutoff]);
    } finally {
      if (config.cleanupUseAdvisoryLock) {
        await pool.query("SELECT pg_advisory_unlock($1)", [lockKey]);
      }
    }
  }

  async function healthCheck({ timeoutMs } = {}) {
    if (!timeoutMs || timeoutMs <= 0) {
      await pool.query("SELECT 1");
      return;
    }

    let timeoutId;
    const timeout = new Promise((_, reject) => {
      timeoutId = setTimeout(() => {
        const error = new Error("db health check timeout");
        error.code = "HEALTH_DB_TIMEOUT";
        reject(error);
      }, timeoutMs);
    });

    try {
      await Promise.race([pool.query("SELECT 1"), timeout]);
    } finally {
      if (timeoutId) clearTimeout(timeoutId);
    }
  }

  async function checkPartitionPresence() {
    const partitioned = await pool.query(
      `SELECT partstrat
       FROM pg_partitioned_table pt
       JOIN pg_class c ON c.oid = pt.partrelid
       WHERE c.relname = 'events'`
    );

    if (partitioned.rows.length === 0) {
      return { partitioned: false, partitions: 0 };
    }

    const partitions = await pool.query(
      `SELECT inhrelid::regclass::text AS name
       FROM pg_inherits
       WHERE inhparent = 'events'::regclass`
    );

    return { partitioned: true, partitions: partitions.rows.length };
  }

  async function validateSchema() {
    async function withTimeout(promise, label) {
      let timeoutId;
      const timeout = new Promise((_, reject) => {
        timeoutId = setTimeout(() => {
          const error = new Error(`schema validation timed out: ${label}`);
          error.code = "SCHEMA_VALIDATION_TIMEOUT";
          reject(error);
        }, schemaValidationTimeoutMs);
      });

      try {
        return await Promise.race([promise, timeout]);
      } finally {
        if (timeoutId) clearTimeout(timeoutId);
      }
    }

    const client = await withTimeout(pool.connect(), "connect");
    try {
      await withTimeout(
        client.query(`SET statement_timeout TO ${schemaValidationTimeoutMs}`),
        "set_statement_timeout"
      );
      await withTimeout(
        client.query(`SET lock_timeout TO ${Math.min(schemaValidationTimeoutMs, 2000)}`),
        "set_lock_timeout"
      );

      const { rows } = await withTimeout(
        client.query(
          `
            SELECT table_name
            FROM information_schema.tables
            WHERE table_schema = 'public'
              AND table_name = ANY($1::text[])
          `,
          [requiredTables]
        ),
        "table_check"
      );

      const found = new Set(rows.map((row) => row.table_name));
      const missing = requiredTables.filter((table) => !found.has(table));
      if (missing.length > 0) {
        const error = new Error(`missing required tables: ${missing.join(", ")}`);
        error.code = "SCHEMA_MISSING_TABLES";
        error.missingTables = missing;
        throw error;
      }

      const { rows: migrationRows } = await withTimeout(
        client.query("SELECT count(*)::int AS count FROM schema_migrations"),
        "migration_count"
      );
      if (!migrationRows[0] || migrationRows[0].count === 0) {
        const error = new Error("no migrations recorded in schema_migrations");
        error.code = "SCHEMA_NO_MIGRATIONS";
        throw error;
      }
    } finally {
      client.release();
    }
  }

  function monthStartUtc(baseDate, offsetMonths) {
    const year = baseDate.getUTCFullYear();
    const month = baseDate.getUTCMonth();
    const total = month + offsetMonths;
    const targetYear = year + Math.floor(total / 12);
    const targetMonth = ((total % 12) + 12) % 12;
    return new Date(Date.UTC(targetYear, targetMonth, 1, 0, 0, 0));
  }

  async function ensurePartitionTable(name, startEpoch, endEpoch) {
    await pool.query(
      `CREATE TABLE IF NOT EXISTS ${name}
       PARTITION OF events
       FOR VALUES FROM (${startEpoch}) TO (${endEpoch});`
    );
  }

  async function ensurePartitions({ monthsAhead = 1, backfillMonths = 0, now } = {}) {
    const partitioned = await pool.query(
      `SELECT partstrat
       FROM pg_partitioned_table pt
       JOIN pg_class c ON c.oid = pt.partrelid
       WHERE c.relname = 'events'`
    );

    if (partitioned.rows.length === 0) {
      return { partitioned: false, created: 0, checked: 0 };
    }

    const baseDate = now instanceof Date ? now : new Date(now || Date.now());
    const targets = [];
    const startOffset = Math.max(-backfillMonths, -120);
    const endOffset = Math.max(monthsAhead, 0);
    for (let offset = startOffset; offset <= endOffset; offset += 1) {
      const start = monthStartUtc(baseDate, offset);
      const end = monthStartUtc(baseDate, offset + 1);
      const suffix = `${start.getUTCFullYear()}_${String(start.getUTCMonth() + 1).padStart(2, "0")}`;
      targets.push({
        name: `events_exp_${suffix}`,
        startEpoch: Math.floor(start.getTime() / 1000),
        endEpoch: Math.floor(end.getTime() / 1000),
      });
    }

    const created = [];
    for (const target of targets) {
      const { rows } = await pool.query("SELECT to_regclass($1) AS name", [target.name]);
      if (!rows[0] || !rows[0].name) {
        await ensurePartitionTable(target.name, target.startEpoch, target.endEpoch);
        created.push(target.name);
      }
    }

    return { partitioned: true, created, checked: targets.length };
  }

  async function getFederationCursor(peerUrl, gid) {
    try {
      const { rows } = await pool.query(
        "SELECT cursor FROM federation_cursors WHERE peer_url = $1 AND gid = $2",
        [peerUrl, gid]
      );
      return rows[0] ? rows[0].cursor : null;
    } catch (err) {
      if (err && err.code === "42P01") {
        return null;
      }
      throw err;
    }
  }

  async function setFederationCursor(peerUrl, gid, cursor) {
    try {
      await pool.query(
        `
          INSERT INTO federation_cursors (peer_url, gid, cursor, updated_at)
          VALUES ($1, $2, $3, NOW())
          ON CONFLICT (peer_url, gid)
          DO UPDATE SET cursor = EXCLUDED.cursor, updated_at = NOW()
        `,
        [peerUrl, gid, cursor]
      );
      return true;
    } catch (err) {
      if (err && err.code === "42P01") {
        return false;
      }
      throw err;
    }
  }

  async function listPeerReputation() {
    const result = await pool.query(
      "SELECT id, peer_id, score, note, created_at, updated_at FROM peer_reputation ORDER BY updated_at DESC"
    );
    return result.rows;
  }

  async function createPeerReputation({ peerId, score, note }) {
    const result = await pool.query(
      `INSERT INTO peer_reputation (peer_id, score, note)
       VALUES ($1, $2, $3)
       ON CONFLICT (peer_id)
       DO UPDATE SET score = EXCLUDED.score, note = EXCLUDED.note, updated_at = NOW()
       RETURNING id, peer_id, score, note, created_at, updated_at`,
      [peerId, score, note || null]
    );
    return result.rows[0];
  }

  async function updatePeerReputation({ id, score, note }) {
    const result = await pool.query(
      `UPDATE peer_reputation
       SET score = $2,
           note = $3,
           updated_at = NOW()
       WHERE id = $1
       RETURNING id, peer_id, score, note, created_at, updated_at`,
      [id, score, note || null]
    );
    return result.rows[0] || null;
  }

  async function deletePeerReputation(id) {
    const result = await pool.query("DELETE FROM peer_reputation WHERE id = $1", [id]);
    return result.rowCount > 0;
  }

  async function listPeerBanlist() {
    const result = await pool.query(
      "SELECT id, type, value, reason, enabled, created_at, updated_at FROM peer_banlist ORDER BY updated_at DESC"
    );
    return result.rows;
  }

  async function listActivePeerBanlistValues() {
    const result = await pool.query(
      "SELECT value FROM peer_banlist WHERE enabled = true"
    );
    return result.rows.map((row) => row.value);
  }

  async function createPeerBanlist({ type, value, reason }) {
    const result = await pool.query(
      `INSERT INTO peer_banlist (type, value, reason)
       VALUES ($1, $2, $3)
       RETURNING id, type, value, reason, enabled, created_at, updated_at`,
      [type, value, reason || null]
    );
    return result.rows[0];
  }

  async function updatePeerBanlist({ id, reason, enabled }) {
    const result = await pool.query(
      `UPDATE peer_banlist
       SET reason = COALESCE($2, reason),
           enabled = COALESCE($3, enabled),
           updated_at = NOW()
       WHERE id = $1
       RETURNING id, type, value, reason, enabled, created_at, updated_at`,
      [id, reason, enabled]
    );
    return result.rows[0] || null;
  }

  async function deletePeerBanlist(id) {
    const result = await pool.query("DELETE FROM peer_banlist WHERE id = $1", [id]);
    return result.rowCount > 0;
  }

  async function listPeerMappings() {
    const result = await pool.query(
      `SELECT id, peer_url, peer_token, rotated_token, rotated_expires_at, join_token, join_token_expires_at,
              sync_gids, enabled, auto_sync, peer_state, quarantine_reason, quarantined_until,
              self_id, joined_at, last_seen_at, created_at, updated_at
       FROM peer_mappings
       ORDER BY updated_at DESC`
    );
    return result.rows.map(mapPeerMappingRow);
  }

  async function getPeerMappingByUrl(peerUrl) {
    const result = await pool.query(
      `SELECT id, peer_url, peer_token, rotated_token, rotated_expires_at, join_token, join_token_expires_at,
              sync_gids, enabled, auto_sync, peer_state, quarantine_reason, quarantined_until,
              self_id, joined_at, last_seen_at, created_at, updated_at
       FROM peer_mappings
       WHERE peer_url = $1`,
      [peerUrl]
    );
    return mapPeerMappingRow(result.rows[0]);
  }

  async function createPeerMapping({ peerUrl, peerToken, syncGids, autoSync, peerState, quarantineReason, quarantinedUntil }) {
    const result = await pool.query(
      `INSERT INTO peer_mappings (peer_url, peer_token, sync_gids, auto_sync, peer_state, quarantine_reason, quarantined_until)
       VALUES ($1, $2, $3, $4, $5, $6, $7)
       RETURNING id, peer_url, peer_token, rotated_token, rotated_expires_at, join_token, join_token_expires_at,
                 sync_gids, enabled, auto_sync, peer_state, quarantine_reason, quarantined_until,
                 self_id, joined_at, last_seen_at, created_at, updated_at`,
      [
        peerUrl,
        peerToken,
        JSON.stringify(syncGids || []),
        Boolean(autoSync),
        peerState || "active",
        quarantineReason || null,
        quarantinedUntil || null,
      ]
    );
    return mapPeerMappingRow(result.rows[0]);
  }

  async function updatePeerMapping({
    id,
    peerToken,
    syncGids,
    enabled,
    autoSync,
    peerState,
    quarantineReason,
    quarantinedUntil,
  }) {
    const result = await pool.query(
      `UPDATE peer_mappings
       SET peer_token = COALESCE($2, peer_token),
           sync_gids = COALESCE($3, sync_gids),
           enabled = COALESCE($4, enabled),
           auto_sync = COALESCE($5, auto_sync),
           peer_state = COALESCE($6, peer_state),
           quarantine_reason = CASE
             WHEN $6 = 'active' THEN NULL
             WHEN $7 IS NOT NULL THEN $7
             ELSE quarantine_reason
           END,
           quarantined_until = CASE
             WHEN $6 = 'active' THEN NULL
             WHEN $8 IS NOT NULL THEN $8
             ELSE quarantined_until
           END,
           updated_at = NOW()
       WHERE id = $1
       RETURNING id, peer_url, peer_token, rotated_token, rotated_expires_at, join_token, join_token_expires_at,
                 sync_gids, enabled, auto_sync, peer_state, quarantine_reason, quarantined_until,
                 self_id, joined_at, last_seen_at, created_at, updated_at`,
      [
        id,
        peerToken,
        syncGids ? JSON.stringify(syncGids) : null,
        enabled,
        autoSync,
        peerState,
        quarantineReason,
        quarantinedUntil,
      ]
    );
    return mapPeerMappingRow(result.rows[0]);
  }

  async function deletePeerMapping(id) {
    const result = await pool.query("DELETE FROM peer_mappings WHERE id = $1", [id]);
    return result.rowCount > 0;
  }

  async function countActivePeerConnections(heartbeatTtlSeconds = 120) {
    const result = await pool.query(
      `SELECT count(*)::int AS count
       FROM peer_mappings
       WHERE enabled = true
         AND (
           peer_state = 'active'
           OR peer_state IS NULL
           OR (peer_state = 'quarantined' AND quarantined_until IS NOT NULL AND quarantined_until <= NOW())
         )
         AND last_seen_at >= NOW() - make_interval(secs => $1)`,
      [heartbeatTtlSeconds]
    );
    return result.rows[0]?.count || 0;
  }

  async function countAutoSyncMappings() {
    const result = await pool.query(
      `SELECT count(*)::int AS count
       FROM peer_mappings
       WHERE enabled = true
         AND auto_sync = true
         AND (
           peer_state = 'active'
           OR peer_state IS NULL
           OR (peer_state = 'quarantined' AND quarantined_until IS NOT NULL AND quarantined_until <= NOW())
         )`
    );
    return result.rows[0]?.count || 0;
  }

  async function listInboundPeers(heartbeatTtlSeconds = 120) {
    const result = await pool.query(
      `SELECT self_id, peer_url, last_seen_at, enabled, peer_state, quarantine_reason, quarantined_until
       FROM peer_mappings
       ORDER BY last_seen_at DESC NULLS LAST`
    );
    const nowMs = Date.now();
    const ttlMs = Math.max(1, heartbeatTtlSeconds) * 1000;
    return result.rows.map((row) => {
      const seenAt = row.last_seen_at ? Date.parse(row.last_seen_at) : NaN;
      const state = effectivePeerState(row, nowMs);
      const online =
        state === "active" && Number.isFinite(seenAt) && nowMs - seenAt <= ttlMs;
      return {
        self_id: row.self_id || null,
        peer_url: row.peer_url,
        last_seen_at: row.last_seen_at,
        peer_state: row.peer_state || "active",
        effective_state: state,
        quarantine_reason: row.quarantine_reason || null,
        quarantined_until: row.quarantined_until || null,
        status: state === "quarantined" ? "quarantined" : online ? "online" : "offline",
      };
    });
  }

  async function getPeerMappingBySelfId(selfId) {
    const result = await pool.query(
      `SELECT id, peer_url, peer_token, rotated_token, rotated_expires_at, join_token, join_token_expires_at,
              sync_gids, enabled, auto_sync, peer_state, quarantine_reason, quarantined_until,
              self_id, joined_at, last_seen_at, created_at, updated_at
       FROM peer_mappings
       WHERE self_id = $1`,
      [selfId]
    );
    return mapPeerMappingRow(result.rows[0]);
  }

  async function upsertPeerJoinRequest({ peerUrl, selfId, peerToken }) {
    const result = await pool.query(
      `INSERT INTO peer_mappings (peer_url, peer_token, sync_gids, enabled, self_id, last_seen_at)
       VALUES ($1, $2, '[]'::jsonb, TRUE, $3, NOW())
       ON CONFLICT (peer_url)
       DO UPDATE SET self_id = COALESCE(EXCLUDED.self_id, peer_mappings.self_id),
                     peer_token = CASE
                       WHEN peer_mappings.peer_token IS NULL OR peer_mappings.peer_token = ''
                       THEN EXCLUDED.peer_token
                       ELSE peer_mappings.peer_token
                     END,
                     last_seen_at = NOW(),
                     updated_at = NOW()
       RETURNING id, peer_url, peer_token, rotated_token, rotated_expires_at, join_token, join_token_expires_at,
                 sync_gids, enabled, auto_sync, peer_state, quarantine_reason, quarantined_until,
                 self_id, joined_at, last_seen_at, created_at, updated_at`,
      [peerUrl, peerToken || null, selfId || null]
    );
    return mapPeerMappingRow(result.rows[0]);
  }

  async function updatePeerTokenIfEmpty({ peerUrl, peerToken }) {
    const result = await pool.query(
      `UPDATE peer_mappings
       SET peer_token = $2,
           updated_at = NOW()
       WHERE peer_url = $1
         AND (peer_token IS NULL OR peer_token = '')
       RETURNING id, peer_url, peer_token, rotated_token, rotated_expires_at, join_token, join_token_expires_at,
                 sync_gids, enabled, auto_sync, peer_state, quarantine_reason, quarantined_until,
                 self_id, joined_at, last_seen_at, created_at, updated_at`,
      [peerUrl, peerToken || null]
    );
    return mapPeerMappingRow(result.rows[0]);
  }

  async function updatePeerJoinToken({ peerUrl, joinToken, joinTokenExpiresAt }) {
    const result = await pool.query(
      `UPDATE peer_mappings
       SET join_token = $2,
           join_token_expires_at = $3,
           updated_at = NOW()
       WHERE peer_url = $1
       RETURNING id, peer_url, peer_token, rotated_token, rotated_expires_at, join_token, join_token_expires_at,
                 sync_gids, enabled, auto_sync, peer_state, quarantine_reason, quarantined_until,
                 self_id, joined_at, last_seen_at, created_at, updated_at`,
      [peerUrl, joinToken, joinTokenExpiresAt]
    );
    return mapPeerMappingRow(result.rows[0]);
  }

  async function issuePeerRotatedToken({
    peerUrl,
    selfId,
    rotatedToken,
    rotatedExpiresAt,
    joinToken,
    joinTokenExpiresAt,
  }) {
    const result = await pool.query(
      `UPDATE peer_mappings
       SET rotated_token = $2,
           rotated_expires_at = $3,
           join_token = $4,
           join_token_expires_at = $5,
           self_id = COALESCE($6, self_id),
           joined_at = NOW(),
           updated_at = NOW()
       WHERE peer_url = $1
       RETURNING id, peer_url, peer_token, rotated_token, rotated_expires_at, join_token, join_token_expires_at,
                 sync_gids, enabled, auto_sync, peer_state, quarantine_reason, quarantined_until,
                 self_id, joined_at, last_seen_at, created_at, updated_at`,
      [peerUrl, rotatedToken, rotatedExpiresAt, joinToken, joinTokenExpiresAt, selfId || null]
    );
    return mapPeerMappingRow(result.rows[0]);
  }

  async function storeRotatedToken({ peerUrl, rotatedToken, rotatedExpiresAt }) {
    const result = await pool.query(
      `UPDATE peer_mappings
       SET rotated_token = $2,
           rotated_expires_at = $3,
           joined_at = COALESCE(joined_at, NOW()),
           updated_at = NOW()
       WHERE peer_url = $1
       RETURNING id, peer_url, peer_token, rotated_token, rotated_expires_at, join_token, join_token_expires_at,
                 sync_gids, enabled, auto_sync, peer_state, quarantine_reason, quarantined_until,
                 self_id, joined_at, last_seen_at, created_at, updated_at`,
      [peerUrl, rotatedToken, rotatedExpiresAt]
    );
    return mapPeerMappingRow(result.rows[0]);
  }

  async function clearPeerRotatedToken({ peerUrl }) {
    const result = await pool.query(
      `UPDATE peer_mappings
       SET rotated_token = NULL,
           rotated_expires_at = NULL,
           updated_at = NOW()
       WHERE peer_url = $1
       RETURNING id, peer_url, peer_token, rotated_token, rotated_expires_at, join_token, join_token_expires_at,
                 sync_gids, enabled, auto_sync, peer_state, quarantine_reason, quarantined_until,
                 self_id, joined_at, last_seen_at, created_at, updated_at`,
      [peerUrl]
    );
    return mapPeerMappingRow(result.rows[0]);
  }

  async function markPeerJoined({ peerUrl, selfId }) {
    const result = await pool.query(
      `UPDATE peer_mappings
       SET joined_at = NOW(),
           self_id = COALESCE($2, self_id),
           updated_at = NOW()
       WHERE peer_url = $1
       RETURNING id, peer_url, peer_token, rotated_token, rotated_expires_at, join_token, join_token_expires_at,
                 sync_gids, enabled, auto_sync, peer_state, quarantine_reason, quarantined_until,
                 self_id, joined_at, last_seen_at, created_at, updated_at`,
      [peerUrl, selfId || null]
    );
    return mapPeerMappingRow(result.rows[0]);
  }

  async function markPeerSeen({ peerUrl, selfId }) {
    const result = await pool.query(
      `UPDATE peer_mappings
       SET last_seen_at = NOW(),
           self_id = COALESCE($2, self_id),
           updated_at = NOW()
       WHERE peer_url = $1
       RETURNING id, peer_url, peer_token, rotated_token, rotated_expires_at, join_token, join_token_expires_at,
                 sync_gids, enabled, auto_sync, peer_state, quarantine_reason, quarantined_until,
                 self_id, joined_at, last_seen_at, created_at, updated_at`,
      [peerUrl, selfId || null]
    );
    return mapPeerMappingRow(result.rows[0]);
  }

  async function close() {
    if (cleanupTimer) {
      clearInterval(cleanupTimer);
      cleanupTimer = null;
    }
    closed = true;
    const shutdownDeadline = Date.now() + 2000;
    while (cleanupInFlight && Date.now() < shutdownDeadline) {
      await new Promise((resolve) => setTimeout(resolve, 50));
    }
    await pool.end();
  }

  return {
    ingestEvents,
    fetchEvents,
    cleanupExpired,
    healthCheck,
    checkPartitionPresence,
    ensurePartitions,
    validateSchema,
    getFederationCursor,
    setFederationCursor,
    listPeerReputation,
    createPeerReputation,
    updatePeerReputation,
    deletePeerReputation,
    listPeerBanlist,
    listActivePeerBanlistValues,
    createPeerBanlist,
    updatePeerBanlist,
    deletePeerBanlist,
    listPeerMappings,
    countActivePeerConnections,
    countAutoSyncMappings,
    listInboundPeers,
    getPeerMappingByUrl,
    createPeerMapping,
    updatePeerMapping,
    deletePeerMapping,
    getPeerMappingBySelfId,
    upsertPeerJoinRequest,
    updatePeerTokenIfEmpty,
    updatePeerJoinToken,
    issuePeerRotatedToken,
    storeRotatedToken,
    clearPeerRotatedToken,
    markPeerJoined,
    markPeerSeen,
    isJoinNonceReplay,
    getPoolStats: () => (pool.getStats ? pool.getStats() : null),
    drainPoolWaitSamples: () => (pool.drainWaitSamples ? pool.drainWaitSamples() : []),
    close,
    getCleanupStats: () => ({
      inFlight: cleanupInFlight,
      lastDurationMs: cleanupLastDurationMs,
      stuckCount: cleanupStuckCount,
      lastStartedAtMs: cleanupStartMs,
    }),
  };
}

function createNonceCache({ maxEntries, ttlSeconds, redis, prefix = "nonce" }) {
  if (!Number.isFinite(maxEntries) || maxEntries <= 0) return null;
  if (!Number.isFinite(ttlSeconds) || ttlSeconds <= 0) return null;

  if (redis) {
    return {
      async remember(key) {
        const result = await redis.set(`${prefix}:${key}`, "1", { EX: ttlSeconds, NX: true });
        return result === "OK";
      },
    };
  }

  const cache = new Map();

  function pruneExpired(now) {
    for (const [key, entry] of cache.entries()) {
      if (now - entry.ts > ttlSeconds) {
        cache.delete(key);
      }
    }
  }

  function ensureCapacity() {
    while (cache.size > maxEntries) {
      const oldest = cache.keys().next().value;
      if (!oldest) break;
      cache.delete(oldest);
    }
  }

  return {
    async remember(key, now) {
      pruneExpired(now);
      if (cache.has(key)) return false;
      cache.set(key, { ts: now });
      ensureCapacity();
      return true;
    },
  };
}

module.exports = { createStorage, createNonceCache };
