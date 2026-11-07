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
  const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "registry-admin-fed-"));
  const dbPath = path.join(tempDir, "registry.sqlite");
  const port = 18000 + Math.floor(Math.random() * 1000);
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
      REGISTRY_FEDERATION_SESSION_TTL_SECONDS: "300",
      REGISTRY_FEDERATION_MAX_PEERS_PER_SESSION: "4",
    },
    stdio: "ignore",
  });

  try {
    await waitForHealth(baseUrl, child);

    const relays = [
      ["relay-a", "https://relay-a.test"],
      ["relay-b", "https://relay-b.test"],
      ["relay-c", "https://relay-c.test"],
    ];
    for (const [relayId, endpoint] of relays) {
      const heartbeat = await api(baseUrl, "/v1/registry/relays:heartbeat", {
        method: "POST",
        body: {
          relay_id: relayId,
          public_key: `${relayId}-public-key`,
          endpoint,
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
    const rawCookie = login.res.headers.get("set-cookie");
    assert(rawCookie, "admin login should set session cookie");
    const cookie = rawCookie.split(";")[0];
    const csrfToken = login.payload.data?.csrf_token;
    assert(csrfToken, "admin login should return csrf token");

    for (const relayId of ["relay-a", "relay-b", "relay-c"]) {
      const trust = await api(baseUrl, `/admin/api/relays/${encodeURIComponent(relayId)}/trust`, {
        method: "PATCH",
        body: { trust_level: "trusted", reason: "test trust promotion" },
        cookie,
        csrfToken,
      });
      assert(trust.res.status === 200, `trust update failed for ${relayId}`);

      const federation = await api(baseUrl, `/admin/api/relays/${encodeURIComponent(relayId)}/federation`, {
        method: "PATCH",
        body: { federation_enabled: true, reason: "test federation enable" },
        cookie,
        csrfToken,
      });
      assert(federation.res.status === 200, `federation enable failed for ${relayId}`);
    }

    const sessionOne = await api(baseUrl, "/v1/registry/federation/sessions", {
      method: "POST",
      body: { relay_id: "relay-a", requested_gids: ["group-1"] },
    });
    const sessionTwo = await api(baseUrl, "/v1/registry/federation/sessions", {
      method: "POST",
      body: { relay_id: "relay-b", requested_gids: ["group-2"] },
    });
    assert(sessionOne.res.status === 201, "session one issue should succeed");
    assert(sessionTwo.res.status === 201, "session two issue should succeed");

    const listResult = await api(baseUrl, "/admin/api/federation/sessions", { cookie });
    assert(listResult.res.status === 200, "session list should succeed");
    assert(Array.isArray(listResult.payload.data.items), "session list should return items");
    assert(listResult.payload.data.items.length >= 2, "expected seeded sessions in admin list");
    assert(listResult.payload.data.topology.edge_count >= 2, "topology snapshot should include edges");
    console.log("PASS admin_federation_sessions_list");

    const sessionId = sessionOne.payload.session.session_id;
    const detailResult = await api(
      baseUrl,
      `/admin/api/federation/sessions/${encodeURIComponent(sessionId)}`,
      { cookie }
    );
    assert(detailResult.res.status === 200, "session details should succeed");
    assert(detailResult.payload.data.item.session_id === sessionId, "details should match session id");
    console.log("PASS admin_federation_session_detail");

    const missingReason = await api(
      baseUrl,
      `/admin/api/federation/sessions/${encodeURIComponent(sessionId)}/revoke`,
      { method: "POST", body: {}, cookie, csrfToken }
    );
    assert(missingReason.res.status === 400, "session revoke should require reason");
    assert(missingReason.payload.error.code === "reason_required", "missing reason error should match");
    console.log("PASS admin_federation_session_revoke_reason_required");

    const revokeResult = await api(
      baseUrl,
      `/admin/api/federation/sessions/${encodeURIComponent(sessionId)}/revoke`,
      { method: "POST", body: { reason: "incident response" }, cookie, csrfToken }
    );
    assert(revokeResult.res.status === 200, "session revoke should succeed");
    assert(revokeResult.payload.data.item.status === "revoked", "revoked session should report revoked status");
    console.log("PASS admin_federation_session_revoke");

    const rebalanceMissingReason = await api(baseUrl, "/admin/api/federation/topology/rebalance", {
      method: "POST",
      body: {},
      cookie,
      csrfToken,
    });
    assert(rebalanceMissingReason.res.status === 400, "topology rebalance should require reason");
    console.log("PASS admin_federation_topology_rebalance_reason_required");

    const rebalanceResult = await api(baseUrl, "/admin/api/federation/topology/rebalance", {
      method: "POST",
      body: { reason: "policy reload" },
      cookie,
      csrfToken,
    });
    assert(rebalanceResult.res.status === 200, "topology rebalance should succeed");
    assert(rebalanceResult.payload.data.revoked_count >= 1, "topology rebalance should revoke active sessions");
    console.log("PASS admin_federation_topology_rebalance");
  } finally {
    child.kill("SIGTERM");
    await new Promise((resolve) => child.once("exit", resolve));
    fs.rmSync(tempDir, { recursive: true, force: true });
  }
}

main().catch((err) => {
  console.error("FAIL admin_federation", err && err.stack ? err.stack : String(err));
  process.exit(1);
});
