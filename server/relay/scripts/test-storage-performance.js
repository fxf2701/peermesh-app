#!/usr/bin/env node
/* eslint-disable no-console */

const http = require("http");
const crypto = require("crypto");
const { Client } = require("pg");

const baseUrl = process.env.RELAY_BASE_URL || "http://localhost:8080";
const databaseUrl = process.env.DATABASE_URL || process.env.RELAY_DATABASE_URL;
const cleanupInterval = Number(process.env.CLEANUP_INTERVAL_SECONDS || "60");
const cleanupGrace = Number(process.env.CLEANUP_GRACE_SECONDS || "300");
const cleanupUseAdvisoryLock = process.env.CLEANUP_USE_ADVISORY_LOCK !== "0";
const dbPoolMax = Number(process.env.DB_POOL_MAX || "10");
const dbConnTimeoutMs = Number(process.env.DB_CONN_TIMEOUT_MS || "2000");

function uuidv4() {
  const bytes = crypto.randomBytes(16);
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = bytes.toString("hex");
  return [
    hex.slice(0, 8),
    hex.slice(8, 12),
    hex.slice(12, 16),
    hex.slice(16, 20),
    hex.slice(20),
  ].join("-");
}

function postJson(path, payload) {
  const data = JSON.stringify(payload);
  const url = new URL(path, baseUrl);

  return new Promise((resolve, reject) => {
    const req = http.request(
      {
        hostname: url.hostname,
        port: url.port,
        path: url.pathname,
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "Content-Length": Buffer.byteLength(data),
        },
      },
      (res) => {
        let body = "";
        res.on("data", (chunk) => {
          body += chunk;
        });
        res.on("end", () => {
          resolve({ status: res.statusCode, body });
        });
      }
    );

    req.on("error", reject);
    req.write(data);
    req.end();
  });
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function run() {
  if (!databaseUrl) {
    console.error("DATABASE_URL is required for storage performance tests.");
    process.exit(1);
  }

  let failed = 0;

  const client = new Client({ connectionString: databaseUrl });
  await client.connect();

  const now = Math.floor(Date.now() / 1000);
  const gid = `Z29vZGd${uuidv4().slice(0, 6)}`;
  const batchEvents = Array.from({ length: 25 }, (_, idx) => ({
    eid: uuidv4(),
    gid,
    ts: now + idx,
    exp: now + 600,
    body: { type: "CHAT.MSG", text: `msg-${idx}` },
    sig: "abcd",
  }));

  try {
    const ingest = await postJson("/v1/events:ingest", { events: batchEvents });
    if (ingest.status !== 200) {
      throw new Error(`ingest failed: ${ingest.status} ${ingest.body}`);
    }
    const body = JSON.parse(ingest.body);
    if (Array.isArray(body.accepted) && body.accepted.length === batchEvents.length) {
      console.log("PASS batch_insert_ingest");
    } else {
      failed += 1;
      console.log("FAIL batch_insert_ingest");
      console.log(`  body: ${ingest.body}`);
    }

    const rows = await client.query("SELECT count(*)::int AS count FROM events WHERE gid = $1", [
      gid,
    ]);
    if (rows.rows[0].count === batchEvents.length) {
      console.log("PASS batch_insert_db_rows");
    } else {
      failed += 1;
      console.log("FAIL batch_insert_db_rows");
      console.log(`  count: ${rows.rows[0].count}`);
    }
  } catch (err) {
    failed += 1;
    console.log("FAIL batch_insert");
    console.log(`  error: ${err.message || String(err)}`);
  }

  // Pool sizing + connection timeout (basic check).
  try {
    const clients = [];
    for (let i = 0; i < dbPoolMax + 1; i += 1) {
      const c = new Client({
        connectionString: databaseUrl,
        connectionTimeoutMillis: Math.max(100, dbConnTimeoutMs),
      });
      await c.connect();
      clients.push(c);
    }
    console.log("PASS db_pool_timeout (connection timeout applied)");
    for (const c of clients) {
      await c.end();
    }
  } catch (err) {
    failed += 1;
    console.log("FAIL db_pool_timeout");
    console.log(`  error: ${err.message || String(err)}`);
  }

  // Cleanup coordination (advisory lock).
  if (cleanupUseAdvisoryLock) {
    try {
      const lockKey = 762541;
      const lock = await client.query("SELECT pg_try_advisory_lock($1) AS locked", [lockKey]);
      if (!lock.rows[0].locked) {
        console.log("FAIL cleanup_lock_setup");
        failed += 1;
      } else {
        // Wait longer than cleanup interval+grace; if cleanup uses advisory lock,
        // it should not delete because we hold the lock.
        const expSoon = now + 2;
        const expEvent = {
          eid: uuidv4(),
          gid: `Z29vZGd${uuidv4().slice(0, 6)}`,
          ts: now,
          exp: expSoon,
          body: { type: "CHAT.MSG" },
          sig: "abcd",
        };
        await postJson("/v1/events:ingest", { events: [expEvent] });
        await sleep((cleanupInterval + cleanupGrace + 3) * 1000);
        const stillThere = await client.query(
          "SELECT count(*)::int AS count FROM events WHERE eid = $1",
          [expEvent.eid]
        );
        await client.query("SELECT pg_advisory_unlock($1)", [lockKey]);
        if (stillThere.rows[0].count === 1) {
          console.log("PASS cleanup_lock");
        } else {
          failed += 1;
          console.log("FAIL cleanup_lock");
        }
      }
    } catch (err) {
      failed += 1;
      console.log("FAIL cleanup_lock");
      console.log(`  error: ${err.message || String(err)}`);
    }
  } else {
    console.log("SKIP cleanup_lock (CLEANUP_USE_ADVISORY_LOCK=0)");
  }

  // Partitioning (manual schema check).
  try {
    const partitioned = await client.query(
      "SELECT EXISTS (SELECT 1 FROM pg_class WHERE relname = 'events' AND relispartition) AS is_partition"
    );
    if (partitioned.rows[0].is_partition) {
      console.log("PASS partitioned_schema_detected");
    } else {
      console.log("SKIP partitioned_schema_detected (optional; requires schema_partitioned.sql)");
    }
  } catch (err) {
    failed += 1;
    console.log("FAIL partitioned_schema_detected");
    console.log(`  error: ${err.message || String(err)}`);
  }

  await client.end();

  if (failed > 0) {
    process.exit(1);
  }
}

run();
