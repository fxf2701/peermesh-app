#!/usr/bin/env node

const fs = require("fs");
const os = require("os");
const path = require("path");
const { spawn } = require("child_process");
const { normalizeBetaProgramConfig } = require("../src/client_beta_program");

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

async function main() {
  const normalized = normalizeBetaProgramConfig({
    betaProgram: {
      enabled: true,
      season: " Beta Season 1 ",
      endsAt: "",
      latestVersion: "0.9.3",
      minimumSupportedVersion: "0.9.0",
      changelog: [" Nearby Rooms ", "", "Chat Moderation"],
    },
  });
  assert(normalized.enabled === true, "enabled should be preserved");
  assert(normalized.season === "Beta Season 1", "season should trim");
  assert(normalized.endsAt === null, "blank endsAt should normalize to null");
  assert(normalized.changelog.length === 2, "changelog should filter blanks");
  console.log("PASS client_beta_program_normalize");

  const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "registry-client-beta-program-"));
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
      REGISTRY_ADMIN_ALLOW_PLAINTEXT_PASSWORD: "1",
      REGISTRY_ADMIN_PASSWORD: "test-admin-password",
      REGISTRY_BETA_PROGRAM_JSON: JSON.stringify({
        enabled: true,
        season: "Beta Season 1",
        endsAt: null,
        latestVersion: "0.9.3",
        minimumSupportedVersion: "0.9.0",
        changelog: ["Nearby Rooms", "Chat Moderation"],
      }),
    },
    stdio: "ignore",
  });

  try {
    await waitForHealth(baseUrl, child);
    const res = await fetch(`${baseUrl}/v1/registry/client/beta-program`);
    const payload = await res.json();
    assert(res.status === 200, "beta program endpoint should succeed");
    assert(payload.ok === true, "beta program endpoint should return ok");
    assert(payload.beta_program?.enabled === true, "beta program should be enabled");
    assert(payload.beta_program?.season === "Beta Season 1", "beta program should echo season");
    assert(Array.isArray(payload.beta_program?.changelog), "beta program should include changelog");
    console.log("PASS client_beta_program_endpoint");
  } finally {
    child.kill("SIGTERM");
    fs.rmSync(tempDir, { recursive: true, force: true });
  }
}

main().catch((err) => {
  console.error(err);
  process.exitCode = 1;
});
