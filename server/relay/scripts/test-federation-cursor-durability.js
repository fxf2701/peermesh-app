#!/usr/bin/env node
/* eslint-disable no-console */

const http = require("http");
const crypto = require("crypto");
const { Client } = require("pg");
const { createStorage } = require("../src/storage");
const { loadConfig } = require("../src/config");
const { syncPeerOnce } = require("../src/federation");
const { encodeCursor } = require("../src/utils");

const config = loadConfig();
const testPeerPort = Number(process.env.FEDERATION_TEST_PEER_PORT || "19091");
const testPeerUrl = `http://127.0.0.1:${testPeerPort}`;
const testGid = process.env.FEDERATION_TEST_GID || "Z29vZGdtest";
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
    console.log("FAIL federation_cursor_durability");
    console.log("  DATABASE_URL is required");
    process.exit(1);
  }

  const storage = createStorage({ config, redis: null });
  const db = new Client({ connectionString: config.databaseUrl });
  await db.connect();

  const newerCursor = encodeCursor({ ts: now + 10, eid: uuidv4() });
  const olderCursor = encodeCursor({ ts: now - 10, eid: uuidv4() });

  const peerServer = http.createServer((req, res) => {
    if (!req.url.startsWith("/v1/events:fetch")) {
      res.writeHead(404);
      res.end("not_found");
      return;
    }

    if (req.headers["x-relay-peer-token"] !== peerToken) {
      res.writeHead(403);
      res.end(JSON.stringify({ error: "invalid_token" }));
      return;
    }

    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ events: [], next_cursor: olderCursor }));
  });

  await new Promise((resolve) => peerServer.listen(testPeerPort, resolve));

  try {
    await db.query("DELETE FROM federation_cursors WHERE peer_url = $1 AND gid = $2", [
      testPeerUrl,
      testGid,
    ]);
    await storage.setFederationCursor(testPeerUrl, testGid, newerCursor);

    await syncPeerOnce({
      peerUrl: testPeerUrl,
      gid: testGid,
      peerToken,
      config: {
        ...config,
        federationAllowCursorRegression: false,
      },
      storage,
    });

    const result = await db.query(
      "SELECT cursor FROM federation_cursors WHERE peer_url = $1 AND gid = $2",
      [testPeerUrl, testGid]
    );
    const stored = result.rows[0] && result.rows[0].cursor;
    if (stored === newerCursor) {
      console.log("PASS federation_cursor_durability");
    } else {
      failed += 1;
      console.log("FAIL federation_cursor_durability");
      console.log(`  expected: ${newerCursor}`);
      console.log(`  got: ${stored}`);
    }
  } catch (err) {
    failed += 1;
    console.log("FAIL federation_cursor_durability");
    console.log(`  error: ${err.message || String(err)}`);
  } finally {
    peerServer.close();
    await storage.close();
    await db.end();
  }

  if (failed > 0) {
    process.exit(1);
  }
}

run();
