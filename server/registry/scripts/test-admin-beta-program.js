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
  const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "registry-admin-beta-program-"));
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
      REGISTRY_BETA_PROGRAM_JSON: JSON.stringify({
        enabled: true,
        season: "Bootstrap Season",
        latestVersion: "0.9.3",
        minimumSupportedVersion: "0.9.0",
        changelog: ["Bootstrap item"],
      }),
    },
    stdio: "ignore",
  });

  try {
    await waitForHealth(baseUrl, child);

    const publicBootstrap = await api(baseUrl, "/v1/registry/client/beta-program");
    assert(publicBootstrap.res.status === 200, "public beta program endpoint should succeed");
    assert(publicBootstrap.payload.beta_program?.enabled === true, "bootstrap beta enabled should be true");
    assert(publicBootstrap.payload.beta_program?.season === "Bootstrap Season", "bootstrap season should match env");
    console.log("PASS admin_beta_program_bootstrap");

    const login = await api(baseUrl, "/admin/api/auth/login", {
      method: "POST",
      body: { username: "admin", password: TEST_ADMIN_PASSWORD },
    });
    assert(login.res.status === 200, "admin login should succeed");
    const cookie = login.res.headers.get("set-cookie")?.split(";")[0];
    const csrfToken = login.payload.data?.csrf_token;
    assert(cookie, "admin login should set session cookie");
    assert(csrfToken, "admin login should return csrf token");

    const adminRead = await api(baseUrl, "/admin/api/beta-program", { cookie });
    assert(adminRead.res.status === 200, "admin beta program read should succeed");
    assert(adminRead.payload.data.enabled === true, "admin beta program should reflect bootstrap config");
    console.log("PASS admin_beta_program_read");

    const noReason = await api(baseUrl, "/admin/api/beta-program", {
      method: "PATCH",
      body: { enabled: false },
      cookie,
      csrfToken,
    });
    assert(noReason.res.status === 400, "beta program patch should require reason");
    assert(noReason.payload.error.code === "reason_required", "beta program patch should use reason_required");
    console.log("PASS admin_beta_program_reason_guard");

    const update = await api(baseUrl, "/admin/api/beta-program", {
      method: "PATCH",
      body: {
        enabled: false,
        season: "Closed Season",
        endsAt: "2026-08-31T23:59:59Z",
        latestVersion: "1.0.0",
        minimumSupportedVersion: "0.9.5",
        changelog: ["Item A", "Item B"],
        reason: "Close beta intake temporarily",
      },
      cookie,
      csrfToken,
    });
    assert(update.res.status === 200, "beta program patch should succeed");
    assert(update.payload.data.enabled === false, "updated beta enabled should be false");
    assert(update.payload.data.season === "Closed Season", "updated season should match");
    assert(update.payload.data.changelog.length === 2, "updated changelog should persist");
    console.log("PASS admin_beta_program_update");

    const publicUpdated = await api(baseUrl, "/v1/registry/client/beta-program");
    assert(publicUpdated.res.status === 200, "updated public beta endpoint should succeed");
    assert(publicUpdated.payload.beta_program?.enabled === false, "public beta endpoint should reflect admin update");
    assert(publicUpdated.payload.beta_program?.latestVersion === "1.0.0", "public latestVersion should reflect admin update");
    console.log("PASS admin_beta_program_public_reflects_update");
  } finally {
    child.kill("SIGTERM");
    await new Promise((resolve) => child.once("exit", resolve));
    fs.rmSync(tempDir, { recursive: true, force: true });
  }
}

main().catch((err) => {
  console.error("FAIL admin_beta_program", err && err.stack ? err.stack : String(err));
  process.exit(1);
});
