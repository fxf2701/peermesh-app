#!/usr/bin/env node

const fs = require("fs");
const os = require("os");
const path = require("path");
const { spawn, spawnSync } = require("child_process");

const TEST_ADMIN_PASSWORD = "admin1234";
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

async function api(baseUrl, pathName, { method = "GET", body, cookie } = {}) {
  const res = await fetch(`${baseUrl}${pathName}`, {
    method,
    headers: {
      "Content-Type": "application/json",
      ...(cookie ? { Cookie: cookie } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  const payload = await res.json().catch(() => ({}));
  return { res, payload };
}

function runPython(script, args = []) {
  const result = spawnSync("python3", ["-c", script, ...args], { encoding: "utf8" });
  if (result.status !== 0) {
    throw new Error(result.stderr || result.stdout || "python3 command failed");
  }
  return result.stdout.trim();
}

function createLegacyFixture(dbPath) {
  const script = `
import sqlite3, sys, json
db = sqlite3.connect(sys.argv[1])
db.executescript("""
CREATE TABLE relays (
  relay_id TEXT PRIMARY KEY,
  public_key TEXT NOT NULL,
  endpoint TEXT NOT NULL,
  region TEXT,
  capabilities_json TEXT NOT NULL DEFAULT '[]',
  version TEXT,
  last_seen INTEGER NOT NULL,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);

CREATE TABLE used_nonces (
  nonce TEXT PRIMARY KEY,
  seen_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL
);

CREATE TABLE registry_audit (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  actor TEXT NOT NULL,
  action TEXT NOT NULL,
  resource_type TEXT NOT NULL,
  resource_id TEXT,
  reason TEXT,
  created_at INTEGER NOT NULL
);
""")
db.execute(
  "INSERT INTO relays (relay_id, public_key, endpoint, region, capabilities_json, version, last_seen, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
  ("legacy-relay", "legacy-public-key", "https://legacy-relay.test", "legacy-region", json.dumps(["store_forward", "federation"]), "legacy-version", 1716900000, 1716800000, 1716850000)
)
db.execute(
  "INSERT INTO registry_audit (actor, action, resource_type, resource_id, reason, created_at) VALUES (?, ?, ?, ?, ?, ?)",
  ("legacy-admin", "legacy_action", "relay", "legacy-relay", "legacy reason", 1716900001)
)
db.commit()
db.close()
`;
  runPython(script, [dbPath]);
}

function inspectMigratedSchema(dbPath) {
  const script = `
import sqlite3, sys, json
db = sqlite3.connect(sys.argv[1])
def columns(table):
  return [row[1] for row in db.execute(f"PRAGMA table_info({table})").fetchall()]
def indexes(table):
  return [row[1] for row in db.execute(f"PRAGMA index_list({table})").fetchall()]
def table_exists(name):
  row = db.execute("SELECT name FROM sqlite_master WHERE type='table' AND name=?", (name,)).fetchone()
  return bool(row)
payload = {
  "relay_columns": columns("relays"),
  "audit_columns": columns("registry_audit"),
  "relay_indexes": indexes("relays"),
  "audit_indexes": indexes("registry_audit"),
  "has_relay_certificates": table_exists("relay_certificates"),
  "has_federation_sessions": table_exists("federation_sessions"),
  "has_registry_revocation_feed": table_exists("registry_revocation_feed"),
}
print(json.dumps(payload))
db.close()
`;
  return JSON.parse(runPython(script, [dbPath]));
}

async function main() {
  const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "registry-schema-migration-"));
  const dbPath = path.join(tempDir, "registry.sqlite");
  const port = 21000 + Math.floor(Math.random() * 1000);
  const baseUrl = `http://127.0.0.1:${port}`;

  createLegacyFixture(dbPath);

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

    const login = await api(baseUrl, "/admin/api/auth/login", {
      method: "POST",
      body: { username: "admin", password: TEST_ADMIN_PASSWORD },
    });
    assert(login.res.status === 200, "admin login should succeed after migration");
    const cookieHeader = login.res.headers.get("set-cookie");
    assert(cookieHeader, "admin login should return cookie");
    const cookie = cookieHeader.split(";")[0];

    const relays = await api(baseUrl, "/admin/api/relays", { cookie });
    assert(relays.res.status === 200, "relay directory should succeed after migration");
    const legacyRelay = (relays.payload.data.items || []).find((item) => item.relay_id === "legacy-relay");
    assert(legacyRelay, "legacy relay should remain visible after migration");
    assert(legacyRelay.trust_level === "community", "legacy relay trust_level should default to community");
    assert(legacyRelay.status === "active", "legacy relay status should default to active");
    assert(legacyRelay.federation_enabled === false, "legacy relay federation_enabled should default to false");
    console.log("PASS registry_schema_migration_legacy_relay_defaults");

    const audit = await api(baseUrl, "/admin/api/audit", { cookie });
    assert(audit.res.status === 200, "audit endpoint should succeed after migration");
    const legacyAudit = (audit.payload.data.items || []).find((item) => item.action === "legacy_action");
    assert(legacyAudit, "legacy audit entry should remain visible after migration");
    assert(legacyAudit.result === "success", "legacy audit result should default to success");
    console.log("PASS registry_schema_migration_legacy_audit_defaults");
  } finally {
    child.kill("SIGTERM");
    await new Promise((resolve) => child.once("exit", resolve));
  }

  const schema = inspectMigratedSchema(dbPath);
  assert(schema.relay_columns.includes("trust_level"), "relays table should gain trust_level");
  assert(schema.relay_columns.includes("status"), "relays table should gain status");
  assert(schema.relay_columns.includes("federation_enabled"), "relays table should gain federation_enabled");
  console.log("PASS registry_schema_migration_relay_columns");

  assert(schema.audit_columns.includes("result"), "registry_audit should gain result");
  console.log("PASS registry_schema_migration_audit_column");

  assert(schema.has_relay_certificates, "relay_certificates table should be created");
  assert(schema.has_federation_sessions, "federation_sessions table should be created");
  assert(schema.has_registry_revocation_feed, "registry_revocation_feed table should be created");
  console.log("PASS registry_schema_migration_new_tables");

  assert(schema.relay_indexes.includes("idx_relays_trust_level"), "trust_level index should exist");
  assert(schema.relay_indexes.includes("idx_relays_status"), "status index should exist");
  assert(schema.audit_indexes.includes("idx_registry_audit_created_at"), "registry_audit index should exist");
  console.log("PASS registry_schema_migration_indexes");

  fs.rmSync(tempDir, { recursive: true, force: true });
}

main().catch((err) => {
  console.error("FAIL registry_schema_migration", err && err.stack ? err.stack : String(err));
  process.exit(1);
});
