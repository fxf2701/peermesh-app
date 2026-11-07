#!/usr/bin/env node

const fs = require("fs");
const os = require("os");
const path = require("path");
const { spawn } = require("child_process");
const { signRegistryRequest, signLegacyRegistryRequest } = require("../src/auth");

const TEST_ADMIN_PASSWORD_HASH = "scrypt$16384$8$1$c880d2df7323d9fdfc95083fd301508c$2b4a5142d0b35d012e7df68ae6e8f368d81bf48af40f8cb594fe7ad379cd9ca2";

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function waitForHealth(baseUrl, child, attempts = 50) {
  for (let i = 0; i < attempts; i += 1) {
    if (child.exitCode !== null) {
      throw new Error(`registry server exited early with code ${child.exitCode}`);
    }
    try {
      const res = await fetch(`${baseUrl}/healthz`);
      if (res.ok) return;
    } catch (_) {
      // ignore startup races
    }
    await sleep(100);
  }
  throw new Error("registry server did not become healthy");
}

async function request(baseUrl, pathName, { method = "GET", rawBody = "", headers = {} } = {}) {
  const res = await fetch(`${baseUrl}${pathName}`, {
    method,
    headers,
    body: rawBody || undefined,
  });
  const payload = await res.json().catch(() => ({}));
  return { res, payload };
}

function heartbeatBody(relayId) {
  return JSON.stringify({
    relay_id: relayId,
    public_key: `${relayId}-public-key`,
    endpoint: `https://${relayId}.test`,
    region: "test",
    capabilities: ["store_forward", "capsule_push", "capsule_pull", "federation"],
    version: "test",
  });
}

function signedHeaders({ secret, method, pathName, timestamp, rawBody }) {
  return {
    "Content-Type": "application/json",
    "X-Registry-Timestamp": String(timestamp),
    "X-Registry-Signature": signRegistryRequest({
      secret,
      method,
      path: pathName,
      timestamp: String(timestamp),
      rawBody,
    }),
  };
}

function legacySignedHeaders({ secret, method, pathName, timestamp, nonce, rawBody }) {
  return {
    "Content-Type": "application/json",
    "X-Registry-Timestamp": String(timestamp),
    "X-Registry-Nonce": nonce,
    "X-Registry-Signature": signLegacyRegistryRequest({
      secret,
      method,
      path: pathName,
      timestamp: String(timestamp),
      nonce,
      rawBody,
    }),
  };
}

