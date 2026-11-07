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
  const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "registry-admin-reason-guards-"));
  const dbPath = path.join(tempDir, "registry.sqlite");
  const port = 23000 + Math.floor(Math.random() * 1000);
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

    const heartbeat = await api(baseUrl, "/v1/registry/relays:heartbeat", {
      method: "POST",
      body: {
        relay_id: "relay-a",
        public_key: "relay-a-public-key",
        endpoint: "https://relay-a.test",
        region: "test",
        capabilities: ["store_forward", "capsule_push", "capsule_pull", "federation"],
        version: "test",
      },
    });
    assert(heartbeat.res.status === 200, "heartbeat failed for relay-a");

    const login = await api(baseUrl, "/admin/api/auth/login", {
      method: "POST",
      body: { username: "admin", password: TEST_ADMIN_PASSWORD },
    });
    assert(login.res.status === 200, "admin login should succeed");
    const cookie = login.res.headers.get("set-cookie")?.split(";")[0];
    const csrfToken = login.payload.data?.csrf_token;
    assert(cookie, "admin login should set session cookie");
    assert(csrfToken, "admin login should return csrf token");

    const trustNoReason = await api(baseUrl, "/admin/api/relays/relay-a/trust", {
      method: "PATCH",
      body: { trust_level: "trusted" },
      cookie,
      csrfToken,
    });
    assert(trustNoReason.res.status === 400, "trust change should require reason");
    assert(trustNoReason.payload.error.code === "reason_required", "trust reason_required code should match");
    console.log("PASS admin_reason_guard_trust");

    const statusNoReason = await api(baseUrl, "/admin/api/relays/relay-a/status", {
      method: "PATCH",
      body: { status: "suspended" },
      cookie,
      csrfToken,
    });
    assert(statusNoReason.res.status === 400, "status change should require reason");
    assert(statusNoReason.payload.error.code === "reason_required", "status reason_required code should match");
    console.log("PASS admin_reason_guard_status");

    const certIssueNoReason = await api(baseUrl, "/admin/api/certificates", {
      method: "POST",
      body: { relay_id: "relay-a" },
      cookie,
      csrfToken,
    });
    assert(certIssueNoReason.res.status === 400, "certificate issuance should require reason");
    assert(certIssueNoReason.payload.error.code === "reason_required", "cert issue reason_required code should match");
    console.log("PASS admin_reason_guard_certificate_issue");

    const certIssue = await api(baseUrl, "/admin/api/certificates", {
      method: "POST",
      body: { relay_id: "relay-a", reason: "seed certificate" },
      cookie,
      csrfToken,
    });
    assert(certIssue.res.status === 201, "certificate issuance with reason should succeed");
    const certificateId = certIssue.payload.data.item.id;

    const certRenewNoReason = await api(baseUrl, `/admin/api/certificates/${encodeURIComponent(certificateId)}/renew`, {
      method: "PATCH",
      body: {},
      cookie,
      csrfToken,
    });
    assert(certRenewNoReason.res.status === 400, "certificate renewal should require reason");
    assert(certRenewNoReason.payload.error.code === "reason_required", "cert renew reason_required code should match");
    console.log("PASS admin_reason_guard_certificate_renew");

    const certRevokeNoReason = await api(baseUrl, `/admin/api/certificates/${encodeURIComponent(certificateId)}/revoke`, {
      method: "PATCH",
      body: {},
      cookie,
      csrfToken,
    });
    assert(certRevokeNoReason.res.status === 400, "certificate revoke should require reason");
    assert(certRevokeNoReason.payload.error.code === "reason_required", "cert revoke reason_required code should match");
    console.log("PASS admin_reason_guard_certificate_revoke");
  } finally {
    child.kill("SIGTERM");
    await new Promise((resolve) => child.once("exit", resolve));
    fs.rmSync(tempDir, { recursive: true, force: true });
  }
}

main().catch((err) => {
  console.error("FAIL admin_reason_guards", err && err.stack ? err.stack : String(err));
  process.exit(1);
});
