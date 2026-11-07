#!/usr/bin/env node
const { loadConfig } = require("../src/config");
const { createFederationSessionManager, signPeerGrant, signSessionGrant } = require("../src/federation_sessions");

const TEST_ADMIN_PASSWORD_HASH = "scrypt$16384$8$1$c880d2df7323d9fdfc95083fd301508c$2b4a5142d0b35d012e7df68ae6e8f368d81bf48af40f8cb594fe7ad379cd9ca2";

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

function registerRelay(store, relayId, endpoint, now) {
  store.upsertRelay({
    relay_id: relayId,
    public_key: `${relayId}-public-key`,
    endpoint,
    region: "test",
    capabilities: ["store_forward", "capsule_push", "capsule_pull", "federation"],
    version: "test",
  }, now);
  store.updateRelayTrust(relayId, "trusted", now);
  store.updateRelayFederation(relayId, true, now);
}

function createFakeStore(policyOverrides = {}) {
  const relays = new Map();
  const sessions = new Map();
  const persistedPolicy = {
    registry_deployment_mode: "official_managed",
    deny_relays: [],
    suspended_relays: [],
    minimum_trust_level_for_auto_sync: "verified",
    allowed_statuses_for_discovery: ["active"],
    minimum_trust_level_for_federation: "trusted",
    federation_session_ttl_seconds: 300,
    max_peers_per_session: 4,
    allowed_statuses_for_federation: ["active"],
    allowed_federation_permissions: ["push", "pull_reconcile"],
    federation_policy_version: 1,
    ...policyOverrides,
  };
  return {
    upsertRelay(relay, now) {
      const existing = relays.get(relay.relay_id) || {
        trust_level: "community",
        status: "active",
        federation_enabled: false,
        created_at: now,
      };
      const next = {
        ...existing,
        ...relay,
        last_seen: now,
        updated_at: now,
      };
      relays.set(relay.relay_id, next);
      return next;
    },
    updateRelayTrust(relayId, trustLevel, now) {
      const relay = relays.get(relayId);
      if (!relay) return null;
      relay.trust_level = trustLevel;
      relay.updated_at = now;
      return relay;
    },
    updateRelayFederation(relayId, enabled, now) {
      const relay = relays.get(relayId);
      if (!relay) return null;
      relay.federation_enabled = Boolean(enabled);
      relay.updated_at = now;
      return relay;
    },
    getRelay(relayId) {
      return relays.get(relayId) || null;
    },
    listActiveRelays(now, ttlSeconds) {
      return Array.from(relays.values())
        .filter((relay) => relay.status === "active" && now - relay.last_seen <= ttlSeconds)
        .sort((a, b) => b.last_seen - a.last_seen || a.relay_id.localeCompare(b.relay_id));
    },
    listRelays() {
      return Array.from(relays.values())
        .sort((a, b) => b.last_seen - a.last_seen || a.relay_id.localeCompare(b.relay_id));
    },
    policy() {
      return { ...persistedPolicy };
    },
    activeCertificateForRelay() {
      return null;
    },
    insertFederationSession(session) {
      sessions.set(session.session_id, { ...session });
      return sessions.get(session.session_id);
    },
    getFederationSession(sessionId) {
      return sessions.get(sessionId) || null;
    },
    updateFederationSession(session) {
      sessions.set(session.session_id, { ...session });
      return sessions.get(session.session_id);
    },
    revokeFederationSession(sessionId, now) {
      const session = sessions.get(sessionId);
      if (!session) return null;
      if (!session.revoked_at) {
        session.revoked_at = now;
        session.updated_at = now;
      }
      return session;
    },
  };
}

