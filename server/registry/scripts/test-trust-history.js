#!/usr/bin/env node

const fs = require("fs");
const os = require("os");
const path = require("path");
const { spawn } = require("child_process");

const TEST_ADMIN_PASSWORD = "admin1234";
const TEST_ADMIN_PASSWORD_HASH = "scrypt$16384$8$1$c880d2df7323d9fdfc95083fd301508c$2b4a5142d0b35d012e7df68ae6e8f368d81bf48af40f8cb594fe7ad379cd9ca2";

function assert(condition, message) {
  if (!condition) throw new Error(message);
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
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  throw new Error("registry server did not become healthy");
}

async function api(baseUrl, pathName, { method = "GET", body, cookie, csrfToken } = {}) {
  const res = await fetch(`${baseUrl}${pathName}`, {
    method,
    headers: {
      "Content-Type": "application/json",
      ...(cookie ? { Cookie: cookie } : {}),
      ...(csrfToken ? { "X-CSRF-Token": csrfToken } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  const payload = await res.json().catch(() => ({}));
  return { res, payload };
}

async function main() {
  const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "registry-trust-history-"));
  const dbPath = path.join(tempDir, "registry.sqlite");
  const port = 22000 + Math.floor(Math.random() * 1000);
  const baseUrl = `http://127.0.0.1:${port}`;

  const child = spawn(process.execPath, ["src/index.js"], {
    cwd: path.join(__dirname, ".."),
    env: {
      ...process.env,
      HOST: "127.0.0.1",
      PORT: String(port),
      REGISTRY_DB_PATH: dbPath,
      REGISTRY_SHARED_SECRET: "test-registry-secret",
      REGISTRY_REQUIRE_SIGNATURE: "0",
      REGISTRY_ADMIN_USERNAME: "admin",
      REGISTRY_ADMIN_PASSWORD_HASH: TEST_ADMIN_PASSWORD_HASH,
      REGISTRY_FEDERATION_SESSION_SIGNING_SECRET: "test-session-secret",
    },
    stdio: "ignore",
  });

  try {
    await waitForHealth(baseUrl, child);

    for (const relayId of ["relay-a", "relay-b"]) {
      const heartbeat = await api(baseUrl, "/v1/registry/relays:heartbeat", {
        method: "POST",
        body: {
          relay_id: relayId,
          public_key: `${relayId}-public-key`,
          endpoint: `https://${relayId}.test`,
          region: "test",
          capabilities: ["store_forward", "capsule_push", "capsule_pull", "federation"],
          version: "test",
        },
      });
      assert(heartbeat.res.status === 200, `heartbeat failed for ${relayId}`);
    }

    const login = await api(baseUrl, "/admin/api/auth/login", {
      method: "POST",
      body: { username: "admin", password: TEST_ADMIN_PASSWORD },
    });
    assert(login.res.status === 200, "admin login should succeed");
    const cookie = login.res.headers.get("set-cookie")?.split(";")[0];
    const csrfToken = login.payload.data?.csrf_token;
    assert(cookie, "admin login should set session cookie");
    assert(csrfToken, "admin login should return csrf token");

    const promoteVerified = await api(baseUrl, "/admin/api/relays/relay-a/trust", {
      method: "PATCH",
      body: { trust_level: "verified", reason: "identity reviewed" },
      cookie,
      csrfToken,
    });
    assert(promoteVerified.res.status === 200, "relay-a verify should succeed");

    const promoteTrusted = await api(baseUrl, "/admin/api/relays/relay-a/trust", {
      method: "PATCH",
      body: { trust_level: "trusted", reason: "federation approved" },
      cookie,
      csrfToken,
    });
    assert(promoteTrusted.res.status === 200, "relay-a trust promotion should succeed");

    const demoteCommunity = await api(baseUrl, "/admin/api/relays/relay-b/trust", {
      method: "PATCH",
      body: { trust_level: "community", reason: "reset baseline" },
      cookie,
      csrfToken,
    });
    assert(demoteCommunity.res.status === 200, "relay-b demotion should succeed");

    const allHistory = await api(baseUrl, "/admin/api/trust", { cookie });
    assert(allHistory.res.status === 200, "trust history list should succeed");
    assert(Array.isArray(allHistory.payload.data.items), "trust history items should be returned");
    assert(allHistory.payload.data.total >= 3, "trust history should include seeded transitions");
    assert(
      allHistory.payload.data.items.some((item) => item.relay_id === "relay-a" && item.resulting_trust_level === "trusted"),
      "trust history should include relay-a trusted transition"
    );
    assert(
      allHistory.payload.data.items.some((item) => item.relay_id === "relay-b" && item.resulting_trust_level === "community"),
      "trust history should include relay-b community transition"
    );
    console.log("PASS trust_history_list");

    const relayFiltered = await api(baseUrl, "/admin/api/trust?relay_id=relay-a", { cookie });
    assert(relayFiltered.res.status === 200, "filtered trust history should succeed");
    assert(relayFiltered.payload.data.items.length === 2, "relay-a should have exactly two trust transitions");
    assert(
      relayFiltered.payload.data.items.every((item) => item.relay_id === "relay-a"),
      "filtered trust history should only include relay-a"
    );
    assert(
      relayFiltered.payload.data.items[0].current_trust_level === "trusted",
      "filtered trust history should include current trust level"
    );
    console.log("PASS trust_history_filtered");
  } finally {
    child.kill("SIGTERM");
    await new Promise((resolve) => child.once("exit", resolve));
    fs.rmSync(tempDir, { recursive: true, force: true });
  }
}

main().catch((err) => {
  console.error("FAIL trust_history", err && err.stack ? err.stack : String(err));
  process.exit(1);
});