async function main() {
  const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "registry-signing-"));
  const dbPath = path.join(tempDir, "registry.sqlite");
  const port = 20000 + Math.floor(Math.random() * 1000);
  const baseUrl = `http://127.0.0.1:${port}`;
  const sharedSecret = "test-registry-secret";
  const method = "POST";
  const pathName = "/v1/registry/relays:heartbeat";

  const child = spawn(process.execPath, ["src/index.js"], {
    cwd: path.join(__dirname, ".."),
    env: {
      ...process.env,
      HOST: "127.0.0.1",
      PORT: String(port),
      REGISTRY_DB_PATH: dbPath,
      REGISTRY_SHARED_SECRET: sharedSecret,
      REGISTRY_REQUIRE_SIGNATURE: "1",
      REGISTRY_ADMIN_USERNAME: "admin",
      REGISTRY_ADMIN_PASSWORD_HASH: TEST_ADMIN_PASSWORD_HASH,
      REGISTRY_FEDERATION_SESSION_SIGNING_SECRET: "test-session-secret",
    },
    stdio: "ignore",
  });

  try {
    await waitForHealth(baseUrl, child);

    const validBody = heartbeatBody("relay-signing-valid");
    const validTimestamp = Math.floor(Date.now() / 1000);
    const valid = await request(baseUrl, pathName, {
      method,
      rawBody: validBody,
      headers: signedHeaders({
        secret: sharedSecret,
        method,
        pathName,
        timestamp: validTimestamp,
        rawBody: validBody,
      }),
    });
    assert(valid.res.status === 200, "valid v1 signature should succeed");
    console.log("PASS registry_signing_valid_v1");

    const invalidSignatureBody = heartbeatBody("relay-signing-invalid");
    const invalidSignatureTimestamp = Math.floor(Date.now() / 1000);
    const invalidSignature = await request(baseUrl, pathName, {
      method,
      rawBody: invalidSignatureBody,
      headers: {
        "Content-Type": "application/json",
        "X-Registry-Timestamp": String(invalidSignatureTimestamp),
        "X-Registry-Signature": "00".repeat(32),
      },
    });
    assert(invalidSignature.res.status === 401, "invalid signature should be rejected");
    assert(invalidSignature.payload.error.code === "invalid_signature", "invalid signature code should match");
    console.log("PASS registry_signing_invalid_signature");

    const skewBody = heartbeatBody("relay-signing-skew");
    const skewTimestamp = Math.floor(Date.now() / 1000) - 1000;
    const skew = await request(baseUrl, pathName, {
      method,
      rawBody: skewBody,
      headers: signedHeaders({
        secret: sharedSecret,
        method,
        pathName,
        timestamp: skewTimestamp,
        rawBody: skewBody,
      }),
    });
    assert(skew.res.status === 401, "timestamp skew should be rejected");
    assert(skew.payload.error.code === "timestamp_out_of_window", "timestamp skew code should match");
    console.log("PASS registry_signing_timestamp_skew");

    const tamperOriginalBody = heartbeatBody("relay-signing-tamper");
    const tamperSentBody = JSON.stringify({
      ...JSON.parse(tamperOriginalBody),
      endpoint: "https://tampered.example.test",
    });
    const tamperTimestamp = Math.floor(Date.now() / 1000);
    const tamper = await request(baseUrl, pathName, {
      method,
      rawBody: tamperSentBody,
      headers: signedHeaders({
        secret: sharedSecret,
        method,
        pathName,
        timestamp: tamperTimestamp,
        rawBody: tamperOriginalBody,
      }),
    });
    assert(tamper.res.status === 401, "tampered body should be rejected");
    assert(tamper.payload.error.code === "invalid_signature", "tampered body code should match");
    console.log("PASS registry_signing_body_tamper");

    const replayBody = heartbeatBody("relay-signing-replay-v1");
    const replayTimestamp = Math.floor(Date.now() / 1000);
    const replayHeaders = signedHeaders({
      secret: sharedSecret,
      method,
      pathName,
      timestamp: replayTimestamp,
      rawBody: replayBody,
    });
    const replayOne = await request(baseUrl, pathName, {
      method,
      rawBody: replayBody,
      headers: replayHeaders,
    });
    const replayTwo = await request(baseUrl, pathName, {
      method,
      rawBody: replayBody,
      headers: replayHeaders,
    });
    assert(replayOne.res.status === 200, "first v1 signed request should succeed");
    assert(replayTwo.res.status === 200, "replayed v1 signed request is currently accepted");
    console.log("PASS registry_signing_v1_replay_current_behavior");

    const legacyBody = heartbeatBody("relay-signing-legacy");
    const legacyTimestamp = Math.floor(Date.now() / 1000);
    const legacyHeaders = legacySignedHeaders({
      secret: sharedSecret,
      method,
      pathName,
      timestamp: legacyTimestamp,
      nonce: "legacy-replay-nonce-1",
      rawBody: legacyBody,
    });
    const legacyOne = await request(baseUrl, pathName, {
      method,
      rawBody: legacyBody,
      headers: legacyHeaders,
    });
    const legacyTwo = await request(baseUrl, pathName, {
      method,
      rawBody: legacyBody,
      headers: legacyHeaders,
    });
    assert(legacyOne.res.status === 200, "first legacy signed request should succeed");
    assert(legacyTwo.res.status === 401, "replayed legacy signed request should be rejected");
    assert(legacyTwo.payload.error.code === "nonce_replay", "legacy replay code should match");
    console.log("PASS registry_signing_legacy_nonce_replay");
  } finally {
    child.kill("SIGTERM");
    await new Promise((resolve) => child.once("exit", resolve));
    fs.rmSync(tempDir, { recursive: true, force: true });
  }
}

main().catch((err) => {
  console.error("FAIL registry_signing", err && err.stack ? err.stack : String(err));
  process.exit(1);
});