function main() {
  const store = createFakeStore();
  const config = loadConfig({
    REGISTRY_DB_PATH: ":memory:",
    REGISTRY_SHARED_SECRET: "test-registry-secret",
    REGISTRY_ADMIN_PASSWORD_HASH: TEST_ADMIN_PASSWORD_HASH,
    REGISTRY_FEDERATION_SESSION_SIGNING_SECRET: "test-session-secret",
    REGISTRY_FEDERATION_MIN_TRUST_LEVEL: "trusted",
    REGISTRY_FEDERATION_SESSION_TTL_SECONDS: "300",
    REGISTRY_FEDERATION_MAX_PEERS_PER_SESSION: "4",
  });
  const manager = createFederationSessionManager({ config, store });
  const now = 1716900000;

  registerRelay(store, "relay-a", "https://relay-a.test", now);
  registerRelay(store, "relay-b", "https://relay-b.test", now);

  const issued = manager.issue({
    relay_id: "relay-a",
    requested_gids: ["group-1", "group-1"],
    capacity: {
      max_peers: 2,
      max_events_per_minute: 300,
      max_bytes_per_minute: 1024,
    },
  }, now);
  assert(issued.ok, `expected issue ok, got ${issued.code}`);
  assert(issued.session.session_id.startsWith("fs_"), "session id must use fs_ prefix");
  assert(issued.session.expires_at === now + 300, "session expiry must honor config ttl");
  assert(issued.session.peers.length === 1, "session should assign one eligible peer");
  assert(issued.session.peers[0].relay_id === "relay-b", "session should assign relay-b");
  assert(issued.session.peers[0].allowed_gids.length === 1, "requested gids should be deduplicated");
  assert(issued.session.peers[0].session_token.includes("."), "peer token must be generated");
  assert(issued.session.peers[0].registry_signature, "peer grant must be registry-signed");
  assert(
    issued.session.peers[0].registry_signature === signPeerGrant(config.federationSessionSigningSecret, {
      session_id: issued.session.session_id,
      source_relay_id: issued.session.relay_id,
      destination_relay_id: issued.session.peers[0].relay_id,
      endpoint: issued.session.peers[0].endpoint,
      allowed_gids: issued.session.peers[0].allowed_gids,
      permissions: issued.session.peers[0].permissions,
      limits: issued.session.limits,
      expires_at: issued.session.peers[0].expires_at,
      policy_version: issued.session.peers[0].policy_version,
      session_token: issued.session.peers[0].session_token,
    }),
    "peer grant signature must verify"
  );
  assert(
    issued.session.registry_signature === signSessionGrant(config.federationSessionSigningSecret, issued.session),
    "registry signature must verify against session grant"
  );
  console.log("PASS federation_session_issue");

  store.updateRelayTrust("relay-a", "community", now);
  const denied = manager.issue({ relay_id: "relay-a", requested_gids: ["group-1"] }, now);
  assert(!denied.ok && denied.code === "insufficient_trust", "community relay should be denied");
  store.updateRelayTrust("relay-a", "trusted", now);
  console.log("PASS federation_session_policy_denial");

  const privateStore = createFakeStore({
    registry_deployment_mode: "private_self_hosted",
    minimum_trust_level_for_auto_sync: "community",
    minimum_trust_level_for_federation: "community",
  });
  registerRelay(privateStore, "private-relay-a", "https://private-relay-a.test", now);
  registerRelay(privateStore, "private-relay-b", "https://private-relay-b.test", now);
  privateStore.updateRelayTrust("private-relay-a", "community", now);
  privateStore.updateRelayTrust("private-relay-b", "community", now);
  const privateConfig = loadConfig({
    REGISTRY_DB_PATH: ":memory:",
    REGISTRY_SHARED_SECRET: "test-registry-secret",
    REGISTRY_ADMIN_PASSWORD_HASH: TEST_ADMIN_PASSWORD_HASH,
    REGISTRY_PRIVATE_MODE: "1",
    REGISTRY_FEDERATION_SESSION_SIGNING_SECRET: "test-private-session-secret",
    REGISTRY_FEDERATION_SESSION_TTL_SECONDS: "300",
    REGISTRY_FEDERATION_MAX_PEERS_PER_SESSION: "4",
  });
  const privateManager = createFederationSessionManager({ config: privateConfig, store: privateStore });
  const privateIssued = privateManager.issue({ relay_id: "private-relay-a", requested_gids: ["private-group"] }, now + 1);
  assert(privateIssued.ok, `expected private mode issue ok, got ${privateIssued.code}`);
  assert(privateIssued.session.peers.length === 1, "private mode should still assign eligible peers");
  assert(privateIssued.session.peers[0].relay_id === "private-relay-b", "private mode should keep community relay eligible");
  assert(privateConfig.federationMinTrustLevel === "community", "private mode config should default federation trust to community");
  console.log("PASS federation_session_private_registry_mode");

  const shortSession = manager.issue({ relay_id: "relay-a", requested_gids: ["group-1"] }, now + 1);
  assert(shortSession.ok, `expected short session issue ok, got ${shortSession.code}`);
  const expiredRenew = manager.renew(shortSession.session.session_id, { relay_id: "relay-a" }, now + 302);
  assert(!expiredRenew.ok && expiredRenew.code === "session_expired", "expired session must not renew");
  console.log("PASS federation_session_expiry");

  const renewed = manager.renew(issued.session.session_id, { relay_id: "relay-a" }, now + 120);
  assert(renewed.ok, `expected renew ok, got ${renewed.code}`);
  assert(renewed.session.expires_at === now + 420, "renewal should extend expiry from renewal time");
  assert(renewed.session.registry_signature !== issued.session.registry_signature, "renewal should re-sign grant");
  console.log("PASS federation_session_renew");

  const revoked = manager.revoke(issued.session.session_id, { relay_id: "relay-a" }, now + 180);
  assert(revoked.ok, `expected revoke ok, got ${revoked.code}`);
  assert(revoked.session.revoked_at === now + 180, "revocation timestamp must be stored");
  const deniedRenew = manager.renew(issued.session.session_id, { relay_id: "relay-a" }, now + 181);
  assert(!deniedRenew.ok && deniedRenew.code === "session_revoked", "revoked session must not renew");
  console.log("PASS federation_session_revoke");
}

try {
  main();
} catch (err) {
  console.error("FAIL federation_sessions", err && err.stack ? err.stack : String(err));
  process.exit(1);
}
