#!/usr/bin/env node
/* eslint-disable no-console */

const http = require("http");
const crypto = require("crypto");
const { Client } = require("pg");
const { createStorage } = require("../src/storage");
const { loadConfig } = require("../src/config");
const { syncPeerOnce, computeBackoffMs } = require("../src/federation");

const config = loadConfig();
const testPeerPort = Number(process.env.FEDERATION_TEST_PEER_PORT || "19090");
const testPeerUrl = `http://127.0.0.1:${testPeerPort}`;
const testGid = process.env.FEDERATION_TEST_GID || "Z29vZGdtest";
const selfId = config.federationSelfId || "relay-test";
const peerToken = process.env.FEDERATION_TEST_PEER_TOKEN || "test-peer-token";

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

async function run() {
  let failed = 0;
  const now = Math.floor(Date.now() / 1000);

  if (!config.databaseUrl) {
    console.log("FAIL federation_sync");
    console.log("  DATABASE_URL is required");
    process.exit(1);
  }

  const storage = createStorage({ config, redis: null });
  const db = new Client({ connectionString: config.databaseUrl });
  await db.connect();

  try {
    await db.query("DELETE FROM federation_cursors WHERE peer_url = $1 AND gid = $2", [
      testPeerUrl,
      testGid,
    ]);
    await db.query("DELETE FROM events WHERE gid = $1", [testGid]);
  } catch (err) {
    console.log("FAIL federation_sync");
    console.log(`  error: ${err.message || String(err)}`);
    await db.end();
    process.exit(1);
  }

  const events = [
    {
      eid: uuidv4(),
      gid: testGid,
      ts: now,
      exp: now + 600,
      body: { type: "CHAT.MSG", nonce: `peer-${uuidv4()}` },
      sig: "abcd",
    },
    {
      eid: uuidv4(),
      gid: testGid,
      ts: now + 1,
      exp: now + 600,
      body: { type: "CHAT.MSG", nonce: `peer-${uuidv4()}` },
      sig: "abcd",
    },
  ];

  let requestCount = 0;
  let sawLoopHeader = false;

  const peerServer = http.createServer((req, res) => {
    if (!req.url.startsWith("/v1/events:fetch")) {
      res.writeHead(404);
      res.end("not_found");
      return;
    }

    if (req.headers["x-relay-peer"] !== "1") {
      res.writeHead(403);
      res.end(JSON.stringify({ error: "missing_peer_header" }));
      return;
    }

    if (req.headers["x-relay-peer-token"] !== peerToken) {
      res.writeHead(403);
      res.end(JSON.stringify({ error: "invalid_token" }));
      return;
    }

    if (typeof req.headers["x-relay-loop"] === "string") {
      sawLoopHeader = req.headers["x-relay-loop"].includes(selfId);
    }

    const url = new URL(req.url, testPeerUrl);
    const gid = url.searchParams.get("gid");
    const cursor = url.searchParams.get("cursor");

    if (gid !== testGid) {
      res.writeHead(400);
      res.end(JSON.stringify({ error: "unexpected_gid" }));
      return;
    }

    requestCount += 1;
    if (requestCount === 1) {
      if (cursor) {
        res.writeHead(400);
        res.end(JSON.stringify({ error: "unexpected_cursor" }));
        return;
      }
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ events, next_cursor: "cursor-1" }));
      return;
    }

    if (requestCount === 2) {
      if (cursor !== "cursor-1") {
        res.writeHead(400);
        res.end(JSON.stringify({ error: "missing_cursor" }));
        return;
      }
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ events: [], next_cursor: "cursor-2" }));
      return;
    }

    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ events: [], next_cursor: "cursor-2" }));
  });

  await new Promise((resolve) => peerServer.listen(testPeerPort, resolve));

  try {
    await syncPeerOnce({
      peerUrl: testPeerUrl,
      gid: testGid,
      peerToken,
      config: {
        ...config,
        federationSelfId: selfId,
        federationSyncLimit: 2,
      },
      storage,
    });

    await syncPeerOnce({
      peerUrl: testPeerUrl,
      gid: testGid,
      peerToken,
      config: {
        ...config,
        federationSelfId: selfId,
        federationSyncLimit: 2,
      },
      storage,
    });

    const cursorResult = await db.query(
      "SELECT cursor FROM federation_cursors WHERE peer_url = $1 AND gid = $2",
      [testPeerUrl, testGid]
    );
    const storedCursor = cursorResult.rows[0] && cursorResult.rows[0].cursor;
    const eventResult = await db.query("SELECT COUNT(*) FROM events WHERE gid = $1", [testGid]);
    const eventCount = Number(eventResult.rows[0].count || 0);

    if (storedCursor === "cursor-2" && eventCount === events.length && sawLoopHeader) {
      console.log("PASS federation_sync_cursor");
    } else {
      failed += 1;
      console.log("FAIL federation_sync_cursor");
      console.log(`  cursor: ${storedCursor}`);
      console.log(`  events: ${eventCount}`);
      console.log(`  loop_header: ${sawLoopHeader}`);
    }
  } catch (err) {
    failed += 1;
    console.log("FAIL federation_sync_cursor");
    console.log(`  error: ${err.message || String(err)}`);
  } finally {
    peerServer.close();
    await storage.close();
    await db.end();
  }

  try {
    const backoffOk =
      computeBackoffMs(1000, 8000, 0, 0) === 1000 &&
      computeBackoffMs(1000, 8000, 1, 0) === 2000 &&
      computeBackoffMs(1000, 8000, 2, 0) === 4000 &&
      computeBackoffMs(1000, 8000, 3, 0) === 8000 &&
      computeBackoffMs(1000, 8000, 4, 0) === 8000;
    if (backoffOk) {
      console.log("PASS federation_backoff");
    } else {
      failed += 1;
      console.log("FAIL federation_backoff");
    }
  } catch (err) {
    failed += 1;
    console.log("FAIL federation_backoff");
    console.log(`  error: ${err.message || String(err)}`);
  }

  if (failed > 0) {
    process.exit(1);
  }
}

run();
