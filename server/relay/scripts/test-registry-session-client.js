#!/usr/bin/env node
const { createRegistrySessionClient } = require("../src/registry_session_client");

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

function jsonResponse(status, payload) {
  return {
    ok: status >= 200 && status < 300,
    status,
    text: async () => JSON.stringify(payload),
  };
}

function createFetchStub() {
  const calls = [];
  let revocationMode = "foreign";
  return {
    calls,
    setRevocationMode(mode) {
      revocationMode = mode;
    },
    fetch: async (url, options) => {
      calls.push({ url, options });
      const path = new URL(url).pathname;
      const body = JSON.parse(options.body || "{}");
      if (path === "/v1/registry/federation/sessions") {
        return jsonResponse(201, {
          ok: true,
          session: {
            session_id: "fs_test",
            relay_id: body.relay_id,
            issued_at: 1000,
            expires_at: 1300,
            policy_version: 1,
            permissions: ["push", "pull_reconcile"],
            peers: [
              {
                relay_id: "relay-b",
                endpoint: "http://relay-b:8080",
                allowed_gids: body.requested_gids || [],
                permissions: ["push"],
                session_token: "token.mac",
              },
            ],
            limits: { max_events_per_minute: 100, max_bytes_per_minute: 1000, max_in_flight: 1 },
            registry_signature: "sig",
          },
        });
      }
      if (path === "/v1/registry/federation/sessions/fs_test:renew") {
        return jsonResponse(200, {
          ok: true,
          session: {
            session_id: "fs_test",
            relay_id: body.relay_id,
            issued_at: 1000,
            expires_at: 1450,
            policy_version: 1,
            permissions: ["push", "pull_reconcile"],
            peers: [],
            limits: { max_events_per_minute: 100, max_bytes_per_minute: 1000, max_in_flight: 1 },
            registry_signature: "sig2",
          },
        });
      }
      if (path === "/v1/registry/federation/revocations") {
        const revokedSessionId = revocationMode === "active" ? "fs_test" : "fs_revoked";
        return jsonResponse(200, {
          ok: true,
          generated_at: 1250,
          revoked_sessions: [revokedSessionId],
          items: [
            {
              session_id: revokedSessionId,
              relay_id: "relay-b",
              revoked_at: 1240,
              expires_at: 1300,
            },
          ],
        });
      }
      return jsonResponse(404, { ok: false, error: { code: "not_found" } });
    },
  };
}

async function main() {
  let now = 1000;
  const fetchStub = createFetchStub();
  const client = createRegistrySessionClient({
    config: {
      registryFederationSessionsEnabled: true,
      registryUrl: "http://registry:8080",
      registrySharedSecret: "secret",
      federationSelfId: "relay-a",
      federationSyncGids: ["group-1"],
      registryFederationSessionRefreshIntervalSeconds: 30,
      registryFederationSessionRetrySeconds: 30,
      registryFederationSessionRenewAheadSeconds: 60,
      registryFederationSessionMaxPeers: 2,
      registryFederationSessionMaxEventsPerMinute: 100,
      registryFederationSessionMaxBytesPerMinute: 1000,
    },
    fetchImpl: fetchStub.fetch,
    nowSeconds: () => now,
  });

  assert(client.enabled(), "client should be enabled");
  await client.refresh({ force: true });
  assert(fetchStub.calls.length === 1, "first refresh should issue a session");
  assert(client.getActiveSession().session_id === "fs_test", "active session should be cached");
  assert(client.status().active === true, "status should report active session");
  console.log("PASS registry_session_issue_cache");

  await client.refresh();
  assert(fetchStub.calls.length === 1, "refresh before next_refresh_at should reuse cache");
  console.log("PASS registry_session_cache_reuse");

  now = 1250;
  await client.refresh();
  assert(fetchStub.calls.length === 2, "refresh near expiry should renew session");
  assert(client.getActiveSession().expires_at === 1450, "renewed session should replace cache");
  console.log("PASS registry_session_renew");

  const renewCall = fetchStub.calls[1];
  assert(
    renewCall.options.headers["X-Registry-Timestamp"] === "1250" &&
      typeof renewCall.options.headers["X-Registry-Signature"] === "string",
    "registry request must be signed"
  );
  console.log("PASS registry_session_signed_request");

  await client.refreshRevocations({ force: true });
  assert(client.isSessionRevoked("fs_revoked"), "revoked session should be cached");
  assert(!client.isSessionRevoked("fs_test"), "active session should not be marked revoked");
  assert(client.status().revoked_sessions_cached === 1, "status should include revocation cache size");
  console.log("PASS registry_session_revocations_cache");

  fetchStub.setRevocationMode("active");
  await client.refreshRevocations({ force: true });
  assert(client.isSessionRevoked("fs_test"), "active session should become revoked after feed refresh");
  assert(client.getActiveSession() === null, "revoked active session should no longer be returned");
  assert(client.status().active === false, "status should report inactive after revocation");
  assert(client.status().status === "revoked", "status should report revoked after feed refresh");
  console.log("PASS registry_session_active_revocation_invalidation");
}

main().catch((err) => {
  console.error("FAIL registry_session_client", err && err.stack ? err.stack : String(err));
  process.exit(1);
});
