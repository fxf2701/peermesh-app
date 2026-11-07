#!/usr/bin/env node
/* eslint-disable no-console */

const fs = require("fs");
const os = require("os");
const path = require("path");
const crypto = require("crypto");
const { execFileSync } = require("child_process");
const { isDeepStrictEqual } = require("util");
const { Client } = require("pg");

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

function deepEqual(a, b) {
  return isDeepStrictEqual(a, b);
}

async function run() {
  const databaseUrl = process.env.DATABASE_URL;
  if (!databaseUrl) {
    throw new Error("DATABASE_URL is required");
  }

  const now = Math.floor(Date.now() / 1000);
  const gid = `Z29vZGd${uuidv4().slice(0, 6)}`;
  const event = {
    eid: uuidv4(),
    gid,
    ts: now,
    exp: now + 1200,
    body: {
      v: 1,
      enc: "AES-256-GCM",
      kid: `gk-${uuidv4().slice(0, 8)}`,
      cty: "peermesh/app-event+json",
      nonce: Buffer.from(`nonce-${uuidv4()}`).toString("base64url"),
      ct: Buffer.from(`ciphertext-${uuidv4()}`).toString("base64url"),
      tag: Buffer.from(`tag-${uuidv4().slice(0, 12)}`).toString("base64url"),
    },
    sig: Buffer.from(`sig-${uuidv4()}`).toString("base64url"),
  };

  const outputDir = fs.mkdtempSync(path.join(os.tmpdir(), "relay-export-test-"));
  const outputPath = path.join(outputDir, "events.jsonl");
  const client = new Client({ connectionString: databaseUrl });
  await client.connect();

  try {
    await client.query(
      `INSERT INTO events (eid, gid, ts, exp, body, sig)
       VALUES ($1, $2, $3, $4, $5, $6)`,
      [event.eid, event.gid, event.ts, event.exp, event.body, event.sig]
    );

    execFileSync(
      process.execPath,
      [path.join(__dirname, "export-events.js")],
      {
        env: {
          ...process.env,
          DATABASE_URL: databaseUrl,
          EXPORT_GID: gid,
          EXPORT_OUTPUT: outputPath,
        },
        stdio: "pipe",
      }
    );

    const lines = fs
      .readFileSync(outputPath, "utf8")
      .split("\n")
      .filter((line) => line.trim().length > 0);
    if (lines.length !== 1) {
      throw new Error(`expected 1 exported event, got ${lines.length}`);
    }

    const exported = JSON.parse(lines[0]);
    const checksumPath = `${outputPath}.sha256`;
    if (!fs.existsSync(checksumPath)) {
      throw new Error("checksum file missing");
    }

    const ok =
      exported.eid === event.eid &&
      exported.gid === event.gid &&
      exported.ts === event.ts &&
      exported.exp === event.exp &&
      exported.sig === event.sig &&
      deepEqual(exported.body, event.body);

    if (!ok) {
      throw new Error(
        `exported event mismatch exported=${JSON.stringify(exported)} expected=${JSON.stringify(event)}`
      );
    }

    console.log("PASS export_events_encrypted_body_round_trip");
  } finally {
    await client.end();
  }
}

run().catch((err) => {
  console.log("FAIL export_events_encrypted_body_round_trip");
  console.log(`  error: ${err && err.message ? err.message : String(err)}`);
  process.exit(1);
});
