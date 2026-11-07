#!/usr/bin/env node

const fs = require("fs");
const os = require("os");
const path = require("path");
const { spawn } = require("child_process");
const { signClientAccessGrant } = require("../src/client_access_grants");

const TEST_ADMIN_PASSWORD = "admin1234";
const TEST_ADMIN_PASSWORD_HASH =
  "scrypt$16384$8$1$c880d2df7323d9fdfc95083fd301508c$2b4a5142d0b35d012e7df68ae6e8f368d81bf48af40f8cb594fe7ad379cd9ca2";

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

async function loginAdmin(baseUrl) {
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
  return { cookie, csrfToken };
}

async function main() {
  const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "registry-client-grants-"));
  const dbPath = path.join(tempDir, "registry.sqlite");
  const port = 21000 + Math.floor(Math.random() * 1000);
  const baseUrl = `http://127.0.0.1:${port}`;
  const signingSecret = "test-client-grant-secret";

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
      REGISTRY_CLIENT_ACCESS_GRANT_SIGNING_SECRET: signingSecret,
      REGISTRY_CLIENT_ACCESS_MAX_CANDIDATES: "2",
      REGISTRY_CLIENT_ACCESS_GRANT_TTL_SECONDS: "300",
      REGISTRY_CLIENT_ACCESS_MIN_TRUST_LEVEL: "community",
    },
    stdio: "ignore",
  });

  try {
    await waitForHealth(baseUrl, child);

    const relays = [
      ["relay-vn-01", "https://relay-vn-01.test", "vn-south", ["store_forward", "capsule_push"]],
      ["relay-sg-02", "https://relay-sg-02.test", "sg", ["store_forward", "capsule_pull"]],
      ["relay-no-store", "https://relay-no-store.test", "vn-south", ["capsule_pull"]],
      ["relay-http-01", "http://relay-http-01.test", "vn-south", ["store_forward", "capsule_push"]],
    ];
    for (const [relayId, endpoint, region, capabilities] of relays) {
      const heartbeat = await api(baseUrl, "/v1/registry/relays:heartbeat", {
        method: "POST",
        body: {
          relay_id: relayId,
          public_key: `${relayId}-public-key`,
          endpoint,
          region,
          capabilities,
          version: "test",
        },
      });
      assert(heartbeat.res.status === 200, `heartbeat failed for ${relayId}`);
    }

    const issue = await api(baseUrl, "/v1/registry/client/sessions", {
      method: "POST",
      body: {
        client_instance_id: "android-device-01",
        requested_gids: ["group-1"],
        requested_operations: ["ingest", "fetch"],
        network_hints: {
          region: "vn-south",
          transport: "https",
        },
      },
    });

    assert(issue.res.status === 201, "client access grant issue should succeed");
    assert(issue.payload.ok === true, "grant response should be ok");
    const grant = issue.payload.grant;
    assert(grant.grant_id && grant.grant_id.startsWith("cg_"), "grant id should be returned");
    assert(grant.client_instance_id === "android-device-01", "client id should round trip");
    assert(Array.isArray(grant.candidate_relays) && grant.candidate_relays.length === 2, "expected 2 candidate relays");
    assert(grant.candidate_relays[0].relay_id === "relay-vn-01", "region-matching relay should be first");
    assert(grant.candidate_relays[1].relay_id === "relay-sg-02", "second relay should remain eligible");
    assert(grant.allowed_gids.length === 1 && grant.allowed_gids[0] === "group-1", "gid scope should round trip");
    assert(
      Array.isArray(grant.permissions) &&
        grant.permissions.includes("ingest") &&
        grant.permissions.includes("fetch"),
      "permissions should round trip"
    );
    assert(grant.connection_mode === "client_selects_and_relay_establishes_session", "connection mode should match");
    assert(grant.expires_at > grant.issued_at, "grant should expire after issue time");
    assert(grant.access_token && grant.access_token.includes("."), "access token should be present");
    assert(
      grant.registry_signature === signClientAccessGrant(signingSecret, grant),
      "registry signature should validate"
    );
    console.log("PASS client_access_grant_issue");

    const { cookie, csrfToken } = await loginAdmin(baseUrl);
    const trustRelaySg = await api(
      baseUrl,
      `/admin/api/relays/${encodeURIComponent("relay-sg-02")}/trust`,
      {
        method: "PATCH",
        body: { trust_level: "trusted", reason: "selection policy test" },
        cookie,
        csrfToken,
      }
    );
    assert(trustRelaySg.res.status === 200, "admin trust update should succeed");

    const policyPatch = await api(baseUrl, "/admin/api/policy", {
      method: "PATCH",
      body: {
        minimum_trust_level_for_client_access: "trusted",
        client_access_grant_ttl_seconds: 600,
        max_candidates_per_client_access_grant: 1,
        allowed_client_access_permissions: ["fetch"],
        client_access_policy_version: 9,
        prefer_same_region_for_client_access: false,
        reason: "selection policy test",
      },
      cookie,
      csrfToken,
    });
    assert(policyPatch.res.status === 200, "client access policy patch should succeed");
    assert(
      policyPatch.payload.data.minimum_trust_level_for_client_access === "trusted",
      "policy patch should persist client trust floor"
    );
    assert(
      policyPatch.payload.data.max_candidates_per_client_access_grant === 1,
      "policy patch should persist candidate limit"
    );
    assert(
      policyPatch.payload.data.prefer_same_region_for_client_access === false,
      "policy patch should persist region preference"
    );
    console.log("PASS client_access_grant_policy_patch");

    const disallowedPermission = await api(baseUrl, "/v1/registry/client/sessions", {
      method: "POST",
      body: {
        client_instance_id: "android-device-03",
        requested_gids: ["group-3"],
        requested_operations: ["ingest"],
        network_hints: {
          region: "vn-south",
          transport: "https",
        },
      },
    });
    assert(disallowedPermission.res.status === 403, "policy should reject disallowed client permission");
    assert(
      disallowedPermission.payload.error.code === "no_allowed_permissions",
      "policy permission rejection code should match"
    );
    console.log("PASS client_access_grant_policy_rejects_disallowed_permission");

    const policyDrivenIssue = await api(baseUrl, "/v1/registry/client/sessions", {
      method: "POST",
      body: {
        client_instance_id: "android-device-04",
        requested_gids: ["group-4"],
        requested_operations: ["fetch"],
        network_hints: {
          region: "vn-south",
          transport: "https",
        },
      },
    });
    assert(policyDrivenIssue.res.status === 201, "policy-driven issue should succeed");
    const policyGrant = policyDrivenIssue.payload.grant;
    assert(policyGrant.policy_version === 9, "grant should use persisted policy version");
    assert(policyGrant.expires_at - policyGrant.issued_at === 600, "grant ttl should follow persisted policy");
    assert(policyGrant.permissions.length === 1 && policyGrant.permissions[0] === "fetch", "grant permissions should be narrowed by policy");
    assert(policyGrant.candidate_relays.length === 1, "grant should respect max candidates policy");
    assert(
      policyGrant.candidate_relays[0].relay_id === "relay-sg-02",
      "grant should follow trust-first ordering when same-region preference is disabled"
    );
    console.log("PASS client_access_grant_policy_driven_selection");

    const renew = await api(
      baseUrl,
      `/v1/registry/client/sessions/${encodeURIComponent(grant.grant_id)}:renew`,
      {
        method: "POST",
        body: {
          client_instance_id: "android-device-01",
          requested_gids: ["group-1", "group-2"],
          requested_operations: ["fetch"],
        },
      }
    );
    assert(renew.res.status === 200, "client access grant renew should succeed");
    assert(renew.payload.ok === true, "renew response should be ok");
    const renewedGrant = renew.payload.grant;
    assert(renewedGrant.grant_id === grant.grant_id, "renew should preserve grant id");
    assert(renewedGrant.issued_at >= grant.issued_at, "renew should update issue time");
    assert(renewedGrant.expires_at > grant.expires_at, "renew should extend expiry");
    assert(
      renewedGrant.allowed_gids.length === 2 && renewedGrant.allowed_gids.includes("group-2"),
      "renew should update gid scope"
    );
    assert(
      renewedGrant.permissions.length === 1 && renewedGrant.permissions[0] === "fetch",
      "renew should update permissions"
    );
    assert(renewedGrant.policy_version === 9, "renew should apply persisted client access policy version");
    assert(
      renewedGrant.registry_signature === signClientAccessGrant(signingSecret, renewedGrant),
      "renewed grant signature should validate"
    );
    console.log("PASS client_access_grant_renew");

    const reassign = await api(
      baseUrl,
      `/v1/registry/client/sessions/${encodeURIComponent(grant.grant_id)}:renew`,
      {
        method: "POST",
        body: {
          client_instance_id: "android-device-01",
          network_hints: {
            transport: "http",
          },
        },
      }
    );
    assert(reassign.res.status === 200, "client access grant reassignment should succeed");
    const reassignedGrant = reassign.payload.grant;
    assert(
      reassignedGrant.candidate_relays.length === 1 &&
        reassignedGrant.candidate_relays[0].relay_id === "relay-http-01",
      "renew should allow candidate reassignment"
    );
    console.log("PASS client_access_grant_reassignment");

    const mismatch = await api(
      baseUrl,
      `/v1/registry/client/sessions/${encodeURIComponent(grant.grant_id)}:renew`,
      {
        method: "POST",
        body: {
          client_instance_id: "android-device-99",
        },
      }
    );
    assert(mismatch.res.status === 403, "client mismatch should be rejected");
    assert(mismatch.payload.error.code === "client_instance_mismatch", "client mismatch code should match");
    console.log("PASS client_access_grant_rejects_client_mismatch");

    const invalidOperations = await api(baseUrl, "/v1/registry/client/sessions", {
      method: "POST",
      body: {
        client_instance_id: "android-device-01",
        requested_gids: ["group-1"],
        requested_operations: ["delete"],
      },
    });
    assert(invalidOperations.res.status === 400, "invalid operations should be rejected");
    assert(invalidOperations.payload.error.code === "invalid_requested_operations", "invalid ops code should match");
    console.log("PASS client_access_grant_rejects_invalid_operations");

    const noCandidates = await api(baseUrl, "/v1/registry/client/sessions", {
      method: "POST",
      body: {
        client_instance_id: "android-device-02",
        requested_gids: ["group-1"],
        requested_operations: ["ingest"],
        network_hints: {
          transport: "http",
        },
      },
    });
    assert(noCandidates.res.status === 503, "no candidates should return 503");
    assert(noCandidates.payload.error.code === "no_candidate_relays", "no candidates code should match");
    console.log("PASS client_access_grant_rejects_no_candidates");

    const renewNoCandidates = await api(
      baseUrl,
      `/v1/registry/client/sessions/${encodeURIComponent(grant.grant_id)}:renew`,
      {
        method: "POST",
        body: {
          client_instance_id: "android-device-01",
          network_hints: {
            transport: "ftp",
          },
        },
      }
    );
    assert(renewNoCandidates.res.status === 400, "invalid renew transport should be rejected");
    assert(renewNoCandidates.payload.error.code === "invalid_network_transport", "invalid renew transport code should match");
    console.log("PASS client_access_grant_renew_rejects_invalid_transport");
  } finally {
    child.kill("SIGTERM");
    await new Promise((resolve) => child.once("exit", resolve));
    fs.rmSync(tempDir, { recursive: true, force: true });
  }
}

main().catch((err) => {
  console.error("FAIL client_access_grants", err && err.stack ? err.stack : String(err));
  process.exit(1);
});
