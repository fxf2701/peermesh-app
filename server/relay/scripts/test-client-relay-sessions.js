#!/usr/bin/env node

const {
  createClientRelaySessionManager,
} = require("../src/client_relay_sessions");
const { signClientAccessGrant } = require("../src/client_access_grants");

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

function baseConfig() {
  return {
    relaySelfId: "relay-a",
    federationSelfId: "relay-a",
    clientRelaySessionsEnabled: true,
    clientRelaySessionBindingSecret: "relay-session-secret",
    clientRelaySessionTtlSeconds: 120,
    clientRelaySessionCleanupIntervalSeconds: 0,
    clientRelaySessionMaxPerClient: 2,
    registryClientAccessGrantSigningSecret: "registry-client-grant-secret",
  };
}

function createGrant(overrides = {}) {
  const grant = {
    grant_id: "cg_test",
    issued_at: 1000,
    expires_at: 1600,
    policy_version: 3,
    client_instance_id: "android-device-01",
    candidate_relays: [
      { relay_id: "relay-a", endpoint: "http://relay-a:8080", trust_level: "trusted", region: "vn-south" },
      { relay_id: "relay-b", endpoint: "http://relay-b:8080", trust_level: "trusted", region: "sg" },
    ],
    allowed_gids: ["group-1", "group-2"],
    permissions: ["ingest", "fetch"],
    limits: { max_events_per_minute: 120, max_bytes_per_minute: 1048576 },
    access_token: "registry-access-token",
    connection_mode: "client_selects_and_relay_establishes_session",
    ...overrides,
  };
  grant.registry_signature = signClientAccessGrant("registry-client-grant-secret", grant);
  return grant;
}

function createOpenRequest(overrides = {}) {
  return {
    grant_id: "cg_test",
    access_token: "registry-access-token",
    client_instance_id: "android-device-01",
    relay_id: "relay-a",
    requested_gids: ["group-1"],
    requested_operations: ["fetch"],
    client_nonce: "client-nonce",
    client_session_material: { version: 1, mode: "session_binding" },
    ...overrides,
  };
}

