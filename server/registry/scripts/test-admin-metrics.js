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
  const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "registry-admin-metrics-"));
  const dbPath = path.join(tempDir, "registry.sqlite");
  const port = 19000 + Math.floor(Math.random() * 1000);
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

    for (const relayId of ["relay-a", "relay-b"]) {
      const trust = await api(baseUrl, `/admin/api/relays/${encodeURIComponent(relayId)}/trust`, {
        method: "PATCH",
        body: { trust_level: "trusted", reason: "metrics seed trust" },
        cookie,
        csrfToken,
      });
      assert(trust.res.status === 200, `trust update failed for ${relayId}`);
      const federation = await api(baseUrl, `/admin/api/relays/${encodeURIComponent(relayId)}/federation`, {
        method: "PATCH",
        body: { federation_enabled: true, reason: "metrics seed federation" },
        cookie,
        csrfToken,
      });
      assert(federation.res.status === 200, `federation update failed for ${relayId}`);
    }

    const certificateIssue = await api(baseUrl, "/admin/api/certificates", {
      method: "POST",
      body: { relay_id: "relay-a", reason: "metrics seed certificate" },
      cookie,
      csrfToken,
    });
    assert(certificateIssue.res.status === 201, "certificate issue should succeed");
    const certificateId = certificateIssue.payload.data.item.id;
    const certificateRevoke = await api(baseUrl, `/admin/api/certificates/${encodeURIComponent(certificateId)}/revoke`, {
      method: "PATCH",
      body: { reason: "metrics seed revoke" },
      cookie,
      csrfToken,
    });
    assert(certificateRevoke.res.status === 200, "certificate revoke should succeed");

    const policyUpdate = await api(baseUrl, "/admin/api/policy", {
      method: "PATCH",
      body: { minimum_trust_level_for_federation: "trusted", reason: "metrics seed policy" },
      cookie,
      csrfToken,
    });
    assert(policyUpdate.res.status === 200, "policy update should succeed");

    const sessionIssued = await api(baseUrl, "/v1/registry/federation/sessions", {
      method: "POST",
      body: { relay_id: "relay-a", requested_gids: ["group-1"] },
    });
    assert(sessionIssued.res.status === 201, "federation session issue should succeed");

    const sessionDenied = await api(baseUrl, "/v1/registry/federation/sessions", {
      method: "POST",
      body: { relay_id: "relay-missing", requested_gids: ["group-2"] },
    });
    assert(sessionDenied.res.status >= 400, "federation session denial should occur");

    const page = await fetch(`${baseUrl}/admin/metrics`, {
      headers: { Cookie: cookie },
    });
    const pageHtml = await page.text();
    assert(page.status === 200, "metrics page should be served");
    assert(pageHtml.includes("Operational Metrics"), "metrics page should contain metrics panel");
    console.log("PASS admin_metrics_page");

    const metrics = await api(baseUrl, "/admin/api/metrics", { cookie });
    assert(metrics.res.status === 200, "metrics api should succeed");
    const data = metrics.payload.data || {};
    assert(Number.isFinite(data.heartbeats_per_minute), "metrics should include heartbeats_per_minute");
    assert(Number.isFinite(data.heartbeats_per_second), "metrics should include heartbeats_per_second");
    assert(data.certificate_issues >= 1, "metrics should count certificate issues");
    assert(data.certificate_revocations >= 1, "metrics should count certificate revocations");
    assert(data.policy_updates >= 1, "metrics should count policy updates");
    assert(data.federation_session_issues >= 1, "metrics should count federation session issues");
    assert(data.federation_session_denials >= 1, "metrics should count federation session denials");
    assert(data.federation_sessions_active >= 1, "metrics should count active federation sessions");
    assert(Number.isFinite(data.federation_sessions_expired), "metrics should include expired session count");
    assert(Number.isFinite(data.federation_sessions_revoked), "metrics should include revoked session count");
    console.log("PASS admin_metrics_api");
  } finally {
    child.kill("SIGTERM");
    await new Promise((resolve) => child.once("exit", resolve));
    fs.rmSync(tempDir, { recursive: true, force: true });
  }
}

main().catch((err) => {
  console.error("FAIL admin_metrics", err && err.stack ? err.stack : String(err));
  process.exit(1);
});
