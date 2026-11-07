#!/usr/bin/env node
/* eslint-disable no-console */

const http = require("http");
const crypto = require("crypto");
const { Client } = require("pg");

const baseUrl = process.env.RELAY_BASE_URL || "http://localhost:8080";
const databaseUrl = process.env.DATABASE_URL || process.env.RELAY_DATABASE_URL;
const cleanupInterval = Number(process.env.CLEANUP_INTERVAL_SECONDS || "60");
const cleanupGrace = Number(process.env.CLEANUP_GRACE_SECONDS || "300");

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
    console.error("DATABASE_URL is required for TTL tests.");
    process.exit(1);
  }

  if (cleanupInterval > 10 || cleanupGrace > 10) {
    console.error(
      "Set CLEANUP_INTERVAL_SECONDS and CLEANUP_GRACE_SECONDS to <= 10s for TTL tests."
    );
    process.exit(1);
  }

  const client = new Client({ connectionString: databaseUrl });
  await client.connect();

  let failed = 0;
  const now = Math.floor(Date.now() / 1000);
  const gid = `Z29vZGd${uuidv4().slice(0, 6)}`;

  const expSoon = now + 2;
  const expLater = now + 60;
  const eidExpired = uuidv4();
  const eidRetained = uuidv4();

  try {
    const indexes = await client.query(
      "SELECT indexname FROM pg_indexes WHERE tablename = 'events'"
    );
    const names = new Set(indexes.rows.map((row) => row.indexname));
    const hasExp = names.has("events_exp_idx");
    const hasFetch = names.has("events_gid_ts_eid_idx");
    if (hasExp && hasFetch) {
      console.log("PASS indexes_present");
    } else {
      failed += 1;
      console.log("FAIL indexes_present");
      console.log(`  events_exp_idx: ${hasExp}`);
      console.log(`  events_gid_ts_eid_idx: ${hasFetch}`);
    }

    const ingest = await postJson("/v1/events:ingest", {
      events: [
        {
          eid: eidExpired,
          gid,
          ts: now - 3600,
          exp: expSoon,
          body: { type: "CHAT.MSG" },
          sig: "abcd",
        },
        {
          eid: eidRetained,
          gid,
          ts: now - 3600,
          exp: expLater,
          body: { type: "CHAT.MSG" },
          sig: "abcd",
        },
      ],
    });

    if (ingest.status !== 200) {
      failed += 1;
      console.log("FAIL ingest_setup");
      console.log(`  status: ${ingest.status}`);
      console.log(`  body: ${ingest.body}`);
    } else {
      console.log("PASS ingest_setup");
    }

    const countBefore = await client.query(
      "SELECT count(*)::int AS count FROM events WHERE eid = ANY($1)",
      [[eidExpired, eidRetained]]
    );
    if (countBefore.rows[0].count === 2) {
      console.log("PASS rows_inserted");
    } else {
      failed += 1;
      console.log("FAIL rows_inserted");
      console.log(`  count: ${countBefore.rows[0].count}`);
    }

    const waitSeconds = cleanupInterval + cleanupGrace + 3;
    await sleep(waitSeconds * 1000);

    const countAfter = await client.query(
      "SELECT eid FROM events WHERE eid = ANY($1)",
      [[eidExpired, eidRetained]]
    );
    const remaining = new Set(countAfter.rows.map((row) => row.eid));
    const expiredGone = !remaining.has(eidExpired);
    const retainedStill = remaining.has(eidRetained);

    if (expiredGone && retainedStill) {
      console.log("PASS cleanup_expired");
    } else {
      failed += 1;
      console.log("FAIL cleanup_expired");
      console.log(`  expired_present: ${remaining.has(eidExpired)}`);
      console.log(`  retained_present: ${remaining.has(eidRetained)}`);
    }
  } catch (err) {
    failed += 1;
    console.log("FAIL ttl_tests");
    console.log(`  error: ${err.message || String(err)}`);
  } finally {
    await client.query("DELETE FROM events WHERE eid = ANY($1)", [[eidExpired, eidRetained]]);
    await client.end();
  }

  if (failed > 0) {
    process.exit(1);
  }
}

run();