async function main() {
  {
    const manager = createClientRelaySessionManager({
      config: baseConfig(),
      nowSeconds: () => 1100,
    });
    const result = manager.open(createOpenRequest(), createGrant());
    assert(result.ok, "session open should succeed");
    assert(result.status === 201, "session open should return 201");
    assert(result.session.session_id.startsWith("crs_"), "session id should be generated");
    assert(result.session.permissions.length === 1 && result.session.permissions[0] === "fetch", "session should narrow operations");
    assert(result.session.allowed_gids.length === 1 && result.session.allowed_gids[0] === "group-1", "session should narrow gids");
    assert(result.session.expires_at === 1220, "session ttl should be bounded by relay session ttl");
    assert(result.session.session_binding_token.includes("."), "binding token should be issued");
    console.log("PASS client_relay_session_open");
    manager.shutdown();
  }

  {
    const manager = createClientRelaySessionManager({
      config: baseConfig(),
      nowSeconds: () => 1100,
    });
    const wrongRelay = manager.open(
      createOpenRequest({ relay_id: "relay-b" }),
      createGrant()
    );
    assert(!wrongRelay.ok, "wrong relay should be rejected");
    assert(wrongRelay.code === "wrong_destination_relay", "wrong relay code should match");
    console.log("PASS client_relay_session_rejects_wrong_relay");
    manager.shutdown();
  }

  {
    const manager = createClientRelaySessionManager({
      config: baseConfig(),
      nowSeconds: () => 1100,
    });
    const outOfScope = manager.open(
      createOpenRequest({ requested_gids: ["group-9"] }),
      createGrant()
    );
    assert(!outOfScope.ok, "out of scope gid should be rejected");
    assert(outOfScope.code === "gid_not_allowed", "gid code should match");
    console.log("PASS client_relay_session_rejects_gid_out_of_scope");
    manager.shutdown();
  }

  {
    let now = 1100;
    const manager = createClientRelaySessionManager({
      config: baseConfig(),
      nowSeconds: () => now,
    });
    const opened = manager.open(createOpenRequest(), createGrant());
    assert(opened.ok, "session should open before renew");
    now = 1150;
    const renewed = manager.renew(
      opened.session.session_id,
      {
        access_token: "registry-access-token",
        client_instance_id: "android-device-01",
        requested_gids: ["group-1", "group-2"],
        requested_operations: ["ingest", "fetch"],
        client_session_material: { version: 1, mode: "session_binding" },
      },
      createGrant(),
      now
    );
    assert(renewed.ok, "session renew should succeed");
    assert(renewed.status === 200, "session renew should return 200");
    assert(renewed.session.session_id === opened.session.session_id, "renew should keep session id");
    assert(renewed.session.permissions.length === 2, "renew should widen only within grant scope");
    assert(renewed.session.expires_at === 1270, "renew should advance ttl from renew time");
    console.log("PASS client_relay_session_renew");
    manager.shutdown();
  }

  {
    let now = 1100;
    const manager = createClientRelaySessionManager({
      config: baseConfig(),
      nowSeconds: () => now,
    });
    const opened = manager.open(createOpenRequest(), createGrant());
    const binding = manager.validateRequestBinding({
      sessionId: opened.session.session_id,
      sessionToken: opened.session.session_binding_token,
      grantId: "cg_test",
      operation: "fetch",
      gids: ["group-1"],
      now,
    });
    assert(binding.ok, "request binding validation should succeed");
    now = 1400;
    const expired = manager.validateRequestBinding({
      sessionId: opened.session.session_id,
      sessionToken: opened.session.session_binding_token,
      grantId: "cg_test",
      operation: "fetch",
      gids: ["group-1"],
      now,
    });
    assert(!expired.ok, "expired session should fail validation");
    assert(expired.code === "client_session_expired", "expired session code should match");
    console.log("PASS client_relay_session_validate_binding");
    manager.shutdown();
  }

  {
    const manager = createClientRelaySessionManager({
      config: baseConfig(),
      nowSeconds: () => 1100,
    });
    const first = manager.open(createOpenRequest(), createGrant());
    const second = manager.open(
      createOpenRequest({ client_nonce: "client-nonce-2", requested_gids: ["group-2"] }),
      createGrant()
    );
    const third = manager.open(
      createOpenRequest({ client_nonce: "client-nonce-3", requested_gids: ["group-1"] }),
      createGrant()
    );
    assert(first.ok && second.ok, "first two sessions should open");
    assert(!third.ok, "session cap should reject third session");
    assert(third.code === "too_many_client_sessions", "session cap code should match");
    console.log("PASS client_relay_session_max_per_client");
    manager.shutdown();
  }

  {
    const manager = createClientRelaySessionManager({
      config: baseConfig(),
      nowSeconds: () => 1100,
    });
    const opened = manager.open(createOpenRequest(), createGrant());
    const closed = manager.close(opened.session.session_id, {
      client_instance_id: "android-device-01",
    }, 1110);
    assert(closed.ok, "close should succeed");
    assert(closed.session.closed_at === 1110, "close timestamp should be recorded");
    const afterClose = manager.validateRequestBinding({
      sessionId: opened.session.session_id,
      sessionToken: opened.session.session_binding_token,
      operation: "fetch",
      gids: ["group-1"],
      now: 1111,
    });
    assert(!afterClose.ok, "closed session should no longer validate");
    assert(afterClose.code === "client_session_required", "closed session should be evicted");
    console.log("PASS client_relay_session_close");
    manager.shutdown();
  }
}

main().catch((err) => {
  console.error("FAIL client_relay_sessions", err && err.stack ? err.stack : String(err));
  process.exit(1);
});
