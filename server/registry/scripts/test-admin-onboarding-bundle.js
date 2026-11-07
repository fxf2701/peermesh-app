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
  const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "registry-admin-onboarding-bundle-"));
  const dbPath = path.join(tempDir, "registry.sqlite");
  const port = 25000 + Math.floor(Math.random() * 1000);
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
      REGISTRY_CLIENT_ACCESS_GRANT_SIGNING_SECRET: "test-grant-secret",
      REGISTRY_FEDERATION_SESSION_SIGNING_SECRET: "test-federation-secret",
    },
    stdio: "ignore",
  });

  try {
    await waitForHealth(baseUrl, child);

    const login = await api(baseUrl, "/admin/api/auth/login", {
      method: "POST",
      body: { username: "admin", password: TEST_ADMIN_PASSWORD },
    });
    assert(login.res.status === 200, "admin login should succeed");
    const cookie = login.res.headers.get("set-cookie")?.split(";")[0];
    const csrfToken = login.payload.data?.csrf_token;
    assert(cookie, "admin login should set session cookie");
    assert(csrfToken, "admin login should return csrf token");

    const noReason = await api(baseUrl, "/admin/api/onboarding/relay-bundle", {
      method: "POST",
      body: {
        relay_id: "pending-relay",
        include_heartbeat: true,
      },
      cookie,
      csrfToken,
    });
    assert(noReason.res.status === 400, "onboarding bundle export should require reason");
    assert(noReason.payload.error.code === "reason_required", "onboarding reason_required code should match");
    console.log("PASS admin_onboarding_bundle_reason_guard");

    const exported = await api(baseUrl, "/admin/api/onboarding/relay-bundle", {
      method: "POST",
      body: {
        relay_id: "pending-relay",
        include_heartbeat: true,
        include_client_access: true,
        include_federation_sessions: true,
        reason: "prepare onboarding handoff",
      },
      cookie,
      csrfToken,
    });
    assert(exported.res.status === 200, "onboarding bundle export should succeed");
    const bundle = exported.payload.data;
    assert(bundle.filename === "pending-relay-registry-onboarding.env", "bundle filename should be stable");
    assert(bundle.registry_url === baseUrl, "bundle should derive registry URL from request");
    assert(bundle.content.includes("REGISTRY_SHARED_SECRET=test-registry-secret"), "bundle should include registry shared secret");
    assert(bundle.content.includes("REGISTRY_CLIENT_ACCESS_GRANT_SIGNING_SECRET=test-grant-secret"), "bundle should include client access signing secret");
    assert(bundle.content.includes("REGISTRY_FEDERATION_SESSION_SIGNING_SECRET=test-federation-secret"), "bundle should include federation session signing secret");
    assert(bundle.content.includes("CLIENT_RELAY_SESSION_BINDING_SECRET="), "bundle should instruct relay-local binding secret");
    assert(bundle.content.includes("FEDERATION_MTLS_CERT_PATH"), "bundle should mention mTLS operator-managed paths");
    console.log("PASS admin_onboarding_bundle_export");

    const audit = await api(baseUrl, "/admin/api/audit", {
      method: "GET",
      cookie,
    });
    assert(audit.res.status === 200, "audit listing should succeed");
    const exportedAudit = (audit.payload.data.items || []).find((item) => item.action === "relay_onboarding_bundle_exported");
    assert(exportedAudit, "audit should record onboarding bundle export");
    assert(exportedAudit.reason === "prepare onboarding handoff", "audit should retain export reason");
    assert(exportedAudit.resource_id === "pending-relay", "audit should tag the target relay id");
    console.log("PASS admin_onboarding_bundle_audit");
  } finally {
    child.kill("SIGTERM");
    await new Promise((resolve) => child.once("exit", resolve));
    fs.rmSync(tempDir, { recursive: true, force: true });
  }
}

main().catch((err) => {
  console.error("FAIL admin_onboarding_bundle", err && err.stack ? err.stack : String(err));
  process.exit(1);
});
