#!/usr/bin/env node

const fs = require("fs");
const os = require("os");
const path = require("path");
const { spawn } = require("child_process");
const { createRegistryHeartbeatClient } = require("../../relay/src/registry_heartbeat_client");

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

async function fetchRelayDirectory(baseUrl) {
  const res = await fetch(`${baseUrl}/v1/registry/relays`);
  const payload = await res.json().catch(() => ({}));
  return { res, payload };
}

async function waitForCondition(check, { timeoutMs = 4000, intervalMs = 100 } = {}) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const value = await check();
    if (value) return value;
    await sleep(intervalMs);
  }
  return null;
}

function startHeartbeatClient({ registryUrl, sharedSecret, relayId, endpoint, region = "compat-test" }) {
  const logs = [];
  const client = createRegistryHeartbeatClient({
    config: {
      registryHeartbeatEnabled: true,
      registryUrl,
      registrySharedSecret: sharedSecret,
      registryAdvertisedEndpoint: endpoint,
      registryRelayRegion: region,
      registryHeartbeatIntervalSeconds: 60,
      federationSelfId: relayId,
      federationSelfUrl: endpoint,
    },
    onLog(level, event, meta = {}) {
      logs.push({ level, event, meta });
    },
  });
  client.start();
  return {
    stop() {
      client.stop();
    },
    logs,
  };
}

async function main() {
  const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "registry-heartbeat-compat-"));
  const dbPath = path.join(tempDir, "registry.sqlite");
  const port = 21000 + Math.floor(Math.random() * 1000);
  const baseUrl = `http://127.0.0.1:${port}`;
  const sharedSecret = "heartbeat-compat-secret";

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

    const goodRelayId = "relay-heartbeat-compat-good";
    const goodEndpoint = "https://relay-heartbeat-compat-good.test";
    const goodClient = startHeartbeatClient({
      registryUrl: baseUrl,
      sharedSecret,
      relayId: goodRelayId,
      endpoint: goodEndpoint,
    });
    try {
      const registered = await waitForCondition(async () => {
        const { res, payload } = await fetchRelayDirectory(baseUrl);
        if (!res.ok || !Array.isArray(payload.relays)) return null;
        return payload.relays.find((relay) => relay.relay_id === goodRelayId) || null;
      });
      assert(registered, "signed relay heartbeat client should register relay");
      assert(registered.endpoint === goodEndpoint, "registered relay endpoint should match heartbeat payload");
      assert(
        goodClient.logs.some((entry) => entry.event === "registry_heartbeat_ok"),
        "relay heartbeat client should log successful signed heartbeat"
      );
      console.log("PASS signed_heartbeat_compat_registers_relay");
    } finally {
      goodClient.stop();
    }

    const badRelayId = "relay-heartbeat-compat-bad";
    const badClient = startHeartbeatClient({
      registryUrl: baseUrl,
      sharedSecret: "wrong-shared-secret",
      relayId: badRelayId,
      endpoint: "https://relay-heartbeat-compat-bad.test",
    });
    try {
      const failureLogged = await waitForCondition(
        async () => badClient.logs.find((entry) => entry.event === "registry_heartbeat_failed") || null
      );
      assert(failureLogged, "invalid signed heartbeat should log failure");
      assert(
        Number(failureLogged.meta.status) === 401,
        "invalid signed heartbeat should fail with 401"
      );
      const { payload } = await fetchRelayDirectory(baseUrl);
      const relays = Array.isArray(payload.relays) ? payload.relays : [];
      assert(
        !relays.some((relay) => relay.relay_id === badRelayId),
        "relay signed with wrong secret should not register"
      );
      console.log("PASS signed_heartbeat_compat_rejects_wrong_secret");
    } finally {
      badClient.stop();
    }
  } finally {
    child.kill("SIGTERM");
    await new Promise((resolve) => child.once("exit", resolve));
    fs.rmSync(tempDir, { recursive: true, force: true });
  }
}

main().catch((err) => {
  console.error("FAIL signed_heartbeat_compat", err && err.stack ? err.stack : String(err));
  process.exit(1);
});
