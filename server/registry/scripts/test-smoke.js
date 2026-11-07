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
      // Ignore startup races.
    }
    await sleep(100);
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

async function seedRelay(baseUrl, relayId, endpoint, region = "test") {
  const result = await api(baseUrl, "/v1/registry/relays:heartbeat", {
    method: "POST",
    body: {
      relay_id: relayId,
      public_key: `${relayId}-public-key`,
      endpoint,
      region,
      capabilities: ["store_forward", "capsule_push", "capsule_pull", "federation"],
      version: "test",
    },
  });
  assert(result.res.status === 200, `heartbeat failed for ${relayId}`);
}

function spawnRegistry(dbPath, port) {
  return spawn(process.execPath, ["src/index.js"], {
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
}

async function main() {
  const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "registry-smoke-"));
  const dbPath = path.join(tempDir, "registry.sqlite");
  const port = 19000 + Math.floor(Math.random() * 1000);
  const baseUrl = `http://127.0.0.1:${port}`;

  let child = spawnRegistry(dbPath, port);

  try {
    await waitForHealth(baseUrl, child);

    const health = await api(baseUrl, "/healthz");
    assert(health.res.status === 200, "healthz should return 200");
    assert(health.payload.role === "registry", "healthz should report registry role");
    console.log("PASS registry_smoke_health");

    const ready = await api(baseUrl, "/readyz");
    assert(ready.res.status === 200, "readyz should return 200");
    assert(ready.payload.db === "ok", "readyz should report db ok");
    console.log("PASS registry_smoke_readiness");

    await seedRelay(baseUrl, "relay-a", "https://relay-a.test");
    await seedRelay(baseUrl, "relay-b", "https://relay-b.test");

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
    console.log("PASS registry_smoke_login");

    const missingCsrf = await api(baseUrl, "/admin/api/relays/relay-a/trust", {
      method: "PATCH",
      body: { trust_level: "trusted", reason: "missing csrf" },
      cookie,
    });
    assert(missingCsrf.res.status === 403, "admin mutation without csrf token should fail");
    assert(missingCsrf.payload.error.code === "csrf_invalid", "missing csrf error should match");
    console.log("PASS registry_smoke_csrf_required");

    const trustUpdate = await api(baseUrl, "/admin/api/relays/relay-a/trust", {
      method: "PATCH",
      body: { trust_level: "trusted", reason: "smoke trust promotion" },
      cookie,
      csrfToken,
    });
    assert(trustUpdate.res.status === 200, "trust update should succeed");

    const statusUpdate = await api(baseUrl, "/admin/api/relays/relay-b/status", {
      method: "PATCH",
      body: { status: "suspended", reason: "smoke suspension" },
      cookie,
      csrfToken,
    });
    assert(statusUpdate.res.status === 200, "status update should succeed");

    const dashboard = await api(baseUrl, "/admin/api/dashboard", { cookie });
    assert(dashboard.res.status === 200, "dashboard should succeed");
    assert(typeof dashboard.payload.data.active_relays === "number", "dashboard should include active relay count");
    assert(typeof dashboard.payload.data.audit_events === "number", "dashboard should include audit count");
    console.log("PASS registry_smoke_dashboard");

    const relays = await api(baseUrl, "/admin/api/relays", { cookie });
    assert(relays.res.status === 200, "relay directory should succeed");
    assert(Array.isArray(relays.payload.data.items), "relay directory should return items");
    assert(relays.payload.data.items.some((item) => item.relay_id === "relay-a"), "relay-a should be present");
    assert(relays.payload.data.items.some((item) => item.relay_id === "relay-b" && item.status === "suspended"), "relay-b suspended state should be visible");
    console.log("PASS registry_smoke_relay_directory");

    const relayQuery = await api(
      baseUrl,
      "/admin/api/relays?q=relay-b&status=suspended&sort=last_seen&order=desc&limit=1&offset=0",
      { cookie }
    );
    assert(relayQuery.res.status === 200, "relay directory query should succeed");
    assert(relayQuery.payload.data.total === 1, "relay query should report one match");
    assert(relayQuery.payload.data.items.length === 1, "relay query page should include one item");
    assert(relayQuery.payload.data.items[0].relay_id === "relay-b", "relay query should return relay-b");
    console.log("PASS registry_smoke_relay_directory_query");

    const policy = await api(baseUrl, "/admin/api/policy", { cookie });
    assert(policy.res.status === 200, "policy endpoint should succeed");
    assert(typeof policy.payload.data.minimum_trust_level_for_federation === "string", "policy should expose federation trust level");
    assert(typeof policy.payload.data.federation_policy_version === "number", "policy should expose policy version");
    console.log("PASS registry_smoke_policy");

    const policyPatch = await api(baseUrl, "/admin/api/policy", {
      method: "PATCH",
      body: {
        minimum_trust_level_for_federation: "verified",
        allowed_statuses_for_discovery: ["active", "suspended"],
        federation_session_ttl_seconds: 900,
        max_peers_per_session: 2,
        allowed_federation_permissions: ["push"],
        federation_policy_version: 7,
        deny_relays: ["relay-a"],
        suspended_relays: ["relay-b"],
        reason: "smoke policy update",
      },
      cookie,
      csrfToken,
    });
    assert(policyPatch.res.status === 200, "policy patch should succeed");
    assert(policyPatch.payload.data.minimum_trust_level_for_federation === "verified", "policy patch should persist federation trust");
    assert(policyPatch.payload.data.federation_session_ttl_seconds === 900, "policy patch should persist session ttl");
    assert(policyPatch.payload.data.max_peers_per_session === 2, "policy patch should persist max peers");
    assert(Array.isArray(policyPatch.payload.data.allowed_federation_permissions), "policy patch should persist permissions");
    assert(policyPatch.payload.data.allowed_federation_permissions.length === 1, "policy patch permissions should be replaced");
    assert(policyPatch.payload.data.allowed_federation_permissions[0] === "push", "policy patch permission should match");
    assert(policyPatch.payload.data.federation_policy_version === 7, "policy patch version should persist");
    console.log("PASS registry_smoke_policy_patch");

    const publicPolicy = await api(baseUrl, "/v1/registry/policy");
    assert(publicPolicy.res.status === 200, "public policy feed should succeed");
    assert(publicPolicy.payload.minimum_trust_level_for_federation === "verified", "public policy should reflect persisted trust");
    assert(publicPolicy.payload.federation_session_ttl_seconds === 900, "public policy should reflect persisted ttl");
    console.log("PASS registry_smoke_public_policy_feed");

    const privateModePatch = await api(baseUrl, "/admin/api/policy", {
      method: "PATCH",
      body: {
        registry_deployment_mode: "private_self_hosted",
        reason: "switch smoke registry into private mode",
      },
      cookie,
      csrfToken,
    });
    assert(privateModePatch.res.status === 200, "private mode patch should succeed");
    assert(privateModePatch.payload.data.registry_deployment_mode === "private_self_hosted", "policy should persist deployment mode");
    assert(privateModePatch.payload.data.minimum_trust_level_for_auto_sync === "community", "private mode should relax auto-sync trust");
    assert(privateModePatch.payload.data.minimum_trust_level_for_federation === "community", "private mode should relax federation trust");
    console.log("PASS registry_smoke_private_mode_patch");

    const privatePublicPolicy = await api(baseUrl, "/v1/registry/policy");
    assert(privatePublicPolicy.res.status === 200, "public policy after private mode should succeed");
    assert(privatePublicPolicy.payload.registry_deployment_mode === "private_self_hosted", "public policy should expose deployment mode");
    assert(privatePublicPolicy.payload.minimum_trust_level_for_federation === "community", "public policy should reflect private-mode trust");
    console.log("PASS registry_smoke_private_mode_public_policy");

    const discovery = await api(baseUrl, "/v1/registry/relays");
    assert(discovery.res.status === 200, "public relay discovery should succeed");
    assert(Array.isArray(discovery.payload.relays), "public relay discovery should return items");
    assert(discovery.payload.relays.some((item) => item.relay_id === "relay-b"), "suspended relay should be discoverable when policy allows it");
    assert(!discovery.payload.relays.some((item) => item.relay_id === "relay-a"), "deny_relays should be excluded from discovery");
    console.log("PASS registry_smoke_policy_driven_discovery");

    const revocations = await api(baseUrl, "/admin/api/revocations", { cookie });
    assert(revocations.res.status === 200, "revocations endpoint should succeed");
    assert(Array.isArray(revocations.payload.data.revoked_relays), "revocations should include revoked relay list");
    assert(typeof revocations.payload.data.version === "number", "revocations should include version");
    assert(!revocations.payload.data.revoked_relays.includes("relay-b"), "suspended relay should not appear in revocations");
    console.log("PASS registry_smoke_revocations");

    const revokeRelay = await api(baseUrl, "/admin/api/relays/relay-b/status", {
      method: "PATCH",
      body: { status: "revoked", reason: "smoke revocation" },
      cookie,
      csrfToken,
    });
    assert(revokeRelay.res.status === 200, "relay revocation should succeed");

    const revocationsBeforeRebuild = await api(baseUrl, "/admin/api/revocations", { cookie });
    assert(revocationsBeforeRebuild.res.status === 200, "revocations before rebuild should succeed");
    assert(!revocationsBeforeRebuild.payload.data.revoked_relays.includes("relay-b"), "revocation feed should remain unchanged before rebuild");
    const initialRevocationVersion = revocationsBeforeRebuild.payload.data.version;
    console.log("PASS registry_smoke_revocations_snapshot_persisted");

    const revocationRebuild = await api(baseUrl, "/admin/api/revocations/rebuild", {
      method: "POST",
      body: { reason: "smoke revocation rebuild" },
      cookie,
      csrfToken,
    });
    assert(revocationRebuild.res.status === 200, "revocation rebuild should succeed");
    assert(revocationRebuild.payload.data.version === initialRevocationVersion + 1, "revocation rebuild should increment version");
    assert(revocationRebuild.payload.data.revoked_relays.includes("relay-b"), "revocation rebuild should publish revoked relay");
    console.log("PASS registry_smoke_revocations_rebuild");

    const audit = await api(baseUrl, "/admin/api/audit", { cookie });
    assert(audit.res.status === 200, "audit endpoint should succeed");
    assert(Array.isArray(audit.payload.data.items), "audit endpoint should return items");
    assert(
      audit.payload.data.items.some((item) => item.action === "relay_trust_promoted" && item.resource_id === "relay-a"),
      "audit should include trust update"
    );
    assert(
      audit.payload.data.items.some((item) => item.action === "relay_suspended" && item.resource_id === "relay-b"),
      "audit should include suspension update"
    );
    console.log("PASS registry_smoke_audit");

    const auditQuery = await api(
      baseUrl,
      "/admin/api/audit?action=relay_suspended&resource_type=relay&sort=created_at&order=desc&limit=1&offset=0",
      { cookie }
    );
    assert(auditQuery.res.status === 200, "audit query should succeed");
    assert(auditQuery.payload.data.total >= 1, "audit query should report at least one match");
    assert(auditQuery.payload.data.items.length === 1, "audit query page should include one item");
    assert(auditQuery.payload.data.items[0].action === "relay_suspended", "audit query should filter by action");
    console.log("PASS registry_smoke_audit_query");

    child.kill("SIGTERM");
    await new Promise((resolve) => child.once("exit", resolve));

    child = spawnRegistry(dbPath, port);
    await waitForHealth(baseUrl, child);

    const restartLogin = await api(baseUrl, "/admin/api/auth/login", {
      method: "POST",
      body: { username: "admin", password: TEST_ADMIN_PASSWORD },
    });
    assert(restartLogin.res.status === 200, "admin login after restart should succeed");
    const restartCookie = restartLogin.res.headers.get("set-cookie").split(";")[0];
    const restartedPolicy = await api(baseUrl, "/admin/api/policy", { cookie: restartCookie });
    assert(restartedPolicy.res.status === 200, "policy endpoint after restart should succeed");
    assert(restartedPolicy.payload.data.registry_deployment_mode === "private_self_hosted", "deployment mode should persist across restart");
    assert(restartedPolicy.payload.data.minimum_trust_level_for_federation === "community", "policy should persist across restart");
    assert(restartedPolicy.payload.data.minimum_trust_level_for_auto_sync === "community", "auto-sync trust should persist across restart");
    assert(restartedPolicy.payload.data.federation_session_ttl_seconds === 900, "policy ttl should persist across restart");
    assert(restartedPolicy.payload.data.max_peers_per_session === 2, "policy max peers should persist across restart");
    assert(Array.isArray(restartedPolicy.payload.data.deny_relays), "policy deny_relays should persist across restart");
    assert(restartedPolicy.payload.data.deny_relays.includes("relay-a"), "policy deny_relays should persist values");
    console.log("PASS registry_smoke_policy_persistence");

    const restartedRevocations = await api(baseUrl, "/admin/api/revocations", { cookie: restartCookie });
    assert(restartedRevocations.res.status === 200, "revocations after restart should succeed");
    assert(restartedRevocations.payload.data.version === initialRevocationVersion + 1, "revocation feed version should persist across restart");
    assert(restartedRevocations.payload.data.revoked_relays.includes("relay-b"), "revocation feed snapshot should persist across restart");
    console.log("PASS registry_smoke_revocation_persistence");
  } finally {
    child.kill("SIGTERM");
    await new Promise((resolve) => child.once("exit", resolve));
    fs.rmSync(tempDir, { recursive: true, force: true });
  }
}

main().catch((err) => {
  console.error("FAIL registry_smoke", err && err.stack ? err.stack : String(err));
  process.exit(1);
});
