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

async function textRequest(baseUrl, pathName, { cookie } = {}) {
  const res = await fetch(`${baseUrl}${pathName}`, {
    headers: {
      ...(cookie ? { Cookie: cookie } : {}),
    },
  });
  const text = await res.text();
  return { res, text };
}

async function main() {
  const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "registry-audit-export-"));
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
      REGISTRY_FEDERATION_SESSION_SIGNING_SECRET: "test-session-secret",
      REGISTRY_FEDERATION_MIN_TRUST_LEVEL: "trusted",
      REGISTRY_FEDERATION_SESSION_TTL_SECONDS: "300",
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

    const trust = await api(baseUrl, "/admin/api/relays/relay-a/trust", {
      method: "PATCH",
      body: { trust_level: "trusted", reason: "audit export trust seed" },
      cookie,
      csrfToken,
    });
    assert(trust.res.status === 200, "trust update should succeed");

    const status = await api(baseUrl, "/admin/api/relays/relay-b/status", {
      method: "PATCH",
      body: { status: "suspended", reason: "audit export status seed" },
      cookie,
      csrfToken,
    });
    assert(status.res.status === 200, "status update should succeed");

    const sessionIssue = await api(baseUrl, "/v1/registry/federation/sessions", {
      method: "POST",
      body: { relay_id: "relay-a", requested_gids: ["group-1"] },
    });
    assert(sessionIssue.res.status === 201, "federation session issue should succeed");

    const csv = await textRequest(
      baseUrl,
      "/admin/api/audit/export?format=csv&sort=created_at&order=desc",
      { cookie }
    );
    assert(csv.res.status === 200, "csv export should succeed");
    assert((csv.res.headers.get("content-type") || "").includes("text/csv"), "csv export content-type should be csv");
    assert((csv.res.headers.get("content-disposition") || "").includes(".csv"), "csv export should send csv attachment");
    assert(csv.text.includes("\"actor\",\"action\",\"resource_type\"") || csv.text.includes("\"id\",\"created_at\""), "csv export should include header row");
    assert(csv.text.includes("relay_trust_promoted"), "csv export should include trust audit row");
    console.log("PASS audit_export_csv");

    const jsonl = await textRequest(
      baseUrl,
      "/admin/api/audit/export?format=jsonl&resource_type=relay&resource_id=relay-a&sort=created_at&order=desc",
      { cookie }
    );
    assert(jsonl.res.status === 200, "jsonl export should succeed");
    assert((jsonl.res.headers.get("content-type") || "").includes("application/x-ndjson"), "jsonl export content-type should be ndjson");
    const lines = jsonl.text.trim().split("\n").filter(Boolean).map((line) => JSON.parse(line));
    assert(lines.length >= 1, "jsonl export should include at least one line");
    assert(lines.every((entry) => entry.resource_type === "relay"), "jsonl export should honor resource_type filter");
    assert(lines.every((entry) => entry.resource_id === "relay-a"), "jsonl export should honor resource_id filter");
    assert(lines.some((entry) => entry.action === "relay_trust_promoted"), "jsonl export should include relay-a trust action");
    console.log("PASS audit_export_jsonl_filtered");

    const invalid = await textRequest(baseUrl, "/admin/api/audit/export?format=xml", { cookie });
    assert(invalid.res.status === 400, "invalid format should return 400");
    assert(invalid.text.includes("invalid_export_format"), "invalid format payload should include error code");
    console.log("PASS audit_export_invalid_format");
  } finally {
    child.kill("SIGTERM");
    await new Promise((resolve) => child.once("exit", resolve));
    fs.rmSync(tempDir, { recursive: true, force: true });
  }
}

main().catch((err) => {
  console.error("FAIL audit_export", err && err.stack ? err.stack : String(err));
  process.exit(1);
});
