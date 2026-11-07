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
  const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "registry-admin-pagination-"));
  const dbPath = path.join(tempDir, "registry.sqlite");
  const port = 24000 + Math.floor(Math.random() * 1000);
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
      REGISTRY_FEDERATION_MIN_TRUST_LEVEL: "trusted",
    },
    stdio: "ignore",
  });

  try {
    await waitForHealth(baseUrl, child);

    for (const relayId of ["relay-c", "relay-a", "relay-b"]) {
      const heartbeat = await api(baseUrl, "/v1/registry/relays:heartbeat", {
        method: "POST",
        body: {
          relay_id: relayId,
          public_key: `${relayId}-public-key`,
          endpoint: `https://${relayId}.test`,
          region: relayId.endsWith("a") ? "north" : "south",
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

    for (const relayId of ["relay-a", "relay-b"]) {
      const trust = await api(baseUrl, `/admin/api/relays/${encodeURIComponent(relayId)}/trust`, {
        method: "PATCH",
        body: { trust_level: "trusted", reason: "seed trusted" },
        cookie,
        csrfToken,
      });
      assert(trust.res.status === 200, `trust update failed for ${relayId}`);
      const federation = await api(baseUrl, `/admin/api/relays/${encodeURIComponent(relayId)}/federation`, {
        method: "PATCH",
        body: { federation_enabled: true, reason: "seed federation" },
        cookie,
        csrfToken,
      });
      assert(federation.res.status === 200, `federation enable failed for ${relayId}`);
    }

    const relaysPage = await api(baseUrl, "/admin/api/relays?limit=2&offset=0&sort=relay_id&order=asc", { cookie });
    assert(relaysPage.res.status === 200, "relays page should succeed");
    assert(relaysPage.payload.data.items.length === 2, "relay page should honor limit");
    assert(relaysPage.payload.data.total >= 3, "relay page should report total");
    assert(relaysPage.payload.data.items[0].relay_id === "relay-a", "relay sort asc should start with relay-a");
    console.log("PASS admin_pagination_relays");

    const sessionOne = await api(baseUrl, "/v1/registry/federation/sessions", {
      method: "POST",
      body: { relay_id: "relay-a", requested_gids: ["group-1"] },
    });
    const sessionTwo = await api(baseUrl, "/v1/registry/federation/sessions", {
      method: "POST",
      body: { relay_id: "relay-b", requested_gids: ["group-2"] },
    });
    assert(sessionOne.res.status === 201, "session one should succeed");
    assert(sessionTwo.res.status === 201, "session two should succeed");

    const sessionsPage = await api(baseUrl, "/admin/api/federation/sessions?limit=1&offset=0&sort=source_relay_id&order=asc", { cookie });
    assert(sessionsPage.res.status === 200, "sessions page should succeed");
    assert(sessionsPage.payload.data.items.length === 1, "sessions page should honor limit");
    assert(sessionsPage.payload.data.total >= 2, "sessions page should report total");
    assert(sessionsPage.payload.data.items[0].source_relay_id === "relay-a", "session sort should honor source relay asc");
    console.log("PASS admin_pagination_federation_sessions");

    const auditPage = await api(baseUrl, "/admin/api/audit?limit=2&offset=0&sort=actor&order=asc", { cookie });
    assert(auditPage.res.status === 200, "audit page should succeed");
    assert(auditPage.payload.data.items.length === 2, "audit page should honor limit");
    assert(auditPage.payload.data.total >= 4, "audit page should report total");
    console.log("PASS admin_pagination_audit");
  } finally {
    child.kill("SIGTERM");
    await new Promise((resolve) => child.once("exit", resolve));
    fs.rmSync(tempDir, { recursive: true, force: true });
  }
}

main().catch((err) => {
  console.error("FAIL admin_pagination", err && err.stack ? err.stack : String(err));
  process.exit(1);
});
